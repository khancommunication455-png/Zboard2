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

package dev.patrickgold.florisboard.stylekit.emojilab

import android.content.Context
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.ShortcutEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Repository facade for [ShortcutEntity]. Handles lower-casing triggers on insert,
 * and exposes a [Flow] of shortcuts so the keyboard can observe changes.
 *
 * Match modes:
 *  - "whole"   → trigger must exactly equal the current word.
 *  - "partial" → trigger.startsWith(currentWord) AND currentWord.length >= 2.
 *
 * Crash safety: every DB call is wrapped in [tryOrNull].
 */
class ShortcutRepository(context: Context) {
    private val dao = tryOrNull { StyleKitDatabase.get(context)?.shortcutDao() }

    fun observeAll(): Flow<List<ShortcutEntity>> =
        dao?.observeAll() ?: flowOf(emptyList())

    suspend fun getAll(): List<ShortcutEntity> = dao?.getAll() ?: emptyList()

    suspend fun getByTrigger(trigger: String): ShortcutEntity? =
        dao?.getByTrigger(trigger.lowercase())

    /** Inserts a shortcut. Lower-cases the trigger. Returns the new row id, or -1 on failure. */
    suspend fun insert(
        trigger: String,
        emojis: String,
        triggerMode: String = "whole",
    ): Long {
        val d = dao ?: return -1L
        val cleanedTrigger = trigger.trim().lowercase()
        if (cleanedTrigger.isEmpty() || emojis.isBlank()) return -1L
        return tryOrNull {
            d.insert(ShortcutEntity(
                trigger = cleanedTrigger,
                emojis = emojis.trim(),
                triggerMode = if (triggerMode == "partial") "partial" else "whole",
                createdAt = System.currentTimeMillis(),
                isBuiltIn = false,
            ))
        } ?: -1L
    }

    suspend fun update(entity: ShortcutEntity, trigger: String, emojis: String, triggerMode: String) {
        val d = dao ?: return
        val cleanedTrigger = trigger.trim().lowercase()
        if (cleanedTrigger.isEmpty() || emojis.isBlank()) return
        tryOrNull {
            d.update(entity.copy(
                trigger = cleanedTrigger,
                emojis = emojis.trim(),
                triggerMode = if (triggerMode == "partial") "partial" else "whole",
            ))
        }
    }

    suspend fun delete(entity: ShortcutEntity) { tryOrNull { dao?.delete(entity) } }
    suspend fun deleteById(id: Long) { tryOrNull { dao?.deleteById(id) } }

    /**
     * Returns shortcuts matching the given [currentWord] according to each shortcut's
     * `triggerMode`. Used by the keyboard to surface emoji-shortcut chips above the
     * keyboard while typing — similar to word suggestions.
     */
    suspend fun matchForCurrentWord(currentWord: String): List<ShortcutEntity> {
        if (currentWord.isBlank()) return emptyList()
        val lower = currentWord.lowercase()
        return (dao?.getAll() ?: emptyList()).filter { sc ->
            when (sc.triggerMode) {
                "whole" -> sc.trigger == lower
                else -> sc.trigger.startsWith(lower) && lower.length >= 2
            }
        }
    }

    /** Seeds built-in shortcuts on first install. Idempotent. */
    suspend fun seedBuiltInsIfNeeded() {
        val d = dao ?: return
        val existingCount = tryOrNull { d.count() } ?: 0
        if (existingCount > 0) return
        for (sc in DefaultShortcuts.all()) {
            tryOrNull { d.insert(sc) }
        }
    }
}
