/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar

enum class SmartbarLayout {
    SUGGESTIONS_ONLY,
    ACTIONS_ONLY,
    SUGGESTIONS_ACTIONS_SHARED,
    SUGGESTIONS_ACTIONS_EXTENDED,

    /**
     * StyleKit addition (Part 1): a single row that auto-swaps between the toolbar
     * icon row and 3 suggestion chips based on whether non-empty candidates exist.
     *
     * - When candidates are empty (no input, suggestions disabled, etc.): the row
     *   shows the QuickActionsRow (toolbar icons), same as `ACTIONS_ONLY`.
     * - The instant candidates become non-empty: the row crossfades into a 3-chip
     *   CandidatesRow (with the center chip visually emphasized), hiding the
     *   toolbar icons underneath.
     * - When input clears / candidates become empty again: it crossfades back to
     *   the toolbar icon row.
     *
     * Designed to feel exactly like Gboard. Crossfade duration is ~130ms (see
     * `StyleKitAutoSwapDurationMs` in Smartbar.kt).
     */
    SUGGESTIONS_ACTIONS_AUTO;
}
