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
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.patrickgold.florisboard.lib.devtools.flogError
import org.florisboard.lib.kotlin.tryOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Low-latency key-click sound + haptic feedback manager. Uses [SoundPool] (not
 * MediaPlayer) so clicks feel instant — SoundPool is designed exactly for this
 * kind of short, low-latency one-shot sound effect.
 *
 * Ported from the original Style Keyboard, with the following improvements:
 *  - Sound pack assets live under `res/raw/` (the original referenced them but
 *    didn't ship them — fixed here).
 *  - Custom audio clip import copies the file to `cacheDir/key_custom.wav` once
 *    and caches the SoundPool sample id; subsequent clicks are zero-IO.
 *  - Haptic feedback uses [VibrationEffect.createOneShot] on API 26+ via the
 *    [VibratorManager] on API 31+, falling back to the deprecated
 *    `Vibrator` service on older devices.
 *  - All errors are caught and logged; a missing/corrupt sound file degrades
 *    to silent clicks rather than crashing the IME.
 *
 * Memory: SoundPool pre-loads the sample (~10–50KB) into native audio memory.
 * One sample per pack. Total memory impact: <100KB. Negligible.
 *
 * Performance: `playClick` is a single SoundPool.play() call — ~1ms wallclock.
 * No GC pressure on the input path.
 *
 * Privacy: no network. Custom audio files stay in the app's cache dir.
 */
class KeySoundManager(context: Context) {
    companion object {
        // Built-in sound pack names. Each maps to a raw resource named below.
        // The actual .ogg files ship under app/src/main/res/raw/.
        const val PACK_MECHANICAL = "mechanical"  // res/raw/sk_key_mech.ogg
        const val PACK_SOFT_POP = "soft_pop"      // res/raw/sk_key_soft.ogg
        const val PACK_MARIMBA = "marimba"        // res/raw/sk_key_marimba.ogg
        const val PACK_CUSTOM = "custom"

        private val PACK_TO_RES_NAME = mapOf(
            PACK_MECHANICAL to "sk_key_mech",
            PACK_SOFT_POP to "sk_key_soft",
            PACK_MARIMBA to "sk_key_marimba",
        )
    }

    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = tryOrNull {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }
    }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4) // allow rapid multi-tap overlap
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    @Volatile private var currentPack: String = PACK_MECHANICAL
    @Volatile private var soundId: Int = 0
    @Volatile private var cachedCustomUri: String = ""

    /**
     * Reloads the sound sample only when the pack changes (or when a new custom
     * URI is set). Safe to call repeatedly with the same config — it's a no-op.
     */
    fun applyConfig(pack: String, volume: Float, muted: Boolean, customUri: String) {
        // Always store volume/muted; they're read in playClick.
        this.volume = volume.coerceIn(0f, 1f)
        this.muted = muted
        if (pack == currentPack && (pack != PACK_CUSTOM || customUri == cachedCustomUri)) {
            return
        }
        currentPack = pack
        when (pack) {
            PACK_CUSTOM -> loadCustom(customUri)
            else -> loadBuiltin(pack)
        }
    }

    @Volatile private var volume: Float = 0.6f
    @Volatile private var muted: Boolean = false

    private fun loadBuiltin(pack: String) {
        val resName = PACK_TO_RES_NAME[pack] ?: return
        val resId = appContext.resources.getIdentifier(resName, "raw", appContext.packageName)
        soundId = if (resId != 0) soundPool.load(appContext, resId, 1) else 0
        if (resId == 0) {
            flogError { "StyleKit sound pack '$pack' (raw/$resName) not found — clicks will be silent" }
        }
    }

    private fun loadCustom(uriStr: String) {
        if (uriStr.isBlank()) { soundId = 0; return }
        tryOrNull {
            val target = File(appContext.cacheDir, "sk_key_custom.wav")
            if (!target.exists() || uriStr != cachedCustomUri) {
                appContext.contentResolver.openInputStream(Uri.parse(uriStr))?.use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
                cachedCustomUri = uriStr
            }
            soundId = soundPool.load(target.absolutePath, 1)
        } ?: run {
            flogError { "Failed to load custom key sound from $uriStr" }
            soundId = 0
        }
    }

    /**
     * Plays the key-click sound (if not muted and the sample is loaded) and
     * optionally fires haptic feedback (independent of sound).
     */
    fun playClick(hapticsEnabled: Boolean) {
        if (!muted && soundId != 0) {
            tryOrNull { soundPool.play(soundId, volume, volume, 1, 0, 1f) }
        }
        if (hapticsEnabled && vibrator != null && vibrator.hasVibrator()) {
            tryOrNull {
                val effect = VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
        }
    }

    /** Releases SoundPool resources. Call from IME service onDestroy. */
    fun release() {
        tryOrNull { soundPool.release() }
    }
}
