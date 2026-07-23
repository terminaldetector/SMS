# AGENTS.md

## Cursor Cloud specific instructions

### Repo layout (important, non-obvious)
This repository is a thin container: the actual source code ships as committed `.zip`
archives at the repo root, not as checked-out source. There are two independent
**on-device mobile apps** (no backend server or database):

- `ChatterUI-master (1).zip` -> extracts to `ChatterUI-master/` — a React Native / Expo (Node.js) LLM chat app.
- `gallery-main-fixed8.zip` -> extracts to `gallery-main/` — Google AI Edge Gallery, an Android (Kotlin/Gradle) on-device LLM app. `fixed8` is the newest archive (supersedes `fixed7`).

The startup update script extracts these zips (only if the target dir is missing),
`chmod +x`'s the Gallery `gradlew`, and runs `npm install` for ChatterUI. The extracted
`ChatterUI-master/` and `gallery-main/` directories are gitignored — do not commit them.

### Toolchain (already provisioned in the VM snapshot)
- Node 22, JDK 21 (Temurin/OpenJDK), Python 3.12 are preinstalled.
- Android SDK lives at `~/android-sdk` (cmdline-tools, `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`). `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`PATH` are exported from `~/.bashrc`. If a non-login shell doesn't have them, `export ANDROID_HOME=$HOME/android-sdk; export ANDROID_SDK_ROOT=$ANDROID_HOME` before running Gradle.

### ChatterUI (`ChatterUI-master/`)
- Lint: `npm run lint` (eslint over `app db lib`).
- Build/compile check: `npx expo export --platform android` compiles the whole app into a Hermes bundle — the fastest way to prove the JS/TS app builds without a device.
- Dev server: `npx expo start` (Metro on port 8081). `npx expo run:android` needs an Android device/emulator.
- Tests: the `npm test` script references the `jest-expo` preset, but neither `jest`/`jest-expo` nor any test files are present, so there is no runnable test suite. Do not assume `npm test` works.
- Web target is listed in `app.config.js` but `react-native-web` is not installed, so `expo start --web` / web export fails. This is expected.

### Google AI Edge Gallery (`gallery-main/Android/src/`)
- Build debug APK: `./gradlew assembleDebug` (Gradle wrapper 9.2.1, AGP 8.13.0, compileSdk 37). Output: `app/build/outputs/apk/debug/app-debug.apk` (~176MB).
- The Hugging Face OAuth values in `ProjectConfig.kt` / `app/build.gradle.kts` are `REPLACE_WITH_...` placeholders. The APK still builds and installs fine with placeholders; only in-app HF sign-in / model download needs real values (`HF_CLIENT_ID`, `HF_REDIRECT_URI`, `HF_REDIRECT_SCHEME`, see `build_debug_apk.yaml`).
- First build downloads the Gradle distribution + dependencies (several minutes); subsequent builds are cached under `~/.gradle`.

### Running the apps end-to-end
Both are Android on-device apps. This VM has **no `/dev/kvm`**, so an Android emulator is not
feasible here. "Running" in this environment means the dev build succeeds:
ChatterUI -> `expo export` bundle; Gallery -> `assembleDebug` APK. Interactive on-device
testing requires a real device or a KVM-enabled host.
