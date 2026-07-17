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

package dev.patrickgold.florisboard.stylekit.appearance

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A StyleKit keyboard theme. Each theme defines background, key background (idle/active),
 * key text color, optional key border, accent color.
 *
 * These themes are layered *on top of* FlorisBoard's existing Snygg theme system
 * when `stylekit.appearance.useStyleKitTheme` is enabled. When off, FlorisBoard's
 * native theme continues to render the keyboard.
 *
 * Ported from the original Style Keyboard's 6 built-in themes.
 */
@Immutable
data class StyleKitTheme(
    val id: String,
    val name: String,
    val background: Color,
    val keyBg: Color,
    val keyBgActive: Color,
    val keyFg: Color,
    val keyBorder: Color?,       // null = filled; non-null = outline style
    val accent: Color,
    val scrimAlpha: Float = 0.0f,
) {
    companion object {
        val Charcoal = StyleKitTheme(
            id = "charcoal",
            name = "Charcoal",
            background = Color(0xFF0A0A0A),
            keyBg = Color(0xFF1F1F1F),
            keyBgActive = Color(0xFF2E2E2E),
            keyFg = Color(0xFFE8E8E8),
            keyBorder = null,
            accent = Color(0xFF06E6FF),
        )
        val Midnight = StyleKitTheme(
            id = "midnight",
            name = "Midnight",
            background = Color(0xFF0B0F1F),
            keyBg = Color(0xFF1A1F35),
            keyBgActive = Color(0xFF283055),
            keyFg = Color(0xFFE8E8E8),
            keyBorder = null,
            accent = Color(0xFF8B5CF6),
        )
        val Ocean = StyleKitTheme(
            id = "ocean",
            name = "Ocean",
            background = Color(0xFF04212A),
            keyBg = Color(0xFF0E3340),
            keyBgActive = Color(0xFF144957),
            keyFg = Color(0xFFE8E8E8),
            keyBorder = null,
            accent = Color(0xFF06E6FF),
        )
        val Sunset = StyleKitTheme(
            id = "sunset",
            name = "Sunset",
            background = Color(0xFF1B0A0A),
            keyBg = Color(0xFF351010),
            keyBgActive = Color(0xFF4D1818),
            keyFg = Color(0xFFE8E8E8),
            keyBorder = null,
            accent = Color(0xFFFF4D9D),
        )
        val NeonBorder = StyleKitTheme(
            id = "neon",
            name = "Neon Border",
            background = Color(0xFF050608),
            keyBg = Color(0x00000000), // transparent — border carries the look
            keyBgActive = Color(0x2206E6FF),
            keyFg = Color(0xFF06E6FF),
            keyBorder = Color(0xFF06E6FF),
            accent = Color(0xFF06E6FF),
            scrimAlpha = 0.05f,
        )
        val Light = StyleKitTheme(
            id = "light",
            name = "Light",
            background = Color(0xFFE8EAED),
            keyBg = Color(0xFFFFFFFF),
            keyBgActive = Color(0xFFD8DADD),
            keyFg = Color(0xFF1A1A1A),
            keyBorder = null,
            accent = Color(0xFF1A73E8),
        )

        val ALL = listOf(Charcoal, Midnight, Ocean, Sunset, NeonBorder, Light)

        fun byId(id: String?): StyleKitTheme = ALL.firstOrNull { it.id == id } ?: Charcoal
    }
}
