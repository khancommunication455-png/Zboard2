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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.stylekit.data.entity.ShortcutEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.florisboard.lib.kotlin.tryOrNull

/**
 * A [SuggestionProvider] that surfaces Emoji Lab shortcuts as suggestion chips
 * above the keyboard. Implemented as a separate provider (rather than being
 * grafted into the adaptive learning provider) so it can be enabled/disabled
 * independently via `emojiShortcutsEnabled` in the StyleKit config.
 *
 * Behavior:
 *  - Reads the current word being typed from [EditorContent.composingText].
 *  - Calls [ShortcutRepository.matchForCurrentWord] to find matching shortcuts.
 *  - Returns each match as a [EmojiShortcutCandidate] (text = the emoji string,
 *    secondaryText = the trigger word for context).
 *  - Tapping a chip deletes the typed trigger and commits the emoji + a trailing
 *    space (handled by [EmojiShortcutCandidate.text] containing the emoji and
 *    the keyboard's existing commit logic deleting the composing text).
 *
 * Privacy: read-only against the local StyleKit Room database. No network.
 * Crash safety: every DB call is wrapped in [tryOrNull]; failures degrade to no chips.
 */
class EmojiShortcutSuggestionProvider(context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "dev.patrickgold.florisboard.stylekit.emojilab.suggestion"
    }

    private val appContext = context.applicationContext
    private val repository = ShortcutRepository(context)

    /** Most recently computed matches; the keyboard UI reads this via [observe]. */
    private val _matches = MutableStateFlow<List<ShortcutEntity>>(emptyList())
    val matches: StateFlow<List<ShortcutEntity>> = _matches.asStateFlow()

    override val providerId = ProviderId

    override suspend fun create() { /* lazy DB */ }

    override suspend fun preload(subtype: Subtype) {
        // Eagerly trigger the Gboard shortcuts index load so the first keystroke
        // doesn't pay the parse cost. Safe to call repeatedly — the loader
        // short-circuits if already loaded.
        tryOrNull { GboardEmojiShortcutsIndex.ensureLoaded(appContext) }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // StyleKit: when a Font Style preset is active, the composing text
        // contains the STYLIZED form (e.g. "𝐥𝐨𝐥" for Math Sans), not the
        // original ASCII the user typed. Normalize it back to ASCII before
        // matching against emoji-shortcut triggers, otherwise typing "lol"
        // with Math Sans on wouldn't surface the 😂 😆 chip. This is the
        // single normalization point that makes the emoji shortcuts work
        // on EVERY font preset (Math Sans, Bold Serif, Bubble, Zalgo,
        // Upside Down, and any future presets the user creates).
        val currentWord = dev.patrickgold.florisboard.stylekit.preset.FontNormalizer
            .normalize(content.composingText.toString())
            .lowercase()
        if (currentWord.isBlank()) {
            _matches.value = emptyList()
            return emptyList()
        }

        // Source 1: the user's own custom shortcuts (StyleKit Room DB).
        val userMatches = tryOrNull { repository.matchForCurrentWord(currentWord) } ?: emptyList()
        _matches.value = userMatches

        // Source 2: the bundled Gboard emoji-shortcuts index (1,218 emojis ↔
        // ~3,000 keywords). Loaded lazily from
        // `assets/ime/media/emoji/gboard_emoji_shortcuts.json` on first use.
        // We de-duplicate against user matches so the user's custom
        // shortcut for "love" (e.g. "💕 😘") wins over Gboard's default
        // if they happen to share the same trigger.
        tryOrNull { GboardEmojiShortcutsIndex.ensureLoaded(appContext) }
        val userTriggerKeys = userMatches.map { it.trigger.lowercase() }.toSet()
        val gboardExact = tryOrNull { GboardEmojiShortcutsIndex.lookup(currentWord) } ?: emptyList()
        val gboardPrefix = tryOrNull { GboardEmojiShortcutsIndex.lookupByPrefix(currentWord, maxCandidateCount) } ?: emptyList()
        val gboardCandidates = buildList {
            // Exact whole-word matches first (highest confidence).
            for (emoji in gboardExact) {
                if (currentWord !in userTriggerKeys) {
                    add(GboardEmojiShortcutCandidate(trigger = currentWord, emoji = emoji, isExact = true))
                }
            }
            // Then prefix matches (lower confidence).
            for ((kw, emojis) in gboardPrefix) {
                if (kw !in userTriggerKeys) {
                    for (emoji in emojis.take(2)) { // limit each prefix kw to 2 emojis to avoid flooding
                        add(GboardEmojiShortcutCandidate(trigger = kw, emoji = emoji, isExact = false))
                    }
                }
            }
        }

        val userCandidates = userMatches.flatMap { sc ->
            // StyleKit fix: SPLIT each user shortcut into one candidate per
            // emoji, so tapping a chip commits a SINGLE emoji (Gboard
            // behavior). The previous implementation used the whole
            // `sc.emojis` string ("😂 😆") as the candidate text, which
            // caused both emojis to be typed when the user tapped a chip.
            // Split on whitespace and any non-emoji punctuation so multi-
            // emoji shortcuts surface as N separate chips.
            splitEmojis(sc.emojis).map { emoji ->
                EmojiShortcutCandidate(shortcut = sc, singleEmoji = emoji)
            }
        }.take(maxCandidateCount)
        val remainingSlots = (maxCandidateCount - userCandidates.size).coerceAtLeast(0)
        val gboardTake = gboardCandidates.take(remainingSlots)
        return userCandidates + gboardTake
    }

    /**
     * Splits an emoji string like "😂 😆" or "💕,😘" into individual emoji
     * tokens. Strips whitespace, commas, slashes, and pipes — common
     * delimiters users type when defining a shortcut. Returns each emoji
     * token as a separate list entry. Handles multi-codepoint emoji
     * (skin-tone modifiers, ZWJ sequences) correctly because we split on
     * delimiter chars only, not on code points.
     */
    private fun splitEmojis(emojis: String): List<String> {
        // Split on any run of whitespace, comma, semicolon, slash, pipe, or
        // em-dash. Each resulting non-blank token is one emoji (or one
        // emoji sequence the user explicitly wants together — we can't tell
        // the difference, so we treat each token as one chip).
        val tokens = emojis.split(Regex("[\\s,;|/]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (tokens.isEmpty()) listOf(emojis.trim()) else tokens
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { "Emoji shortcut accepted: ${candidate.text}" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) { /* no-op */ }
    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        if (candidate is EmojiShortcutCandidate) {
            return tryOrNull { repository.deleteById(candidate.shortcut.rowId); true } ?: false
        }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> = emptyList()
    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double = 0.0
    override suspend fun destroy() { /* no-op */ }
}

/**
 * A suggestion candidate backed by an Emoji Lab [ShortcutEntity].
 *
 * `text` is a SINGLE emoji (not the whole shortcut string). The previous
 * implementation used `shortcut.emojis` (e.g. "😂 😆") as the candidate text,
 * which caused BOTH emojis to be typed when the user tapped a chip. Gboard's
 * behavior is one emoji per chip; we now match that by splitting the
 * shortcut's emoji list into N separate candidates at suggest() time.
 *
 * Pass [singleEmoji] explicitly when constructing from a multi-emoji
 * shortcut; if null, defaults to the full `shortcut.emojis` string for
 * backward compatibility (used only by callers that already split).
 */
data class EmojiShortcutCandidate(
    val shortcut: ShortcutEntity,
    val singleEmoji: String? = null,
) : SuggestionCandidate {
    override val text: CharSequence = singleEmoji ?: shortcut.emojis
    override val secondaryText: CharSequence? = ":${shortcut.trigger}:"
    override val confidence: Double = 1.0
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = !shortcut.isBuiltIn
    override val icon: ImageVector? = Icons.Default.EmojiEmotions
    override val sourceProvider: SuggestionProvider? = null
}

/**
 * A suggestion candidate backed by the bundled Gboard emoji-shortcuts index
 * (1,218 emojis ↔ ~3,000 keywords). Same UI affordance as [EmojiShortcutCandidate]
 * — `text` is the emoji, `secondaryText` is `:trigger:` — but not removable
 * (it's a static asset) and confidence is slightly lower so user-defined
 * shortcuts always win in the dedup-merge step.
 */
data class GboardEmojiShortcutCandidate(
    val trigger: String,
    val emoji: String,
    val isExact: Boolean,
) : SuggestionCandidate {
    override val text: CharSequence = emoji
    override val secondaryText: CharSequence? = ":$trigger:"
    override val confidence: Double = if (isExact) 0.9 else 0.6
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = false
    override val icon: ImageVector? = Icons.Default.EmojiEmotions
    override val sourceProvider: SuggestionProvider? = null
}
