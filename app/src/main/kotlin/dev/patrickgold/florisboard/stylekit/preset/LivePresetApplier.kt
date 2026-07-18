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

package dev.patrickgold.florisboard.stylekit.preset

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitConfigEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Holds the *currently active* preset mapping in memory so the per-keystroke
 * transform path can do a single map lookup with no DB I/O on the critical input path.
 *
 * The IME process observes the StyleKit config + preset table asynchronously.
 * Whenever the active preset changes (or its mapping is edited), [activeMapping]
 * is updated. The KeyboardManager reads [activeMapping] synchronously per keystroke.
 *
 * Memory: a single Map<String,String> of ~80 entries (~5KB). Negligible.
 *
 * Crash safety: any DB failure leaves the previous mapping in place; the IME
 * continues to function. If the very first read fails, [activeMapping] stays empty
 * and [transformChar] returns the original character (no-op).
 *
 * **Restart resilience (StyleKit fix):** The original implementation bailed out
 * permanently if the very first DB read failed (which happens during Direct Boot,
 * before the user has unlocked the device, or whenever the IME process starts
 * before StyleKitDatabase has been seeded). After device restart the IME would
 * come up, the early-return would fire, and the preset would silently stay off
 * forever — until the user manually toggled it in Settings.
 *
 * The fix has two parts:
 *  1. The observer is now launched in a retry loop with exponential backoff.
 *     If `StyleKitDatabase.get()` returns null or the DAO throws, we wait and
 *     try again rather than giving up. Once the observer is successfully
 *     installed, the loop exits and the standard Flow takes over.
 *  2. A new [reattach] entry point lets [FlorisApplication] explicitly nudge us
 *     back to life after `ACTION_USER_UNLOCKED` (Direct Boot exit) — this
 *     collapses the wait time from "next backoff tick" to "immediately".
 */
class LivePresetApplier private constructor(context: Context) {
    companion object {
        @Volatile private var INSTANCE: LivePresetApplier? = null
        fun get(context: Context): LivePresetApplier =
            INSTANCE ?: synchronized(this) { INSTANCE ?: LivePresetApplier(context.applicationContext).also { INSTANCE = it } }

        // Backoff schedule for the retry loop, in milliseconds. Caps at 10s.
        private val BACKOFF_MS = longArrayOf(250, 500, 1000, 2000, 4000, 8000, 10000)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val presetRepo = PresetRepository(appContext)

    private val _activeState = MutableStateFlow(ActiveState())
    val activeState: StateFlow<ActiveState> = _activeState.asStateFlow()

    /** Snapshot of the current mapping for synchronous per-keystroke access. */
    @Volatile
    private var mappingSnapshot: Map<String, String> = emptyMap()

    /** The current observer job — cancelled and restarted by [reattach]. */
    @Volatile private var observerJob: Job? = null

    init {
        startObserver()
    }

    /**
     * (Re)starts the config observer. Safe to call any number of times —
     * cancels the previous job first. Called from [FlorisApplication.init]
     * on first boot and after `ACTION_USER_UNLOCKED` (Direct Boot exit).
     */
    fun reattach() {
        observerJob?.cancel()
        startObserver()
    }

    private fun startObserver() {
        observerJob = scope.launch {
            var attempt = 0
            // Outer retry loop: keep trying to obtain a non-null configDao.
            // This handles Direct Boot (where CE storage isn't available yet)
            // and any transient DB opening failure on cold start.
            while (isActive) {
                val configDao = tryOrNull { StyleKitDatabase.get(appContext)?.configDao() }
                if (configDao != null) {
                    // Got the DAO — install the persistent observer. This
                    // collectLatest call runs forever (until cancelled) and
                    // emits a fresh config row on every DB change.
                    try {
                        configDao.observe().collectLatest { config -> applyConfig(config) }
                    } catch (t: Throwable) {
                        // The flow itself died (DB closed, etc.). Fall through
                        // to the retry loop to re-establish it.
                        // Log via tryOrNull so we never crash on logging.
                        tryOrNull { /* swallow */ }
                    }
                    // If we get here, the flow exited. Wait briefly then retry.
                    if (!isActive) return@launch
                    attempt = 0
                    delay(BACKOFF_MS[0])
                    continue
                }
                // DAO unavailable — backoff and retry.
                val delayMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.size - 1)]
                delay(delayMs)
                attempt++
            }
        }
    }

    /** Applies a freshly-observed config row: loads the preset mapping or clears state. */
    private suspend fun applyConfig(config: StyleKitConfigEntity?) {
        if (config == null) {
            _activeState.value = ActiveState()
            mappingSnapshot = emptyMap()
            // StyleKit: keep FontNormalizer's user-mapping in sync so emoji
            // shortcuts and the learning model work on every font, including
            // custom user presets that map ASCII to arbitrary Unicode.
            FontNormalizer.setActivePresetMapping(emptyMap())
            return
        }
        if (!config.livePresetEnabled || config.activePresetId == 0L) {
            _activeState.value = ActiveState(enabled = false, presetId = 0L, name = null)
            mappingSnapshot = emptyMap()
            FontNormalizer.setActivePresetMapping(emptyMap())
            return
        }
        val preset = tryOrNull { presetRepo.getById(config.activePresetId) }
        if (preset == null) {
            _activeState.value = ActiveState(enabled = false, presetId = 0L, name = null)
            mappingSnapshot = emptyMap()
            FontNormalizer.setActivePresetMapping(emptyMap())
            return
        }
        val mapping = tryOrNull { DefaultPresets.decode(preset.mappingJson) } ?: emptyMap()
        mappingSnapshot = mapping
        // StyleKit: register the FORWARD mapping with FontNormalizer, which
        // builds the reverse (stylized → ASCII) and uses it during normalize()
        // calls. This is what makes emoji shortcuts and the adaptive learning
        // model work on EVERY font — including custom presets the user creates
        // whose stylized forms aren't in any standard Unicode block.
        FontNormalizer.setActivePresetMapping(mapping)
        _activeState.value = ActiveState(
            enabled = true,
            presetId = preset.rowId,
            name = preset.name,
        )
    }

    /**
     * Synchronous per-keystroke transform. Returns the original char as a string
     * if no preset is active or if the char is not in the mapping.
     *
     * Returns a [String] (not [Char]) because Unicode stylized characters
     * (Mathematical Sans-Serif U+1D5A0, Circled Latin U+24B6, Zalgo with
     * combining diacritics, etc.) are encoded as UTF-16 surrogate pairs or
     * multi-char sequences. Truncating to a single Char would break the output
     * (high surrogate alone is invalid Unicode).
     */
    fun transformChar(ch: Char): String {
        if (!_activeState.value.enabled) return ch.toString()
        val snap = mappingSnapshot
        if (snap.isEmpty()) return ch.toString()
        val replacement = snap[ch.toString()]
        return replacement ?: ch.toString()
    }

    /** True if live preset conversion is on AND a preset is selected. */
    fun isActive(): Boolean = _activeState.value.enabled

    @Immutable
    data class ActiveState(
        val enabled: Boolean = false,
        val presetId: Long = 0L,
        val name: String? = null,
    )
}
