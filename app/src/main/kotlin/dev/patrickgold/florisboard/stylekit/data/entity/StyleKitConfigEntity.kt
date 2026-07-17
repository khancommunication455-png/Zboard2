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

package dev.patrickgold.florisboard.stylekit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row configuration table for StyleKit features (appearance, sound, active preset).
 * Storing config as a single typed row keeps schema migrations simple — adding a new
 * config field is one ALTER TABLE.
 */
@Entity(tableName = "sk_config")
data class StyleKitConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = 1, // always 1, single-row table

    // --- Appearance ---
    @ColumnInfo(name = "theme_id")
    val themeId: String = "charcoal",
    @ColumnInfo(name = "key_shape")
    val keyShape: String = "square", // "square" | "circle"
    @ColumnInfo(name = "background_media_uri")
    val backgroundMediaUri: String = "",
    @ColumnInfo(name = "background_scrim_alpha")
    val backgroundScrimAlpha: Float = 0.55f,
    @ColumnInfo(name = "glint_enabled")
    val glintEnabled: Boolean = false,
    @ColumnInfo(name = "glint_color")
    val glintColor: Long = 0xFF06E6FF,
    @ColumnInfo(name = "glint_speed_ms")
    val glintSpeedMs: Int = 2200,
    @ColumnInfo(name = "glint_opacity")
    val glintOpacity: Float = 0.35f,

    // --- Sound & Haptics ---
    @ColumnInfo(name = "sound_pack")
    val soundPack: String = "mechanical", // "mechanical" | "soft_pop" | "marimba" | "custom"
    @ColumnInfo(name = "sound_volume")
    val soundVolume: Float = 0.6f,
    @ColumnInfo(name = "sound_muted")
    val soundMuted: Boolean = false,
    @ColumnInfo(name = "sound_custom_uri")
    val soundCustomUri: String = "",
    @ColumnInfo(name = "haptics_enabled")
    val hapticsEnabled: Boolean = true,

    // --- Key opacity ---
    @ColumnInfo(name = "key_opacity")
    val keyOpacity: Float = 1.0f, // 0.0 (fully transparent) to 1.0 (fully opaque)

    // --- Live preset ---
    @ColumnInfo(name = "active_preset_id")
    val activePresetId: Long = 0L, // 0 = none
    @ColumnInfo(name = "live_preset_enabled")
    val livePresetEnabled: Boolean = false,

    // --- Emoji Lab ---
    @ColumnInfo(name = "emoji_shortcuts_enabled")
    val emojiShortcutsEnabled: Boolean = true,
)
