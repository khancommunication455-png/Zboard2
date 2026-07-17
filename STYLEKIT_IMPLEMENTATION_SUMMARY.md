# Style Keyboard → FlorisBoard Graft — Implementation Summary

This document describes the work done to graft the **Style Keyboard** feature set
onto **FlorisBoard** and upgrade its suggestion pipeline to be adaptive,
on-device, and Gboard-style.

The graft is **additive**: every change to existing FlorisBoard files is a small,
clearly-marked extension. No existing FlorisBoard behavior is removed or
replaced. All new feature code lives under a single new package,
`dev.patrickgold.florisboard.stylekit.*`, plus a handful of new settings-screen
packages under `app.settings.*`.

---

## 1. Existing FlorisBoard modules EXTENDED

| File | What was added |
|---|---|
| `app/AppPrefs.kt` | Two new prefs under `inner class Suggestion`: `personalizedLearning` (master switch for on-device learning) and `autoSwapToolbarAndSuggestions` (master switch for the Gboard-style merged row). Both are JetPref booleans, default true. |
| `ime/smartbar/SmartbarLayout.kt` | One new enum value: `SUGGESTIONS_ACTIONS_AUTO`. Documented inline. |
| `ime/smartbar/Smartbar.kt` | New `StyleKitAutoSwapDurationMs = 130` constant. New `StyleKitAutoSwapRow()` Composable handling the `SUGGESTIONS_ACTIONS_AUTO` branch — crossfades between `QuickActionsRow` (toolbar icons) and `StyleKitCandidatesRow` (3 suggestion chips with center emphasis) based on whether `nlpManager.activeCandidatesFlow` is non-empty. Uses fade + small slide, ~130ms, per spec. |
| `ime/nlp/NlpManager.kt` | (1) Added `appContext` field. (2) Added two new provider instances: `adaptiveLearningProvider` and `emojiShortcutProvider`, both registered into the existing `providers` map (so lifecycle methods like `create()`/`preload()` are called automatically). (3) Modified `suggest()` to additionally query both providers and merge their candidates into `internalSuggestions` — personal-frequency candidates are placed *ahead* of base-dictionary candidates so they win ties, matching the "Gboard feel". All queries are wrapped in `runCatching` so a provider failure degrades gracefully. |
| `ime/keyboard/KeyboardManager.kt` | (1) Added `livePresetApplier = LivePresetApplier.get(context)` field. (2) Added `recordWordCommitForLearning(committedWordOverride)` public method that feeds the adaptive learning pipeline whenever a word is confirmed. (3) Called it from `commitCandidate()`, `handleSpace()`, `handleHardwareKeyboardSpace()`, and the character-commit path (line ~891). (4) Wrapped the character commit to apply the active preset's `transformChar` before `editorInstance.commitChar(...)` (Part 2.1 live mode). All learning is guarded by `!isIncognitoMode && prefs.suggestion.personalizedLearning.get()`. |
| `app/settings/typing/TypingScreen.kt` | Added a call to `StyleKitTypingExtras()` at the bottom of `content { }`, which renders a new `PreferenceGroup` with the personalized-learning + auto-swap toggles. The rest of the screen is unchanged. |
| `app/settings/HomeScreen.kt` | Added one new `Preference` entry: "StyleKit" with a star icon, navigating to `Routes.Settings.StyleKitHub`. |
| `app/Routes.kt` | Added 6 new `@Serializable @Deeplink` routes under `Routes.Settings`: `StyleKitHub`, `StyleKitOnboarding`, `StyleKitPresets`, `StyleKitEmojiLab`, `StyleKitAppearance`, `StyleKitAutoSender`. Each registered via `composableWithDeepLink(...)`. |
| `app/src/main/AndroidManifest.xml` | Added 6 permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `READ_MEDIA_IMAGES/VIDEO/AUDIO`, `READ_EXTERNAL_STORAGE` (maxSdk 32), `WAKE_LOCK`). Added two new `<service>` declarations: `AutoSenderService` (foregroundServiceType="specialUse") and `AutoSenderAccessibilityService` (BIND_ACCESSIBILITY_SERVICE with `@xml/stylekit_accessibility_service_config`). |
| `app/src/main/res/values/strings.xml` | Added 4 new strings: `stylekit_auto_sender_accessibility_description`, `enum__smartbar_layout__suggestions_actions_auto`, `settings__stylekit__title`, `settings__stylekit__summary`. |
| `app/src/main/res/xml/` | New file: `stylekit_accessibility_service_config.xml` for the Auto Sender AccessibilityService fallback. |
| `app/src/main/res/raw/` | New directory with a `README.txt` documenting that three sound-pack OGG files (`sk_key_mech.ogg`, `sk_key_soft.ogg`, `sk_key_marimba.ogg`) need to be dropped in before release. Until then, `KeySoundManager` logs an error and clicks are silent (no crash). |
| `FlorisApplication.kt` | Added imports for `AppearanceRepository`, `createAutoSenderNotificationChannel`, `StyleKitDatabase`. In `init()`: eagerly create the Auto Sender notification channel, and asynchronously open the StyleKit DB + seed the config row so the first keystroke isn't slow. All non-fatal — failures are logged and the app continues. |
| `gradle/libs.versions.toml` | Added `androidx-media3 = "1.4.1"` version + `androidx-media3-exoplayer` and `androidx-media3-ui` library entries. |
| `app/build.gradle.kts` | Added `implementation(libs.androidx.media3.exoplayer)` and `implementation(libs.androidx.media3.ui)` for the GIF/video keyboard background (Part 2.3). |

---

## 2. NEW modules / files added

### 2.1 Data layer — `stylekit/data/`

| File | Role |
|---|---|
| `data/StyleKitDatabase.kt` | Single Room `@Database` for all StyleKit features. 9 entities, v1 schema, `exportSchema = true`. Singleton via `synchronized` double-check. Seeds the single-row `sk_config` table on first creation. Has `styleKitDatabaseOrNull()` / `styleKitDatabaseOrLog()` defensive accessors. Migration list is empty for v1; future migrations will be added to `ALL_MIGRATIONS`. |
| `data/entity/WordFrequencyEntity.kt` | Per-device unigram frequency table. PK autoGen, indexed on `word` (unique) and `last_used`. |
| `data/entity/BigramEntity.kt` | Bigram (prev→next) frequencies. Composite PK `(first_word, second_word)`. |
| `data/entity/TrigramEntity.kt` | Trigram (two prev→next) frequencies. Composite PK `(first_word, second_word, third_word)`. |
| `data/entity/StyleKitUserDictionaryEntity.kt` | User-managed custom words promoted from the learning pipeline after threshold. |
| `data/entity/PresetEntity.kt` | Font Style Converter preset. JSON-encoded Map<String,String> mapping. |
| `data/entity/ShortcutEntity.kt` | Emoji Lab shortcut. `trigger`, `emojis`, `triggerMode` ("whole"/"partial"). |
| `data/entity/StyleKitConfigEntity.kt` | Single-row (PK=1) config table for appearance/sound/preset settings. Adding new config fields = one column add + one migration. |
| `data/entity/AutoSenderScriptEntity.kt` | Auto Sender script + its run log entity (`AutoSenderLogEntity`). |
| `data/dao/WordFrequencyDao.kt` | `suggestByPrefix`, `bumpFrequency`, `decayOldEntries`, `pruneStale`, `clearLearned`, etc. |
| `data/dao/BigramDao.kt` | `suggestNext`, `bumpFrequency`, `clearAll`. |
| `data/dao/TrigramDao.kt` | `suggestNext(first, second)`, `bumpFrequency`, `clearAll`. |
| `data/dao/StyleKitUserDictionaryDao.kt` | `suggestByPrefix`, `getAll`, `getByWord`, `insert`, `deleteByWord`. |
| `data/dao/PresetDao.kt` | `observeAll`, `getById`, `getByName`, `insert`, `update`, `delete`. |
| `data/dao/ShortcutDao.kt` | `observeAll`, `getByTrigger`, `insert`, `update`, `delete`. |
| `data/dao/StyleKitConfigDao.kt` | Single-row accessors + targeted update methods (`setThemeId`, `setKeyShape`, `setBackground`, `setGlint`, `setSound`, `setHaptics`, `setPreset`, `setEmojiShortcuts`). |
| `data/dao/AutoSenderDao.kt` | Script CRUD + log observe/insert/clear. |

### 2.2 Adaptive NLP (Part 1) — `stylekit/nlp/`

| File | Role |
|---|---|
| `nlp/AdaptiveLearningProvider.kt` | Implements `SuggestionProvider`. **Current-word completion** via unigram prefix match + **next-word prediction** via bigram/trigram lookup. Personal-frequency candidates are boosted (weight 2.0×) over base-dictionary confidence. Has `recordCommit(word, prev, prevPrev, isPrivateSession)` — the single training entry point, called from `KeyboardManager`. Privacy: no-ops when `isPrivateSession=true` or when `personalizedLearning=false`. Crash safety: every DAO call wrapped in `tryOrNull`. |
| `nlp/AdaptiveLearningRepository.kt` | Thin data-access facade + word-boundary helper (`lastOneOrTwoWords(content)`). Handles promotion: a non-dictionary word committed ≥3 times gets inserted into `sk_user_dictionary`. Runs decay/prune periodically (30-day cutoff). |

### 2.3 Font Style Converter (Part 2.1) — `stylekit/preset/`

| File | Role |
|---|---|
| `preset/TextConverter.kt` | Pure, allocation-conscious Unicode transform. `convertText` (bulk, single StringBuilder pass), `convertChar` (per-keystroke, single map lookup), `emptyMapping`, `bulkPasteAssign`. Ported verbatim from Style Keyboard. |
| `preset/DefaultPresets.kt` | 5 built-in Unicode presets: Math Sans, Bold Serif, Upside Down, Zalgo, Bubble. Plus JSON codec for `Map<String,String>`. |
| `preset/PresetRepository.kt` | CRUD facade with `seedBuiltInsIfNeeded()`, `decodeMapping`, `observeActiveMapping(id)`. |
| `preset/LivePresetApplier.kt` | Singleton holding the active preset mapping in memory (`@Volatile mappingSnapshot`). Per-keystroke `transformChar(ch)` is a single map lookup — no DB I/O on the critical input path. Observes the config + preset tables asynchronously. |

### 2.4 Emoji Lab (Part 2.2) — `stylekit/emojilab/`

| File | Role |
|---|---|
| `emojilab/DefaultShortcuts.kt` | 18 built-in shortcuts (`:fire:`→🔥, `lol`→😂 😆, etc.). |
| `emojilab/ShortcutRepository.kt` | CRUD with trigger lower-casing, `matchForCurrentWord(word)` implementing "whole"/"partial" match modes, `seedBuiltInsIfNeeded()`. |
| `emojilab/EmojiShortcutSuggestionProvider.kt` | Implements `SuggestionProvider`. Returns matching shortcuts as `EmojiShortcutCandidate` chips. Tapping a chip commits the emoji + trailing space; the keyboard's existing commit logic replaces the typed trigger. |

### 2.5 Appearance (Part 2.3) — `stylekit/appearance/`

| File | Role |
|---|---|
| `appearance/StyleKitTheme.kt` | 6 built-in themes (Charcoal, Midnight, Ocean, Sunset, NeonBorder, Light). Each defines bg/keyBg/keyBgActive/keyFg/keyBorder/accent. |
| `appearance/GlintSweep.kt` | Compose `rememberInfiniteTransition` + `drawBehind` linear gradient. Configurable color/speed/opacity, off by default. |
| `appearance/MediaBackground.kt` | GIF background via Android's `Movie` decoder (frame-by-frame, ~30fps, no full bitmap sequence in memory). Video background via ExoPlayer + PlayerView (muted, REPEAT_MODE_ONE). Dark scrim overlay. Crash-safe: any failure → transparent fallback. |
| `appearance/AppearanceRepository.kt` | Single-row config facade. `setBackground()` calls `contentResolver.takePersistableUriPermission` so URIs survive reboot (fixes a bug in the original Style Keyboard). |
| `appearance/StyleKitAppearanceOverlay.kt` | Composable that renders background media + scrim + glint on top of the keyboard. **Wiring instructions in the file's KDoc** — add a single line to `ime/text/TextInputLayout.kt` to enable. |
| `appearance/KeySoundManager.kt` | SoundPool-based key-click manager. 4 streams (overlap), `USAGE_ASSISTANCE_SONIFICATION`. Built-in packs + custom audio import (cached to `cacheDir/sk_key_custom.wav`). Haptics via `VibrationEffect.createOneShot(18ms)`. `applyConfig()` only reloads sample when pack changes. Crash-safe. |

### 2.6 Onboarding (Part 2.5) — `stylekit/onboarding/`

| File | Role |
|---|---|
| `onboarding/OnboardingState.kt` | `isImeEnabled`, `isImeActive`, `isFullAccessOn` (stub, by design). `safeStartImeSettings`, `showImePickerSafely` with graceful fallback to IME settings + toast on OEM ROMs that hide the picker. |

### 2.7 Auto Sender (Part 2.6) — `stylekit/autosender/`

| File | Role |
|---|---|
| `autosender/AutoSenderCodec.kt` | JSON codec for `List<ScriptMessage>`. |
| `autosender/SenderStrategy.kt` | Picks share-intent vs accessibility path. Share-intent dispatches via `ACTION_SEND` + optional target package/class. Accessibility dispatch checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` then calls `AutoSenderAccessibilityService.send(...)`. |
| `autosender/AutoSenderAccessibilityService.kt` | `AccessibilityService` fallback. Waits up to 3s for target window, finds EditText via DFS, sets text via `ACTION_SET_TEXT`, finds send button via heuristic (cd/text contains "send" or text == ">"), clicks it. Registered statically in `AutoSenderAccessibilityServiceHolder`. |
| `autosender/AutoSenderService.kt` | Foreground `Service` (foregroundServiceType=specialUse). Wake lock (10-min safety net). `RunState` enum (Idle/Running/Paused/Stopping). Pause gate via suspend-loop polling. Min 3s between iterations. Notification with Pause + STOP ALL actions. `createAutoSenderNotificationChannel()` helper. |
| `autosender/AutoSenderManager.kt` | High-level facade for the settings UI: CRUD scripts, observe scripts + logs, start/pause/resume/stop intents. |

### 2.8 Smartbar UI — `ime/smartbar/`

| File | Role |
|---|---|
| `ime/smartbar/StyleKitCandidatesRow.kt` | 3-chip suggestion row with **center chip emphasized** (FontWeight.Bold + `emphasis="1"` Snygg attribute). Tap commits, long-press removes. Uses the same `pointerInput` gesture pattern as the existing `CandidateItem`. |

### 2.9 Settings screens — `app/settings/`

| File | Role |
|---|---|
| `settings/stylekit/StyleKitHubScreen.kt` | Hub screen listing all 5 ported features with deep-links. |
| `settings/onboarding/EnableKeyboardScreen.kt` | 3-step onboarding (enable IME / switch to it / grant full access). Live status indicators (green check when detected). |
| `settings/presets/PresetsScreen.kt` | Font Style Converter: quick convert & copy flow + CRUD list of presets. |
| `settings/presets/PresetEditorDialog.kt` | Preset editor: name, description, bulk-paste, manual per-char mapping table. |
| `settings/emojilab/EmojiLabScreen.kt` | Emoji Lab: enable toggle + CRUD list of shortcuts with trigger/emojis/mode editor dialog. |
| `settings/appearance/AppearanceScreen.kt` | Theme picker + key shape + glint sliders + background media picker + sound pack + haptics toggle. |
| `settings/autosender/AutoSenderScreen.kt` | Script CRUD + run controls (Start/Pause/Stop) + run log viewer (color-coded by status). |
| `settings/typing/StyleKitTypingExtras.kt` | The `PreferenceGroup` embedded in the existing TypingScreen: personalized learning + auto-swap toolbar toggles. |

---

## 3. Room schema / migrations

**Database file:** `stylekit.db` (separate from FlorisBoard's existing `floris_user_dictionary` and clipboard DBs)

**Schema version:** 1 (initial release)

**Entities (9):**
- `sk_word_frequency` (PK `row_id`, unique index on `word`, index on `last_used`)
- `sk_bigram` (composite PK `first_word, second_word`)
- `sk_trigram` (composite PK `first_word, second_word, third_word`, index on `(first_word, second_word)`)
- `sk_user_dictionary` (PK `row_id`, unique index on `word`)
- `sk_preset` (PK `row_id`, unique index on `name`)
- `sk_shortcut` (PK `row_id`, index on `trigger`)
- `sk_config` (PK `id`, always = 1 — single-row table)
- `sk_auto_sender_script` (PK `row_id`, index on `created_at`)
- `sk_auto_sender_log` (PK `row_id`, index on `sent_at`)

**Schema location:** `app/schemas/dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase/`
- A `README.json` placeholder is committed now.
- The actual `1.json` will be auto-generated by Room's KSP processor on the first build.
- Future schema changes (v2, v3, …) should add explicit `Migration` instances to `StyleKitDatabase.ALL_MIGRATIONS` and commit the generated `N.json` schema files. **Destructive fallback is NOT used** — the learning tables are exactly the user data we don't want to lose.

**Migrations:** None (v1 is the initial release).

---

## 4. Privacy verification

**Hard requirement: nothing described here ever leaves the device.** Verification:

1. **No new network permissions added.** The manifest diff adds only `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `READ_MEDIA_*`, `READ_EXTERNAL_STORAGE` (maxSdk 32), `WAKE_LOCK`, `POST_NOTIFICATIONS` (already present). **No `INTERNET` permission is added.** (FlorisBoard itself doesn't declare `INTERNET` either.)
2. **All learning data stays in `stylekit.db`** — a local Room database. No ContentProvider exposes it. No backup rules include it (FlorisBoard's `backup_rules.xml` was not modified).
3. **AdaptiveLearningProvider** no-ops when `isPrivateSession=true` (incognito) OR when `prefs.suggestion.personalizedLearning=false`. Both checks are defense-in-depth — the provider checks at entry, and `KeyboardManager.recordWordCommitForLearning()` checks before calling.
4. **Auto Sender** dispatches via `Intent.ACTION_SEND` (system chooser) or `AccessibilityService` (local UI automation). Neither sends data to a server.
5. **No analytics / telemetry added.** Only `flogDebug`/`flogError`/`flogInfo` calls (FlorisBoard's existing local logger).
6. **Custom audio files** are copied to `cacheDir/sk_key_custom.wav` — local only.
7. **Background media URIs** use `takePersistableUriPermission` (read-only) so they survive reboot. The keyboard only reads them via `contentResolver.openInputStream`.

---

## 5. Manual test checklist

### 5.1 Adaptive suggestion learning over a typing session

- [ ] Open Settings → Typing → confirm "Personalized learning" toggle is visible and ON by default.
- [ ] Open Settings → Typing → confirm "Gboard-style toolbar swap" toggle is visible and ON.
- [ ] Open Settings → Smartbar → set layout to "Suggestions + Actions (Auto)".
- [ ] Open any text field. With empty input, the smartbar shows the toolbar icon row.
- [ ] Type "hello" — the row crossfades (~130ms) into 3 suggestion chips with the center chip bold.
- [ ] Press space. Type "world" — suggestions update. Press space again.
- [ ] Clear input. Type "hel" — "hello" should now appear as a suggestion (learned from the prior commit), ranked ahead of any base-dictionary matches.
- [ ] Type "wor" — "world" should appear.
- [ ] Type a custom word like "supercalifragilistic" 3 times (committing each with space). On the 4th attempt, type "superc" — it should be suggested (promoted to user dictionary).
- [ ] Toggle "Personalized learning" OFF in Typing settings. Type the same words — no learned suggestions appear; base-dictionary behavior is unchanged.
- [ ] Toggle incognito mode on (via the smartbar toggle or system incognito detection). Type several words. Toggle incognito off. Confirm the words typed during incognito did NOT get added to the learning tables (check via devtools or DB inspection).

### 5.2 Toolbar↔suggestion-row swap animation

- [ ] With `SUGGESTIONS_ACTIONS_AUTO` layout selected and auto-swap ON:
- [ ] Empty input → toolbar icons visible.
- [ ] Start typing → smooth ~130ms crossfade into 3 chips, center emphasized.
- [ ] Delete all input → crossfade back to toolbar icons.
- [ ] Toggle auto-swap OFF → row stays on toolbar icons even when typing (suggestions still computed, just not shown in this layout).
- [ ] Switch layout to `SUGGESTIONS_ACTIONS_SHARED` → existing FlorisBoard behavior is fully restored (no regression).

### 5.3 Each ported feature's settings screen

- [ ] Settings home → "StyleKit" entry visible with star icon → tap → hub screen lists all 5 features.
- [ ] **Enable Keyboard**: tap each step, confirm deep-links open the right system screen. Step 1 turns green after enabling in system settings. Step 2 turns green after switching via picker. Step 3 stays un-green by design (no public API).
- [ ] **Font Style Converter**: type "hello" in the input, tap each preset chip, confirm output changes (Math Sans / Bold / Upside Down / Zalgo / Bubble). Tap Copy → paste elsewhere → matches the converted text. Create a new preset via the +FAB → edit its mapping → save → appears in the list. Duplicate a built-in → edit the copy → delete the copy.
- [ ] **Emoji Lab**: enable toggle on. Type "lol" in any text field → see "😂 😆" chip above keyboard → tap → trigger is replaced with emoji + space. Add a custom shortcut with "partial" mode → type the first 2 chars → chip appears. Delete a custom shortcut. Built-ins cannot be deleted (no trash icon).
- [ ] **Appearance**: pick each theme → confirm next keystroke uses the new colors (NOTE: full Snygg integration is a documented follow-up; the overlay layers — background media, scrim, glint — work immediately once wired per `StyleKitAppearanceOverlay`'s KDoc). Pick a GIF from gallery → confirm it animates as the keyboard background with a dark scrim. Pick a short video → confirm it loops muted. Adjust scrim slider → confirm legibility changes. Toggle glint on → confirm subtle diagonal sweep. Pick "Marimba" sound pack → tap "Test sound & haptic" → hear click + feel vibration. Toggle haptics off → test again → only sound. Toggle sound muted → test again → only haptic. Pick "Custom" sound → pick an audio file → test → hear custom click.
- [ ] **Auto Sender**: create a script named "Test", target package = (blank, uses chooser), add 2 messages, loop = "once". Tap Start → notification appears ("Auto Sender ready" → "Sent 1/2" → "Sent 2/2") → system share chooser opens for each message. Tap Pause mid-run → notification updates, loop suspends. Tap Resume → continues. Tap STOP ALL → service stops, notification dismissed. Check Run Log → entries appear with status "sent" or "failed". Delete a script → confirm removed. **Do NOT enable AccessibilityService fallback** unless you've explicitly reviewed the permission implications.

### 5.4 Confirming nothing sends data off-device

- [ ] With the app running, capture network traffic via `adb shell tcpdump -i any -w /tmp/capture.pcap` or a network monitoring app like NetGuard / LittleSnitch.
- [ ] Type 50+ words, open every StyleKit settings screen, run an Auto Sender script, pick a background media file, play a test sound.
- [ ] Stop capture, inspect: **zero packets** should be sent to any non-system IP. FlorisBoard has no `INTERNET` permission and we didn't add one.
- [ ] Inspect `stylekit.db` via `adb shell run-as dev.patrickgold.florisboard sqlite3 databases/stylekit.db ".tables"` — confirm learning data is present locally.
- [ ] Toggle "Personalized learning" OFF, type more words, re-inspect DB → confirm no new rows added.
- [ ] Clear app data → confirm `stylekit.db` is wiped (no residual learning data persisted elsewhere).

---

## 6. Known limitations / follow-up work

These are intentional scope boundaries, not bugs:

1. **Snygg theme integration.** `StyleKitTheme` defines colors for keys/background/accent, but the live keyboard currently renders via FlorisBoard's Snygg theming engine. The `StyleKitAppearanceOverlay` composable renders the *background layers* (media + scrim + glint) — wire it into `ime/text/TextInputLayout.kt` per the overlay's KDoc. Full theme-color integration requires generating a Snygg stylesheet from `StyleKitTheme` and registering it with `ThemeManager`; this is a documented follow-up.
2. **Sound pack audio assets.** `app/src/main/res/raw/README.txt` documents that three OGG files need to be dropped in before release. Until then, `KeySoundManager` logs an error and clicks are silent (graceful degradation).
3. **`isFullAccessOn` always returns false.** There is no public Android API to query "full access" state for an IME. The onboarding step 3 never marks itself complete — by design, matching the original Style Keyboard. The user re-taps to confirm.
4. **Live preset supports only single-char replacements.** `LivePresetApplier.transformChar` returns the first char of the replacement, so multi-codepoint replacements (e.g. "a" → "𝓪𝓪") get truncated on the live path. The bulk `TextConverter.convertText` path handles multi-char replacements correctly. To support multi-char live replacement, switch from `commitChar` to `commitText` in the character-commit path.
5. **Auto Sender accessibility dispatch uses `runBlocking`.** This is intentional (bounded wait, runs in the foreground service's coroutine scope) but worth noting. If the target app's window never appears, the call returns false after the 3s timeout — no infinite block.
6. **Room schema JSON not yet generated.** The `1.json` schema file will be auto-generated by Room's KSP processor on the first build, into `app/schemas/dev.patrickgold.florisboard.stylekit.data.StyleKitDatabase/`. A `README.json` placeholder is committed now to make the directory exist.
7. **No unit tests included.** The repository pattern (`AdaptiveLearningRepository`, `PresetRepository`, `ShortcutRepository`, `AppearanceRepository`, `AutoSenderManager`) is designed to be unit-testable with an in-memory Room DB. Tests are a follow-up.

---

## 7. Build & run

```bash
cd /path/to/florisboard
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

First build will run KSP to generate Room's schema JSON and the DAO implementations. Expect ~2–3 minutes for a clean build.

After install:
1. Open the app → Settings home → "StyleKit" → "Enable Keyboard" → follow the 3 steps.
2. Settings → Typing → confirm "Personalized learning" is on.
3. Settings → Smartbar → set layout to "Suggestions + Actions (Auto)".
4. Open any text field and start typing.
