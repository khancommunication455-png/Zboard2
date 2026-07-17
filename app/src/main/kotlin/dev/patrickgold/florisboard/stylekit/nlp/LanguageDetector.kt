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

/** Result of classifying a single typed word. */
enum class WordLanguageGuess { ENGLISH, ROMAN_URDU, UNKNOWN }

/**
 * Lightweight, on-device, per-word classifier that guesses whether a word the user
 * just typed "looks like" Roman Urdu or English, so [RomanUrduSuggestionProvider]
 * knows which wordlist to correct it against.
 *
 * This is deliberately *not* a full language-ID model — no native deps, no bundled
 * model file, negligible CPU cost per keystroke. It works by building a character
 * bigram frequency profile from each of the two known wordlists once (English: a
 * built-in high-frequency function-word list; Roman Urdu: [RomanUrduDictionary]),
 * then scoring an unknown word by how "typical" its bigrams are of each profile.
 *
 * This is the same idea production language-ID systems (e.g. langid.py) use at
 * small scale, just trimmed down to two languages and word-level granularity
 * instead of document-level.
 */
object LanguageDetector {

    // A compact set of extremely common English function words/word-shapes, used
    // only to build a bigram profile — not a full dictionary. English words not in
    // this seed list are still classified fine via their bigram shape.
    private val englishSeed = listOf(
        "the", "and", "you", "for", "are", "with", "this", "that", "have", "will",
        "not", "but", "what", "all", "were", "when", "your", "can", "said", "there",
        "each", "which", "she", "how", "their", "will", "other", "about", "out",
        "many", "then", "them", "these", "some", "her", "would", "make", "like",
        "him", "into", "time", "has", "look", "two", "more", "write", "see", "number",
        "way", "could", "people", "than", "first", "water", "been", "call", "who",
        "its", "now", "find", "long", "down", "day", "did", "get", "come", "made",
        "may", "part", "over", "know", "hello", "please", "thanks", "okay", "good",
        "morning", "night", "work", "home", "phone", "message", "call", "meeting",
    )

    private val englishProfile: Map<String, Int> by lazy { buildBigramProfile(englishSeed) }
    private val urduProfile: Map<String, Int> by lazy { buildBigramProfile(RomanUrduDictionary.allWords()) }

    private fun buildBigramProfile(words: Collection<String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (word in words) {
            val padded = "^$word$"
            for (i in 0 until padded.length - 1) {
                val bigram = padded.substring(i, i + 2)
                counts[bigram] = (counts[bigram] ?: 0) + 1
            }
        }
        return counts
    }

    private fun score(word: String, profile: Map<String, Int>): Double {
        val padded = "^$word$"
        var score = 0.0
        var total = 0
        for (i in 0 until padded.length - 1) {
            val bigram = padded.substring(i, i + 2)
            score += profile[bigram]?.toDouble() ?: 0.0
            total++
        }
        return if (total == 0) 0.0 else score / total
    }

    /**
     * Classifies a single word as English, Roman Urdu, or unknown (too short /
     * ambiguous / contains non-letters). Exact dictionary hits short-circuit to a
     * confident answer; everything else falls back to the bigram scoring.
     */
    fun classify(word: String): WordLanguageGuess {
        val w = word.lowercase().trim()
        if (w.length < 2 || !w.all { it.isLetter() }) return WordLanguageGuess.UNKNOWN

        // Exact match in the Roman Urdu dictionary is a strong, cheap signal.
        if (RomanUrduDictionary.contains(w)) return WordLanguageGuess.ROMAN_URDU

        val urduScore = score(w, urduProfile)
        val englishScore = score(w, englishProfile)

        // Require a reasonable margin before committing to a guess, otherwise
        // stay UNKNOWN and let the caller skip correction rather than "correct"
        // a perfectly fine word in the wrong direction.
        val margin = 0.15
        return when {
            urduScore <= 0.0 && englishScore <= 0.0 -> WordLanguageGuess.UNKNOWN
            urduScore > englishScore * (1 + margin) -> WordLanguageGuess.ROMAN_URDU
            englishScore > urduScore * (1 + margin) -> WordLanguageGuess.ENGLISH
            else -> WordLanguageGuess.UNKNOWN
        }
    }
}
