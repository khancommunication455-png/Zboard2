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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.stylekit.autosender.AutoSenderManager
import dev.patrickgold.florisboard.stylekit.autosender.AutoSenderService
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.kotlin.tryOrNull
import org.florisboard.lib.snygg.ui.SnyggBox

/**
 * StyleKit quick-action panel shown in the Smartbar overflow grid.
 *
 * Exposes the three StyleKit features users most often want to trigger from
 * inside the keyboard itself, without leaving the current chat:
 *
 *   - **Auto Sender**: starts the most recently-used saved script with one
 *     tap. Tap again to stop. Saves opening the host app.
 *   - **Next Preset**: cycles to the next font preset, instantly applying it
 *     to the live keyboard.
 *   - **Open Settings**: launches the host app's Settings screen so the user
 *     can configure presets/shortcuts/auto-sender.
 */
@Composable
fun StyleKitQuickActionsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoSenderManager = remember { AutoSenderManager(context) }

    val savedScript = remember { mutableStateOf<AutoSenderScriptEntity?>(null) }
    LaunchedEffect(Unit) {
        tryOrNull {
            savedScript.value = StyleKitDatabase.get(context)?.autoSenderDao()?.getAllScripts()?.maxByOrNull { it.updatedAt }
        }
    }

    var isRunning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isRunning = AutoSenderService.isCurrentlyRunning(context)
    }

    SnyggBox(
        elementName = FlorisImeUi.SmartbarActionsOverflow.elementName,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "StyleKit",
                color = Color(0xFF06E6FF),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StyleKitActionButton(
                    modifier = Modifier.weight(1f),
                    label = if (isRunning) "■ Stop Auto" else "▶ Auto Sender",
                    color = if (isRunning) Color(0xFFFF5252) else Color(0xFF4ADE80),
                    onClick = {
                        if (isRunning) {
                            autoSenderManager.stopScript(context)
                            isRunning = false
                            context.showShortToastSync("Auto Sender stopped")
                        } else {
                            val script = savedScript.value
                            if (script == null) {
                                context.showShortToastSync("No script saved. Open Auto Sender in Settings first.")
                            } else {
                                autoSenderManager.startScript(context, script.rowId)
                                isRunning = true
                                context.showShortToastSync("Auto Sender started")
                            }
                        }
                    },
                )

                StyleKitActionButton(
                    modifier = Modifier.weight(1f),
                    label = "🎨 Next Preset",
                    color = Color(0xFF8B5CF6),
                    onClick = {
                        scope.launch {
                            tryOrNull {
                                val db = StyleKitDatabase.get(context) ?: return@tryOrNull
                                val cfg = db.configDao().get() ?: return@tryOrNull
                                val presets = db.presetDao().getAll()
                                if (presets.isEmpty()) {
                                    context.showShortToastSync("No presets available")
                                    return@tryOrNull
                                }
                                val currentIdx = presets.indexOfFirst { it.rowId == cfg.activePresetId }
                                val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % presets.size
                                val nextPreset = presets[nextIdx]
                                db.configDao().setPreset(nextPreset.rowId, enabled = true)
                                context.showShortToastSync("Preset: ${nextPreset.name}")
                            }
                        }
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // StyleKit: one-tap way back to plain typing without leaving the
                // keyboard or hunting through Settings — turns the live preset
                // off (same effect as "deactivatePreset" in the Presets screen).
                StyleKitActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Aa Normal Font",
                    color = Color(0xFF06E6FF),
                    onClick = {
                        scope.launch {
                            tryOrNull {
                                val db = StyleKitDatabase.get(context) ?: return@tryOrNull
                                db.configDao().setPreset(0L, enabled = false)
                                context.showShortToastSync("Back to normal font")
                            }
                        }
                    },
                )
                StyleKitActionButton(
                    modifier = Modifier.weight(1f),
                    label = "⚙ Open Settings",
                    color = Color(0xFFA0A0A0),
                    onClick = {
                        tryOrNull {
                            val intent = Intent(context, dev.patrickgold.florisboard.app.FlorisAppActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StyleKitActionButton(
    modifier: Modifier = Modifier,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "stylekit-btn-scale",
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
