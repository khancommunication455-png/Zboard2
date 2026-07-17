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
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Emoji Lab shortcut. Either:
 *   - triggerMode = "whole"   -> matches only when the typed word exactly equals `trigger`
 *   - triggerMode = "partial" -> matches when the typed word starts with `trigger` (and length >= 2)
 *
 * Matching shortcuts surface as suggestion chips above the keyboard; tapping one
 * deletes the typed trigger and inserts `emojis` + a trailing space.
 */
@Entity(
    tableName = "sk_shortcut",
    indices = [Index(value = ["trigger"])],
)
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    /** Trigger, lower-cased on insert. */
    @ColumnInfo(name = "trigger")
    val trigger: String,
    /** Space-separated emoji sequence to expand to. */
    @ColumnInfo(name = "emojis")
    val emojis: String,
    /** "whole" or "partial". */
    @ColumnInfo(name = "trigger_mode")
    val triggerMode: String = "whole",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,
)
