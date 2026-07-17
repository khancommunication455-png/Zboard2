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
 * Per-device unigram frequency table. Words the user actually types get a frequency
 * bump here; rows are queried by prefix to produce Gboard-style suggestion candidates.
 *
 * Privacy: this table is local-only. It is never synced, exported off-device, or
 * sent to any remote endpoint. It is wiped when the user clears "learned data" or
 * uninstalls the app.
 */
@Entity(
    tableName = "sk_word_frequency",
    indices = [Index(value = ["word"], unique = true), Index(value = ["last_used"])],
)
data class WordFrequencyEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    /** Lower-cased word. */
    @ColumnInfo(name = "word")
    val word: String,
    /** Monotonically incremented count; treated as the weight during ranking. */
    @ColumnInfo(name = "frequency")
    val frequency: Int,
    /** Epoch millis of the most recent commit that touched this row. */
    @ColumnInfo(name = "last_used")
    val lastUsed: Long,
    /** If true, this row was added explicitly by the user (user-dictionary style) and survives decay. */
    @ColumnInfo(name = "is_user_added")
    val isUserAdded: Boolean = false,
    /** Optional BCP-47 locale tag, null means "any locale". */
    @ColumnInfo(name = "locale")
    val locale: String? = null,
)
