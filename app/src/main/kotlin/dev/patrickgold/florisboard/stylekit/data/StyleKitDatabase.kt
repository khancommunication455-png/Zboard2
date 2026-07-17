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

package dev.patrickgold.florisboard.stylekit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.patrickgold.florisboard.stylekit.data.dao.AutoSenderDao
import dev.patrickgold.florisboard.stylekit.data.dao.BigramDao
import dev.patrickgold.florisboard.stylekit.data.dao.PresetDao
import dev.patrickgold.florisboard.stylekit.data.dao.ShortcutDao
import dev.patrickgold.florisboard.stylekit.data.dao.StyleKitConfigDao
import dev.patrickgold.florisboard.stylekit.data.dao.StyleKitUserDictionaryDao
import dev.patrickgold.florisboard.stylekit.data.dao.TrigramDao
import dev.patrickgold.florisboard.stylekit.data.dao.WordFrequencyDao
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderLogEntity
import dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity
import dev.patrickgold.florisboard.stylekit.data.entity.BigramEntity
import dev.patrickgold.florisboard.stylekit.data.entity.PresetEntity
import dev.patrickgold.florisboard.stylekit.data.entity.ShortcutEntity
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitConfigEntity
import dev.patrickgold.florisboard.stylekit.data.entity.StyleKitUserDictionaryEntity
import dev.patrickgold.florisboard.stylekit.data.entity.TrigramEntity
import dev.patrickgold.florisboard.stylekit.data.entity.WordFrequencyEntity
import dev.patrickgold.florisboard.lib.devtools.flogError
import org.florisboard.lib.kotlin.tryOrNull

/**
 * Single Room database for all StyleKit features. Lives in the same APK as FlorisBoard
 * but keeps its data strictly separate from FlorisBoard's own [dev.patrickgold.florisboard.ime.dictionary.FlorisUserDictionaryDatabase]
 * and [dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDatabase].
 *
 * Schema is exported to `app/schemas/dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase/`.
 * Migrations are explicit — we never use destructive fallback, because the learning tables
 * are exactly the data we don't want to lose.
 */
@Database(
    entities = [
        WordFrequencyEntity::class,
        BigramEntity::class,
        TrigramEntity::class,
        StyleKitUserDictionaryEntity::class,
        PresetEntity::class,
        ShortcutEntity::class,
        StyleKitConfigEntity::class,
        AutoSenderScriptEntity::class,
        AutoSenderLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class StyleKitDatabase : RoomDatabase() {
    companion object {
        const val DB_FILE_NAME = "stylekit.db"

        @Volatile private var INSTANCE: StyleKitDatabase? = null

        fun get(context: Context): StyleKitDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: tryOrNull {
                    Room.databaseBuilder(
                        context.applicationContext,
                        StyleKitDatabase::class.java,
                        DB_FILE_NAME,
                    )
                        .addMigrations(*ALL_MIGRATIONS)
                        .addCallback(object : Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // Seed the single-row config table on first creation.
                                db.execSQL(
                                    """
                                    INSERT INTO sk_config (id, theme_id, key_shape, background_media_uri,
                                        background_scrim_alpha, glint_enabled, glint_color, glint_speed_ms,
                                        glint_opacity, sound_pack, sound_volume, sound_muted, sound_custom_uri,
                                        haptics_enabled, key_opacity, active_preset_id, live_preset_enabled,
                                        emoji_shortcuts_enabled)
                                    VALUES (1, 'charcoal', 'square', '', 0.55, 0, ${0xFF06E6FF}, 2200, 0.35,
                                        'mechanical', 0.6, 0, '', 1, 1.0, 0, 0, 1)
                                    """.trimIndent()
                                )
                            }
                        })
                        .fallbackToDestructiveMigrationOnDowngrade(false)
                        .build()
                }.also { INSTANCE = it } ?: throw IllegalStateException("StyleKitDatabase init failed")
            }
        }

        fun resetForTesting(context: Context): StyleKitDatabase {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                context.applicationContext.deleteDatabase(DB_FILE_NAME)
                return get(context)
            }
        }

        // Explicit migrations are listed here as the schema evolves.
        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            // v1 → v2: added `key_opacity` column to sk_config for the
            // adjustable key opacity feature.
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sk_config ADD COLUMN key_opacity REAL NOT NULL DEFAULT 1.0")
                }
            },
        )
    }

    abstract fun wordFrequencyDao(): WordFrequencyDao
    abstract fun bigramDao(): BigramDao
    abstract fun trigramDao(): TrigramDao
    abstract fun userDictionaryDao(): StyleKitUserDictionaryDao
    abstract fun presetDao(): PresetDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun configDao(): StyleKitConfigDao
    abstract fun autoSenderDao(): AutoSenderDao
}

/**
 * Defensive accessor — returns null if the DB couldn't be opened for any reason,
 * rather than crashing the IME process. Callers should fall back to no-op behavior.
 */
fun Context.styleKitDatabaseOrNull(): StyleKitDatabase? = tryOrNull { StyleKitDatabase.get(this) }

/** Logs the failure but doesn't propagate, used by callers that have a sensible no-op fallback. */
fun Context.styleKitDatabaseOrLog(): StyleKitDatabase? = try {
    StyleKitDatabase.get(this)
} catch (e: Throwable) {
    flogError { "StyleKit database unavailable: ${e.message}" }
    null
}
