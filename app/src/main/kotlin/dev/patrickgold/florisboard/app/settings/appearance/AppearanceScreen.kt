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

package dev.patrickgold.florisboard.app.settings.appearance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.stylekit.appearance.AppearanceRepository
import dev.patrickgold.florisboard.stylekit.appearance.KeySoundManager
import dev.patrickgold.florisboard.stylekit.appearance.StyleKitTheme
import kotlinx.coroutines.launch

/**
 * Appearance settings screen — the StyleKit equivalent of the original Style
 * Keyboard's AppearanceScreen. Covers:
 *
 *   1. Theme picker (6 built-ins, each defines bg/keyBg/keyBgActive/keyFg/border/accent)
 *   2. Key shape (rounded-rect vs circular)
 *   3. Glint sweep animation (color, speed, opacity, on/off)
 *   4. Background media (GIF or short muted looping video) + dark scrim
 *   5. Sound & haptics (sound pack, volume, mute, custom audio import, haptics toggle)
 *
 * The actual keyboard rendering reads from [AppearanceRepository.observe] so changes
 * here take effect immediately on the next keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AppearanceRepository(context) }
    val config by repo.observe().collectAsState(initial = defaultResolvedConfig())

    LaunchedEffect(Unit) { repo.ensureConfigSeeded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    val nav = LocalNavController.current
                    IconButton(onClick = { nav?.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ThemePickerSection(config, onSelect = { scope.launch { repo.setThemeId(it) } }) }
            item { KeyShapeSection(config, onSet = { scope.launch { repo.setKeyShape(it) } }) }
            item { KeyOpacitySection(config, onSet = { scope.launch { repo.setKeyOpacity(it) } }) }
            item { GlintSection(config, onChange = { e, c, s, o -> scope.launch { repo.setGlint(e, c, s, o) } }) }
            item { BackgroundSection(config, onPick = { uri, alpha -> scope.launch { repo.setBackground(uri, alpha) } }, onClear = { scope.launch { repo.setBackground("", 0.55f) } }) }
            item { SoundAndHapticsSection(config, onSoundChange = { pack, vol, muted, uri -> scope.launch { repo.setSound(pack, vol, muted, uri) } }, onHapticsChange = { scope.launch { repo.setHaptics(it) } }) }
        }
    }
}

@Composable
private fun ThemePickerSection(config: AppearanceRepository.Resolved, onSelect: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            StyleKitTheme.ALL.forEach { theme ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(theme.keyBg),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(theme.name, modifier = Modifier.weight(1f))
                    if (config.theme.id == theme.id) {
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { onSelect(theme.id) }) { Text("Apply") }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyShapeSection(config: AppearanceRepository.Resolved, onSet: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Key Shape", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onSet("square") },
                    label = { Text("Rounded Rect") },
                )
                AssistChip(
                    onClick = { onSet("circle") },
                    label = { Text("Circular") },
                )
            }
            Text("Current: ${config.keyShape}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun KeyOpacitySection(config: AppearanceRepository.Resolved, onSet: (Float) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Key Opacity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Reduce the opacity of the key caps to make the background media or theme color show through. Useful with GIF/video backgrounds.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val pct = (config.keyOpacity * 100).toInt()
            Text("$pct%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = config.keyOpacity,
                onValueChange = { onSet(it) },
                valueRange = 0.2f..1.0f,
                steps = 15, // 5% increments
            )
            // Live preview of the key opacity
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("Q", "W", "E", "R", "T").forEach { label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(config.theme.keyBg.copy(alpha = config.keyOpacity)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = config.theme.keyFg)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlintSection(
    config: AppearanceRepository.Resolved,
    onChange: (enabled: Boolean, color: Long, speed: Int, opacity: Float) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Glint Sweep", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = config.glintEnabled,
                    onCheckedChange = { v -> onChange(v, config.glintColor.value.toLong(), config.glintSpeedMs, config.glintOpacity) },
                )
            }
            if (config.glintEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("Speed: ${config.glintSpeedMs}ms")
                Slider(
                    value = config.glintSpeedMs.toFloat(),
                    onValueChange = { v -> onChange(true, config.glintColor.value.toLong(), v.toInt(), config.glintOpacity) },
                    valueRange = 800f..5000f,
                )
                Text("Opacity: ${"%.2f".format(config.glintOpacity)}")
                Slider(
                    value = config.glintOpacity,
                    onValueChange = { v -> onChange(true, config.glintColor.value.toLong(), config.glintSpeedMs, v) },
                    valueRange = 0.1f..0.9f,
                )
                Text("Color:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFF06E6FF, 0xFF8B5CF6, 0xFFFF4D9D, 0xFF1A73E8).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .padding(2.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { onChange(true, c, config.glintSpeedMs, config.glintOpacity) }) { Text(" ") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundSection(
    config: AppearanceRepository.Resolved,
    onPick: (uri: String, scrimAlpha: Float) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Take persistable read permission so the URI survives process death.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onPick(uri.toString(), config.backgroundScrimAlpha)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Background Image / Video", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick a GIF or short muted looping video from your gallery. " +
                    "A dark scrim keeps keys legible. Memory-conscious: GIF frames are decoded one at a time.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (config.backgroundMediaUri.isNotBlank()) {
                Text("Current: ${config.backgroundMediaUri.take(48)}…", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { launcher.launch(arrayOf("image/*", "video/*")) }) { Text("Replace") }
                Spacer(Modifier.height(4.dp))
                Button(onClick = onClear) { Text("Clear") }
            } else {
                Button(onClick = { launcher.launch(arrayOf("image/*", "video/*")) }) { Text("Pick file") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Scrim opacity: ${"%.2f".format(config.backgroundScrimAlpha)}")
            Slider(
                value = config.backgroundScrimAlpha,
                onValueChange = { v -> onPick(config.backgroundMediaUri, v) },
                valueRange = 0.1f..0.9f,
            )
        }
    }
}

@Composable
private fun SoundAndHapticsSection(
    config: AppearanceRepository.Resolved,
    onSoundChange: (pack: String, volume: Float, muted: Boolean, customUri: String) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val soundManager = remember { KeySoundManager(context) }
    val customLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onSoundChange("custom", config.soundVolume, config.soundMuted, uri.toString())
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sound & Haptics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mute sounds", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.soundMuted,
                    onCheckedChange = { v -> onSoundChange(config.soundPack, config.soundVolume, v, config.soundCustomUri) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Sound pack:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    KeySoundManager.PACK_MECHANICAL to "Mechanical",
                    KeySoundManager.PACK_SOFT_POP to "Soft Pop",
                    KeySoundManager.PACK_MARIMBA to "Marimba",
                    KeySoundManager.PACK_CUSTOM to "Custom",
                ).forEach { (id, label) ->
                    AssistChip(
                        onClick = {
                            if (id == KeySoundManager.PACK_CUSTOM && config.soundCustomUri.isBlank()) {
                                customLauncher.launch(arrayOf("audio/*"))
                            } else {
                                onSoundChange(id, config.soundVolume, config.soundMuted, config.soundCustomUri)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            if (config.soundPack == KeySoundManager.PACK_CUSTOM) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { customLauncher.launch(arrayOf("audio/*")) }) { Text("Pick custom audio") }
                if (config.soundCustomUri.isNotBlank()) {
                    Text("Custom file set ✓", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Volume: ${"%.0f%%".format(config.soundVolume * 100)}")
            Slider(
                value = config.soundVolume,
                onValueChange = { v -> onSoundChange(config.soundPack, v, config.soundMuted, config.soundCustomUri) },
                valueRange = 0f..1f,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Haptic feedback", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.hapticsEnabled,
                    onCheckedChange = { v -> onHapticsChange(v) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                soundManager.applyConfig(config.soundPack, config.soundVolume, config.soundMuted, config.soundCustomUri)
                soundManager.playClick(config.hapticsEnabled)
            }) { Text("Test sound & haptic") }
        }
    }
}

private fun defaultResolvedConfig() = AppearanceRepository.Resolved(
    theme = StyleKitTheme.Charcoal,
    keyShape = "square",
    backgroundMediaUri = "",
    backgroundScrimAlpha = 0.55f,
    glintEnabled = false,
    glintColor = Color(0xFF06E6FF),
    glintSpeedMs = 2200,
    glintOpacity = 0.35f,
    soundPack = "mechanical",
    soundVolume = 0.6f,
    soundMuted = false,
    soundCustomUri = "",
    hapticsEnabled = true,
)
