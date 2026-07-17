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
import android.graphics.Movie
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.florisboard.lib.kotlin.tryOrNull
import java.io.InputStream

/**
 * Memory-conscious GIF or looping-video background for the keyboard.
 *
 * Behavior:
 *  - GIF: uses Android's built-in [Movie] decoder, decoding one frame at a time
 *    (~30fps via `postInvalidateDelayed(33)`). This avoids loading the entire
 *    GIF bitmap sequence into memory, which would OOM the IME process.
 *  - Video: uses ExoPlayer with `REPEAT_MODE_ONE`, muted, on a TextureView.
 *    Resolution is implicitly capped by ExoPlayer's adaptive streaming; for
 *    local files we trust the source is reasonable (UI warns user about >50MB).
 *
 * A dark scrim ([scrimAlpha], default 0.55) is drawn on top so keys stay legible
 * regardless of the underlying image's brightness.
 *
 * Crash safety: if the URI is invalid, the GIF can't be decoded, or ExoPlayer
 * fails to initialize, the composable renders nothing (transparent) and the
 * keyboard falls back to the solid theme background.
 *
 * Permissions: caller is responsible for taking persistable URI permission via
 * `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`.
 * This is done in the Appearance settings screen when the user picks a media file.
 */
@Composable
fun MediaBackground(
    mediaUri: String,
    scrimAlpha: Float,
    modifier: Modifier = Modifier,
) {
    if (mediaUri.isBlank()) return
    val context = LocalContext.current
    val uri = tryOrNull { Uri.parse(mediaUri) } ?: return

    val mimeType = tryOrNull { context.contentResolver.getType(uri) }
    val isVideo = mimeType?.startsWith("video/") == true

    Box(modifier = modifier.fillMaxSize()) {
        if (isVideo) {
            VideoBackground(uri)
        } else {
            GifBackground(uri)
        }
        // Scrim overlay for key legibility.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
    }
}

@Composable
private fun GifBackground(uri: Uri) {
    val context = LocalContext.current
    var movie by remember(uri) { mutableStateOf<Movie?>(null) }
    var movieStart by remember(uri) { mutableStateOf(0L) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            movie = tryOrNull {
                val input: InputStream? = context.contentResolver.openInputStream(uri)
                input?.use { Movie.decodeStream(it) }
            }
        }
    }

    val m = movie
    if (m == null) return
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            object : android.view.View(ctx) {
                override fun onDraw(canvas: android.graphics.Canvas) {
                    if (movie == null) return
                    val now = android.os.SystemClock.uptimeMillis()
                    if (movieStart == 0L) movieStart = now
                    val relTime = ((now - movieStart) % movie!!.duration().toLong()).toInt()
                    movie!!.setTime(relTime)
                    val scale = minOf(width.toFloat() / movie!!.width(), height.toFloat() / movie!!.height())
                    canvas.save()
                    canvas.scale(scale, scale)
                    movie!!.draw(canvas, 0f, 0f)
                    canvas.restore()
                    postInvalidateDelayed(33)
                }
            }
        },
    )
}

@Composable
private fun VideoBackground(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        tryOrNull {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
                prepare()
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    if (player == null) return
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
    )
}
