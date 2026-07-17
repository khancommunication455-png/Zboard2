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

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import org.florisboard.lib.compose.conditional
import org.florisboard.lib.compose.florisHorizontalScroll
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggSpacer

/**
 * StyleKit Part 1: a 3-chip suggestion row that visually emphasizes the **center**
 * chip (the "primary suggestion" affordance), mimicking Gboard.
 *
 * Behavior:
 *  - Shows up to 3 candidates.
 *  - When 3 candidates are present, the center (index 1) chip is rendered with
 *    `FontWeight.Bold` and an `emphasis="1"` attribute, which Snygg themes can
 *    target with a distinct style rule (e.g. `smartbar-candidate-word[emphasis="1"]`).
 *  - When fewer than 3 candidates are present, the first candidate gets the
 *    emphasis (so a single autocorrect candidate still reads as "primary").
 *  - Tap a chip → `keyboardManager.commitCandidate(...)`.
 *  - Long-press a removable chip → `nlpManager.removeSuggestion(...)`.
 *
 * Uses the same Snygg element names as the existing [CandidatesRow] so existing
 * themes continue to style the chips correctly.
 */
@Composable
fun StyleKitCandidatesRow(
    modifier: Modifier = Modifier,
    candidates: List<SuggestionCandidate> = emptyList(),
) {
    if (candidates.isEmpty()) return

    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val nlpManager by context.nlpManager()
    val subtypeManager by context.subtypeManager()
    val prefs by FlorisPreferenceStore

    // Take up to 3 candidates for the Gboard-style 3-chip layout.
    val list = candidates.take(3)
    val emphasisIndex = if (list.size >= 3) 1 else 0
    val longPressDelay = prefs.keyboard.longPressDelay.get().toLong()

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier
            .fillMaxSize()
            .conditional(list.size > 1) {
                florisHorizontalScroll(scrollbarHeight = CandidatesRowScrollbarHeight)
            },
        horizontalArrangement = if (list.size > 1) Arrangement.Start else Arrangement.Center,
    ) {
        for ((n, candidate) in list.withIndex()) {
            if (n > 0) {
                SnyggSpacer(
                    elementName = FlorisImeUi.SmartbarCandidateSpacer.elementName,
                    modifier = Modifier
                        .fillMaxHeight(0.6f)
                        .weight(0.05f),
                )
            }
            StyleKitCandidateChip(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                candidate = candidate,
                isEmphasized = n == emphasisIndex,
                longPressDelay = longPressDelay,
                onClick = { keyboardManager.commitCandidate(candidate) },
                onLongPress = {
                    if (candidate.isEligibleForUserRemoval) {
                        nlpManager.removeSuggestion(subtypeManager.activeSubtype, candidate)
                    } else {
                        false
                    }
                },
            )
        }
    }
}

@Composable
private fun StyleKitCandidateChip(
    candidate: SuggestionCandidate,
    isEmphasized: Boolean,
    longPressDelay: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { },
    onLongPress: () -> Boolean = { false },
) = with(LocalDensity.current) {
    var isPressed by remember { mutableStateOf(false) }

    val elementName = FlorisImeUi.SmartbarCandidateWord.elementName
    val attributes = mapOf(
        "auto-commit" to if (candidate.isEligibleForAutoCommit) 1 else 0,
        // StyleKit addition: lets Snygg themes target the primary chip with a
        // rule like `smartbar-candidate-word[emphasis="1"]`.
        "emphasis" to if (isEmphasized) 1 else 0,
    )
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE

    SnyggRow(
        elementName = elementName,
        attributes = attributes,
        selector = selector,
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isPressed = true
                    if (down.pressed != down.previousPressed) down.consume()
                    var upOrCancel: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    try {
                        upOrCancel = withTimeout(longPressDelay) {
                            waitForUpOrCancellation()
                        }
                        upOrCancel?.let { if (it.pressed != it.previousPressed) it.consume() }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        if (onLongPress()) {
                            upOrCancel = null
                            isPressed = false
                        }
                        waitForUpOrCancellation()?.let { if (it.pressed != it.previousPressed) it.consume() }
                    }
                    if (upOrCancel != null) {
                        onClick()
                    }
                    isPressed = false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Use plain Text for the main label so we can apply FontWeight
            // directly. SnyggText doesn't expose fontWeight as a parameter
            // (it comes from the Snygg stylesheet); for the emphasized center
            // chip we want to override that, so we use Text here. The Snygg
            // element name is still applied via the parent SnyggRow/SnyggColumn.
            Text(
                text = candidate.text.toString(),
                fontWeight = if (isEmphasized) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (candidate.secondaryText != null) {
                Text(
                    text = candidate.secondaryText!!.toString(),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
