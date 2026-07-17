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
import dev.patrickgold.florisboard.stylekit.data.entity.BigramEntity

@Dao
interface BigramDao {
    @Query(
        """
        SELECT * FROM sk_bigram
        WHERE first_word = :first
        ORDER BY frequency DESC, last_used DESC
        LIMIT :limit
        """
    )
    suspend fun suggestNext(first: String, limit: Int = 5): List<BigramEntity>

    @Query("SELECT * FROM sk_bigram WHERE first_word = :first AND second_word = :second LIMIT 1")
    suspend fun getExact(first: String, second: String): BigramEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BigramEntity): Long

    @Query("UPDATE sk_bigram SET frequency = frequency + :delta, last_used = :now WHERE first_word = :first AND second_word = :second")
    suspend fun bumpFrequency(first: String, second: String, delta: Int, now: Long): Int

    @Query("DELETE FROM sk_bigram")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM sk_bigram")
    suspend fun count(): Int
}
