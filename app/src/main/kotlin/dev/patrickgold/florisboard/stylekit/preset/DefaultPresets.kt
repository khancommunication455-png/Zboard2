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

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The 5 built-in Unicode font presets shipped with StyleKit, plus the JSON codec
 * for persisting a `Map<String,String>` mapping into [PresetEntity.mappingJson].
 *
 * Ported directly from the original Style Keyboard — same preset names, same
 * algorithms, same output. The only change is the package path.
 *
 * Defaults:
 *   1. Math Sans   — codepoint offset into U+1D5A0 / U+1D5BA / U+1D7E2
 *   2. Bold Serif  — codepoint offset into U+1D400 / U+1D41A / U+1D7CE
 *   3. Upside Down — hard-coded char map (A→∀, a→ɐ, …)
 *   4. Zalgo       — letter + 3 random combining diacritics (seeded Random(42))
 *   5. Bubble      — codepoint offset into U+24B6 / U+24D0
 */
object DefaultPresets {
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Encodes a mapping to the JSON form stored in [PresetEntity.mappingJson]. */
    fun encode(map: Map<String, String>): String = json.encodeToString(mapSerializer, map)

    /** Decodes a mapping from the JSON form stored in [PresetEntity.mappingJson]. Returns empty map on failure. */
    fun decode(jsonStr: String): Map<String, String> =
        runCatching { json.decodeFromString(mapSerializer, jsonStr) }.getOrDefault(emptyMap())

    // -- Builders ----------------------------------------------------------------------------

    /** Builds a codepoint-offset preset: A->baseUpper, a->baseLower, 0->baseDigit. */
    private fun offsetPreset(baseUpper: Int, baseLower: Int, baseDigit: Int): Map<String, String> {
        val map = LinkedHashMap<String, String>(128)
        for (i in 0 until 26) {
            map[('A' + i).toString()] = (baseUpper + i).toChar().toString()
            map[('a' + i).toString()] = (baseLower + i).toChar().toString()
        }
        for (i in 0 until 10) {
            map[('0' + i).toString()] = (baseDigit + i).toChar().toString()
        }
        // Pass through space and common punctuation.
        for (c in listOf(' ', '.', ',', '!', '?', '\'', '"', '-', '_', '@', '#', '$', '%', '&', '*', '(', ')')) {
            map[c.toString()] = c.toString()
        }
        return map
    }

    private fun mathSans(): Map<String, String> =
        offsetPreset(baseUpper = 0x1D5A0, baseLower = 0x1D5BA, baseDigit = 0x1D7E2)

    private fun boldSerif(): Map<String, String> =
        offsetPreset(baseUpper = 0x1D400, baseLower = 0x1D41A, baseDigit = 0x1D7CE)

    private fun bubble(): Map<String, String> =
        offsetPreset(baseUpper = 0x24B6, baseLower = 0x24D0, baseDigit = 0x2460)

    private fun upsideDown(): Map<String, String> {
        val upper = "∀qƆpƎℲפHIſʞ˥WNOԀQɹS⊥∩ΛMX⅄Z"
        val lower = "ɐqɔpǝɟƃɥᴉɾʞlɯuodbɹsʇnʌʍxʎz"
        val map = LinkedHashMap<String, String>(128)
        for (i in 0 until 26) {
            map[('A' + i).toString()] = upper[i].toString()
            map[('a' + i).toString()] = lower[i].toString()
        }
        for (i in 0 until 10) map[('0' + i).toString()] = ('0' + i).toString()
        for (c in listOf(' ', '.', ',', '!', '?', '\'', '"', '-', '_', '@', '#', '$', '%', '&', '*', '(', ')')) {
            map[c.toString()] = c.toString()
        }
        return map
    }

    private fun zalgo(): Map<String, String> {
        // Deterministic Zalgo: letter + 3 random combining diacritics (seeded).
        val rng = kotlin.random.Random(42)
        val combiningRange = 0x0300..0x0326
        val map = LinkedHashMap<String, String>(128)
        for (i in 0 until 26) {
            val upper = ('A' + i)
            val lower = ('a' + i)
            val upperZalgo = StringBuilder().apply {
                append(upper)
                repeat(3) { append((combiningRange.random(rng)).toChar()) }
            }.toString()
            val lowerZalgo = StringBuilder().apply {
                append(lower)
                repeat(3) { append((combiningRange.random(rng)).toChar()) }
            }.toString()
            map[upper.toString()] = upperZalgo
            map[lower.toString()] = lowerZalgo
        }
        for (i in 0 until 10) map[('0' + i).toString()] = ('0' + i).toString()
        for (c in listOf(' ', '.', ',', '!', '?', '\'', '"', '-', '_', '@', '#', '$', '%', '&', '*', '(', ')')) {
            map[c.toString()] = c.toString()
        }
        return map
    }

    /** Returns all built-in presets, ready to seed into the database. */
    fun all(): List<BuiltInPreset> = listOf(
        BuiltInPreset("Math Sans", mathSans(), "Mathematical sans-serif italic"),
        BuiltInPreset("Bold Serif", boldSerif(), "Mathematical bold serif"),
        BuiltInPreset("Upside Down", upsideDown(), "Each letter flipped upside down"),
        BuiltInPreset("Zalgo", zalgo(), "Letters decorated with random combining marks"),
        BuiltInPreset("Bubble", bubble(), "Enclosed alphanumerics"),
    )

    data class BuiltInPreset(
        val name: String,
        val mapping: Map<String, String>,
        val description: String,
    )
}
