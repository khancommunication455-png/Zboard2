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
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Spell-correction provider that auto-detects, per typed word, whether it looks
 * like Roman Urdu or English, and — if it doesn't already match a known word —
 * suggests the closest known word from the matching language's wordlist.
 *
 * This is intentionally separate from [AdaptiveLearningProvider]: that provider
 * predicts/completes based on what *you* have typed before, while this one
 * corrects against fixed base wordlists (Roman Urdu via [RomanUrduDictionary],
 * English via [dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider]'s
 * own dictionary, which this provider does not duplicate — it only handles the
 * Roman Urdu side and stays out of the way for English).
 *
 * Flow per composing word:
 *  1. Skip very short words (<3 letters) and anything that isn't purely letters —
 *     too ambiguous to safely correct.
 *  2. [LanguageDetector.classify] guesses ENGLISH / ROMAN_URDU / UNKNOWN.
 *  3. If ENGLISH or UNKNOWN, this provider stays silent (English correction is the
 *     base [dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider]'s job).
 *  4. If ROMAN_URDU: exact dictionary hits return no correction (word is fine).
 *     Otherwise, find the closest words within edit distance 2, ranked by
 *     (closer edit distance) then (higher frequency).
 *
 * Privacy: purely a static, bundled, in-memory wordlist lookup — no persistence,
 * no learning, no network. Safe to run even during incognito sessions.
 */
class RomanUrduSuggestionProvider(private val context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "dev.patrickgold.florisboard.stylekit.nlp.romanurdu"

        private const val MIN_WORD_LENGTH = 3
        private const val MAX_EDIT_DISTANCE = 2
        private const val MAX_CANDIDATES_SCANNED_DISTANCE_1 = Int.MAX_VALUE
    }

    private val prefs by FlorisPreferenceStore

    override val providerId = ProviderId

    override suspend fun create() {
        // Wordlist is a static in-memory map; nothing to initialize.
    }

    override suspend fun preload(subtype: Subtype) {
        // Touch the lazily-built bigram profiles now so the first keystroke isn't slow.
        tryOrNull { LanguageDetector.classify("hai") }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (!prefs.suggestion.romanUrduCorrectionEnabled.get()) return emptyList()
        val composing = content.composingText.toString()
        if (composing.isBlank()) return emptyList()

        return tryOrNull { correctionsFor(composing, maxCandidateCount.coerceAtMost(5)) } ?: emptyList()
    }

    /** Exposed for reuse by other callers (e.g. a future explicit "check spelling" action). */
    fun correctionsFor(rawWord: String, limit: Int): List<SuggestionCandidate> {
        val word = rawWord.lowercase().trim()
        if (word.length < MIN_WORD_LENGTH || !word.all { it.isLetter() }) return emptyList()

        when (LanguageDetector.classify(word)) {
            WordLanguageGuess.ROMAN_URDU -> {
                // Already a known spelling — nothing to correct.
                if (RomanUrduDictionary.contains(word)) return emptyList()
            }
            WordLanguageGuess.ENGLISH, WordLanguageGuess.UNKNOWN -> return emptyList()
        }

        val ranked = RomanUrduDictionary.allWords()
            .asSequence()
            .mapNotNull { candidate ->
                val dist = EditDistance.distance(word, candidate, MAX_EDIT_DISTANCE)
                if (dist <= MAX_EDIT_DISTANCE) candidate to dist else null
            }
            .sortedWith(
                compareBy(
                    { it.second }, // closer edit distance first
                    { -RomanUrduDictionary.frequencyOf(it.first) }, // then more common word first
                ),
            )
            .take(limit)
            .toList()

        return ranked.map { (candidateWord, dist) ->
            // Confidence: distance-1 typos are corrected with higher confidence than
            // distance-2, and more common words get a small extra boost.
            val distanceConfidence = if (dist <= 1) 0.75 else 0.5
            val freqBoost = (RomanUrduDictionary.frequencyOf(candidateWord) / 100.0) * 0.2
            WordSuggestionCandidate(
                text = candidateWord,
                confidence = (distanceConfidence + freqBoost).coerceIn(0.0, 1.0),
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = false,
                sourceProvider = this,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Static wordlist provider — nothing to record. A future iteration could
        // learn accepted Roman Urdu words the same way AdaptiveLearningProvider
        // learns English ones.
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // No-op: no per-user state to roll back.
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return false // Base wordlist entries aren't user-removable.
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return RomanUrduDictionary.allWords().toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return (RomanUrduDictionary.frequencyOf(word.lowercase()) / 100.0).coerceIn(0.0, 1.0)
    }

    override suspend fun destroy() {
        // Nothing to release.
    }
}
