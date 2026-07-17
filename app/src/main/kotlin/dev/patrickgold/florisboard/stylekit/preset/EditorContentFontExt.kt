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

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange

/**
 * Returns a copy of this [EditorContent] with [EditorContent.text] normalized
 * back to plain ASCII (via [FontNormalizer.normalizeWithMap]) and all ranges
 * ([EditorContent.localSelection], [EditorContent.localComposing],
 * [EditorContent.localCurrentWord]) remapped to match.
 *
 * Why this exists: when a Font Style preset is active, everything the user
 * types in the target editor is stylized Unicode (e.g. "𝗁𝖾𝗅𝗅𝗈"), not the
 * ASCII the on-device dictionaries and spell-checkers were built against.
 * Without this, autocorrect/suggestions/emoji-suggestions effectively stop
 * working the moment a preset is turned on — they only ever "see" text that
 * doesn't match anything in the dictionary. Feeding suggestion/spelling
 * providers this normalized snapshot instead of the raw one fixes that for
 * *every* preset (built-in or user-created), not just the ones a provider
 * happens to special-case.
 *
 * If the text is already plain ASCII this returns `this` unchanged (no
 * allocation), so the fast path for users who don't use Font Style presets
 * is untouched.
 */
fun EditorContent.fontNormalized(): EditorContent {
    if (text.isEmpty() || text.all { it.code <= 0x7F }) return this
    val result = FontNormalizer.normalizeWithMap(text)
    if (result.text == text) return this

    fun remap(range: EditorRange): EditorRange {
        if (range.isNotValid) return range
        val maxIdx = result.map.size - 1
        val start = result.map[range.start.coerceIn(0, maxIdx)]
        val end = result.map[range.end.coerceIn(0, maxIdx)]
        return EditorRange(start, end)
    }

    return copy(
        text = result.text,
        localSelection = remap(localSelection),
        localComposing = remap(localComposing),
        localCurrentWord = remap(localCurrentWord),
    )
}
