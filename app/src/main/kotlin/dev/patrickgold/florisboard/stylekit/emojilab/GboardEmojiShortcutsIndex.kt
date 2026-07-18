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

package dev.patrickgold.florisboard.stylekit.emojilab

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock

/**
 * Static, read-only loader for the bundled `gboard_emoji_shortcuts.json` asset
 * (1,218 emoji → ~3,000 keyword mappings extracted from a real Gboard APK).
 *
 * Loaded once per process into a reverse index `word -> [emoji]` for O(1)
 * lookup from the typing path. The index is **supplementary** to the user's
 * custom [ShortcutRepository] matches — typing "omg" surfaces both the user's
 * custom shortcut (if they defined one) and the Gboard defaults (😱 😳).
 *
 * Font-Style integration: the caller (EmojiShortcutSuggestionProvider) is
 * responsible for calling [FontNormalizer.normalize] on the composing text
 * before calling [lookup]. This keeps the loader itself font-agnostic and
 * means the same static index works under Math Sans / Bold Serif / Zalgo /
 * Bubble / Upside Down / etc.
 *
 * Performance: the JSON is ~110 KB. Loaded once on first use; subsequent
 * calls return the cached index. Lookup is a single HashMap read.
 */
object GboardEmojiShortcutsIndex {
    private const val ASSET_PATH = "ime/media/emoji/gboard_emoji_shortcuts.json"

    /** Reverse index: lowercase keyword → list of emoji strings. */
    @Volatile
    private var reverseIndex: Map<String, List<String>>? = null

    private val loadLock = guardedByLock { Any() }

    /** Returns the emoji list for [word], or null if not in the Gboard index. */
    fun lookup(word: String): List<String>? {
        val idx = reverseIndex ?: return null
        if (word.isBlank()) return null
        return idx[word.lowercase().trim()]
    }

    /** Returns up to [limit] (keyword, emoji) pairs whose keyword starts with [prefix]. */
    fun lookupByPrefix(prefix: String, limit: Int): List<Pair<String, List<String>>> {
        val idx = reverseIndex ?: return emptyList()
        if (prefix.length < 2) return emptyList()
        val p = prefix.lowercase().trim()
        return idx.entries
            .filter { it.key.startsWith(p) && it.key != p }
            .take(limit)
            .map { it.key to it.value }
    }

    /** Returns true if the index has been loaded into memory. */
    fun isLoaded(): Boolean = reverseIndex != null

    /**
     * Loads the index from assets if not already loaded. Safe to call any
     * number of times — subsequent calls are no-ops. Returns true if the
     * index is loaded after this call (whether freshly or previously).
     */
    suspend fun ensureLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Fast path: already loaded.
        if (reverseIndex != null) return@withContext true
        loadLock.withLock {
            // Double-check after acquiring lock.
            if (reverseIndex != null) return@withLock true
            val raw = try {
                context.assets.readText(ASSET_PATH)
            } catch (t: Throwable) {
                return@withLock false
            }
            val json = try {
                Json.parseToJsonElement(raw).jsonArray
            } catch (t: Throwable) {
                return@withLock false
            }
            val map = HashMap<String, MutableList<String>>()
            for (entry in json) {
                val obj = entry.jsonObject
                val emoji = obj["emoji"]?.jsonPrimitive?.contentOrNull ?: continue
                val keywords = obj["keywords"]?.jsonArray ?: continue
                for (kw in keywords) {
                    val kwStr = kw.jsonPrimitive.contentOrNull?.lowercase()?.trim() ?: continue
                    if (kwStr.isBlank()) continue
                    map.getOrPut(kwStr) { mutableListOf() }.add(emoji)
                }
            }
            reverseIndex = map
            true
        }
    }
}
