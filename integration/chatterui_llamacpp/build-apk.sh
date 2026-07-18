#!/usr/bin/env bash
# Turnkey ChatterUI APK build (with the HELIX Mesh mod applied).
#
# Run from the ROOT of your ChatterUI checkout AFTER applying the mod (see app_mod/apply.md):
#   bash /path/to/SMS/integration/chatterui_llamacpp/build-apk.sh [--release]
#
# Default builds an installable DEBUG apk (debug-signed, no keystore needed) — ideal for first
# experiments. Pass --release for assembleRelease (needs your own signing config).
#
# NOTE: This CANNOT run in the HELIX cloud dev environment — Google's Android SDK/NDK and Maven
# hosts are blocked there. Run it on your own machine (Linux/macOS) with the Android SDK installed.
set -euo pipefail

VARIANT="Debug"
GRADLE_TASK="assembleDebug"
if [[ "${1:-}" == "--release" ]]; then VARIANT="Release"; GRADLE_TASK="assembleRelease"; fi

say() { printf '\n\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- preflight -------------------------------------------------------------
[[ -f package.json ]] && grep -q '"name": "chatterui"' package.json \
  || die "Run this from the ChatterUI repo root (package.json name must be 'chatterui')."

command -v node >/dev/null || die "Node.js not found (need Node 20+)."
command -v java >/dev/null || die "Java not found (need JDK 17 or 21)."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK" && -d "$SDK" ]] \
  || die "Android SDK not found. Install it (Android Studio) and export ANDROID_SDK_ROOT=/path/to/Android/sdk"
[[ -d "$SDK/ndk" || -n "$(ls -d "$SDK"/ndk* 2>/dev/null || true)" ]] \
  || echo "WARNING: no NDK under \$ANDROID_SDK_ROOT/ndk — cui-llama.rn needs the NDK; install it via sdkmanager."

say "Node $(node -v), $(java -version 2>&1 | head -1), SDK=$SDK, variant=$VARIANT"

# --- build -----------------------------------------------------------------
say "Installing JS dependencies (npm install)…"
npm install

say "Generating the native Android project (expo prebuild)…"
npx expo prebuild --platform android --clean

say "Gradle $GRADLE_TASK (first build downloads the Android toolchain; can take a while)…"
( cd android && ./gradlew "$GRADLE_TASK" )

# --- locate the apk --------------------------------------------------------
APK="$(find android/app/build/outputs/apk -iname '*.apk' | head -1 || true)"
if [[ -n "$APK" ]]; then
  say "APK ready: $APK"
  echo "Install on a connected device:  adb install -r \"$APK\""
else
  die "Build finished but no APK found under android/app/build/outputs/apk/"
fi
