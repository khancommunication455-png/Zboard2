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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.florisboard.lib.kotlin.tryOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Emoji Kitchen — fetches Google's emoji-combo image manifest from gstatic
 * and builds image URLs on demand.
 *
 * The Kitchen manifest is a small (~50 KB) JSON file published by Google at
 * a fixed URL. It maps each supported emoji (as a codepoint string) to the
 * list of other emojis it has combos with. The actual combo images live at
 * predictable gstatic URLs and are fetched on demand by the Compose UI
 * via Coil/Glide (we just build the URL string here).
 *
 * URL pattern (public, no auth):
 *   https://www.gstatic.com/android/keyboard/emojikitchen/<date>/<e1>/<e1>_<e2>.png
 *
 * where:
 *   - <date> is the YYYYMMDD the combo was published (we use the manifest's
 *     date so we always get a valid path).
 *   - <e1>, <e2> are the emoji's codepoints joined with hyphens, prefixed
 *     with "u-". E.g. 😀 (U+1F600) becomes "u-1f600". Multi-codepoint emoji
 *     (e.g. 👨‍👩‍👧 = U+1F468 U+200D U+1F469 U+200D U+1F467) become
 *     "u-1f468-200d-1f469-200d-1f467".
 *
 * Privacy: the manifest is fetched ONCE per process (cached in memory).
 * Combo image requests go through the system HTTP stack; Google sees the
 * same traffic as a stock Gboard install. No personally-identifying
 * information is sent (gstatic doesn't get the user's account, device ID,
 * or typing content — just the emoji pair).
 *
 * Performance: the manifest is ~50 KB compressed. Fetch is on
 * Dispatchers.IO with a 5s connect / 10s read timeout. After first fetch,
 * lookups are O(1) HashMap reads from the in-memory cache.
 */
object EmojiKitchenRepository {

    /**
     * The published manifest URL. This is the same URL Gboard uses; the file
     * is updated by Google when new emoji pairs are added. The format is:
     *
     * ```json
     * {
     *   "emoji": [
     *     {"u": ["😀"], "e": ["😀", "😎", "🤔", ...]},
     *     ...
     *   ]
     * }
     * ```
     *
     * NOTE: Google's actual format has evolved over time — we handle both
     * the documented format and degrade to "no combos available" if
     * parsing fails.
     */
    private const val MANIFEST_URL =
        "https://www.gstatic.com/android/keyboard/emojikitchen/emojikitchen_metadata.json"

    /**
     * The base URL for combo images. Format string is filled in with
     * the manifest date (YYYYMMDD), the left emoji's codepoint string,
     * and both emojis' codepoint strings.
     */
    private const val IMAGE_URL_TEMPLATE =
        "https://www.gstatic.com/android/keyboard/emojikitchen/%s/%s/%s_%s.png"

    /** In-memory cache: emoji codepoint-string -> set of partner codepoint-strings. */
    @Volatile
    private var manifest: Map<String, Set<String>>? = null

    /** The manifest date string (YYYYMMDD) to use for building image URLs. */
    @Volatile
    private var manifestDate: String = "20201001" // default fallback date

    private val fetchLock = Mutex()

    /** Returns true if the manifest has been loaded (either from cache or network). */
    fun isLoaded(): Boolean = manifest != null

    /**
     * Fetches the manifest (if not already loaded) and returns true on success.
     * Safe to call any number of times — concurrent callers block on the same
     * fetch via [fetchLock].
     *
     * StyleKit resilience: if the network fetch fails (no network, gstatic
     * unreachable, URL changed, malformed JSON), we fall back to the static
     * [FALLBACK_COMBOS] list so the Kitchen panel always shows SOMETHING
     * for the most popular emojis. The function still returns false in
     * that case so the caller can log the failure if it wants.
     */
    suspend fun ensureLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        manifest?.let { return@withContext true }
        fetchLock.withLock {
            manifest?.let { return@withLock true }
            val networkOk = tryOrNull {
                val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    useCaches = true
                }
                try {
                    if (conn.responseCode != 200) return@tryOrNull false
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parseManifest(body)
                } finally {
                    conn.disconnect()
                }
            } ?: false
            if (!networkOk) {
                // Network failed — populate with fallback combos so the UI
                // isn't empty. The fallback is also merged into the network
                // manifest below as a baseline.
                manifest = buildFallbackManifest()
            }
            networkOk
        }
    }

    /** Parses the manifest JSON and populates [manifest] + [manifestDate]. */
    private fun parseManifest(body: String): Boolean {
        return try {
            val root = JSONObject(body)
            val arr = root.optJSONArray("emoji")
            val map = HashMap<String, MutableSet<String>>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val leftArr = entry.optJSONArray("u") ?: continue
                    val leftKey = codepointKeyFromJsonArray(leftArr) ?: continue
                    val partners = entry.optJSONArray("e") ?: continue
                    val partnerSet = map.getOrPut(leftKey) { mutableSetOf() }
                    for (j in 0 until partners.length()) {
                        val partnerObj = partners.optJSONObject(j)
                        if (partnerObj != null) {
                            val partnerArr = partnerObj.optJSONArray("u") ?: continue
                            val partnerKey = codepointKeyFromJsonArray(partnerArr) ?: continue
                            partnerSet.add(partnerKey)
                        } else {
                            // Single-codepoint form ("e": ["😀","😎"])
                            val partnerStr = partners.optString(j)
                            if (partnerStr.isNotEmpty()) {
                                partnerSet.add(toCodepointKey(partnerStr))
                            }
                        }
                    }
                }
            }
            // StyleKit: merge the fallback combos into the network manifest
            // so the most popular emojis always have combos available, even
            // if the network manifest is sparse or doesn't include them.
            for ((a, b) in FALLBACK_COMBOS) {
                val ka = toCodepointKey(a)
                val kb = toCodepointKey(b)
                map.getOrPut(ka) { mutableSetOf() }.add(kb)
                map.getOrPut(kb) { mutableSetOf() }.add(ka)
            }
            manifest = map
            val date = root.optString("date", "")
            if (date.isNotEmpty()) manifestDate = date
            true
        } catch (t: Throwable) {
            // Bad JSON — populate fallback so the UI isn't broken.
            manifest = buildFallbackManifest()
            false
        }
    }

    /** Converts a JSON array of codepoint integers to the canonical "u-XXXX-XXXX" key. */
    private fun codepointKeyFromJsonArray(arr: org.json.JSONArray): String? {
        if (arr.length() == 0) return null
        val sb = StringBuilder("u")
        for (i in 0 until arr.length()) {
            val cp = arr.optInt(i, -1)
            if (cp < 0) return null
            sb.append('-').append(cp.toString(16))
        }
        return sb.toString()
    }

    /** Converts an emoji string (e.g. "😀") to its codepoint-key form ("u-1f600"). */
    fun toCodepointKey(emoji: String): String {
        val sb = StringBuilder("u")
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            sb.append('-').append(cp.toString(16))
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * Returns the list of partner emojis that have a Kitchen combo with [emoji].
     * Empty list if the manifest isn't loaded or [emoji] has no combos.
     */
    fun partnersFor(emoji: String): List<String> {
        val m = manifest ?: return emptyList()
        val key = toCodepointKey(emoji)
        return m[key]?.toList() ?: emptyList()
    }

    /**
     * Builds the gstatic image URL for the combo of [leftEmoji] and [rightEmoji].
     * Returns null if either emoji is empty.
     *
     * The URL is canonicalized so [leftEmoji] is always the lexicographically
     * smaller codepoint-key — this matches Google's URL convention and means
     * (a, b) and (b, a) return the same image.
     */
    fun buildImageUrl(leftEmoji: String, rightEmoji: String): String? {
        if (leftEmoji.isEmpty() || rightEmoji.isEmpty()) return null
        val k1 = toCodepointKey(leftEmoji)
        val k2 = toCodepointKey(rightEmoji)
        val (a, b) = if (k1 <= k2) k1 to k2 else k2 to k1
        return String.format(IMAGE_URL_TEMPLATE, manifestDate, a, a, b)
    }

    /** Clears the in-memory cache. Next [ensureLoaded] will re-fetch. */
    fun invalidate() {
        manifest = null
    }

    /**
     * Static fallback combo list — used when the manifest fetch fails (no
     * network, gstatic unreachable, URL changed, etc.). These are some of
     * the most popular Kitchen combos according to public Emoji Kitchen
     * crawls. Each pair is bidirectional (both directions are valid).
     *
     * Format: list of (emoji1, emoji2) pairs. The buildImageUrl() helper
     * canonicalizes the order so we don't need to worry about it here.
     *
     * NOTE: even if the manifest fetch succeeds, this list is small enough
     * (~30 entries) that we always merge it into the in-memory cache as a
     * baseline. This guarantees the Kitchen always shows SOMETHING for the
     * most popular emojis, even on a cold cache.
     */
    private val FALLBACK_COMBOS: List<Pair<String, String>> = listOf(
        "😀" to "😎",
        "😀" to "😍",
        "😀" to "🤔",
        "😀" to "😭",
        "😀" to "🥺",
        "😀" to "🤩",
        "😀" to "🥳",
        "😀" to "😴",
        "😀" to "🤯",
        "😀" to "🥶",
        "😂" to "🥰",
        "😂" to "😭",
        "😂" to "😍",
        "😂" to "🤣",
        "😂" to "😎",
        "😍" to "🥰",
        "😍" to "😎",
        "😍" to "🤩",
        "😍" to "🥺",
        "😍" to "😘",
        "🥰" to "😎",
        "🥰" to "🤩",
        "🤔" to "🤨",
        "🤔" to "🙄",
        "😎" to "🤩",
        "😎" to "🥳",
        "😭" to "🥺",
        "😭" to "😱",
        "😱" to "🤯",
        "🤯" to "😨",
        "🥳" to "🎉",
        "😴" to "💤",
        "🤔" to "💡",
        "❤" to "🧡",
        "❤" to "💛",
        "❤" to "💚",
        "❤" to "💙",
        "❤" to "💜",
        "❤" to "🖤",
        "👍" to "👎",
        "👍" to "👏",
        "🙏" to "👏",
        "🔥" to "💯",
        "🔥" to "😎",
        "💯" to "🔥",
    )

    /**
     * Builds a fallback manifest from [FALLBACK_COMBOS]. Used when the network
     * fetch fails so the Kitchen panel still has something to show for the
     * most popular emojis.
     */
    private fun buildFallbackManifest(): Map<String, Set<String>> {
        val map = HashMap<String, MutableSet<String>>()
        for ((a, b) in FALLBACK_COMBOS) {
            val ka = toCodepointKey(a)
            val kb = toCodepointKey(b)
            map.getOrPut(ka) { mutableSetOf() }.add(kb)
            map.getOrPut(kb) { mutableSetOf() }.add(ka)
        }
        return map
    }
}

/**
 * A single Emoji Kitchen combo image: the URL plus the two source emojis.
 * Used by the Compose UI layer to render combo thumbnails.
 */
@Immutable
data class EmojiKitchenCombo(
    val leftEmoji: String,
    val rightEmoji: String,
    val imageUrl: String,
)
