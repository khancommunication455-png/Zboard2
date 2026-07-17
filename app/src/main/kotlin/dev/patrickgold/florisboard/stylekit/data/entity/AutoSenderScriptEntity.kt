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

package dev.patrickgold.florisboard.stylekit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single message inside an Auto Sender script.
 *
 * NOTE: Auto Sender is a non-keyboard feature kept in its own module for separation.
 * It does NOT touch the IME code path; it just happens to live in the same APK.
 */
@Entity(
    tableName = "sk_auto_sender_script",
    indices = [Index(value = ["created_at"])],
)
data class AutoSenderScriptEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "target_package")
    val targetPackage: String = "",
    @ColumnInfo(name = "target_class")
    val targetClass: String = "",
    @ColumnInfo(name = "use_accessibility")
    val useAccessibility: Boolean = false,
    /** JSON-encoded List<ScriptMessage>. */
    @ColumnInfo(name = "messages_json")
    val messagesJson: String = "[]",
    /** "once" | "n_times" | "infinite". */
    @ColumnInfo(name = "loop_mode")
    val loopMode: String = "once",
    @ColumnInfo(name = "loop_count")
    val loopCount: Int = 1,
    @ColumnInfo(name = "interval_ms")
    val intervalMs: Long = 5000L,
    @ColumnInfo(name = "per_message_delay_ms")
    val perMessageDelayMs: Long = 1000L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sk_auto_sender_log",
    indices = [Index(value = ["sent_at"])],
)
data class AutoSenderLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    @ColumnInfo(name = "sent_at")
    val sentAt: Long,
    @ColumnInfo(name = "target_package")
    val targetPackage: String,
    @ColumnInfo(name = "message")
    val message: String,
    /** "sent" | "skipped" | "failed". */
    @ColumnInfo(name = "status")
    val status: String,
)
