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
 * A "Font Style" preset. Each preset is a JSON-encoded Map<String,String> mapping
 * a source character to its stylized replacement (e.g. 'a' -> U+1D41A bold 'a').
 *
 * Used two ways:
 *   1. Quick "convert & copy" flow in settings (input -> apply preset -> clipboard).
 *   2. Live keyboard mode: when `activePresetId` is set in [StyleKitConfigEntity],
 *      every character the user types is run through the preset's mapping before
 *      being committed to the editor.
 */
@Entity(
    tableName = "sk_preset",
    indices = [Index(value = ["name"], unique = true)],
)
data class PresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    /** JSON-encoded Map<String,String>, source char -> replacement string. */
    @ColumnInfo(name = "mapping_json")
    val mappingJson: String,
    @ColumnInfo(name = "description")
    val description: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,
)
