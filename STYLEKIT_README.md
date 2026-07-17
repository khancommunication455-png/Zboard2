# StyleKit for FlorisBoard — Quick Start

This is FlorisBoard with the Style Keyboard feature set grafted on top.
See [`download/STYLEKIT_IMPLEMENTATION_SUMMARY.md`](download/STYLEKIT_IMPLEMENTATION_SUMMARY.md)
for the full file-by-file breakdown.

## Option A — Build on GitHub Actions (no local setup required)

1. Push this repo to a new GitHub repository (public or private).
2. Go to the **Actions** tab → **Build StyleKit APK** workflow.
3. Click **Run workflow** → choose `debug` or `release` → **Run workflow**.
4. Wait ~10–15 minutes for the build.
5. When complete, download the **artifact** at the bottom of the run page —
   it will be named `stylekit-florisboard-debug.apk` (or `-release-unsigned.apk`).
6. Sideload the APK on any Android 8.0+ device:
   ```
   adb install -r stylekit-florisboard-debug.apk
   ```
7. Open the app → **Settings home** → **StyleKit** → **Enable Keyboard** →
   follow the 3-step onboarding.

The workflow file is at [`.github/workflows/build-stylekit-apk.yml`](.github/workflows/build-stylekit-apk.yml).
It uses `ubuntu-latest` (16 GB RAM), JDK 21, Android SDK 36, NDK 29.0.14206865,
CMake 3.22.1 — all the versions the project's `gradle/tools.versions.toml` expects.

The workflow also runs automatically on every push to `main`/`master` and on
every PR, so you'll get fresh APKs for every commit.

## Option B — Build locally

### Prerequisites
- **JDK 21** (Temurin recommended — https://adoptium.net/)
- **Android SDK** with:
  - `platforms;android-36` (Android 16)
  - `build-tools;36.0.0`
  - `ndk;29.0.14206865`
  - `cmake;3.22.1`
- ~6 GB free disk for Gradle cache + build artifacts

### Steps
```bash
# 1. Set ANDROID_HOME (or create local.properties with sdk.dir=/path/to/Android/Sdk)
export ANDROID_HOME=/path/to/Android/Sdk

# 2. Build the debug APK
./gradlew :app:assembleDebug

# 3. Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a release build (unsigned):
```bash
./gradlew :app:assembleRelease
# APK at app/build/outputs/apk/release/app-release-unsigned.apk
```

### Common issues

| Symptom | Fix |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...` |
| `NDK not configured` | Install NDK `29.0.14206865` via SDK Manager |
| KSP error about `StyleKitDatabase` | Run `./gradlew :app:kspDebugKotlin --stacktrace` to see the actual error |
| Sound packs silent | Expected — `app/src/main/res/raw/_readme.txt` documents that three OGG files need to be dropped in before release. Until then, `KeySoundManager` logs an error and clicks are silent (no crash). |

## What's in the APK

After install:
- **Settings → StyleKit** (star icon on home screen) gives access to:
  - Enable Keyboard (3-step onboarding)
  - Font Style Converter (5 Unicode presets + custom)
  - Emoji Lab (18 built-in shortcuts + custom)
  - Appearance (6 themes, glint, GIF/video background, sound packs, haptics)
  - Auto Sender (scheduled message utility)
- **Settings → Typing** has two new toggles:
  - **Personalized learning** — on-device n-gram learning, nothing leaves the device
  - **Gboard-style toolbar swap** — auto crossfade between toolbar icons and 3 suggestion chips
- **Settings → Smartbar** has a new layout: **Suggestions + Actions (Auto / Gboard-style)**

## Privacy

No `INTERNET` permission is added. All learning data stays in a local Room DB
(`stylekit.db`). The adaptive learning provider no-ops cleanly when incognito mode
is on or when personalized learning is toggled off.

Full privacy verification + manual test checklist is in section 5 of
[`download/STYLEKIT_IMPLEMENTATION_SUMMARY.md`](download/STYLEKIT_IMPLEMENTATION_SUMMARY.md).
