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
 *  3. Map any user-preset-specific stylized chars back to ASCII via the
 *     reverse mapping registered by [setActivePresetMapping]. This is what
 *     makes emoji shortcuts and the learning model work on EVERY font,
 *     including custom presets the user creates (which may use arbitrary
 *     Unicode code points the built-in mapping doesn't know about).
 *  4. Pass through anything that's already ASCII.
 *
 * Returns the same string if no normalization was needed.
 *
 * Performance: O(n) in the input length, allocation-light. Called from the
 * suggestion path on every keystroke, so it must be fast.
 */
object FontNormalizer {

    /**
     * Result of [normalizeWithMap]: the normalized [text], plus [map] — an
     * index table of size `original.length + 1` where `map[i]` gives the
     * offset in [text] corresponding to original char-index `i`. Used to
     * translate cursor/selection/composing ranges from the raw (stylized)
     * editor text to the normalized text and back.
     */
    data class NormalizeResult(val text: String, val map: List<Int>)

    /**
     * Same normalization as [normalize], but also returns a position map so
     * callers (see [dev.patrickgold.florisboard.stylekit.preset.fontNormalized])
     * can remap [dev.patrickgold.florisboard.ime.editor.EditorRange]s (selection,
     * composing region, current word) to match the normalized text.
     *
     * Processes the input one code point at a time (rather than doing a
     * whole-string NFD pass like [normalize]) specifically so each output
     * chunk can be tied back to a single original index — this sacrifices
     * nothing for the common case (each stylized code point maps to exactly
     * one ASCII char) and degrades gracefully for multi-code-point cases
     * (Zalgo combining stacks collapse to their base char at the index of
     * the base char).
     */
    fun normalizeWithMap(text: String): NormalizeResult {
        if (text.isEmpty()) return NormalizeResult(text, listOf(0))
        if (userReverseMapping.isEmpty() && text.all { it.code <= 0x7F }) {
            return NormalizeResult(text, (0..text.length).toList())
        }

        val map = IntArray(text.length + 1)
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            map[i] = out.length
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            val original = String(Character.toChars(cp))

            // User-preset reverse mapping takes priority (single-codepoint
            // stylized forms only — multi-codepoint substring rules can't be
            // position-mapped generically, so those still work via
            // [normalize], just not via this indexed variant).
            val userMapped = userReverseMapping[original]
            if (userMapped != null) {
                out.append(userMapped)
            } else {
                val decomposed = java.text.Normalizer.normalize(original, java.text.Normalizer.Form.NFD)
                val filtered = StringBuilder()
                for (ch in decomposed) {
                    val c = ch.code
                    if (c in 0x0300..0x036F) continue
                    if (c in 0x0483..0x0489) continue
                    if (c in 0x0591..0x05BD) continue
                    if (c == 0x05BF) continue
                    if (c in 0x0610..0x061A) continue
                    if (c in 0x064B..0x065F) continue
                    if (c == 0x0670) continue
                    if (c in 0x06D6..0x06DC) continue
                    if (c in 0x06DF..0x06E4) continue
                    if (c in 0x06E7..0x06E8) continue
                    if (c in 0x06EA..0x06ED) continue
                    if (c == 0x0711) continue
                    if (c in 0x0730..0x074A) continue
                    if (c in 0x0B82..0x0B83) continue
                    filtered.append(ch)
                }
                when {
                    filtered.isEmpty() -> {
                        // Fully consumed by combining-mark stripping (rare) — contributes nothing.
                    }
                    filtered.codePointCount(0, filtered.length) == 1 -> {
                        val fcp = filtered.codePointAt(0)
                        val mapped = mapCodePoint(fcp)
                        if (mapped != null) out.append(mapped) else out.appendCodePoint(fcp)
                    }
                    else -> out.append(filtered)
                }
            }
            i += charCount
        }
        map[text.length] = out.length
        return NormalizeResult(out.toString(), map.toList())
    }

    /**
     * The reverse of the currently-active preset's mapping, set by
     * [LivePresetApplier] whenever the active preset changes. Keys are
     * stylized strings (the preset's value side); values are the original
     * ASCII chars (the preset's key side). When set, this map is consulted
     * AFTER the built-in Unicode-block mapping so user presets layer on
     * top of the defaults.
     *
     * Volatile so the per-keystroke read in [normalize] never blocks on
     * a stale value visible across threads.
     */
    @Volatile
    private var userReverseMapping: Map<String, String> = emptyMap()

    /**
     * Registers a user-preset reverse mapping so [normalize] can convert
     * custom stylized forms back to ASCII. Called by [LivePresetApplier]
     * whenever the active preset changes (including on first apply after
     * device restart — see LivePresetApplier's retry loop).
     *
     * Pass an empty map to clear the user mapping (preset deactivated).
     */
    fun setActivePresetMapping(forwardMapping: Map<String, String>) {
        userReverseMapping = if (forwardMapping.isEmpty()) {
            emptyMap()
        } else {
            // Build reverse: stylized string -> original char.
            // If multiple ASCII chars map to the same stylized form (rare),
            // the last one in iteration order wins.
            forwardMapping.entries.associate { (k, v) -> v to k }
        }
    }

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

        // Fast path: if every char is ASCII AND there's no user mapping
        // active, return as-is. (If a user mapping IS active, we still need
        // to scan because the user might have ASCII-to-ASCII rules like
        // 'a' → '@' — though that's unusual.)
        if (userReverseMapping.isEmpty() && text.all { it.code <= 0x7F }) return text

        // 1. NFD-decompose, then drop combining marks (U+0300–U+036F and a few
        //    other ranges used by Zalgo). This strips accents from "é" → "e"
        //    and removes Zalgo combining stacks.
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val noCombining = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            val cp = ch.code
            // Mn (nonspacing mark) category covers U+0300–U+036F (combining
            // diacritics) and the Zalgo ranges U+0488–U+0489, U+0591–U+05BD,
            // etc. Easiest filter: skip the common combining ranges.
            if (cp in 0x0300..0x036F) continue
            if (cp in 0x0483..0x0489) continue
            if (cp in 0x0591..0x05BD) continue
            if (cp in 0x05BF..0x05BF) continue
            if (cp in 0x0610..0x061A) continue
            if (cp in 0x064B..0x065F) continue
            if (cp in 0x0670..0x0670) continue
            if (cp in 0x06D6..0x06DC) continue
            if (cp in 0x06DF..0x06E4) continue
            if (cp in 0x06E7..0x06E8) continue
            if (cp in 0x06EA..0x06ED) continue
            if (cp == 0x0711) continue
            if (cp in 0x0730..0x074A) continue
            if (cp in 0x0B82..0x0B83) continue
            noCombining.append(ch)
        }

        // 2. Map known stylized blocks back to ASCII. Walk by code point so
        //    we handle surrogate pairs (Mathematical Alphanumeric Symbols
        //    live in the supplementary plane and require 2 Java chars).
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

        // 3. Apply the user-preset reverse mapping as a string-level pass.
        //    This catches user-defined stylized forms that aren't single
        //    code points (e.g. a preset that maps 'a' to "@\u200B" — two
        //    code points). We do this AFTER the code-point pass so the
        //    code-point pass can handle the standard Unicode blocks even
        //    when a user preset is active.
        val userMap = userReverseMapping
        if (userMap.isNotEmpty()) {
            var replaced = out.toString()
            for ((stylized, original) in userMap) {
                if (stylized.isNotEmpty() && stylized != original) {
                    replaced = replaced.replace(stylized, original)
                }
            }
            return replaced
        }

        return out.toString()
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

        // Cross-script "lookalike" characters commonly used in hand-styled
        // text (e.g. "ʟ๏ʟ"). These aren't a contiguous Unicode block like the
        // math-alphanumeric ranges above, so they're listed individually.
        // Not exhaustive -- there are thousands of possible lookalikes across
        // all scripts (see Unicode's confusables.txt) -- but this covers the
        // common ones people actually type for stylized "leet"-style text.
        LOOKALIKE_TABLE[cp]?.let { return it }

        // Not a recognized stylized form
        return null
    }

    private val LOOKALIKE_TABLE: Map<Int, Char> = buildMap {
        // IPA / Phonetic Extensions small-caps block (U+0250–U+02AF, U+1D00–U+1D25)
        // commonly used as fake "small caps" stylized text.
        put(0x0299, 'B'); put(0x1D04, 'C'); put(0x1D05, 'D'); put(0x1D07, 'E')
        put(0xA730, 'F'); put(0x0262, 'G'); put(0x029C, 'H'); put(0x026A, 'I')
        put(0x1D0A, 'J'); put(0x1D0B, 'K'); put(0x029F, 'L'); put(0x1D0D, 'M')
        put(0x0274, 'N'); put(0x1D0F, 'O'); put(0x1D18, 'P'); put(0x0280, 'R')
        put(0xA731, 'S'); put(0x1D1B, 'T'); put(0x1D1C, 'U'); put(0x1D20, 'V')
        put(0x1D21, 'W'); put(0x028F, 'Y'); put(0x1D22, 'Z')
        // Common single-symbol lookalikes for O/0 pulled from other scripts
        // (Thai FONGMAN, etc.) -- the specific ones people actually reach
        // for when styling text.
        put(0x0E4F, 'o')  // ๏ THAI CHARACTER FONGMAN
        put(0x0966, '0')  // ०  DEVANAGARI DIGIT ZERO
        put(0x09E6, '0')  // ০ BENGALI DIGIT ZERO
    }
}
