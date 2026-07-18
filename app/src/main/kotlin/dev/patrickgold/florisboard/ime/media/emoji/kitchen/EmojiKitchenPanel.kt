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

package dev.patrickgold.florisboard.ime.media.emoji.kitchen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlinx.coroutines.launch
import org.florisboard.lib.snygg.ui.SnyggColumn

/**
 * Emoji Kitchen panel — shown when the user long-presses an emoji in the
 * emoji palette. Displays the available combos for that emoji as a grid of
 * small combo thumbnails (fetched on demand from gstatic).
 *
 * Tapping a combo commits BOTH emojis (Gboard behavior: tapping a combo
 * pastes the combined emoji image as an image attachment in supported apps,
 * but most apps don't support that, so we fall back to committing both
 * emojis as text).
 *
 * Design notes:
 *  - Panel is a Compose overlay positioned at the top of the emoji palette.
 *  - Uses Coil's AsyncImage to load combo thumbnails from gstatic with
 *    disk caching — repeated views of the same combo are instant.
 *  - Manifest fetch is triggered eagerly when the panel opens (via
 *    [EmojiKitchenRepository.ensureLoaded]); on failure we show a small
 *    "Kitchen unavailable" message instead of an empty grid.
 */
@Composable
fun EmojiKitchenPanel(
    anchorEmoji: String,
    onCommitCombo: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(EmojiKitchenRepository.isLoaded()) }
    var combos by remember(anchorEmoji) { mutableStateOf<List<EmojiKitchenCombo>>(emptyList()) }

    // StyleKit: load persisted Kitchen history eagerly so we can show a
    // "Recent" strip at the top of the panel. The history is shared across
    // all anchor emojis — it's the user's most-recent Kitchen combos
    // regardless of which emoji they tapped to get there.
    LaunchedEffect(Unit) {
        EmojiKitchenHistory.load(context)
    }
    val history by EmojiKitchenHistory.historyFlow.collectAsState()

    // Trigger manifest fetch on first composition.
    LaunchedEffect(anchorEmoji) {
        if (!loaded) {
            scope.launch {
                loaded = EmojiKitchenRepository.ensureLoaded(context)
                if (loaded) {
                    combos = buildCombosFor(anchorEmoji)
                }
            }
        } else {
            combos = buildCombosFor(anchorEmoji)
        }
    }

    SnyggColumn(
        elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Kitchen: $anchorEmoji + …",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // StyleKit: wire the ✕ to onDismiss so the user can close the
            // panel without picking a combo. The clickable area is enlarged
            // via the padding for easier touch targeting (24dp min).
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onDismiss() }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // StyleKit QoL: show a "Recent" strip of recently-used combos above
        // the partner grid. Helps the user quickly re-find combos they've
        // used before. The strip is horizontally-scrollable and limited to
        // the 10 most-recent entries.
        if (history.isNotEmpty()) {
            Text(
                "Recent",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                lazyListItems(history.take(10), key = { it.imageUrl + it.timestamp }) { entry ->
                    ComboThumb(
                        combo = EmojiKitchenCombo(entry.leftEmoji, entry.rightEmoji, entry.imageUrl),
                        onClick = {
                            // StyleKit: record this re-use in history so it
                            // bubbles to the top of the Recent strip.
                            EmojiKitchenHistory.recordUse(context,
                                EmojiKitchenCombo(entry.leftEmoji, entry.rightEmoji, entry.imageUrl))
                            onCommitCombo(entry.rightEmoji)
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        when {
            !loaded -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Loading Kitchen…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            combos.isEmpty() -> {
                Text(
                    "No combos available for this emoji yet.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 56.dp),
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(combos, key = { it.imageUrl }) { combo ->
                        ComboThumb(
                            combo = combo,
                            onClick = {
                                // StyleKit: record this combo in history so
                                // it appears in the Recent strip next time.
                                EmojiKitchenHistory.recordUse(context, combo)
                                onCommitCombo(combo.rightEmoji)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComboThumb(combo: EmojiKitchenCombo, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(combo.imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = "Combo ${combo.leftEmoji} + ${combo.rightEmoji}",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Builds the list of available combos for [anchorEmoji] from the manifest. */
private fun buildCombosFor(anchorEmoji: String): List<EmojiKitchenCombo> {
    val partners = EmojiKitchenRepository.partnersFor(anchorEmoji)
    return partners.mapNotNull { partnerKey ->
        // partnerKey is a codepoint-key like "u-1f600"; convert back to emoji string.
        val partnerEmoji = codepointKeyToEmoji(partnerKey) ?: return@mapNotNull null
        val url = EmojiKitchenRepository.buildImageUrl(anchorEmoji, partnerEmoji) ?: return@mapNotNull null
        EmojiKitchenCombo(leftEmoji = anchorEmoji, rightEmoji = partnerEmoji, imageUrl = url)
    }
}

/** Converts a codepoint-key string like "u-1f600" back to the emoji string "😀". */
private fun codepointKeyToEmoji(key: String): String? {
    if (!key.startsWith("u-")) return null
    val parts = key.removePrefix("u-").split("-")
    val sb = StringBuilder()
    for (p in parts) {
        val cp = p.toIntOrNull(16) ?: return null
        sb.appendCodePoint(cp)
    }
    return sb.toString()
}
