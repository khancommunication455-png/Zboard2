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

package dev.patrickgold.florisboard.app.settings.autosender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.stylekit.autosender.AutoSenderCodec
import dev.patrickgold.florisboard.stylekit.autosender.AutoSenderManager
import dev.patrickgold.florisboard.stylekit.autosender.AutoSenderService
import dev.patrickgold.florisboard.stylekit.autosender.ScriptMessage
import dev.patrickgold.florisboard.stylekit.autosender.createAutoSenderNotificationChannel
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderLogEntity
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity
import kotlinx.coroutines.launch

/** Editable form state for a script, used by [ScriptEditorDialog]. */
private data class ScriptForm(
    val name: String,
    val targetPackage: String,
    val targetClass: String,
    val useAccessibility: Boolean,
    val loopMode: String,
    val loopCount: Int,
    val intervalMs: Long,
    val perMessageDelayMs: Long,
    val messages: List<ScriptMessage>,
)

private fun AutoSenderScriptEntity.toForm(): ScriptForm = ScriptForm(
    name = name,
    targetPackage = targetPackage,
    targetClass = targetClass,
    useAccessibility = useAccessibility,
    loopMode = loopMode,
    loopCount = loopCount,
    intervalMs = intervalMs,
    perMessageDelayMs = perMessageDelayMs,
    messages = AutoSenderCodec.decodeMessages(messagesJson),
)

private fun ScriptForm.toEntity(id: Long, createdAt: Long, isBuiltIn: Boolean): AutoSenderScriptEntity =
    AutoSenderScriptEntity(
        rowId = id,
        name = name,
        targetPackage = targetPackage,
        targetClass = targetClass,
        useAccessibility = useAccessibility,
        messagesJson = AutoSenderCodec.encodeMessages(messages),
        loopMode = loopMode,
        loopCount = loopCount,
        intervalMs = intervalMs.coerceAtLeast(AutoSenderService.MIN_INTERVAL_MS),
        perMessageDelayMs = perMessageDelayMs,
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis(),
    )

/**
 * Auto Sender settings screen — manages scheduled/automated message scripts.
 * Cleanly separated from the IME code path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSenderScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { AutoSenderManager(context) }
    val scripts by manager.observeScripts().collectAsState(initial = emptyList())
    val logs by manager.observeLogs().collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<AutoSenderScriptEntity?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { createAutoSenderNotificationChannel(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto Sender") },
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
                Icon(Icons.Default.Add, contentDescription = "New script")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("What is Auto Sender?", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "A scheduled/automated message-sending utility that runs as its own " +
                                "foreground service. Completely separate from the keyboard — it just " +
                                "happens to live in the same app. Dispatches via share-intents by default; " +
                                "an optional AccessibilityService fallback is available for apps that " +
                                "don't accept share-intents.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item { Text("Scripts (${scripts.size})", style = MaterialTheme.typography.titleMedium) }
            items(scripts, key = { it.rowId }) { script ->
                ScriptCard(
                    script = script,
                    onEdit = { editing = script },
                    onDelete = { scope.launch { manager.deleteScript(script) } },
                    onStart = { manager.startScript(context, script.rowId) },
                    onStop = { manager.stopScript(context) },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("Run Log (last ${logs.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(logs, key = { it.rowId }) { log -> LogRow(log) }
        }
    }

    if (showCreate) {
        ScriptEditorDialog(
            initial = null,
            onDismiss = { showCreate = false },
            onSave = { form ->
                scope.launch {
                    val id = manager.createScript(form.name)
                    if (id > 0) {
                        manager.updateScript(form.toEntity(id, System.currentTimeMillis(), false))
                    }
                    showCreate = false
                }
            },
        )
    }
    editing?.let { entity ->
        ScriptEditorDialog(
            initial = entity,
            onDismiss = { editing = null },
            onSave = { form ->
                scope.launch {
                    manager.updateScript(form.toEntity(entity.rowId, entity.createdAt, false))
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun ScriptCard(
    script: AutoSenderScriptEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val messageCount = AutoSenderCodec.decodeMessages(script.messagesJson).size
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(script.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onStart) { Icon(Icons.Default.PlayArrow, contentDescription = "Start") }
                IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = "Stop") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
            Text("Target: ${script.targetPackage.ifBlank { "(chooser)" }}", style = MaterialTheme.typography.bodySmall)
            Text("Messages: $messageCount · Loop: ${script.loopMode}", style = MaterialTheme.typography.bodySmall)
            if (script.useAccessibility) {
                Text("Uses AccessibilityService fallback", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LogRow(log: AutoSenderLogEntity) {
    val color = when (log.status) {
        "sent" -> Color(0xFF4CAF50)
        "skipped" -> Color(0xFFFFA000)
        else -> Color(0xFFE53935)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(log.message, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(
                "${log.status} · ${log.targetPackage.ifBlank { "n/a" }}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color,
            )
        }
    }
}

@Composable
private fun ScriptEditorDialog(
    initial: AutoSenderScriptEntity?,
    onDismiss: () -> Unit,
    onSave: (ScriptForm) -> Unit,
) {
    val base = remember(initial) { initial?.toForm() ?: ScriptForm(
        name = "",
        targetPackage = "",
        targetClass = "",
        useAccessibility = false,
        loopMode = "once",
        loopCount = 1,
        intervalMs = 5000L,
        perMessageDelayMs = 1000L,
        messages = emptyList(),
    ) }
    var name by remember { mutableStateOf(base.name) }
    var targetPackage by remember { mutableStateOf(base.targetPackage) }
    var targetClass by remember { mutableStateOf(base.targetClass) }
    var useAccessibility by remember { mutableStateOf(base.useAccessibility) }
    var loopMode by remember { mutableStateOf(base.loopMode) }
    var loopCount by remember { mutableStateOf(base.loopCount.toString()) }
    var intervalMs by remember { mutableStateOf(base.intervalMs.toString()) }
    var perMessageDelayMs by remember { mutableStateOf(base.perMessageDelayMs.toString()) }
    var messagesText by remember {
        mutableStateOf(base.messages.joinToString("\n") { it.text })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val msgs = messagesText.split("\n").filter { it.isNotBlank() }
                        .map { ScriptMessage(it.trim()) }
                    val form = ScriptForm(
                        name = name.trim(),
                        targetPackage = targetPackage.trim(),
                        targetClass = targetClass.trim(),
                        useAccessibility = useAccessibility,
                        loopMode = loopMode,
                        loopCount = loopCount.toIntOrNull() ?: 1,
                        intervalMs = intervalMs.toLongOrNull() ?: 5000L,
                        perMessageDelayMs = perMessageDelayMs.toLongOrNull() ?: 1000L,
                        messages = msgs,
                    )
                    onSave(form)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New Script" else "Edit ${initial.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Script name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = targetPackage, onValueChange = { targetPackage = it }, label = { Text("Target package (optional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = targetClass, onValueChange = { targetClass = it }, label = { Text("Target activity class (optional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use AccessibilityService fallback", modifier = Modifier.weight(1f))
                    Switch(checked = useAccessibility, onCheckedChange = { useAccessibility = it })
                }
                Spacer(Modifier.height(8.dp))
                Text("Loop mode:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("once", "n_times", "infinite").forEach { mode ->
                        AssistChip(onClick = { loopMode = mode }, label = { Text(mode) })
                    }
                }
                if (loopMode == "n_times") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = loopCount, onValueChange = { loopCount = it }, label = { Text("Loop count") }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = intervalMs, onValueChange = { intervalMs = it }, label = { Text("Interval ms (min 3000)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = perMessageDelayMs, onValueChange = { perMessageDelayMs = it }, label = { Text("Per-message delay ms") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = messagesText,
                    onValueChange = { messagesText = it },
                    label = { Text("Messages (one per line)") },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
        },
    )
}
