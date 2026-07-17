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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.stylekit.data.entity.PresetEntity
import dev.patrickgold.florisboard.stylekit.preset.DefaultPresets
import dev.patrickgold.florisboard.stylekit.preset.TextConverter

/**
 * Editor dialog for a single preset. Lets the user:
 *  - set name + description
 *  - bulk-paste a string of symbols (auto-assigned to A–Z / a–z / 0–9)
 *  - manually edit each char's replacement
 *
 * If [initial] is null, creates a new preset starting from [TextConverter.emptyMapping].
 */
@Composable
fun PresetEditorDialog(
    initial: PresetEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, mapping: Map<String, String>, description: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    val initialMapping = remember(initial) {
        initial?.let { DefaultPresets.decode(it.mappingJson) } ?: TextConverter.emptyMapping()
    }
    val mapping = remember { mutableStateOf(initialMapping.toMutableMap()) }
    var bulkPasteText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), mapping.value.toMap(), description.trim()) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New Preset" else "Edit ${initial.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                // Bulk paste
                Text("Bulk paste: paste space-separated symbols, auto-assigned to A–Z, a–z, 0–9.")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = bulkPasteText,
                        onValueChange = { bulkPasteText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Symbols (space-separated)") },
                    )
                    Button(onClick = {
                        if (bulkPasteText.isNotBlank()) {
                            mapping.value = TextConverter
                                .bulkPasteAssign(bulkPasteText, mapping.value)
                                .toMutableMap()
                            bulkPasteText = ""
                        }
                    }) { Text("Assign") }
                }
                Spacer(Modifier.height(12.dp))

                // Manual mapping table (compact, 2 columns per row)
                Text("Manual mapping:")
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(mapping.value.entries.toList(), key = { it.key }) { (source, replacement) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                source,
                                modifier = Modifier.width(32.dp),
                            )
                            OutlinedTextField(
                                value = replacement,
                                onValueChange = { newVal ->
                                    mapping.value = mapping.value.toMutableMap().apply {
                                        put(source, newVal)
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
    )
}
