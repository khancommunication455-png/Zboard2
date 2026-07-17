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

package dev.patrickgold.florisboard.stylekit.nlp

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase
import dev.patrickgold.florisboard.stylekit.data.entity.BigramEntity
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitUserDictionaryEntity
import dev.patrickgold.florisboard.stylekit.data.entity.TrigramEntity
import dev.patrickgold.florisboard.stylekit.data.entity.WordFrequencyEntity
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Thin data-access facade over the StyleKit Room DAOs, plus a small word-boundary
 * helper for extracting "previous word" context from [EditorContent]. Kept separate
 * from [AdaptiveLearningProvider] so the provider logic stays readable and the data
 * layer stays easy to unit-test.
 */
class AdaptiveLearningRepository(context: Context) {
    private val db: StyleKitDatabase? = tryOrNull { StyleKitDatabase.get(context) }
    private val prefs by FlorisPreferenceStore

    /** Whether the user has allowed personalized learning. When false, the provider no-ops. */
    fun isPersonalizedLearningEnabled(): Boolean = tryOrNull { prefs.suggestion.personalizedLearning.get() } ?: false

    suspend fun suggestUnigramsByPrefix(prefix: String, limit: Int): List<WordFrequencyEntity> =
        db?.wordFrequencyDao()?.suggestByPrefix(prefix, limit) ?: emptyList()

    suspend fun suggestUserDictByPrefix(prefix: String, limit: Int): List<StyleKitUserDictionaryEntity> =
        db?.userDictionaryDao()?.suggestByPrefix(prefix, limit) ?: emptyList()

    suspend fun suggestBigrams(first: String, limit: Int): List<BigramEntity> =
        db?.bigramDao()?.suggestNext(first, limit) ?: emptyList()

    suspend fun suggestTrigrams(first: String, second: String, limit: Int): List<TrigramEntity> =
        db?.trigramDao()?.suggestNext(first, second, limit) ?: emptyList()

    suspend fun bumpUnigram(word: String, now: Long) {
        val dao = db?.wordFrequencyDao() ?: return
        val touched = dao.bumpFrequency(word, delta = 1, now = now)
        if (touched == 0) {
            dao.insert(WordFrequencyEntity(word = word, frequency = 1, lastUsed = now))
        }
    }

    suspend fun decrementUnigram(word: String) {
        val dao = db?.wordFrequencyDao() ?: return
        val now = System.currentTimeMillis()
        val existing = dao.getExact(word) ?: return
        if (existing.frequency > 1) {
            dao.bumpFrequency(word, delta = -1, now = now)
        }
    }

    suspend fun bumpBigram(first: String, second: String, now: Long) {
        val dao = db?.bigramDao() ?: return
        val touched = dao.bumpFrequency(first, second, delta = 1, now = now)
        if (touched == 0) {
            dao.insert(BigramEntity(firstWord = first, secondWord = second, frequency = 1, lastUsed = now))
        }
    }

    suspend fun bumpTrigram(first: String, second: String, third: String, now: Long) {
        val dao = db?.trigramDao() ?: return
        val touched = dao.bumpFrequency(first, second, third, delta = 1, now = now)
        if (touched == 0) {
            dao.insert(TrigramEntity(
                firstWord = first, secondWord = second, thirdWord = third, frequency = 1, lastUsed = now,
            ))
        }
    }

    suspend fun maybePromoteToUserDictionary(word: String) {
        val wDao = db?.wordFrequencyDao() ?: return
        val uDao = db?.userDictionaryDao() ?: return
        val existing = wDao.getExact(word) ?: return
        if (existing.isUserAdded) return
        if (existing.frequency < 3) return
        if (uDao.getByWord(word) != null) return
        // Only promote words that look like real words (letters only, length 2–32).
        if (word.length < 2 || word.length > 32) return
        if (!word.all { it.isLetter() || it == '\'' || it == '-' }) return
        uDao.insert(StyleKitUserDictionaryEntity(word = word, addedAt = System.currentTimeMillis()))
    }

    /**
     * Runs decay + prune at most once per process invocation per day. Uses a sentinel
     * row in the word_frequency table by checking the oldest `last_used` to decide.
     * Cheap and avoids needing a separate metadata table.
     */
    suspend fun maybeRunDecay(now: Long) {
        val dao = db?.wordFrequencyDao() ?: return
        val cutoff = now - DECAY_AGE_MS
        tryOrNull {
            dao.decayOldEntries(cutoff)
            dao.pruneStale()
        }
    }

    suspend fun removeFromLearning(word: String): Boolean {
        val wDao = db?.wordFrequencyDao() ?: return false
        val uDao = db?.userDictionaryDao() ?: return false
        val w = wDao.getExact(word)
        if (w != null) {
            // Mark as "removed" by zeroing frequency; keep row so it doesn't re-promote.
            wDao.insert(w.copy(frequency = 0, isUserAdded = false))
        }
        uDao.deleteByWord(word)
        return true
    }

    suspend fun allLearnedWords(): List<String> {
        val wDao = db?.wordFrequencyDao() ?: return emptyList()
        // Returns words with frequency > 0.
        return wDao.suggestByPrefix(prefix = "", limit = 5000).map { it.word }
    }

    suspend fun frequencyOf(word: String): Double {
        val wDao = db?.wordFrequencyDao() ?: return 0.0
        val e = wDao.getExact(word) ?: return 0.0
        return (e.frequency / 100.0).coerceIn(0.0, 1.0)
    }

    /**
     * Returns the last one or two space-delimited words before the cursor, for next-word
     * prediction context. Returns (word1, word2) where word1 is the most recent word
     * and word2 is the one before that. Either may be null.
     *
     * StyleKit: when a Font Style preset is active, the text before the cursor
     * contains STYLIZED characters. We normalize them back to ASCII here so the
     * bigram/trigram lookup keys match what the user originally typed.
     */
    fun lastOneOrTwoWords(content: EditorContent): Pair<String?, String?> {
        val raw = content.textBeforeSelection.toString()
        if (raw.isBlank()) return null to null
        val textBefore = dev.patrickgold.florisboard.stylekit.preset.FontNormalizer.normalize(raw)
        // Walk back from the end, skipping trailing whitespace, then collect word chars.
        var i = textBefore.length
        // skip trailing whitespace
        while (i > 0 && textBefore[i - 1].isWhitespace()) i--
        // collect word1
        var end1 = i
        while (i > 0 && !textBefore[i - 1].isWhitespace()) i--
        val word1 = textBefore.substring(i, end1).takeIf { it.isNotBlank() }
        // skip whitespace between
        while (i > 0 && textBefore[i - 1].isWhitespace()) i--
        // collect word2
        var end2 = i
        while (i > 0 && !textBefore[i - 1].isWhitespace()) i--
        val word2 = textBefore.substring(i, end2).takeIf { it.isNotBlank() }
        return word1?.lowercase() to word2?.lowercase()
    }

    companion object {
        private const val DECAY_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}
