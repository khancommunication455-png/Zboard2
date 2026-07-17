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

package dev.patrickgold.florisboard.stylekit.autosender

import android.content.Context
import android.content.Intent
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.florisboard.lib.kotlin.tryOrNull

/**
 * High-level facade for the Auto Sender settings UI: start/pause/resume/stop a
 * script, observe scripts + logs. The actual foreground service runs in its own
 * process component; this class only builds intents against it.
 */
class AutoSenderManager(context: Context) {
    private val appContext = context.applicationContext
    private val dao = tryOrNull { StyleKitDatabase.get(appContext)?.autoSenderDao() }

    fun observeScripts(): Flow<List<AutoSenderScriptEntity>> =
        dao?.observeScripts() ?: flowOf(emptyList())

    fun observeLogs(limit: Int = 200): Flow<List<dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderLogEntity>> =
        dao?.observeLogs(limit) ?: flowOf(emptyList())

    suspend fun getAllScripts(): List<AutoSenderScriptEntity> = dao?.getAllScripts() ?: emptyList()
    suspend fun getScriptById(id: Long): AutoSenderScriptEntity? = dao?.getScriptById(id)

    suspend fun createScript(name: String): Long {
        val d = dao ?: return -1L
        val now = System.currentTimeMillis()
        return tryOrNull {
            d.insertScript(AutoSenderScriptEntity(
                name = name,
                createdAt = now,
                updatedAt = now,
            ))
        } ?: -1L
    }

    suspend fun updateScript(entity: AutoSenderScriptEntity) {
        tryOrNull { dao?.updateScript(entity.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun deleteScript(entity: AutoSenderScriptEntity) {
        tryOrNull { dao?.deleteScript(entity) }
    }

    fun startScript(context: Context, scriptId: Long) {
        val intent = Intent(context, AutoSenderService::class.java).apply {
            action = AutoSenderService.ACTION_START
            putExtra(AutoSenderService.EXTRA_SCRIPT_ID, scriptId)
        }
        tryOrNull {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun pauseScript(context: Context) {
        sendControl(context, AutoSenderService.ACTION_PAUSE)
    }

    fun resumeScript(context: Context) {
        sendControl(context, AutoSenderService.ACTION_RESUME)
    }

    fun stopScript(context: Context) {
        sendControl(context, AutoSenderService.ACTION_STOP)
    }

    private fun sendControl(context: Context, action: String) {
        val intent = Intent(context, AutoSenderService::class.java).apply { this.action = action }
        tryOrNull {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
