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

package dev.patrickgold.florisboard.stylekit.preset

import android.content.Context
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.PresetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Repository facade for [PresetEntity]. Handles JSON encoding/decoding of the
 * mapping via [DefaultPresets], and exposes a [Flow] of decoded mappings so the
 * settings UI and the live-keyboard mode can both observe changes.
 *
 * Crash safety: every DB call is wrapped in [tryOrNull]; any failure degrades
 * to an empty list / no-op rather than crashing the IME process.
 */
class PresetRepository(context: Context) {
    private val dao = tryOrNull { StyleKitDatabase.get(context)?.presetDao() }

    fun observeAll(): Flow<List<PresetEntity>> =
        dao?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun getAll(): List<PresetEntity> = dao?.getAll() ?: emptyList()

    suspend fun getById(id: Long): PresetEntity? = dao?.getById(id)

    suspend fun getByName(name: String): PresetEntity? = dao?.getByName(name)

    /** Inserts a new preset. Returns the new row id, or -1 on failure. */
    suspend fun insert(name: String, mapping: Map<String, String>, description: String = ""): Long {
        val d = dao ?: return -1L
        val now = System.currentTimeMillis()
        return tryOrNull {
            d.insert(PresetEntity(
                name = name,
                mappingJson = DefaultPresets.encode(mapping),
                description = description,
                createdAt = now,
                updatedAt = now,
                isBuiltIn = false,
            ))
        } ?: -1L
    }

    suspend fun update(entity: PresetEntity, mapping: Map<String, String>, description: String) {
        val d = dao ?: return
        tryOrNull {
            d.update(entity.copy(
                mappingJson = DefaultPresets.encode(mapping),
                description = description,
                updatedAt = System.currentTimeMillis(),
            ))
        }
    }

    suspend fun delete(entity: PresetEntity) {
        tryOrNull { dao?.delete(entity) }
    }

    suspend fun deleteById(id: Long) {
        tryOrNull { dao?.deleteById(id) }
    }

    /** Decodes a preset's mapping. Returns empty map on failure. */
    fun decodeMapping(entity: PresetEntity): Map<String, String> =
        DefaultPresets.decode(entity.mappingJson)

    /** Seeds built-in presets on first install. Idempotent — skips if any preset already exists. */
    suspend fun seedBuiltInsIfNeeded() {
        val d = dao ?: return
        val existingCount = tryOrNull { d.count() } ?: 0
        if (existingCount > 0) return
        val now = System.currentTimeMillis()
        for (preset in DefaultPresets.all()) {
            tryOrNull {
                d.insert(PresetEntity(
                    name = preset.name,
                    mappingJson = DefaultPresets.encode(preset.mapping),
                    description = preset.description,
                    createdAt = now,
                    updatedAt = now,
                    isBuiltIn = true,
                ))
            }
        }
    }

    /** Flow of decoded mappings for the active preset (id from [StyleKitConfigEntity.activePresetId]). */
    fun observeActiveMapping(activePresetId: Long): Flow<Map<String, String>> =
        observeAll().map { presets ->
            val match = presets.firstOrNull { it.rowId == activePresetId }
            match?.let { DefaultPresets.decode(it.mappingJson) } ?: emptyMap()
        }
}
