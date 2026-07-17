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
import android.net.Uri
import androidx.compose.runtime.Immutable
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitConfigEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Repository facade for the StyleKit appearance configuration (single-row table).
 * Exposes a [Flow] of the typed [Resolved] config so both the settings UI and
 * the keyboard renderer can observe changes.
 *
 * Also handles the persistable URI permission dance for media backgrounds:
 * [setBackground] calls `contentResolver.takePersistableUriPermission` so the
 * keyboard process can still read the file after process death / reboot. This
 * fixes a bug in the original Style Keyboard where URIs would break after reboot.
 */
class AppearanceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = tryOrNull { StyleKitDatabase.get(appContext)?.configDao() }

    fun observe(): Flow<Resolved> =
        (dao?.observe() ?: kotlinx.coroutines.flow.flowOf(null))
            .map { cfg -> cfg.toResolved() }

    suspend fun get(): Resolved = dao?.get().toResolved()

    suspend fun setThemeId(themeId: String) { tryOrNull { dao?.setThemeId(themeId) } }
    suspend fun setKeyShape(shape: String) { tryOrNull { dao?.setKeyShape(shape) } }

    /**
     * Sets the background media URI. Takes persistable read permission so the URI
     * survives process death / reboot (the original Style Keyboard had a bug where
     * it didn't take this permission and URIs broke after reboot).
     *
     * Pass empty string to clear.
     */
    suspend fun setBackground(uri: String, scrimAlpha: Float) {
        if (uri.isNotBlank()) {
            tryOrNull {
                val parsed = Uri.parse(uri)
                appContext.contentResolver.takePersistableUriPermission(
                    parsed,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        tryOrNull { dao?.setBackground(uri, scrimAlpha) }
    }

    suspend fun setGlint(enabled: Boolean, color: Long, speedMs: Int, opacity: Float) {
        tryOrNull { dao?.setGlint(enabled, color, speedMs, opacity) }
    }

    suspend fun setSound(pack: String, volume: Float, muted: Boolean, customUri: String) {
        tryOrNull { dao?.setSound(pack, volume, muted, customUri) }
    }

    suspend fun setHaptics(enabled: Boolean) { tryOrNull { dao?.setHaptics(enabled) } }

    suspend fun setKeyOpacity(opacity: Float) {
        tryOrNull { dao?.setKeyOpacity(opacity.coerceIn(0.1f, 1.0f)) }
    }

    /** Ensures the single config row exists. Idempotent — safe to call on every boot. */
    suspend fun ensureConfigSeeded() {
        val d = dao ?: return
        if (d.get() == null) {
            tryOrNull { d.upsert(StyleKitConfigEntity()) }
        }
    }

    private fun StyleKitConfigEntity?.toResolved(): Resolved {
        val e = this ?: StyleKitConfigEntity()
        return Resolved(
            theme = StyleKitTheme.byId(e.themeId),
            keyShape = e.keyShape,
            backgroundMediaUri = e.backgroundMediaUri,
            backgroundScrimAlpha = e.backgroundScrimAlpha,
            glintEnabled = e.glintEnabled,
            glintColor = androidx.compose.ui.graphics.Color(e.glintColor),
            glintSpeedMs = e.glintSpeedMs,
            glintOpacity = e.glintOpacity,
            soundPack = e.soundPack,
            soundVolume = e.soundVolume,
            soundMuted = e.soundMuted,
            soundCustomUri = e.soundCustomUri,
            hapticsEnabled = e.hapticsEnabled,
            keyOpacity = e.keyOpacity,
        )
    }

    @Immutable
    data class Resolved(
        val theme: StyleKitTheme = StyleKitTheme.Charcoal,
        val keyShape: String = "square",
        val backgroundMediaUri: String = "",
        val backgroundScrimAlpha: Float = 0.55f,
        val glintEnabled: Boolean = false,
        val glintColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF06E6FF),
        val glintSpeedMs: Int = 2200,
        val glintOpacity: Float = 0.35f,
        val soundPack: String = "mechanical",
        val soundVolume: Float = 0.6f,
        val soundMuted: Boolean = false,
        val soundCustomUri: String = "",
        val hapticsEnabled: Boolean = true,
        val keyOpacity: Float = 1.0f,
    )
}
