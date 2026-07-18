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

package dev.patrickgold.florisboard.ime.media.emoji.kitchen

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the user's recently-used Emoji Kitchen combos so they can quickly
 * re-find combos they've used before.
 *
 * Storage: a single JSON file at `<filesDir>/emoji_kitchen_history.json`.
 * Format:
 * ```json
 * {
 *   "combos": [
 *     {"left": "😀", "right": "😎", "url": "https://...", "ts": 1700000000000},
 *     ...
 *   ]
 * }
 * ```
 *
 * The list is capped at [MAX_ENTRIES] (default 30) entries — oldest evicted
 * first. Tiny file size (~3KB max), so we read/write the whole thing on
 * each change. No DB needed.
 *
 * Thread safety: all public methods are synchronized on the singleton.
 * The Compose UI observes via [historyFlow] which emits on every change.
 */
object EmojiKitchenHistory {
    private const val FILE_NAME = "emoji_kitchen_history.json"
    private const val MAX_ENTRIES = 30

    @Immutable
    data class Entry(
        val leftEmoji: String,
        val rightEmoji: String,
        val imageUrl: String,
        val timestamp: Long,
    )

    private val _historyFlow = MutableStateFlow<List<Entry>>(emptyList())
    val historyFlow: StateFlow<List<Entry>> = _historyFlow.asStateFlow()

    @Volatile
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return
            val text = file.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val arr = root.optJSONArray("combos") ?: return
            val list = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(Entry(
                    leftEmoji = obj.optString("left"),
                    rightEmoji = obj.optString("right"),
                    imageUrl = obj.optString("url"),
                    timestamp = obj.optLong("ts"),
                ))
            }
            _historyFlow.value = list.sortedByDescending { it.timestamp }
        } catch (_: Throwable) {
            // Bad file — start with empty history.
        }
    }

    /**
     * Records a combo as just-used. Moves it to the top of the list (or
     * inserts if new). Trims to [MAX_ENTRIES]. Persists to disk async.
     */
    @Synchronized
    fun recordUse(context: Context, combo: EmojiKitchenCombo) {
        load(context) // ensure loaded
        val now = System.currentTimeMillis()
        val entry = Entry(
            leftEmoji = combo.leftEmoji,
            rightEmoji = combo.rightEmoji,
            imageUrl = combo.imageUrl,
            timestamp = now,
        )
        val current = _historyFlow.value.toMutableList()
        // Remove any existing entry with the same imageUrl (dedup).
        current.removeAll { it.imageUrl == entry.imageUrl }
        current.add(0, entry)
        val trimmed = current.take(MAX_ENTRIES)
        _historyFlow.value = trimmed
        // Persist to disk synchronously (file is tiny, ~3KB).
        try {
            val arr = JSONArray()
            for (e in trimmed) {
                arr.put(JSONObject().apply {
                    put("left", e.leftEmoji)
                    put("right", e.rightEmoji)
                    put("url", e.imageUrl)
                    put("ts", e.timestamp)
                })
            }
            val root = JSONObject().apply { put("combos", arr) }
            File(context.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
            // Disk write failed — in-memory history is still correct.
        }
    }

    /** Clears all history. Persists to disk. */
    @Synchronized
    fun clear(context: Context) {
        _historyFlow.value = emptyList()
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) { /* ignore */ }
    }
}
