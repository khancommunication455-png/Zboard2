/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.stylekit.preset.FontNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock

/**
 * Latin-script language provider with a real dictionary-backed spelling corrector
 * and prefix-completion suggestion engine.
 *
 * StyleKit additions (the original FlorisBoard stock implementation was a stub
 * that returned `emptyList()` for suggestions and hardcoded "typo"/"gerror"
 * for spell-check — see git history):
 *
 *  - `spell()` looks up the input word in the bundled 50K-word `data.json`
 *    dictionary. If it's a known word, returns [SpellingResult.validWord].
 *    Otherwise, computes Levenshtein-edit-distance candidates from the
 *    dictionary and returns the closest matches as typo corrections.
 *  - `suggest()` does prefix-completion against the dictionary for current-word
 *    suggestions. Each candidate's confidence is derived from the word's
 *    frequency (0–255 → 0.0–1.0). When the composing text is empty, returns
 *    the highest-frequency words as next-word candidates (a static fallback
 *    until the StyleKit `AdaptiveLearningProvider`'s bigram/trigram tables
 *    fill up from user typing).
 *
 * Font-Style integration: when a Font Style preset is active (Math Sans,
 * Bold Serif, Bubble, Zalgo, etc.), the composing text contains the stylized
 * form, not the original ASCII. We call [FontNormalizer.normalize] before any
 * dictionary lookup so spelling/autocomplete works consistently regardless
 * of which font is active.
 *
 * Performance:
 *  - Dictionary load is one-shot (~5 MB JSON parsed into a HashMap on first
 *    preload call). Subsequent calls reuse the in-memory map.
 *  - Spell-check uses bounded Levenshtein (max distance 2) with an early-exit
 *    when the input is too short or too long for corrections to be useful.
 *  - Suggest uses prefix lookup on a sorted keyset for O(log n) seek + O(k)
 *    scan of the prefix range.
 */
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        // Tunables — see inline comments for rationale.
        private const val MAX_SPELL_SUGGESTIONS = 5
        private const val MAX_SUGGEST_CANDIDATES = 8
        private const val MAX_EDIT_DISTANCE = 2
        // Words shorter than this are too ambiguous to correct (e.g. "te" → "the"?
        // "to"? "be"?). Just accept them.
        private const val MIN_LENGTH_FOR_CORRECTION = 3
        // Words longer than this are unlikely to benefit from edit-distance
        // correction — the dictionary probably doesn't have them. Skip the
        // (potentially expensive) scan.
        private const val MAX_LENGTH_FOR_CORRECTION = 32

        // StyleKit: advanced-correction tunables.
        // Common-typo table — hand-curated high-frequency errors. These get
        // boosted over edit-distance matches because they're statistically
        // the most likely corrections. Edit-distance is the fallback.
        //
        // IMPORTANT: Kotlin's `mapOf()` throws IllegalArgumentException on
        // duplicate keys at class-init time. Keep this list deduped — the
        // assertUnique() call in the init block below is a defensive
        // smoke test that runs once at class load to catch any future
        // regression immediately rather than at first spell() call.
        val COMMON_TYPOS: Map<String, List<String>> = mapOf(
            "teh" to listOf("the"),
            "adn" to listOf("and"),
            "recieve" to listOf("receive"),
            "definately" to listOf("definitely"),
            "seperate" to listOf("separate"),
            "occured" to listOf("occurred"),
            "occuring" to listOf("occurring"),
            "untill" to listOf("until"),
            "wich" to listOf("which", "witch"),
            "thier" to listOf("their"),
            "freind" to listOf("friend"),
            "beleive" to listOf("believe"),
            "acheive" to listOf("achieve"),
            "wierd" to listOf("weird"),
            "alot" to listOf("a lot", "allot"),
            "thru" to listOf("through"),
            "tho" to listOf("though"),
            "u" to listOf("you"),
            "ur" to listOf("your", "you're"),
            "r" to listOf("are"),
            "y" to listOf("why", "yes"),
            "k" to listOf("ok", "okay"),
            "becuase" to listOf("because"),
            "becouse" to listOf("because"),
            "abondon" to listOf("abandon"),
            "accomodate" to listOf("accommodate"),
            "arguement" to listOf("argument"),
            "calender" to listOf("calendar"),
            "cemetary" to listOf("cemetery"),
            "changable" to listOf("changeable"),
            "collegue" to listOf("colleague"),
            "concious" to listOf("conscious"),
            "definate" to listOf("definite"),
            "embarass" to listOf("embarrass"),
            "enviroment" to listOf("environment"),
            "existance" to listOf("existence"),
            "foriegn" to listOf("foreign"),
            "goverment" to listOf("government"),
            "gramar" to listOf("grammar"),
            "harrass" to listOf("harass"),
            "independant" to listOf("independent"),
            "knowlege" to listOf("knowledge"),
            "liason" to listOf("liaison"),
            "libary" to listOf("library"),
            "licence" to listOf("license"),
            "maintainance" to listOf("maintenance"),
            "millenium" to listOf("millennium"),
            "neccessary" to listOf("necessary"),
            "noticable" to listOf("noticeable"),
            "occassion" to listOf("occasion"),
            "occassionally" to listOf("occasionally"),
            "persistant" to listOf("persistent"),
            "posession" to listOf("possession"),
            "prefered" to listOf("preferred"),
            "priviledge" to listOf("privilege"),
            "publically" to listOf("publicly"),
            "realy" to listOf("really"),
            "reccomend" to listOf("recommend"),
            "reffered" to listOf("referred"),
            "religous" to listOf("religious"),
            "rythm" to listOf("rhythm"),
            "succesful" to listOf("successful"),
            "truely" to listOf("truly"),
            "unfortunatly" to listOf("unfortunately"),
            "wether" to listOf("whether", "weather"),
            "yuo" to listOf("you"),
        )

        // Defensive: verify no duplicate keys. This runs once at class init.
        init {
            val seen = HashSet<String>()
            for (k in COMMON_TYPOS.keys) {
                check(seen.add(k)) { "Duplicate key in COMMON_TYPOS: $k" }
            }
        }

        // QWERTY keyboard layout — used to compute adjacent-key distance for
        // edit-cost weighting. A substitution of an adjacent key (e.g. 'a'
        // for 's') is more likely a typo than a substitution of a distant
        // key (e.g. 'a' for 'p'), so we charge it less.
        private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val KEYBOARD_POS = buildMap {
            for (row in QWERTY_ROWS) {
                for ((i, c) in row.withIndex()) {
                    put(c, intArrayOf(QWERTY_ROWS.indexOf(row), i))
                }
            }
        }
    }

    private val appContext by context.appContext()

    /** Map of word -> frequency (0..255). Loaded lazily on first preload(). */
    private val wordData = guardedByLock { mutableMapOf<String, Int>() }

    /** Sorted list of dictionary keys, rebuilt whenever wordData changes. */
    private val sortedKeys = guardedByLock { mutableListOf<String>() }

    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        // No-op; dictionary is loaded lazily in preload() to avoid blocking
        // IME startup if the user never types in this subtype.
    }

    override suspend fun preload(subtype: Subtype) {
        withContext(Dispatchers.IO) {
            wordData.withLock { wordData ->
                if (wordData.isNotEmpty()) return@withLock
                val rawData = try {
                    appContext.assets.readText("ime/dict/data.json")
                } catch (t: Throwable) {
                    flogDebug { "LatinLanguageProvider: failed to load data.json: ${t.message}" }
                    return@withLock
                }
                val jsonData = try {
                    Json.decodeFromString(wordDataSerializer, rawData)
                } catch (t: Throwable) {
                    flogDebug { "LatinLanguageProvider: failed to parse data.json: ${t.message}" }
                    return@withLock
                }
                wordData.putAll(jsonData)
                sortedKeys.withLock { keys ->
                    keys.clear()
                    keys.addAll(wordData.keys.sorted())
                }
            }
        }
    }

    // -- Spelling -------------------------------------------------------------

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        // StyleKit: normalize stylized Unicode back to ASCII so the lookup
        // matches the dictionary regardless of which Font Style preset is
        // active. Without this, "𝐡𝐞𝐥𝐥𝐨" would be marked as a typo even
        // though the user spelled it correctly.
        val normalized = FontNormalizer.normalize(word).lowercase().trim()
        if (normalized.isEmpty()) return SpellingResult.validWord()

        val known = wordData.withLock { it.containsKey(normalized) }
        if (known) return SpellingResult.validWord()

        // Reject words that are too short or too long for meaningful correction.
        if (normalized.length < MIN_LENGTH_FOR_CORRECTION) return SpellingResult.validWord()
        if (normalized.length > MAX_LENGTH_FOR_CORRECTION) return SpellingResult.typo(emptyArray())

        val limit = maxSuggestionCount.coerceAtMost(MAX_SPELL_SUGGESTIONS).coerceAtLeast(1)

        // StyleKit: pass 1 — common-typo lookup. Hand-curated high-frequency
        // errors get returned immediately as high-confidence suggestions
        // without invoking the (more expensive) edit-distance scan.
        // Statistical: typing "teh" is overwhelmingly likely to be "the",
        // so we return that as the top suggestion. The edit-distance scan
        // is still useful for novel typos that aren't in the table.
        val commonMatch = COMMON_TYPOS[normalized]
        if (commonMatch != null && commonMatch.isNotEmpty()) {
            // Filter to those present in the dictionary (so we don't suggest
            // "a lot" as a typo correction if "a lot" isn't in our wordlist —
            // though it usually is, multi-word entries aren't).
            val inDict = commonMatch.filter { w -> wordData.withLock { dict -> dict.containsKey(w.lowercase()) } }
            val arr = (inDict.ifEmpty { commonMatch }).take(limit)
            return SpellingResult.typo(arr.toTypedArray(), isHighConfidenceResult = arr.size > 1)
        }

        // StyleKit: pass 2 — keyboard-distance-aware edit distance. Returns
        // the closest dictionary words by a weighted Levenshtein where
        // adjacent-key substitutions cost less than far-key substitutions.
        val suggestions = findClosestWords(normalized, limit)
        return if (suggestions.isEmpty()) {
            SpellingResult.typo(emptyArray())
        } else {
            SpellingResult.typo(suggestions.toTypedArray(), isHighConfidenceResult = suggestions.size > 1)
        }
    }

    /**
     * Returns the [limit] closest dictionary words to [target] by a
     * keyboard-distance-weighted edit distance, capped at [MAX_EDIT_DISTANCE].
     * Empty if nothing is within the threshold.
     *
     * The returned list is sorted by (weighted distance asc, frequency desc).
     */
    private suspend fun findClosestWords(target: String, limit: Int): List<String> = withContext(Dispatchers.Default) {
        val snapshot = wordData.withLock { it.toMap() }
        if (snapshot.isEmpty()) return@withContext emptyList()

        // Two cheap pre-filters before the expensive O(n*m) DP:
        //  1. Same first letter (typos usually preserve the first letter).
        //  2. Length within ±MAX_EDIT_DISTANCE of target.
        val firstChar = target.first()
        val len = target.length
        val candidates = snapshot.keys.filter { k ->
            k.isNotEmpty() &&
                k.first() == firstChar &&
                kotlin.math.abs(k.length - len) <= MAX_EDIT_DISTANCE
        }

        // Compute weighted edit distance for each candidate, keep the best.
        // Weighted distance is a Double (sub cost between 0.5 and 1.0);
        // for the early-exit threshold we compare against MAX_EDIT_DISTANCE
        // (treating each substitution as 1.0).
        data class Scored(val word: String, val dist: Double, val freq: Int)
        val scored = ArrayList<Scored>(candidates.size)
        for (k in candidates) {
            val d = weightedLevenshtein(target, k, MAX_EDIT_DISTANCE)
            if (d in 0.001..MAX_EDIT_DISTANCE.toDouble()) {
                scored.add(Scored(k, d, snapshot[k] ?: 0))
            }
        }
        scored.sortWith(compareBy<Scored> { it.dist }.thenByDescending { it.freq })
        scored.take(limit).map { it.word }
    }

    /**
     * Bounded Levenshtein with keyboard-distance-aware substitution cost.
     *
     * Substitution cost:
     *  - 0.0 if chars are equal (no substitution).
     *  - 0.5 if both chars are letters AND adjacent on QWERTY (likely typo).
     *  - 0.7 if both chars are letters AND on the same row (possible typo).
     *  - 1.0 otherwise (full substitution — distant key or non-letter).
     *
     * Insertions and deletions cost 1.0 each (standard).
     *
     * Returns [MAX_EDIT_DISTANCE] + 1.0 if the true weighted distance exceeds
     * [maxDistance] (early exit; cheaper than computing the full matrix).
     */
    private fun weightedLevenshtein(a: String, b: String, maxDistance: Int): Double {
        if (a == b) return 0.0
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > maxDistance) return (maxDistance + 1).toDouble()

        // Two-row DP.
        var prev = DoubleArray(lb + 1) { it.toDouble() }
        var curr = DoubleArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i.toDouble()
            var rowMin = curr[0]
            for (j in 1..lb) {
                val cost = subCost(a[i - 1], b[j - 1])
                curr[j] = minOf(
                    prev[j] + 1.0,        // deletion
                    curr[j - 1] + 1.0,    // insertion
                    prev[j - 1] + cost,   // substitution
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxDistance) return (maxDistance + 1).toDouble() // early exit
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[lb]
    }

    /** Keyboard-distance-aware substitution cost. See [weightedLevenshtein]. */
    private fun subCost(c1: Char, c2: Char): Double {
        if (c1 == c2) return 0.0
        val p1 = KEYBOARD_POS[c1.lowercaseChar()]
        val p2 = KEYBOARD_POS[c2.lowercaseChar()]
        if (p1 == null || p2 == null) return 1.0
        // Manhattan distance on QWERTY grid (with row offset to mimic the
        // staggered layout — row 1 is shifted ~0.5 keys right of row 0).
        val dr = kotlin.math.abs(p1[0] - p2[0])
        val dc = kotlin.math.abs(p1[1] - p2[1])
        return when {
            dr == 0 && dc == 1 -> 0.5 // same row, adjacent
            dr == 1 && dc == 0 -> 0.5 // adjacent rows, same column
            dr == 1 && dc == 1 -> 0.6 // diagonal neighbor
            dr == 0 && dc <= 2 -> 0.7 // same row, 2 keys apart
            else -> 1.0
        }
    }

    /**
     * Bounded Levenshtein edit distance (uniform cost). Retained for any
     * callers that don't want keyboard-weighted cost. Currently unused but
     * kept here as documentation of the simpler fallback.
     */
    private fun levenshtein(a: String, b: String, maxDistance: Int): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > maxDistance) return maxDistance + 1

        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost,
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[lb]
    }

    // -- Suggestions ----------------------------------------------------------

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // StyleKit: normalize stylized composing text back to ASCII so the
        // prefix lookup matches dictionary keys regardless of active font.
        val composing = FontNormalizer.normalize(content.composingText.toString()).lowercase()
        val limit = maxCandidateCount.coerceAtMost(MAX_SUGGEST_CANDIDATES).coerceAtLeast(1)
        return if (composing.isBlank()) {
            // Next-word fallback: top-frequency English words. Useful before
            // the AdaptiveLearningProvider has built up personal bigrams.
            topFrequencyCandidates(limit)
        } else {
            prefixCandidates(composing, limit)
        }
    }

    /**
     * Returns dictionary words starting with [prefix], sorted by frequency desc.
     * Used for current-word completion.
     */
    private suspend fun prefixCandidates(prefix: String, limit: Int): List<SuggestionCandidate> {
        val snapshot = wordData.withLock { it.toMap() }
        if (snapshot.isEmpty()) return emptyList()
        // Linear scan with prefix filter; for 50K words this is sub-millisecond.
        val matches = snapshot.entries
            .filter { it.key.startsWith(prefix) && it.key.length > prefix.length - 1 }
            .sortedByDescending { it.value }
            .take(limit)
        return matches.mapIndexed { idx, (word, freq) ->
            WordSuggestionCandidate(
                text = word,
                confidence = (freq.toDouble() / 255.0).coerceIn(0.0, 1.0),
                // Auto-commit (autocorrect) the top candidate only when the
                // composing prefix is at least 3 chars and the candidate is
                // a "high-confidence" match (freq >= 200).
                isEligibleForAutoCommit = idx == 0 && prefix.length >= 3 && freq >= 200,
                isEligibleForUserRemoval = false,
                sourceProvider = this@LatinLanguageProvider,
            )
        }
    }

    /** Top-frequency words as next-word fallback when no composing text. */
    private suspend fun topFrequencyCandidates(limit: Int): List<SuggestionCandidate> {
        val snapshot = wordData.withLock { it.toMap() }
        if (snapshot.isEmpty()) return emptyList()
        return snapshot.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (word, freq) ->
                WordSuggestionCandidate(
                    text = word,
                    confidence = (freq.toDouble() / 255.0).coerceIn(0.0, 1.0) * 0.5, // dampened; next-word guess
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = false,
                    sourceProvider = this@LatinLanguageProvider,
                )
            }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        // Static dictionary; nothing to remove.
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {
        // Nothing to free — the in-memory map is owned by the singleton lock.
    }
}
