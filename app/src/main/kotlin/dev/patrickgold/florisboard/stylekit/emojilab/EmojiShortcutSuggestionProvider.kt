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

    private val repository = ShortcutRepository(context)

    /** Most recently computed matches; the keyboard UI reads this via [observe]. */
    private val _matches = MutableStateFlow<List<ShortcutEntity>>(emptyList())
    val matches: StateFlow<List<ShortcutEntity>> = _matches.asStateFlow()

    override val providerId = ProviderId

    override suspend fun create() { /* lazy DB */ }

    override suspend fun preload(subtype: Subtype) { /* no-op */ }

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
        // with Math Sans on wouldn't surface the 😂 😆 chip.
        val currentWord = dev.patrickgold.florisboard.stylekit.preset.FontNormalizer
            .normalize(content.composingText.toString())
            .lowercase()
        if (currentWord.isBlank()) {
            _matches.value = emptyList()
            return emptyList()
        }
        val matched = tryOrNull { repository.matchForCurrentWord(currentWord) } ?: emptyList()
        _matches.value = matched
        return matched.take(maxCandidateCount).map { sc ->
            EmojiShortcutCandidate(sc)
        }
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
 * `text` is the emoji string itself; the keyboard's commit logic will replace
 * the current composing word (the typed trigger) with this text.
 */
data class EmojiShortcutCandidate(
    val shortcut: ShortcutEntity,
) : SuggestionCandidate {
    override val text: CharSequence = shortcut.emojis
    override val secondaryText: CharSequence? = ":${shortcut.trigger}:"
    override val confidence: Double = 1.0
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = !shortcut.isBuiltIn
    override val icon: ImageVector? = Icons.Default.EmojiEmotions
    override val sourceProvider: SuggestionProvider? = null
}
