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

package dev.patrickgold.florisboard.app.settings.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.stylekit.data.entity.PresetEntity
import dev.patrickgold.florisboard.stylekit.preset.DefaultPresets
import dev.patrickgold.florisboard.stylekit.preset.PresetExportImport
import dev.patrickgold.florisboard.stylekit.preset.PresetRepository
import dev.patrickgold.florisboard.stylekit.preset.TextConverter
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToastSync

/**
 * Font Style Converter ("Presets") settings screen.
 *
 * Two main flows:
 *   1. **Quick convert & copy**: type into the top input field, tap a preset chip
 *      to apply it, result is shown live. Copy / Share / Clear buttons.
 *   2. **CRUD list**: each preset is a Card with Edit / Duplicate / Delete actions.
 *
 * Built-ins are flagged `isBuiltIn = true` and can be duplicated but not directly
 * edited (the user duplicates then edits the copy).
 *
 * The "live keyboard mode" toggle lives on the Appearance screen because it
 * affects the keyboard renderer; this screen only manages the preset library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { PresetRepository(context) }
    val presets by repository.observeAll().collectAsState(initial = emptyList())

    var inputText by remember { mutableStateOf("") }
    var editingPreset by remember { mutableStateOf<PresetEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // StyleKit: SAF launcher for export ("create file") and import ("open file").
    // Both use ActivityResultContracts.CreateDocument / OpenDocument respectively.
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val all = repository.getAll()
                val ok = PresetExportImport.exportToUri(context, all, uri)
                context.showShortToastSync(
                    if (ok) "Exported ${all.size} preset(s)" else "Export failed"
                )
            }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = PresetExportImport.importFromUri(context, uri)
                if (imported.isEmpty()) {
                    context.showShortToastSync("Import failed: file is empty or invalid")
                    return@launch
                }
                // Fetch existing names so we can de-dup on import.
                val existing = repository.getAll().map { it.name }.toSet()
                var inserted = 0
                var skipped = 0
                for ((name, mapping) in imported) {
                    val finalName = if (name in existing) "$name (imported)" else name
                    val rowId = repository.insert(finalName, mapping, description = "Imported")
                    if (rowId > 0) inserted++ else skipped++
                }
                context.showShortToastSync("Imported $inserted preset(s)" + if (skipped > 0) ", $skipped skipped" else "")
            }
        }
    }

    // Seed built-ins on first launch.
    LaunchedEffect(Unit) { repository.seedBuiltInsIfNeeded() }

    // Read the persisted StyleKit config (active preset + live toggle) so the
    // chips show the currently-active preset for the LIVE keyboard, not just
    // for this screen's preview.
    val rawConfig = remember { mutableStateOf<dev.patrickgold.florisboard.stylekit.data.entity.StyleKitConfigEntity?>(null) }
    LaunchedEffect(Unit) {
        rawConfig.value = dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase.get(context)?.configDao()?.get()
    }

    val activeId = rawConfig.value?.activePresetId ?: 0L
    val liveEnabled = rawConfig.value?.livePresetEnabled ?: false
    val activePreset = presets.firstOrNull { it.rowId == activeId } ?: presets.firstOrNull()
    val activeMapping = activePreset?.let { DefaultPresets.decode(it.mappingJson) } ?: emptyMap()
    val convertedText = remember(inputText, activeMapping) {
        TextConverter.convertText(inputText, activeMapping)
    }

    fun activatePreset(id: Long) {
        scope.launch {
            dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase.get(context)?.configDao()?.setPreset(id, enabled = true)
            rawConfig.value = rawConfig.value?.copy(activePresetId = id, livePresetEnabled = true)
                ?: dev.patrickgold.florisboard.stylekit.data.entity.StyleKitConfigEntity(activePresetId = id, livePresetEnabled = true)
            context.showShortToastSync("Preset activated — type anywhere to see it")
        }
    }

    fun deactivatePreset() {
        scope.launch {
            dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase.get(context)?.configDao()?.setPreset(0L, enabled = false)
            rawConfig.value = rawConfig.value?.copy(activePresetId = 0L, livePresetEnabled = false)
            context.showShortToastSync("Live preset off")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Font Style Converter") },
                navigationIcon = {
                    val nav = LocalNavController.current
                    IconButton(onClick = { nav?.popBackStack() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Back")
                    }
                },
                // StyleKit: per-preset export/import is in the per-row actions
                // (see PresetRow). These top-bar buttons are for bulk export
                // (all presets at once) and bulk import.
                actions = {
                    IconButton(onClick = {
                        // Bulk export — user picks a destination file via SAF.
                        exportLauncher.launch(PresetExportImport.suggestedFileName(presets.size))
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Export all presets")
                    }
                    IconButton(onClick = {
                        // Bulk import — user picks a .zboardpreset JSON file.
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                    }) {
                        Icon(Icons.Default.Upload, contentDescription = "Import presets")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New preset")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            // === Quick convert & copy ===
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Quick Convert & Copy", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("Type text to convert") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Preset chips — tapping activates the preset for the LIVE
                    // keyboard (sets `active_preset_id` + `live_preset_enabled`
                    // in the StyleKit config row). The LivePresetApplier in the
                    // IME process observes the config and updates its in-memory
                    // mapping within ~1 frame.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.forEach { preset ->
                            val isSelected = preset.rowId == activeId && liveEnabled
                            AssistChip(
                                onClick = {
                                    if (isSelected) deactivatePreset() else activatePreset(preset.rowId)
                                },
                                label = { Text(preset.name) },
                                colors = if (isSelected) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (liveEnabled) {
                        Text(
                            "● LIVE: ${activePreset?.name ?: "—"} is active. Type anywhere to see the styled output.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "Tap a preset to activate it for the live keyboard.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = convertedText.ifBlank { "(converted output appears here)" },
                        fontFamily = FontFamily.Default,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("style", convertedText))
                            context.showShortToastSync("Copied")
                        }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("Copy") }
                        Button(onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, convertedText)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share via"))
                        }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Share") }
                        Button(onClick = { inputText = "" }) { Text("Clear") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Your Presets", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // === CRUD list ===
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presets, key = { it.rowId }) { preset ->
                    PresetRow(
                        preset = preset,
                        sample = TextConverter.convertText("Sample", DefaultPresets.decode(preset.mappingJson)),
                        onEdit = { editingPreset = preset },
                        onDuplicate = {
                            scope.launch {
                                val mapping = DefaultPresets.decode(preset.mappingJson)
                                repository.insert("${preset.name} (copy)", mapping, preset.description)
                            }
                        },
                        onShare = {
                            // StyleKit: per-preset quick-share via ACTION_SEND.
                            // Sends the JSON payload as text — recipient can save
                            // it to a .zboardpreset file and import on their device.
                            val payload = PresetExportImport.buildSharePayload(preset)
                            if (payload != null) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(android.content.Intent.EXTRA_TEXT, payload)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "${preset.name}.zboardpreset")
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Share preset"))
                            } else {
                                context.showShortToastSync("Could not build share payload")
                            }
                        },
                        onDelete = { scope.launch { repository.delete(preset) } },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        PresetEditorDialog(
            initial = null,
            onDismiss = { showCreateDialog = false },
            onSave = { name, mapping, desc ->
                scope.launch {
                    repository.insert(name, mapping, desc)
                    showCreateDialog = false
                }
            },
        )
    }
    editingPreset?.let { entity ->
        PresetEditorDialog(
            initial = entity,
            onDismiss = { editingPreset = null },
            onSave = { name, mapping, desc ->
                scope.launch {
                    repository.update(entity, mapping, desc)
                    editingPreset = null
                }
            },
        )
    }
}

@Composable
private fun PresetRow(
    preset: PresetEntity,
    sample: String,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                if (preset.description.isNotBlank()) {
                    Text(preset.description, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Sample: $sample",
                    fontFamily = FontFamily.Default,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Default.Add, contentDescription = "Duplicate") }
            // StyleKit: per-preset share — sends the JSON payload via
            // ACTION_SEND so the user can AirDrop / message / save it to
            // a file. Combined with the top-bar Import button, this gives
            // full export/import workflow for individual presets.
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share / export") }
            if (!preset.isBuiltIn) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }
}
