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

package dev.patrickgold.florisboard.stylekit.appearance

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Optional overlay that renders the StyleKit appearance layers (background media,
 * dark scrim, glint sweep) BEHIND the keyboard keys.
 *
 * IMPORTANT — sizing:
 * This composable is designed to be placed as the FIRST child inside a parent
 * `Box`. The caller passes `Modifier.matchParentSize()` so it sizes to the
 * parent's measured size WITHOUT influencing the parent's measurement. If we
 * used `fillMaxSize()` here, the parent Box would be forced to expand to fill
 * all available vertical space, making the keyboard cover the entire screen.
 *
 * The overlay is fully transparent when no background media and no glint are
 * configured, so it has zero visual impact by default.
 *
 * Crash safety: every layer is independently wrapped; if the media URI fails
 * to load, only that layer is skipped.
 */
@Composable
fun StyleKitAppearanceOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { AppearanceRepository(context) }
    val config by repo.observe().collectAsState(initial = null)

    val cfg = config ?: return
    Box(modifier = modifier) {
        // 1. Background media (GIF or video) — only when configured.
        if (cfg.backgroundMediaUri.isNotBlank()) {
            MediaBackground(
                mediaUri = cfg.backgroundMediaUri,
                scrimAlpha = cfg.backgroundScrimAlpha,
            )
        }
        // 2. Glint sweep — only when enabled.
        if (cfg.glintEnabled) {
            GlintSweep(
                enabled = true,
                color = cfg.glintColor,
                speedMs = cfg.glintSpeedMs,
                opacity = cfg.glintOpacity,
            )
        }
    }
}
