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

package dev.patrickgold.florisboard.stylekit.onboarding

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.lib.devtools.flogError
import org.florisboard.lib.kotlin.tryOrNull

/**
 * State-checking utilities for the 3-step onboarding screen.
 *
 * Step 1: "Enable keyboard in system input-method settings" — checks
 *   [Settings.Secure.ENABLED_INPUT_METHODS] for our IME service's flattened
 *   component name.
 *
 * Step 2: "Switch to it as the active input method" — checks that we're the
 *   system's [Settings.Secure.DEFAULT_INPUT_METHOD]. Falls back to opening the
 *   system IME picker; if the picker throws (some OEM ROMs hide it behind
 *   reflection), opens the IME settings screen as a graceful fallback.
 *
 * Step 3: "Grant full access (optional)" — there is no public API to query
 *   this, so we always return false (matches the original Style Keyboard's
 *   approach: the user re-taps the step to confirm). This is by design.
 *
 * Privacy: only reads system Settings.Secure values; no network, no writes
 * beyond what the system activities themselves do.
 */
object OnboardingState {
    /** True if our IME service is listed in the system's enabled input methods. */
    fun isImeEnabled(context: Context): Boolean = tryOrNull {
        val packageName = context.packageName
        val flattened = ComponentName(packageName, FlorisImeService::class.java.name).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            ?: return@tryOrNull false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (entry.equals(flattened, ignoreCase = true)) return@tryOrNull true
        }
        false
    } ?: false

    /** True if our IME service is the system's currently-selected default input method. */
    fun isImeActive(context: Context): Boolean = tryOrNull {
        val packageName = context.packageName
        val default = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: return@tryOrNull false
        default.contains(packageName, ignoreCase = true)
    } ?: false

    /**
     * Always returns false — there is no public API to query "full access" state.
     * The user re-taps the step to confirm.
     */
    fun isFullAccessOn(context: Context): Boolean = false

    /**
     * Opens the system input-method settings (step 1 + step 3). Returns true if
     * the activity was successfully launched.
     */
    fun safeStartImeSettings(context: Context): Boolean = safeStartActivity(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    /**
     * Shows the system IME picker (step 2). Returns true if the picker was shown;
     * if showing the picker throws, falls back to opening the IME settings screen
     * and shows a toast.
     */
    fun showImePickerSafely(context: Context): Boolean {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        return try {
            imm?.showInputMethodPicker() ?: false
            if (imm == null) {
                safeStartImeSettings(context)
                toast(context, "Opening keyboard settings instead.")
                false
            } else true
        } catch (t: Throwable) {
            flogError { "showInputMethodPicker failed: ${t.message}; falling back to IME settings" }
            toast(context, "Opening keyboard settings instead.")
            safeStartImeSettings(context)
        }
    }

    private fun safeStartActivity(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context is Activity) context.startActivity(intent)
        else context.startActivity(intent)
        true
    } catch (e: android.content.ActivityNotFoundException) {
        toast(context, "No settings app available.")
        false
    } catch (e: SecurityException) {
        toast(context, "Not allowed to open settings.")
        false
    } catch (t: Throwable) {
        flogError { "safeStartActivity failed: ${t.message}" }
        false
    }

    private fun toast(context: Context, message: String) {
        tryOrNull { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}
