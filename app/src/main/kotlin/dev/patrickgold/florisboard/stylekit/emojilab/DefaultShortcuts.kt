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

import dev.patrickgold.florisboard.stylekit.data.entity.ShortcutEntity

/**
 * The 18 built-in emoji shortcuts shipped by default. Each is `triggerMode = "whole"`,
 * `isBuiltIn = true`. Trigger is lower-cased on insert by the repository.
 *
 * Ported verbatim from the original Style Keyboard defaults.
 */
object DefaultShortcuts {
    fun all(): List<ShortcutEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            ShortcutEntity(trigger = "lol", emojis = "😂 😆", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "love", emojis = "💕 😘", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "omg", emojis = "🤯 😱", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "fire", emojis = "🔥", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "sad", emojis = "😢 💔", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "happy", emojis = "😀 😊", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "angry", emojis = "😡 😤", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "cool", emojis = "😎 🕶", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "ok", emojis = "👍 ✅", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "yes", emojis = "✅ 💯", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "no", emojis = "❌ 🚫", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "thanks", emojis = "🙏 😊", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "bye", emojis = "👋 😘", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "hi", emojis = "👋 😀", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "wow", emojis = "😮 🤩", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "sleep", emojis = "😴 💤", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "coffee", emojis = "☕ 😌", triggerMode = "whole", createdAt = now, isBuiltIn = true),
            ShortcutEntity(trigger = "party", emojis = "🎉 🥳", triggerMode = "whole", createdAt = now, isBuiltIn = true),
        )
    }
}
