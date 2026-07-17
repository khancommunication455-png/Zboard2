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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.patrickgold.florisboard.stylekit.data.entity.PresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM sk_preset ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM sk_preset ORDER BY updated_at DESC")
    suspend fun getAll(): List<PresetEntity>

    @Query("SELECT * FROM sk_preset WHERE row_id = :id LIMIT 1")
    suspend fun getById(id: Long): PresetEntity?

    @Query("SELECT * FROM sk_preset WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): PresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PresetEntity): Long

    @Update
    suspend fun update(entity: PresetEntity)

    @Delete
    suspend fun delete(entity: PresetEntity)

    @Query("DELETE FROM sk_preset WHERE row_id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM sk_preset")
    suspend fun count(): Int
}
