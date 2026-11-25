#!/usr/bin/env bash
# Build, install, and launch SuperFX Studio on an attached Android / ChromeOS device.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STUDIO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android/Sdk}"
ADB_BIN="${ADB_BIN:-${ANDROID_HOME}/platform-tools/adb}"
if [[ ! -x "${ADB_BIN}" ]]; then
    ADB_BIN="$(command -v adb || true)"
fi

if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
    echo "Error: adb not found at '${ANDROID_HOME}/platform-tools/adb' or on PATH." >&2
    exit 1
fi

cd "${STUDIO_DIR}"
./gradlew :sfx-studio-android:assembleDebug --no-daemon

APK_PATH="${STUDIO_DIR}/sfx-studio-android/build-gradle/outputs/apk/debug/sfx-studio-android-debug.apk"
if [[ ! -f "${APK_PATH}" ]]; then
    echo "Error: Built APK not found at ${APK_PATH}" >&2
    exit 1
fi

echo "Installing ${APK_PATH} via ${ADB_BIN}..."
"${ADB_BIN}" install -r "${APK_PATH}"

echo "Launching SuperFX Studio (dev.secondsun.sfxstudio/.android.MainActivity)..."
"${ADB_BIN}" shell am start -n dev.secondsun.sfxstudio/dev.secondsun.sfxstudio.android.MainActivity
