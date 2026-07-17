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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderLogEntity
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Foreground service that runs an Auto Sender script: dispatches each message
 * on schedule, either via share-intents (default) or the AccessibilityService
 * fallback (when [AutoSenderScriptEntity.useAccessibility] is true).
 *
 * Keeps itself alive with a partial wake lock (10-minute auto-release safety net)
 * and a low-importance notification. The user can Pause / Resume / Stop via the
 * notification action buttons or the settings screen.
 *
 * Safety rails:
 *  - Minimum 3s between iterations (even if the user sets lower).
 *  - Loop bounds: "once" → 1, "n_times" → max(1, loopCount), "infinite" → Int.MAX_VALUE.
 *  - Per-message delay uses msg.delayMs if > 0, else script.perMessageDelayMs.
 *  - Pause is honored via a suspend-loop polling `_state == Paused`.
 *
 * Privacy: this service is unrelated to the IME code path. It is its own module
 * with its own notification channel and its own settings screen. It does not
 * touch typed content; it only dispatches messages the user explicitly defined.
 */
class AutoSenderService : Service() {
    companion object {
        const val NOTIF_CHANNEL_ID = "stylekit_auto_sender"
        const val NOTIF_ID = 4242

        const val ACTION_START = "dev.patrickgold.florisboard.stylekit.autosender.START"
        const val ACTION_PAUSE = "dev.patrickgold.florisboard.stylekit.autosender.PAUSE"
        const val ACTION_RESUME = "dev.patrickgold.florisboard.stylekit.autosender.RESUME"
        const val ACTION_STOP = "dev.patrickgold.florisboard.stylekit.autosender.STOP"
        const val EXTRA_SCRIPT_ID = "script_id"

        /** Minimum interval between iterations, regardless of script config. */
        const val MIN_INTERVAL_MS = 3000L

        /**
         * In-memory running flag, set true in [onCreate] and false in [onDestroy].
         * The keyboard's quick-action panel reads this so its "Auto Sender" button
         * can flip between Start and Stop labels.
         */
        @Volatile
        @JvmStatic
        private var running: Boolean = false

        /** True iff the Auto Sender foreground service is currently active. */
        @JvmStatic
        fun isCurrentlyRunning(context: android.content.Context): Boolean {
            if (running) return true
            return tryOrNull {
                val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                val active = nm.activeNotifications
                active.any { it.id == NOTIF_ID }
            } ?: false
        }
    }

    enum class RunState { Idle, Running, Paused, Stopping }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentScript: AutoSenderScriptEntity? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SenderStrategy.liveService = this
        running = true
        startForegroundCompat(NOTIF_ID, buildNotification(running = false, text = "Auto Sender ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, -1L)
                if (scriptId <= 0) {
                    flogError { "AutoSenderService.START: missing script_id" }
                    stopSelf(); return START_NOT_STICKY
                }
                startScript(scriptId)
            }
            ACTION_PAUSE -> _state.value = RunState.Paused
            ACTION_RESUME -> _state.value = RunState.Running
            ACTION_STOP -> stopScript()
        }
        return START_STICKY
    }

    private fun startScript(scriptId: Long) {
        scope.launch {
            val script = tryOrNull {
                StyleKitDatabase.get(this@AutoSenderService).autoSenderDao().getScriptById(scriptId)
            }
            if (script == null) {
                flogError { "AutoSenderService: script $scriptId not found" }
                stopSelf(); return@launch
            }
            currentScript = script
            acquireWakeLock()
            _state.value = RunState.Running
            runLoop(script)
        }
    }

    private suspend fun runLoop(script: AutoSenderScriptEntity) {
        val iterations = when (script.loopMode) {
            "once" -> 1
            "n_times" -> script.loopCount.coerceAtLeast(1)
            else -> Int.MAX_VALUE
        }
        val interval = script.intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val messages = AutoSenderCodec.decodeMessages(script.messagesJson)
        if (messages.isEmpty()) {
            flogError { "AutoSenderService: script has no messages" }
            stopScript(); return
        }

        var sent = 0
        for (i in 0 until iterations) {
            if (_state.value == RunState.Stopping) break
            // Pause gate
            while (_state.value == RunState.Paused) {
                delay(200)
                if (_state.value == RunState.Stopping) break
            }
            if (_state.value == RunState.Stopping) break

            for (msg in messages) {
                if (_state.value == RunState.Stopping) break
                val ok = tryOrNull { SenderStrategy.send(this, script, msg.text) } ?: false
                logAttempt(script.targetPackage, msg.text, ok)
                sent++
                updateNotification(running = true, text = "Sent $sent / ${if (iterations == Int.MAX_VALUE) "∞" else iterations * messages.size}")
                delay(if (msg.delayMs > 0) msg.delayMs else script.perMessageDelayMs)
            }
            if (i < iterations - 1) delay(interval)
        }
        stopScript()
    }

    private fun stopScript() {
        _state.value = RunState.Stopping
        releaseWakeLock()
        currentScript = null
        scope.launch { _state.value = RunState.Idle; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun logAttempt(targetPackage: String, message: String, ok: Boolean) {
        scope.launch {
            tryOrNull {
                StyleKitDatabase.get(this@AutoSenderService).autoSenderDao().insertLog(
                    AutoSenderLogEntity(
                        sentAt = System.currentTimeMillis(),
                        targetPackage = targetPackage,
                        message = message,
                        status = if (ok) "sent" else "failed",
                    )
                )
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StyleKit:AutoSender").apply {
            // 10-minute safety net to avoid battery drain if the service is forgotten.
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        tryOrNull { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun buildNotification(running: Boolean, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, FlorisAppActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseIntent = PendingIntent.getService(
            this, 1, Intent(this, AutoSenderService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, AutoSenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Auto Sender")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(running)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Pause", pauseIntent)
            .addAction(0, "STOP ALL", stopIntent)
            .build()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun updateNotification(running: Boolean, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        tryOrNull { nm.notify(NOTIF_ID, buildNotification(running, text)) }
    }

    override fun onDestroy() {
        running = false
        releaseWakeLock()
        if (SenderStrategy.liveService === this) SenderStrategy.liveService = null
        super.onDestroy()
    }
}

/**
 * Helper for creating the Auto Sender notification channel. Call from
 * [dev.patrickgold.florisboard.FlorisApplication.onCreate] or from the settings
 * screen before starting the service.
 */
fun createAutoSenderNotificationChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
        AutoSenderService.NOTIF_CHANNEL_ID,
        "Auto Sender Status",
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = "Status updates for the Auto Sender scheduled-message utility"
        setShowBadge(false)
    }
    nm.createNotificationChannel(channel)
}
