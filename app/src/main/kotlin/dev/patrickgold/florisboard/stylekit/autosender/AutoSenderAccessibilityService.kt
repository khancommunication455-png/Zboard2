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

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.florisboard.lib.kotlin.tryOrNull
import java.lang.ref.WeakReference

/**
 * AccessibilityService fallback for Auto Sender. Used when the target app doesn't
 * accept share-intents (most chat apps do, but some don't).
 *
 * Strategy:
 *  1. Wait up to 3s for the target app's window to appear (polling rootInActiveWindow).
 *  2. Find the first EditText in the view hierarchy.
 *  3. Set its text via ACTION_SET_TEXT.
 *  4. Find a "send" button (heuristic: contentDescription/text contains "send", or text == ">").
 *  5. Click it via ACTION_CLICK.
 *
 * Registered statically via [AutoSenderAccessibilityServiceHolder] so the
 * foreground service can reach the live instance.
 *
 * Privacy: this service is ONLY active when the user has explicitly enabled it
 * in system Accessibility settings AND the foreground Auto Sender service is
 * running with a script that requests accessibility mode. We never read screen
 * contents outside of the dispatch path.
 *
 * Crash safety: every node operation is wrapped in [tryOrNull]; any failure
 * returns false and the sender logs "failed" status without crashing.
 *
 * Note: this service must be declared in AndroidManifest.xml with
 * `BIND_ACCESSIBILITY_SERVICE` permission and an `@xml/accessibility_service_config`
 * metadata.
 */
class AutoSenderAccessibilityService : AccessibilityService() {
    companion object {
        /** Wait this long for the target window to appear before giving up. */
        private const val TARGET_WINDOW_TIMEOUT_MS = 3000L
        /** Poll interval while waiting for the target window. */
        private const val POLL_INTERVAL_MS = 80L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutoSenderAccessibilityServiceHolder.instance = this
    }

    override fun onDestroy() {
        AutoSenderAccessibilityServiceHolder.instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No-op — everything is driven synchronously from [send].
    }

    override fun onInterrupt() { /* no-op */ }

    /**
     * Sends [message] to the target app's EditText and clicks the send button.
     * Returns true if both actions succeeded.
     *
     * Note: this is a *blocking* call — callers should run it in a background
     * coroutine. The foreground service does so via `runBlocking` inside its
     * own scope.
     */
    suspend fun send(targetPackage: String, message: String): Boolean = tryOrNull {
        val root = findTargetRoot(targetPackage) ?: return@tryOrNull false
        val edit = findEditableNode(root) ?: return@tryOrNull false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val okEditText = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(150) // let target UI update
        val sendButton = findSendButton(root)
        val okSend = sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        okEditText && okSend
    } ?: false.let { false.also { flogError { "Accessibility send failed for $targetPackage" } } }

    private suspend fun findTargetRoot(targetPackage: String): AccessibilityNodeInfo? {
        return withTimeoutOrNull(TARGET_WINDOW_TIMEOUT_MS) {
            while (true) {
                val root = rootInActiveWindow
                if (root != null && root.packageName?.toString() == targetPackage) return@withTimeoutOrNull root
                delay(POLL_INTERVAL_MS)
            }
            null
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.widget.EditText" && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNode(child)?.let { return it }
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()
        val isSend = (cd.contains("send") || cd.contains("senden") || text.contains("send") || text == ">")
            && node.isClickable
        if (isSend) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSendButton(child)?.let { return it }
        }
        return null
    }
}

/** Static holder for the live AccessibilityService instance. */
object AutoSenderAccessibilityServiceHolder {
    @Volatile var instance: AutoSenderAccessibilityService? = null
    @Volatile var ref: WeakReference<AutoSenderAccessibilityService> = WeakReference(null)
}
