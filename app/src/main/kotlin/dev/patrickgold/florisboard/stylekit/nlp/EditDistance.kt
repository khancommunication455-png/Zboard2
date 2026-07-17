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

/**
 * Small, dependency-free Damerau-Levenshtein (optimal string alignment variant)
 * implementation used for spell correction. Counts insertions, deletions,
 * substitutions and adjacent transpositions as single edits — which matches the
 * kind of typo a thumb keyboard actually produces ("hte" -> "the").
 *
 * Kept intentionally simple (no native code, no allocation-heavy DP tricks) since
 * it only ever runs against short words (a handful of characters) and a bounded
 * candidate list.
 */
object EditDistance {

    /**
     * Returns the edit distance between [a] and [b], but stops early and returns
     * [maxDistance] + 1 as soon as it's clear the true distance exceeds [maxDistance].
     * This keeps correction cheap even when scanning a few hundred candidate words.
     */
    fun distance(a: String, b: String, maxDistance: Int = 2): Int {
        val lenDiff = kotlin.math.abs(a.length - b.length)
        if (lenDiff > maxDistance) return maxDistance + 1

        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        // d[i][j] = edit distance between a[0..i) and b[0..j)
        val d = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) d[i][0] = i
        for (j in 0..n) d[0][j] = j

        for (i in 1..m) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(
                    d[i - 1][j] + 1,      // deletion
                    d[i][j - 1] + 1,      // insertion
                    d[i - 1][j - 1] + cost, // substitution
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 1) // transposition
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDistance) return maxDistance + 1
        }
        return d[m][n]
    }
}
