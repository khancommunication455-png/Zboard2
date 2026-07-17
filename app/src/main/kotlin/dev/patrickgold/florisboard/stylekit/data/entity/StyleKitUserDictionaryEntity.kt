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
 * User-managed custom words that aren't in the base dictionary. The adaptive
 * learning provider promotes a typed word into this table after it has been
 * committed enough times, so custom slang / usernames / names get suggested
 * after a few uses, not from day one.
 *
 * This is distinct from FlorisBoard's existing [dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry]
 * (which mirrors the system UserDictionary provider). This table is private to
 * StyleKit, never synced, and managed entirely by the learning pipeline.
 */
@Entity(
    tableName = "sk_user_dictionary",
    indices = [Index(value = ["word"], unique = true)],
)
data class StyleKitUserDictionaryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    @ColumnInfo(name = "word")
    val word: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "locale")
    val locale: String? = null,
)
