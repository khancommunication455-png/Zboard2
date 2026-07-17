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

/**
 * Bigram (previous word -> next word) frequency table. Drives the
 * mid-sentence "next word" predictions, exactly like Gboard.
 */
@Entity(
    tableName = "sk_bigram",
    primaryKeys = ["first_word", "second_word"],
    indices = [Index(value = ["first_word"]), Index(value = ["second_word"])],
)
data class BigramEntity(
    @ColumnInfo(name = "first_word")
    val firstWord: String,
    @ColumnInfo(name = "second_word")
    val secondWord: String,
    @ColumnInfo(name = "frequency")
    val frequency: Int,
    @ColumnInfo(name = "last_used")
    val lastUsed: Long,
)
