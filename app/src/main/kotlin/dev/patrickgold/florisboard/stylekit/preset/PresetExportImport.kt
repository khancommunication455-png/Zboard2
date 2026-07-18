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
import android.net.Uri
import dev.patrickgold.florisboard.stylekit.data.entity.PresetEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.kotlin.tryOrNull
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Export / import helper for Font Style presets.
 *
 * File format (UTF-8 JSON, .zboardpreset extension):
 *
 * ```json
 * {
 *   "format": "zboard-preset/v1",
 *   "exportedAt": 1700000000000,
 *   "presets": [
 *     {
 *       "name": "My Fancy Font",
 *       "description": "Custom style",
 *       "mapping": { "a": "𝓪", "b": "𝓫", ... }
 *     },
 *     ...
 *   ]
 * }
 * ```
 *
 * - The file is human-readable so users can edit it by hand if they want.
 * - The `format` field lets us add schema migrations later.
 * - On import, built-in presets in the file are skipped (we never overwrite
 *   built-ins from an import — the user must explicitly duplicate them).
 * - If a preset with the same name already exists, the imported one is
 *   renamed to "<name> (imported)" to avoid silent overwrites.
 *
 * Privacy: import/export is purely local. The file is whatever the user
 * picks via the system file picker (SAF); we never upload anything.
 */
object PresetExportImport {
    private const val FORMAT_VERSION = "zboard-preset/v1"
    private const val DEFAULT_EXTENSION = "zboardpreset"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class ExportedFile(
        val format: String,
        val exportedAt: Long,
        val presets: List<ExportedPreset>,
    )

    @Serializable
    private data class ExportedPreset(
        val name: String,
        val description: String,
        val mapping: Map<String, String>,
    )

    /**
     * Exports the given presets to the [outputStream] as UTF-8 JSON.
     * Closes the stream when done. Returns true on success.
     */
    fun exportToStream(presets: List<PresetEntity>, outputStream: OutputStream): Boolean = try {
        val payload = ExportedFile(
            format = FORMAT_VERSION,
            exportedAt = System.currentTimeMillis(),
            presets = presets.map {
                ExportedPreset(
                    name = it.name,
                    description = it.description,
                    mapping = DefaultPresets.decode(it.mappingJson),
                )
            },
        )
        val text = json.encodeToString(ExportedFile.serializer(), payload)
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(text) }
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Exports the given presets to the URI picked by the user (SAF).
     * Returns true on success.
     */
    fun exportToUri(context: Context, presets: List<PresetEntity>, uri: Uri): Boolean = tryOrNull {
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            exportToStream(presets, out)
        } ?: false
    } ?: false

    /**
     * Imports presets from the given input stream. Returns the list of
     * decoded presets (NOT yet inserted into the DB — caller does that
     * via [PresetRepository.insert]). Returns empty list on parse failure.
     */
    fun importFromStream(inputStream: java.io.InputStream): List<Pair<String, Map<String, String>>> = try {
        val text = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val payload = json.decodeFromString(ExportedFile.serializer(), text)
        // Format check — accept anything starting with "zboard-preset/" so
        // future versions can still be read (forward-compat).
        if (!payload.format.startsWith("zboard-preset/")) return emptyList()
        payload.presets.map { it.name to it.mapping }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Imports presets from the given SAF URI. Returns the list of decoded
     * (name, mapping) pairs. Returns empty list on failure.
     */
    fun importFromUri(context: Context, uri: Uri): List<Pair<String, Map<String, String>>> = tryOrNull {
        context.contentResolver.openInputStream(uri)?.use { input ->
            importFromStream(input)
        } ?: emptyList()
    } ?: emptyList()

    /** Suggested file name for the export picker. */
    fun suggestedFileName(presetCount: Int): String {
        val stamp = System.currentTimeMillis() / 1000
        return if (presetCount == 1) "preset-$stamp.$DEFAULT_EXTENSION"
        else "presets-$stamp.$DEFAULT_EXTENSION"
    }

    /**
     * Builds a single-preset JSON payload suitable for sharing via ACTION_SEND
     * (text/plain). The recipient can save the text to a `.zboardpreset` file
     * and import it on their device via the Import button.
     *
     * Returns the JSON string, or null on encoding failure.
     */
    fun buildSharePayload(preset: PresetEntity): String? = try {
        val payload = ExportedFile(
            format = FORMAT_VERSION,
            exportedAt = System.currentTimeMillis(),
            presets = listOf(
                ExportedPreset(
                    name = preset.name,
                    description = preset.description,
                    mapping = DefaultPresets.decode(preset.mappingJson),
                )
            ),
        )
        json.encodeToString(ExportedFile.serializer(), payload)
    } catch (t: Throwable) {
        null
    }
}
