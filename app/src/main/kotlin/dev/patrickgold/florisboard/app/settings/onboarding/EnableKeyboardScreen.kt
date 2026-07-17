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

package dev.patrickgold.florisboard.app.settings.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.stylekit.onboarding.OnboardingState

/**
 * 3-step "Enable Keyboard" onboarding screen:
 *
 *   1. Enable the keyboard in system input-method settings.
 *   2. Switch to it as the active input via the picker.
 *   3. Optionally grant "full access" (no public API to verify, so step 3 never
 *      marks itself done — the user re-taps to confirm).
 *
 * Each step shows live completion status (green check when detected) and
 * deep-links to the right system screen. Devices that don't expose the standard
 * picker gracefully fall back to opening IME settings + a toast.
 *
 * Ported from the original Style Keyboard's EnableKeyboardScreen, but using
 * FlorisBoard's IME service class for the package-name checks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnableKeyboardScreen() {
    val context = LocalContext.current
    var enabledInIme by remember { mutableStateOf(false) }
    var isActive by remember { mutableStateOf(false) }
    var fullAccess by remember { mutableStateOf(false) }

    // Re-check status whenever the screen is resumed (via re-composition).
    LaunchedEffect(Unit) {
        enabledInIme = OnboardingState.isImeEnabled(context)
        isActive = OnboardingState.isImeActive(context)
        fullAccess = OnboardingState.isFullAccessOn(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enable Keyboard") },
                navigationIcon = {
                    val nav = LocalNavController.current
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
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Three quick steps to start using your customized keyboard.",
                style = MaterialTheme.typography.bodyMedium,
            )
            StepCard(
                stepNumber = 1,
                title = "Turn on the keyboard",
                description = "Enable this keyboard in the system input-method settings.",
                done = enabledInIme,
                onAction = {
                    OnboardingState.safeStartImeSettings(context)
                },
                actionLabel = "Open input method settings",
            )
            StepCard(
                stepNumber = 2,
                title = "Select as active input",
                description = "Switch to this keyboard as your active input method.",
                done = isActive,
                onAction = {
                    OnboardingState.showImePickerSafely(context)
                },
                actionLabel = "Show input picker",
            )
            StepCard(
                stepNumber = 3,
                title = "Grant full access (optional)",
                description = "Required only if you want to use features that need additional permissions " +
                    "(e.g. Auto Sender accessibility fallback). There is no public API to verify this — " +
                    "re-tap the button after enabling.",
                done = fullAccess,
                optional = true,
                onAction = {
                    OnboardingState.safeStartImeSettings(context)
                },
                actionLabel = "Open input method settings",
            )
        }
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    description: String,
    done: Boolean,
    optional: Boolean = false,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (done) Color(0xFF4CAF50)
                        else if (optional) Color(0xFF8B5CF6)
                        else MaterialTheme.colorScheme.primary
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.White)
                } else {
                    Text(
                        "$stepNumber",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
