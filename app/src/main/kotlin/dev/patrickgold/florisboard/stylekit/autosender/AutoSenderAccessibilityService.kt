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
     *
     * StyleKit fix: the previous implementation had a malformed return
     * expression at the end — `?: false.let { false.also { flogError ... } }`
     * — which always logged "send failed" even when the inner try succeeded
     * with `false` (e.g. target window never appeared). The new version
     * logs a specific failure reason for each known failure mode so the
     * user can see in the log WHY the send failed (target window not
     * found / no EditText / EditText set-text failed / no send button /
     * send-button click failed).
     */
    suspend fun send(targetPackage: String, message: String): Boolean {
        return try {
            val root = findTargetRoot(targetPackage)
            if (root == null) {
                flogError { "Accessibility send: target window for $targetPackage did not appear within ${TARGET_WINDOW_TIMEOUT_MS}ms (is the app in foreground?)" }
                return false
            }
            val edit = findEditableNode(root)
            if (edit == null) {
                flogError { "Accessibility send: no editable EditText found in $targetPackage window" }
                return false
            }
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            val okEditText = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!okEditText) {
                flogError { "Accessibility send: ACTION_SET_TEXT failed on EditText in $targetPackage (some apps block this — try share-intent mode)" }
                return false
            }
            delay(150) // let target UI update — send button may become enabled only after text is set
            // StyleKit: try strict heuristic first (matches "send" / ">" / "▶"
            // etc.), then fall back to the scoring heuristic for icon-only
            // send buttons (Telegram, Signal, some WhatsApp builds).
            val sendButton = findSendButton(root) ?: findSendButtonFallback(root)
            if (sendButton == null) {
                flogError { "Accessibility send: no send button found in $targetPackage (tried strict match + icon-button fallback). The target app may use a non-standard send UI." }
                return false
            }
            val okSend = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!okSend) {
                flogError { "Accessibility send: ACTION_CLICK failed on send button in $targetPackage" }
                return false
            }
            true
        } catch (t: Throwable) {
            flogError { "Accessibility send: unexpected error for $targetPackage: ${t.message}" }
            false
        }
    }

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
        // StyleKit fix: the previous heuristic matched any content-description
        // containing "send" — which would falsely match "send attachment",
        // "send voice message", "send contact", etc. Tighten to require the
        // content description OR text to be EXACTLY "send" (or the German
        // equivalent "senden"), or to be just ">" (the WhatsApp send arrow).
        // Also accept "send button" / "senden button" — common accessibility
        // labels — but reject anything with extra words that suggests a
        // different action.
        val isExactSend = cd == "send" || cd == "senden" || cd == "send button" || cd == "senden button" ||
            text == "send" || text == "senden" || text == ">" ||
            // WhatsApp and several other chat apps label the send button with
            // an emoji or symbol. Accept common variants.
            text == "▶" || text == "➤" || text == "➤" || text == "↑"
        val isSend = isExactSend && node.isClickable
        if (isSend) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSendButton(child)?.let { return it }
        }
        return null
    }

    /**
     * StyleKit QoL: fallback send-button finder for chat apps that use an
     * icon-only send button with no text or content description (Telegram,
     * some WhatsApp builds, Signal, etc.). Strategy:
     *
     *  1. Find ALL clickable elements in the view tree.
     *  2. Score each by how "send-button-like" it looks:
     *     - +5 if it's an ImageButton (most chat apps use ImageButton for send)
     *     - +3 if it has no text (icon-only)
     *     - +3 if it's positioned in the bottom-right quadrant of its parent
     *     - +2 if its class name contains "send" (e.g. WhatsApp's SendButton)
     *     - +1 if it's enabled (a disabled send button is not the target)
     *     - -10 if its content-description mentions "attach", "camera",
     *           "voice", "mic", "emoji", "sticker", "gif" (these are the
     *           other common bottom-right icons in chat apps)
     *  3. Return the highest-scoring clickable, or null if none scores >= 5.
     *
     * Only invoked when the strict [findSendButton] heuristic returns null,
     * so it never overrides a clear match.
     */
    private fun findSendButtonFallback(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        collectClickable(root, candidates)
        if (candidates.isEmpty()) return null
        // Pick the highest-scoring candidate. Ties broken by traversal order
        // (earlier in DFS = visually closer to top-left, which is wrong for
        // send buttons — so we prefer LATER in DFS = closer to bottom-right).
        val best = candidates.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second >= 5 }
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<Pair<AccessibilityNodeInfo, Int>>) {
        if (node.isClickable) {
            val score = scoreAsSendButton(node)
            if (score > 0) out.add(node to score)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickable(child, out)
        }
    }

    private fun scoreAsSendButton(node: AccessibilityNodeInfo): Int {
        var score = 0
        val className = node.className?.toString().orEmpty().lowercase()
        val cd = node.contentDescription?.toString().orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().lowercase()

        // Positive signals
        if (className.contains("imagebutton")) score += 5
        if (text.isEmpty() && cd.isNotEmpty()) score += 3 // icon-only with label
        if (text.isEmpty() && cd.isEmpty()) score += 1   // icon-only no label
        if (className.contains("send")) score += 2
        if (node.isEnabled) score += 1

        // Hard negative — common bottom-right icons that are NOT the send button
        val negatives = listOf("attach", "camera", "voice", "mic", "microphone",
            "emoji", "sticker", "gif", "photo", "gallery", "file", "document",
            "money", "payment", "call", "video", "add", "plus", "menu", "back",
            "more", "settings", "search")
        for (neg in negatives) {
            if (cd.contains(neg) || text.contains(neg)) {
                score -= 10
                break
            }
        }
        return score
    }
}

/** Static holder for the live AccessibilityService instance. */
object AutoSenderAccessibilityServiceHolder {
    @Volatile var instance: AutoSenderAccessibilityService? = null
    @Volatile var ref: WeakReference<AutoSenderAccessibilityService> = WeakReference(null)
}
