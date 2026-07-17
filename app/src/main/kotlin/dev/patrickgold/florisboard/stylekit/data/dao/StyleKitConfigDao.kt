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

package dev.patrickgold.florisboard.stylekit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StyleKitConfigDao {
    @Query("SELECT * FROM sk_config WHERE id = 1 LIMIT 1")
    fun observe(): Flow<StyleKitConfigEntity?>

    @Query("SELECT * FROM sk_config WHERE id = 1 LIMIT 1")
    suspend fun get(): StyleKitConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StyleKitConfigEntity)

    @Update
    suspend fun update(entity: StyleKitConfigEntity)

    @Query("UPDATE sk_config SET theme_id = :themeId WHERE id = 1")
    suspend fun setThemeId(themeId: String)

    @Query("UPDATE sk_config SET key_shape = :shape WHERE id = 1")
    suspend fun setKeyShape(shape: String)

    @Query("UPDATE sk_config SET background_media_uri = :uri, background_scrim_alpha = :alpha WHERE id = 1")
    suspend fun setBackground(uri: String, alpha: Float)

    @Query("UPDATE sk_config SET glint_enabled = :enabled, glint_color = :color, glint_speed_ms = :speed, glint_opacity = :opacity WHERE id = 1")
    suspend fun setGlint(enabled: Boolean, color: Long, speed: Int, opacity: Float)

    @Query("UPDATE sk_config SET sound_pack = :pack, sound_volume = :volume, sound_muted = :muted, sound_custom_uri = :uri WHERE id = 1")
    suspend fun setSound(pack: String, volume: Float, muted: Boolean, uri: String)

    @Query("UPDATE sk_config SET haptics_enabled = :enabled WHERE id = 1")
    suspend fun setHaptics(enabled: Boolean)

    @Query("UPDATE sk_config SET key_opacity = :opacity WHERE id = 1")
    suspend fun setKeyOpacity(opacity: Float)

    @Query("UPDATE sk_config SET active_preset_id = :id, live_preset_enabled = :enabled WHERE id = 1")
    suspend fun setPreset(id: Long, enabled: Boolean)

    @Query("UPDATE sk_config SET emoji_shortcuts_enabled = :enabled WHERE id = 1")
    suspend fun setEmojiShortcuts(enabled: Boolean)
}
