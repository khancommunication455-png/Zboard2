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
 * Pure, allocation-conscious Unicode transform engine for Font Style presets.
 *
 * Each preset is just a `Map<String, String>` from source character to replacement
 * string. The two entry points are:
 *
 *   - [convertText]: bulk transform of a whole input string. Single StringBuilder
 *     pass, O(n). Used by the "convert & copy" flow in settings.
 *   - [convertChar]: per-keystroke transform. Single map lookup, ~constant time.
 *     Used by the live-keyboard mode where the active preset is applied as the
 *     user types.
 *
 * Performance budget: per-keystroke path must stay well under 100µs so it never
 * delays character commit. Map lookup is the only allocation (and it's avoided if
 * the active preset is empty).
 *
 * Ported from the original Style Keyboard implementation, with the following
 * differences:
 *  - Multi-char source keys are still NOT supported in [convertChar] (it would
 *    require look-behind state). [convertText] could support them but doesn't,
 *    to keep the live/bulk paths identical.
 *  - [emptyMapping] returns a complete A–Z / a–z / 0–9 / common-punct seed map
 *    so the preset editor can show a full table by default.
 *  - [bulkPasteAssign] lets the user paste a string of symbols and have them
 *    auto-assigned to a–z / A–Z / 0–9 in order.
 */
object TextConverter {

    /** O(n) single-StringBuilder pass. Bench target: ~5µs/keystroke. */
    fun convertText(inputString: String, presetMap: Map<String, String>): String {
        if (inputString.isEmpty()) return ""
        if (presetMap.isEmpty()) return inputString
        val len = inputString.length
        val out = StringBuilder(len + (len shr 2))
        for (i in 0 until len) {
            val ch = inputString[i]
            val replacement = presetMap[ch.toString()]
            if (replacement != null) out.append(replacement) else out.append(ch)
        }
        return out.toString()
    }

    /** Per-keystroke path. Multi-char source keys NOT supported here. */
    fun convertChar(ch: Char, presetMap: Map<String, String>): String =
        presetMap[ch.toString()] ?: ch.toString()

    /** Returns a seed mapping covering A–Z, a–z, 0–9, common punctuation. */
    fun emptyMapping(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>(128)
        for (c in 'A'..'Z') map[c.toString()] = c.toString()
        for (c in 'a'..'z') map[c.toString()] = c.toString()
        for (c in '0'..'9') map[c.toString()] = c.toString()
        for (c in listOf(' ', '.', ',', '!', '?', '\'', '"', '-', '_', '@', '#', '$', '%', '&', '*', '(', ')')) {
            map[c.toString()] = c.toString()
        }
        return map
    }

    /**
     * Sequentially assigns non-whitespace symbols in [source] to A–Z, a–Z, 0–9
     * (in that order), starting from [base] so the user can incrementally extend
     * an existing preset. Used by the "Bulk paste" button in the preset editor.
     */
    fun bulkPasteAssign(source: String, base: Map<String, String> = emptyMapping()): Map<String, String> {
        val out = LinkedHashMap(base)
        val targets = ArrayList<String>(out.keys)
        val symbols = source.split(Regex("\\s+")).filter { it.isNotBlank() }
        var i = 0
        for (sym in symbols) {
            if (i >= targets.size) break
            out[targets[i]] = sym
            i++
        }
        return out
    }
}
