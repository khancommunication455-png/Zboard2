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

package dev.patrickgold.florisboard.stylekit.appearance

import android.content.Context
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Bridge between the StyleKit appearance config (Room) and the [KeySoundManager].
 *
 * The KeyboardManager owns one instance of this. On construction it launches a
 * background collector that observes [AppearanceRepository.observe] and pushes
 * the latest sound/haptics config into the [KeySoundManager] so per-keystroke
 * `playClick()` calls are always in sync with what the user picked in the
 * Appearance settings screen.
 *
 * Crash safety: every step is wrapped in `tryOrNull`; if the DB or the
 * SoundPool fails to initialize, [playClick] becomes a silent no-op and the
 * keyboard continues to function.
 *
 * Performance: `playClick` is a single SoundPool.play() call on the input
 * thread — no allocations, no coroutine launch.
 */
class KeySoundController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val repo = tryOrNull { AppearanceRepository(appContext) }
    val soundManager = tryOrNull { KeySoundManager(appContext) }

    @Volatile private var hapticsEnabled: Boolean = true

    init {
        if (repo != null && soundManager != null) {
            scope.launch {
                runCatching {
                    repo.observe().collectLatest { cfg ->
                        hapticsEnabled = cfg.hapticsEnabled
                        tryOrNull {
                            soundManager.applyConfig(
                                pack = cfg.soundPack,
                                volume = cfg.soundVolume,
                                muted = cfg.soundMuted,
                                customUri = cfg.soundCustomUri,
                            )
                        } ?: run {
                            flogError { "StyleKit: failed to apply sound config" }
                        }
                    }
                }
            }
        }
    }

    /**
     * Called by KeyboardManager on every key UP event. Plays the configured
     * key-click sound (unless muted) and fires haptic feedback (if enabled).
     * Safe to call from the input thread.
     */
    fun playClick() {
        tryOrNull { soundManager?.playClick(hapticsEnabled) }
    }
}
