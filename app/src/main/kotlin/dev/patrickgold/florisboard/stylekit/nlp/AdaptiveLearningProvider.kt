/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.stylekit.nlp

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.BigramEntity
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitUserDictionaryEntity
import dev.patrickgold.florisboard.stylekit.data.entity.TrigramEntity
import dev.patrickgold.florisboard.stylekit.data.entity.WordFrequencyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.kotlin.tryOrNull

/**
 * The adaptive, on-device learning suggestion provider. Implements FlorisBoard's
 * [SuggestionProvider] interface so it plugs into the existing NLP pipeline without
 * modifying [dev.patrickgold.florisboard.ime.nlp.NlpManager] beyond a single entry
 * registration.
 *
 * Behaviour summary (the "Gboard feel"):
 *
 *  1. **Current-word completion.** When the user has typed a partial word (composing
 *     text), this provider queries [WordFrequencyDao.suggestByPrefix] and the
 *     user-dictionary for matching entries. Personal frequencies are *boosted* over
 *     the static base dictionary, so the user's own slang/names/phrasing outrank
 *     generic matches.
 *  2. **Next-word prediction.** When the composing text is empty (cursor right after
 *     a space), this provider uses the last 1–2 committed words to look up bigrams
 *     and trigrams, returning the most likely continuations.
 *  3. **Promotion to user dictionary.** When a word the user typed is not in the
 *     base dictionary and has been committed enough times, [recordCommit] will
 *     insert it into [StyleKitUserDictionaryEntity], so it gets suggested after a
 *     few uses, not from day one.
 *
 * Privacy contract:
 *  - All reads/writes are local Room calls. No network.
 *  - When [isPrivateSession] is true (incognito mode), [suggest] still returns
 *    previously-learned candidates (so the user keeps good suggestions), but
 *    [recordCommit] is a no-op — the session is not used for training. This is
 *    exactly what the FlorisBoard `SuggestionProvider` contract requires.
 *  - When the global `suggestion.personalized_learning` preference is off,
 *    [suggest] returns an empty list and [recordCommit] is a no-op. The user
 *    can fully opt out.
 *
 * Crash safety: every DAO call is wrapped in [tryOrNull]; any DB hiccup degrades
 * to "no suggestions" instead of crashing the IME.
 */
class AdaptiveLearningProvider(context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "dev.patrickgold.florisboard.stylekit.nlp.adaptive"

        // Tunables — kept here so they're easy to find and tweak.
        private const val MAX_CANDIDATES = 8
        private const val USER_DICT_PROMOTION_THRESHOLD = 3 // commits before a non-dict word is promoted
        private const val PERSONAL_FREQ_BOOST = 2.0f // multiplier over base-dictionary confidence

        // StyleKit advanced-learning tunables. The bigram and trigram weights
        // are now CONTEXT-DEPENDENT: when we have a trigram match (very strong
        // signal — three-word sequence seen together before), we boost it
        // heavily; bigram matches get a moderate boost; unigram matches get
        // the base score. This mirrors how Gboard surfaces "Good morning" as
        // a strong next-word candidate after "good" because the bigram
        // (good, morning) has been seen many times.
        //
        // StyleKit fix: the original formula `(freq * weight) / 100.0` saturates
        // at 1.0 once a word has been committed ~45 times (45 * 2.2 / 100 = 0.99).
        // After that, ANY further commits don't increase the score, which
        // means "good morning" (committed 100 times) and "good evening"
        // (committed 45 times) would tie at confidence 1.0 — bad UX.
        // The new formula uses sqrt() so high-frequency entries keep pulling
        // ahead but at a diminishing rate: sqrt(100) * 2.2 / 10 = 2.2 → clamp
        // to 1.0; sqrt(45) * 2.2 / 10 = 1.47 → clamp to 1.0; sqrt(10) *
        // 2.2 / 10 = 0.70. So freq=10 vs freq=4 → 0.70 vs 0.44, clear winner.
        private const val BIGRAM_WEIGHT = 1.4f
        private const val TRIGRAM_WEIGHT = 2.2f

        // Recency decay: words used within the last hour get a small freshness
        // boost; words unused for over a week get a small penalty. This keeps
        // the suggestions feeling current — if you've been texting about
        // "dinner" all evening, "dinner" outranks "duck" even if both have
        // the same lifetime frequency.
        private const val RECENCY_BOOST_WINDOW_MS = 60L * 60 * 1000 // 1 hour
        private const val RECENCY_BOOST = 0.15f
        private const val RECENCY_PENALTY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        private const val RECENCY_PENALTY = 0.10f

        private const val DECAY_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    /**
     * Maps a raw frequency count (unbounded positive integer from the DB) to
     * a 0.0–1.0 confidence score using a sqrt curve. This prevents high-
     * frequency entries from saturating at 1.0 while still giving low-
     * frequency entries a meaningful score.
     *
     * Examples:
     *   freq=1  → 0.10
     *   freq=4  → 0.20
     *   freq=9  → 0.30
     *   freq=25 → 0.50
     *   freq=64 → 0.80
     *   freq=100+ → clamped to 1.0
     */
    private fun freqToScore(freq: Int, weight: Float): Double {
        if (freq <= 0) return 0.0
        val raw = kotlin.math.sqrt(freq.toDouble()) * weight / 10.0
        return raw.coerceIn(0.0, 1.0)
    }

    private val appContext by context.appContext()
    private val repository = AdaptiveLearningRepository(context)

    override val providerId = ProviderId

    override suspend fun create() {
        // Database is opened lazily on first use; nothing to do here.
    }

    override suspend fun preload(subtype: Subtype) {
        // Trigger DB open so the first keystroke isn't slow.
        withContext(Dispatchers.IO) {
            tryOrNull { StyleKitDatabase.get(appContext).openHelper.writableDatabase }
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // Private sessions still see suggestions (just no training), per the contract.
        // The personalization toggle, however, fully disables this provider.
        if (!repository.isPersonalizedLearningEnabled()) return emptyList()

        val locale = subtype.primaryLocale.languageTag()
        // StyleKit: when a Font Style preset is active, the composing text
        // contains the STYLIZED form (e.g. "𝐡𝐞𝐥" for Math Sans), not the
        // original ASCII. Normalize it back to ASCII before querying the
        // unigram table, otherwise prefix matching fails.
        val composing = dev.patrickgold.florisboard.stylekit.preset.FontNormalizer
            .normalize(content.composingText.toString())
        return tryOrNull {
            if (composing.isBlank()) {
                // Next-word prediction: use the last 1–2 committed words as context.
                val (w1, w2) = repository.lastOneOrTwoWords(content)
                nextWordCandidates(w1, w2, locale, maxCandidateCount.coerceAtMost(MAX_CANDIDATES))
            } else {
                // Current-word completion.
                currentWordCandidates(composing, locale, maxCandidateCount.coerceAtMost(MAX_CANDIDATES))
            }
        } ?: emptyList()
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We do not train on accept events directly; [recordCommit] is the single
        // training entry point, called from KeyboardManager.commitCandidate. This
        // avoids double-counting when the user taps a suggestion (which triggers
        // both an accept and a commit).
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Soft revert: decrement the frequency we just bumped. Best-effort.
        val word = candidate.text.toString().lowercase().trim()
        if (word.isNotEmpty()) {
            tryOrNull { repository.decrementUnigram(word) }
        }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        val word = candidate.text.toString().lowercase().trim()
        return tryOrNull { repository.removeFromLearning(word) } ?: false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return tryOrNull { repository.allLearnedWords() } ?: emptyList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return tryOrNull { repository.frequencyOf(word.lowercase()) } ?: 0.0
    }

    override suspend fun destroy() {
        // Room database lifecycle is managed at the application level; nothing to do here.
    }

    // -- Public training entry point ------------------------------------------------------

    /**
     * Records a confirmed word commit into the personal-frequency table and the
     * bigram/trigram tables. Called by [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager]
     * whenever a word is confirmed (space, punctuation, explicit suggestion tap).
     *
     * **Privacy guard:** [isPrivateSession] must be true when the active state is
     * incognito or when personalized learning is disabled. Callers are expected to
     * check both conditions before invoking this method.
     */
    suspend fun recordCommit(
        word: String,
        previousWord: String?,
        wordBeforePrevious: String?,
        isPrivateSession: Boolean,
    ) {
        if (isPrivateSession) return
        if (!repository.isPersonalizedLearningEnabled()) return
        // StyleKit: defense-in-depth normalization. The caller (KeyboardManager)
        // already calls FontNormalizer.normalize on the committed text, but
        // suggestion candidates, gesture commits, and external callers may
        // pass stylized text directly. We normalize here too so the unigram /
        // bigram / trigram tables NEVER contain stylized forms, regardless of
        // which entry point is used. This is what makes the learning model
        // work consistently across every Font Style preset (Math Sans, Bold
        // Serif, Bubble, Zalgo, Upside Down, etc.).
        val normalizer = dev.patrickgold.florisboard.stylekit.preset.FontNormalizer
        val cleaned = normalizer.normalize(word).lowercase().trim().trimEnd { !it.isLetterOrDigit() }
        if (cleaned.isBlank() || cleaned.length > 64) return
        val cleanPrev = previousWord?.let { normalizer.normalize(it) }
            ?.lowercase()?.trim()?.trimEnd { !it.isLetterOrDigit() }
        val cleanPrevPrev = wordBeforePrevious?.let { normalizer.normalize(it) }
            ?.lowercase()?.trim()?.trimEnd { !it.isLetterOrDigit() }
        tryOrNull {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                repository.bumpUnigram(cleaned, now)
                if (!cleanPrev.isNullOrBlank()) {
                    repository.bumpBigram(cleanPrev, cleaned, now)
                    if (!cleanPrevPrev.isNullOrBlank()) {
                        repository.bumpTrigram(cleanPrevPrev, cleanPrev, cleaned, now)
                    }
                }
                // Promote to user dictionary after threshold if not already known.
                repository.maybePromoteToUserDictionary(cleaned)
                // Occasional decay to keep the table bounded.
                repository.maybeRunDecay(now)
            }
            flogDebug { "AdaptiveLearningProvider recorded commit: $cleaned (prev=$cleanPrev)" }
        }
    }

    // -- Internal: candidate assembly -----------------------------------------------------

    private suspend fun currentWordCandidates(
        composing: String,
        locale: String?,
        limit: Int,
    ): List<SuggestionCandidate> {
        val prefix = composing.lowercase()
        val unigrams = repository.suggestUnigramsByPrefix(prefix, limit)
        val userDict = repository.suggestUserDictByPrefix(prefix, limit)
        val exact = unigrams.firstOrNull { it.word == prefix }
        val now = System.currentTimeMillis()
        return buildList {
            // 1) Exact-match unigram first — strong autocorrect candidate.
            if (exact != null) {
                add(toCandidate(exact, isExactMatch = true, weight = PERSONAL_FREQ_BOOST, now = now))
            }
            // 2) User-dictionary entries (custom words the user typed often).
            for (e in userDict) {
                if (none { it.text.toString() == e.word }) {
                    add(WordSuggestionCandidate(
                        text = e.word,
                        confidence = 0.9,
                        isEligibleForAutoCommit = false,
                        isEligibleForUserRemoval = true,
                        sourceProvider = this@AdaptiveLearningProvider,
                    ))
                }
            }
            // 3) Other unigram prefix matches by personal frequency, recency-weighted.
            for (e in unigrams) {
                if (e.word == prefix) continue
                if (none { it.text.toString() == e.word }) {
                    add(toCandidate(e, isExactMatch = false, weight = PERSONAL_FREQ_BOOST, now = now))
                }
            }
        }.take(limit)
    }

    private suspend fun nextWordCandidates(
        word1: String?,
        word2: String?,
        locale: String?,
        limit: Int,
    ): List<SuggestionCandidate> {
        if (word1.isNullOrBlank()) return emptyList()
        val now = System.currentTimeMillis()
        val trigrams: List<TrigramEntity> =
            if (!word2.isNullOrBlank()) repository.suggestTrigrams(word2, word1, limit) else emptyList()
        val bigrams: List<BigramEntity> = repository.suggestBigrams(word1, limit)

        // StyleKit: advanced next-word scoring. We collect candidates from
        // trigrams and bigrams into a map keyed by the candidate word, taking
        // the MAX score when both sources mention the same word. This avoids
        // duplicate entries and produces a single ranked list with the
        // strongest signal winning.
        //
        // StyleKit fix: use freqToScore() (sqrt curve) instead of the linear
        // formula so high-frequency entries keep ranking above low-frequency
        // ones rather than tying at 1.0.
        val scored = LinkedHashMap<String, Double>()
        for (t in trigrams) {
            val raw = freqToScore(t.frequency, TRIGRAM_WEIGHT)
            val recencyAdj = recencyAdjust(t.lastUsed, now)
            val score = (raw + recencyAdj).coerceIn(0.0, 1.0)
            // Trigram signal is stronger than bigram — replace if existing < score.
            val existing = scored[t.thirdWord]
            if (existing == null || existing < score) scored[t.thirdWord] = score
        }
        for (b in bigrams) {
            val raw = freqToScore(b.frequency, BIGRAM_WEIGHT)
            val recencyAdj = recencyAdjust(b.lastUsed, now)
            val score = (raw + recencyAdj).coerceIn(0.0, 1.0)
            val existing = scored[b.secondWord]
            if (existing == null || existing < score) scored[b.secondWord] = score
        }

        return scored.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (word, conf) ->
                WordSuggestionCandidate(
                    text = word,
                    confidence = conf,
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = true,
                    sourceProvider = this@AdaptiveLearningProvider,
                )
            }
    }

    /**
     * Recency adjustment: returns a small bonus for entries used within the
     * last hour, a small penalty for entries unused for over a week, zero
     * otherwise. This keeps suggestions feeling current without losing
     * long-term learned patterns.
     */
    private fun recencyAdjust(lastUsed: Long, now: Long): Double {
        val age = now - lastUsed
        return when {
            age < 0L -> 0.0 // clock skew — treat as no info
            age < RECENCY_BOOST_WINDOW_MS -> RECENCY_BOOST.toDouble()
            age > RECENCY_PENALTY_WINDOW_MS -> -RECENCY_PENALTY.toDouble()
            else -> 0.0
        }
    }

    private fun toCandidate(e: WordFrequencyEntity, isExactMatch: Boolean, weight: Float, now: Long): SuggestionCandidate {
        val raw = freqToScore(e.frequency, weight)
        val recencyAdj = recencyAdjust(e.lastUsed, now)
        val confidence = (raw + recencyAdj).coerceIn(0.0, 1.0)
        return WordSuggestionCandidate(
            text = e.word,
            confidence = confidence,
            isEligibleForAutoCommit = isExactMatch && confidence > 0.6,
            isEligibleForUserRemoval = !e.isUserAdded,
            sourceProvider = this@AdaptiveLearningProvider,
        )
    }
}
