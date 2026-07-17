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

package dev.patrickgold.florisboard.stylekit.autosender

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON codec for the Auto Sender script's messages list. The script itself is
 * stored as a row in [dev.patrickgold.florisboard.stylekit.data.entity.AutoSenderScriptEntity];
 * the `messages_json` column is a serialized `List<ScriptMessage>`.
 */
@Serializable
data class ScriptMessage(
    val text: String,
    val delayMs: Long = 0L,
)

object AutoSenderCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val listSerializer = ListSerializer(ScriptMessage.serializer())

    fun encodeMessages(messages: List<ScriptMessage>): String =
        json.encodeToString(listSerializer, messages)

    fun decodeMessages(jsonStr: String): List<ScriptMessage> =
        runCatching { json.decodeFromString(listSerializer, jsonStr) }.getOrDefault(emptyList())
}
