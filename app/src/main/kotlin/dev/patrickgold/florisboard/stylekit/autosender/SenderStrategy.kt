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

package dev.patrickgold.florisboard.stylekit.autosender

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Picks the right dispatch strategy for an Auto Sender script:
 *  - If `useAccessibility` is false (default): try share-intent first. Returns
 *    true if the chooser was shown. Note: the chooser is shown, but the user
 *    still has to confirm the send in the target app — status is logged as
 *    "sent" optimistically.
 *  - If `useAccessibility` is true: dispatch via the AccessibilityService fallback.
 *    Requires the user to have enabled the service in system Accessibility settings.
 *
 * Crash safety: every dispatch is wrapped in [tryOrNull]; any failure logs "failed"
 * status and continues to the next message.
 */
object SenderStrategy {
    /** Holds the "currently running" service instance so strategies can reach it. */
    @Volatile var liveService: AutoSenderService? = null

    fun send(context: Context, script: AutoSenderScriptEntity, message: String): Boolean {
        return if (script.useAccessibility) {
            sendViaAccessibility(context, script.targetPackage, message)
        } else {
            sendViaShareIntent(context, script, message)
        }
    }

    private fun sendViaShareIntent(
        context: Context,
        script: AutoSenderScriptEntity,
        message: String,
    ): Boolean = try {
        // StyleKit fix: the previous implementation logged success as soon as
        // the chooser was shown, which is misleading — the user still has to
        // tap a target app and confirm send. We still return true here (the
        // dispatch itself succeeded), but emit a clear log message so the
        // user understands why "sent" doesn't mean "actually delivered" in
        // share-intent mode. Switching the script to accessibility mode
        // (script.useAccessibility = true) is the way to get fully automatic
        // sends — see SenderStrategy.sendViaAccessibility for setup steps.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            if (script.targetPackage.isNotBlank()) setPackage(script.targetPackage)
            if (script.targetClass.isNotBlank()) {
                val cn = android.content.ComponentName.unflattenFromString(script.targetClass)
                if (cn != null) component = cn
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Send via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        flogError {
            "Share-intent dispatch: chooser shown. User must tap a target app and confirm send. " +
                "For fully automatic sending, enable Accessibility mode in the script settings " +
                "and grant Accessibility permission to ZBoard."
        }
        true
    } catch (t: Throwable) {
        flogError { "Share-intent dispatch failed: ${t.message}" }
        false
    }

    private fun sendViaAccessibility(context: Context, targetPackage: String, message: String): Boolean {
        val enabled = tryOrNull {
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val expected = "${context.packageName}/${AutoSenderAccessibilityService::class.java.name}"
            services.split(':').any { it.equals(expected, ignoreCase = true) }
        } ?: false
        if (!enabled) {
            // StyleKit fix: be very explicit about WHAT the user needs to do.
            // The previous log just said "not enabled" — the user has no idea
            // what to enable or where. The new message spells out the exact
            // path: Settings → Accessibility → Installed services → ZBoard →
            // enable. This is what surfaces in the Auto Sender log UI.
            flogError {
                "AccessibilityService not enabled. To use accessibility mode, open " +
                    "Settings → Accessibility → Installed services → ZBoard, then toggle ON. " +
                    "(Package: ${context.packageName})"
            }
            return false
        }
        val service = AutoSenderAccessibilityServiceHolder.instance
        if (service == null) {
            flogError {
                "AccessibilityService is enabled but not yet bound. This usually means the " +
                    "service was just enabled — try restarting the Auto Sender script in a few seconds. " +
                    "If the problem persists, toggle the accessibility service off and on again."
            }
            return false
        }
        // Blocking call inside the foreground service's coroutine scope.
        return kotlinx.coroutines.runBlocking { service.send(targetPackage, message) }
    }
}
