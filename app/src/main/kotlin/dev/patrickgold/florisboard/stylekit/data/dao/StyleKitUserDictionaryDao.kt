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
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitUserDictionaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StyleKitUserDictionaryDao {
    @Query(
        """
        SELECT * FROM sk_user_dictionary
        WHERE word LIKE :prefix || '%'
        ORDER BY added_at DESC
        LIMIT :limit
        """
    )
    suspend fun suggestByPrefix(prefix: String, limit: Int = 5): List<StyleKitUserDictionaryEntity>

    @Query("SELECT * FROM sk_user_dictionary ORDER BY added_at DESC")
    fun observeAll(): Flow<List<StyleKitUserDictionaryEntity>>

    @Query("SELECT * FROM sk_user_dictionary ORDER BY added_at DESC")
    suspend fun getAll(): List<StyleKitUserDictionaryEntity>

    @Query("SELECT * FROM sk_user_dictionary WHERE word = :word LIMIT 1")
    suspend fun getByWord(word: String): StyleKitUserDictionaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StyleKitUserDictionaryEntity): Long

    @Query("DELETE FROM sk_user_dictionary WHERE word = :word")
    suspend fun deleteByWord(word: String): Int

    @Query("DELETE FROM sk_user_dictionary")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM sk_user_dictionary")
    suspend fun count(): Int
}
