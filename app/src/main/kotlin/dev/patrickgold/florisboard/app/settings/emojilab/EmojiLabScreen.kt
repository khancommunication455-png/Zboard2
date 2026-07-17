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

package dev.patrickgold.florisboard.app.settings.emojilab

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.ShortcutEntity
import dev.patrickgold.florisboard.stylekit.emojilab.ShortcutRepository
import kotlinx.coroutines.launch

/**
 * Emoji Lab settings screen. CRUD for emoji shortcuts that expand while typing.
 *
 * Each shortcut has:
 *  - trigger (lowercased on save)
 *  - emojis (space-separated emoji sequence)
 *  - triggerMode: "whole" (exact match) or "partial" (prefix match, length >= 2)
 *
 * Matching shortcuts surface as chips above the keyboard while typing, similar
 * to word suggestions. Tapping a chip deletes the typed trigger and inserts the
 * emoji + a trailing space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiLabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ShortcutRepository(context) }
    val shortcuts by repository.observeAll().collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<ShortcutEntity?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    val configDao = remember { StyleKitDatabase.get(context)?.configDao() }
    var emojiShortcutsEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        emojiShortcutsEnabled = configDao?.get()?.emojiShortcutsEnabled ?: true
    }

    LaunchedEffect(Unit) { repository.seedBuiltInsIfNeeded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emoji Lab") },
                navigationIcon = {
                    val nav = LocalNavController.current
                    IconButton(onClick = { nav?.popBackStack() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "New shortcut")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable emoji shortcut chips", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When on, matching shortcuts appear as chips above the keyboard while typing.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = emojiShortcutsEnabled,
                        onCheckedChange = { v ->
                            emojiShortcutsEnabled = v
                            scope.launch { configDao?.setEmojiShortcuts(v) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Shortcuts (${shortcuts.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shortcuts, key = { it.rowId }) { sc ->
                    ShortcutRow(
                        shortcut = sc,
                        onEdit = { editing = sc },
                        onDelete = { scope.launch { repository.delete(sc) } },
                    )
                }
            }
        }
    }

    if (showCreate) {
        ShortcutEditorDialog(
            initial = null,
            onDismiss = { showCreate = false },
            onSave = { trigger, emojis, mode ->
                scope.launch {
                    repository.insert(trigger, emojis, mode)
                    showCreate = false
                }
            },
        )
    }
    editing?.let { entity ->
        ShortcutEditorDialog(
            initial = entity,
            onDismiss = { editing = null },
            onSave = { trigger, emojis, mode ->
                scope.launch {
                    repository.update(entity, trigger, emojis, mode)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun ShortcutRow(
    shortcut: ShortcutEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shortcut.emojis, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(":${shortcut.trigger}:", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Mode: ${if (shortcut.triggerMode == "whole") "whole word" else "prefix"}" +
                        (if (shortcut.isBuiltIn) " · built-in" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            if (!shortcut.isBuiltIn) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }
}

@Composable
private fun ShortcutEditorDialog(
    initial: ShortcutEntity?,
    onDismiss: () -> Unit,
    onSave: (trigger: String, emojis: String, triggerMode: String) -> Unit,
) {
    var trigger by remember { mutableStateOf(initial?.trigger ?: "") }
    var emojis by remember { mutableStateOf(initial?.emojis ?: "") }
    var mode by remember { mutableStateOf(initial?.triggerMode ?: "whole") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = trigger.isNotBlank() && emojis.isNotBlank(),
                onClick = { onSave(trigger, emojis, mode) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New Shortcut" else "Edit Shortcut") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("Trigger (e.g. :fire: or lol)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emojis,
                    onValueChange = { emojis = it },
                    label = { Text("Emoji(s) — space-separated for multiple") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Match mode:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { mode = "whole" },
                        label = { Text("Whole word") },
                    )
                    AssistChip(
                        onClick = { mode = "partial" },
                        label = { Text("Prefix (>= 2 chars)") },
                    )
                }
            }
        },
    )
}

