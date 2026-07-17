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

package dev.patrickgold.florisboard.app.settings.stylekit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes

/**
 * StyleKit hub — single entry point that lists all ported features. Shown as a
 * top-level entry in the FlorisBoard settings home screen. Each item deep-links
 * to the corresponding settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleKitHubScreen() {
    val nav = LocalNavController.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StyleKit") },
                navigationIcon = {
                    IconButton(onClick = { nav?.popBackStack() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Features ported from Style Keyboard and grafted onto FlorisBoard. " +
                    "All data is stored on-device; nothing is ever sent off-device.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            HubItem(
                title = "Enable Keyboard",
                description = "3-step onboarding: enable IME, switch to it, optionally grant full access.",
                onClick = { nav?.navigate(Routes.Settings.StyleKitOnboarding) },
            )
            HubItem(
                title = "Font Style Converter",
                description = "Unicode font presets (Bold, Math Sans, Bubble, Zalgo, …). Convert & copy, or apply live as you type.",
                onClick = { nav?.navigate(Routes.Settings.StyleKitPresets) },
            )
            HubItem(
                title = "Emoji Lab",
                description = "Text shortcuts that expand to emoji strings. Whole-word or prefix match.",
                onClick = { nav?.navigate(Routes.Settings.StyleKitEmojiLab) },
            )
            HubItem(
                title = "Appearance",
                description = "Themes, key shapes, glint sweep, GIF/video background, sound & haptics.",
                onClick = { nav?.navigate(Routes.Settings.StyleKitAppearance) },
            )
            HubItem(
                title = "Auto Sender",
                description = "Scheduled/automated message-sending utility. Separate from the keyboard.",
                onClick = { nav?.navigate(Routes.Settings.StyleKitAutoSender) },
            )
        }
    }
}

@Composable
private fun HubItem(title: String, description: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onClick) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open")
            }
        }
    }
}
