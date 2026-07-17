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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Subtle diagonal "glint" sweep effect across the keyboard background, ported
 * from the original Style Keyboard.
 *
 * The sweep is an `infiniteRepeatable` linear animation that moves a translucent
 * gradient band from left (-0.3 * width) to right (1.3 * width) over [speedMs].
 * Band width is 25% of the keyboard width.
 *
 * Configurable via the StyleKit config table:
 *  - `glintEnabled`   (default false — off by default per spec)
 *  - `glintColor`     (default 0xFF06E6FF)
 *  - `glintSpeedMs`   (default 2200; UI range 800–5000)
 *  - `glintOpacity`   (default 0.35; UI range 0.1–0.9)
 *
 * Memory/perf: a single `drawBehind` draw with one linear gradient per frame.
 * No allocations per frame beyond the brush. Off-screen when [enabled] is false.
 */
@Composable
fun GlintSweep(
    enabled: Boolean,
    color: Color,
    speedMs: Int,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val transition = rememberInfiniteTransition(label = "stylekit-glint")
    val position by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(speedMs.coerceIn(400, 10_000), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "stylekit-glint-pos",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val sweepWidth = size.width * 0.25f
                val start = position * size.width
                val end = start + sweepWidth
                val brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, color.copy(alpha = opacity), Color.Transparent),
                    start = Offset(start, 0f),
                    end = Offset(end, size.height),
                )
                drawRect(brush)
            },
    )
}
