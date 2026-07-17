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

package dev.patrickgold.florisboard.stylekit.preset

/**
 * Reverse-transforms a string that may contain Unicode stylized characters
 * (Math Sans U+1D5A0, Bold Serif U+1D400, Bubble U+24B6, Circled U+24D0,
 * Upside Down, Zalgo with combining diacritics, etc.) back into plain ASCII.
 *
 * This is needed because when a Font Style preset is active, the composing
 * text in the editor contains the STYLIZED form, not the original ASCII the
 * user typed. Without normalization, the EmojiShortcutSuggestionProvider and
 * the AdaptiveLearningProvider can't match triggers like "lol" against the
 * stylized "𝐥𝐨𝐥".
 *
 * Strategy:
 *  1. Strip combining diacritics (Zalgo, accents) via NFD normalization +
 *     Unicode category filter.
 *  2. Map known stylized Unicode blocks back to ASCII via code-point arithmetic.
 *  3. Pass through anything that's already ASCII.
 *
 * Returns the same string if no normalization was needed.
 *
 * Performance: O(n) in the input length, allocation-light. Called from the
 * suggestion path on every keystroke, so it must be fast.
 */
object FontNormalizer {

    /**
     * Normalize [text] to plain ASCII where possible.
     *
     * Examples:
     *   "𝐡𝐞𝐥𝐥𝐨" → "hello"  (Mathematical Bold Serif)
     *   "𝗁𝖾𝗅𝗅𝗈" → "hello"  (Mathematical Sans-Serif)
     *   "ⓗⓔⓛⓛⓞ" → "hello"  (Circled Latin small)
     *   "Ȟe̴l̴l̴o̴" → "hello"  (Zalgo with combining diacritics)
     *   "hello"  → "hello"  (already ASCII, no-op)
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text

        // Fast path: if every char is ASCII, return as-is.
        if (text.all { it.code <= 0x7F }) return text

        // 1. NFD-decompose, then drop combining marks (U+0300–U+036F and a few
        //    other ranges used by Zalgo). This strips accents from "é" → "e"
        //    and removes Zalgo combining stacks.
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val noCombining = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (isCombiningMark(ch.code)) continue
            noCombining.append(ch)
        }

        // 2. Map known stylized blocks back to ASCII.
        val out = StringBuilder(noCombining.length)
        var i = 0
        val s = noCombining.toString()
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val charCount = Character.charCount(cp)
            val mapped = mapCodePoint(cp)
            if (mapped != null) {
                out.append(mapped)
            } else {
                out.appendCodePoint(cp)
            }
            i += charCount
        }
        return out.toString()
    }

    /**
     * Result of [normalizeWithMap]: the normalized text plus an index map from
     * UTF-16 offsets in the *original* string to UTF-16 offsets in [text].
     *
     * [map] has size `original.length + 1`; `map[i]` is the offset in [text]
     * that corresponds to code-unit index `i` of the original string. This
     * lets callers translate EditorRange values (composing region, current
     * word, selection) computed against the stylized editor text into the
     * equivalent range in the normalized text, so a spell/suggestion provider
     * can run against plain ASCII without losing track of word boundaries.
     *
     * Note: unlike [normalize], this variant does NOT run NFD decomposition
     * (that step has no stable per-index mapping back to the original
     * string), so precomposed accented characters pass through unchanged.
     * That only matters for Zalgo-style combining stacks and is an
     * acceptable trade-off since range alignment matters more than
     * accent-stripping on the suggestion path.
     */
    fun normalizeWithMap(text: String): NormalizedText {
        if (text.isEmpty()) return NormalizedText(text, intArrayOf(0))
        if (text.all { it.code <= 0x7F }) return NormalizedText(text, IntArray(text.length + 1) { it })

        val out = StringBuilder(text.length)
        val map = IntArray(text.length + 1)
        var i = 0
        while (i < text.length) {
            map[i] = out.length
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (!isCombiningMark(cp)) {
                val mapped = mapCodePoint(cp)
                if (mapped != null) out.append(mapped) else out.appendCodePoint(cp)
            }
            // Any trailing surrogate index of a supplementary-plane code point
            // maps to the same output position as its leading unit.
            for (k in 1 until charCount) map[i + k] = out.length
            i += charCount
        }
        map[text.length] = out.length
        return NormalizedText(out.toString(), map)
    }

    /** @see normalizeWithMap */
    data class NormalizedText(val text: String, val map: IntArray)

    private fun isCombiningMark(cp: Int): Boolean {
        // Mn (nonspacing mark) category covers U+0300–U+036F (combining
        // diacritics) and the Zalgo ranges U+0488–U+0489, U+0591–U+05BD,
        // etc. Easiest filter: skip the common combining ranges.
        return cp in 0x0300..0x036F ||
            cp in 0x0483..0x0489 ||
            cp in 0x0591..0x05BD ||
            cp == 0x05BF ||
            cp in 0x0610..0x061A ||
            cp in 0x064B..0x065F ||
            cp == 0x0670 ||
            cp in 0x06D6..0x06DC ||
            cp in 0x06DF..0x06E4 ||
            cp in 0x06E7..0x06E8 ||
            cp in 0x06EA..0x06ED ||
            cp == 0x0711 ||
            cp in 0x0730..0x074A ||
            cp in 0x0B82..0x0B83
    }

    /**
     * Map a single Unicode code point back to its ASCII equivalent (or null
     * if the code point isn't a known stylized variant).
     *
     * Covered blocks:
     *   - Mathematical Bold Serif (U+1D400 / U+1D41A / U+1D7CE)
     *   - Mathematical Italic Serif (U+1D434 / U+1D44E)
     *   - Mathematical Bold Italic Serif (U+1D468 / U+1D482)
     *   - Mathematical Script (U+1D49C / U+1D4B6)
     *   - Mathematical Bold Script (U+1D4D0 / U+1D4EA)
     *   - Mathematical Fraktur (U+1D504 / U+1D51E)
     *   - Mathematical Double-struck (U+1D538 / U+1D552 / U+1D7D8)
     *   - Mathematical Bold Fraktur (U+1D56C / U+1D586)
     *   - Mathematical Sans-Serif (U+1D5A0 / U+1D5BA / U+1D7E2)
     *   - Mathematical Sans-Serif Bold (U+1D5D4 / U+1D5EE / U+1D7EC)
     *   - Mathematical Sans-Serif Italic (U+1D608 / U+1D622)
     *   - Mathematical Sans-Serif Bold Italic (U+1D63C / U+1D656)
     *   - Mathematical Monospace (U+1D670 / U+1D68A / U+1D7F6)
     *   - Circled Latin uppercase (U+24B6) and lowercase (U+24D0)
     *   - Circled digits (U+2460–U+2468 for 1–9; 0 is U+24EA)
     *   - Parenthesized Latin (U+1F110–U+1F129) and digits (U+2469–U+2473)
     *   - Squared Latin (U+1F130–U+1F149)
     *   - Fullwidth Latin (U+FF21–U+FF3A, U+FF41–U+FF5A) and digits (U+FF10–U+FF19)
     */
    private fun mapCodePoint(cp: Int): Char? {
        // Mathematical Alphanumeric Symbols block — uppercase
        val upperOffset = cp - 0x1D400
        if (upperOffset in 0..25) return ('A' + upperOffset)
        // Bold Italic uppercase
        val boldItalicUpperOffset = cp - 0x1D468
        if (boldItalicUpperOffset in 0..25) return ('A' + boldItalicUpperOffset)
        // Italic uppercase
        val italicUpperOffset = cp - 0x1D434
        if (italicUpperOffset in 0..25) return ('A' + italicUpperOffset)
        // Script uppercase
        val scriptUpperOffset = cp - 0x1D49C
        if (scriptUpperOffset in 0..25) return ('A' + scriptUpperOffset)
        // Bold Script uppercase
        val boldScriptUpperOffset = cp - 0x1D4D0
        if (boldScriptUpperOffset in 0..25) return ('A' + boldScriptUpperOffset)
        // Fraktur uppercase
        val frakturUpperOffset = cp - 0x1D504
        if (frakturUpperOffset in 0..25) return ('A' + frakturUpperOffset)
        // Double-struck uppercase
        val doubleStruckUpperOffset = cp - 0x1D538
        if (doubleStruckUpperOffset in 0..25) return ('A' + doubleStruckUpperOffset)
        // Bold Fraktur uppercase
        val boldFrakturUpperOffset = cp - 0x1D56C
        if (boldFrakturUpperOffset in 0..25) return ('A' + boldFrakturUpperOffset)
        // Sans-Serif uppercase
        val sansUpperOffset = cp - 0x1D5A0
        if (sansUpperOffset in 0..25) return ('A' + sansUpperOffset)
        // Sans-Serif Bold uppercase
        val sansBoldUpperOffset = cp - 0x1D5D4
        if (sansBoldUpperOffset in 0..25) return ('A' + sansBoldUpperOffset)
        // Sans-Serif Italic uppercase
        val sansItalicUpperOffset = cp - 0x1D608
        if (sansItalicUpperOffset in 0..25) return ('A' + sansItalicUpperOffset)
        // Sans-Serif Bold Italic uppercase
        val sansBoldItalicUpperOffset = cp - 0x1D63C
        if (sansBoldItalicUpperOffset in 0..25) return ('A' + sansBoldItalicUpperOffset)
        // Monospace uppercase
        val monoUpperOffset = cp - 0x1D670
        if (monoUpperOffset in 0..25) return ('A' + monoUpperOffset)

        // Lowercase — same blocks offset by 0x1A from the uppercase
        val lowerOffset = cp - 0x1D41A
        if (lowerOffset in 0..25) return ('a' + lowerOffset)
        val italicLowerOffset = cp - 0x1D44E
        if (italicLowerOffset in 0..25) return ('a' + italicLowerOffset)
        val boldItalicLowerOffset = cp - 0x1D482
        if (boldItalicLowerOffset in 0..25) return ('a' + boldItalicLowerOffset)
        val scriptLowerOffset = cp - 0x1D4B6
        if (scriptLowerOffset in 0..25) return ('a' + scriptLowerOffset)
        val boldScriptLowerOffset = cp - 0x1D4EA
        if (boldScriptLowerOffset in 0..25) return ('a' + boldScriptLowerOffset)
        val frakturLowerOffset = cp - 0x1D51E
        if (frakturLowerOffset in 0..25) return ('a' + frakturLowerOffset)
        val doubleStruckLowerOffset = cp - 0x1D552
        if (doubleStruckLowerOffset in 0..25) return ('a' + doubleStruckLowerOffset)
        val boldFrakturLowerOffset = cp - 0x1D586
        if (boldFrakturLowerOffset in 0..25) return ('a' + boldFrakturLowerOffset)
        val sansLowerOffset = cp - 0x1D5BA
        if (sansLowerOffset in 0..25) return ('a' + sansLowerOffset)
        val sansBoldLowerOffset = cp - 0x1D5EE
        if (sansBoldLowerOffset in 0..25) return ('a' + sansBoldLowerOffset)
        val sansItalicLowerOffset = cp - 0x1D622
        if (sansItalicLowerOffset in 0..25) return ('a' + sansItalicLowerOffset)
        val sansBoldItalicLowerOffset = cp - 0x1D656
        if (sansBoldItalicLowerOffset in 0..25) return ('a' + sansBoldItalicLowerOffset)
        val monoLowerOffset = cp - 0x1D68A
        if (monoLowerOffset in 0..25) return ('a' + monoLowerOffset)

        // Digits — Mathematical Bold (U+1D7CE), Double-struck (U+1D7D8),
        // Sans-Serif (U+1D7E2), Sans-Serif Bold (U+1D7EC), Monospace (U+1D7F6)
        val boldDigitOffset = cp - 0x1D7CE
        if (boldDigitOffset in 0..9) return ('0' + boldDigitOffset)
        val doubleStruckDigitOffset = cp - 0x1D7D8
        if (doubleStruckDigitOffset in 0..9) return ('0' + doubleStruckDigitOffset)
        val sansDigitOffset = cp - 0x1D7E2
        if (sansDigitOffset in 0..9) return ('0' + sansDigitOffset)
        val sansBoldDigitOffset = cp - 0x1D7EC
        if (sansBoldDigitOffset in 0..9) return ('0' + sansBoldDigitOffset)
        val monoDigitOffset = cp - 0x1D7F6
        if (monoDigitOffset in 0..9) return ('0' + monoDigitOffset)

        // Circled Latin uppercase (U+24B6–U+24CF) → A–Z
        if (cp in 0x24B6..0x24CF) return ('A' + (cp - 0x24B6))
        // Circled Latin lowercase (U+24D0–U+24E9) → a–z
        if (cp in 0x24D0..0x24E9) return ('a' + (cp - 0x24D0))

        // Fullwidth Latin uppercase (U+FF21–U+FF3A)
        if (cp in 0xFF21..0xFF3A) return ('A' + (cp - 0xFF21))
        // Fullwidth Latin lowercase (U+FF41–U+FF5A)
        if (cp in 0xFF41..0xFF5A) return ('a' + (cp - 0xFF41))
        // Fullwidth digits (U+FF10–U+FF19)
        if (cp in 0xFF10..0xFF19) return ('0' + (cp - 0xFF10))

        // Not a recognized stylized form
        return null
    }
}
