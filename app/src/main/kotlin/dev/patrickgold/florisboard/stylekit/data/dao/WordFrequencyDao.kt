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
import dev.patrickgold.florisboard.stylekit.data.entity.WordFrequencyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WordFrequencyDao {
    @Query(
        """
        SELECT * FROM sk_word_frequency
        WHERE word LIKE :prefix || '%'
        ORDER BY frequency DESC, last_used DESC
        LIMIT :limit
        """
    )
    suspend fun suggestByPrefix(prefix: String, limit: Int = 8): List<WordFrequencyEntity>

    @Query("SELECT * FROM sk_word_frequency WHERE word = :word LIMIT 1")
    suspend fun getExact(word: String): WordFrequencyEntity?

    @Query("SELECT * FROM sk_word_frequency WHERE word = :word AND (locale = :locale OR locale IS NULL) LIMIT 1")
    suspend fun getExact(word: String, locale: String?): WordFrequencyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WordFrequencyEntity): Long

    @Query("UPDATE sk_word_frequency SET frequency = frequency + :delta, last_used = :now WHERE word = :word")
    suspend fun bumpFrequency(word: String, delta: Int, now: Long): Int

    @Query("UPDATE sk_word_frequency SET frequency = MAX(1, frequency / 2) WHERE last_used < :cutoff AND is_user_added = 0")
    suspend fun decayOldEntries(cutoff: Long): Int

    @Query("DELETE FROM sk_word_frequency WHERE frequency <= 1 AND is_user_added = 0")
    suspend fun pruneStale(): Int

    @Query("DELETE FROM sk_word_frequency WHERE is_user_added = 0")
    suspend fun clearLearned(): Int

    @Query("DELETE FROM sk_word_frequency")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM sk_word_frequency")
    suspend fun count(): Int

    @Query("SELECT * FROM sk_word_frequency ORDER BY frequency DESC, last_used DESC LIMIT :limit")
    fun observeTop(limit: Int = 20): Flow<List<WordFrequencyEntity>>
}
