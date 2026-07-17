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
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderLogEntity
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoSenderDao {
    // Scripts
    @Query("SELECT * FROM sk_auto_sender_script ORDER BY updated_at DESC")
    fun observeScripts(): Flow<List<AutoSenderScriptEntity>>

    @Query("SELECT * FROM sk_auto_sender_script ORDER BY updated_at DESC")
    suspend fun getAllScripts(): List<AutoSenderScriptEntity>

    @Query("SELECT * FROM sk_auto_sender_script WHERE row_id = :id LIMIT 1")
    suspend fun getScriptById(id: Long): AutoSenderScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(entity: AutoSenderScriptEntity): Long

    @Update
    suspend fun updateScript(entity: AutoSenderScriptEntity)

    @Delete
    suspend fun deleteScript(entity: AutoSenderScriptEntity)

    @Query("DELETE FROM sk_auto_sender_script WHERE row_id = :id")
    suspend fun deleteScriptById(id: Long): Int

    // Logs
    @Query("SELECT * FROM sk_auto_sender_log ORDER BY sent_at DESC LIMIT :limit")
    fun observeLogs(limit: Int = 200): Flow<List<AutoSenderLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entity: AutoSenderLogEntity): Long

    @Query("DELETE FROM sk_auto_sender_log")
    suspend fun clearLogs(): Int
}
