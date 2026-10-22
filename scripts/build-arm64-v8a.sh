#!/usr/bin/env bash
# Build the arm64-v8a debug APK of OpenCode for Termux.
#
# Requirements:
#   - JDK 17+
#   - Android SDK with platform 36 and NDK 29.0.14206865
#   - ANDROID_HOME (or ANDROID_SDK_ROOT) pointing to the SDK
#
# Usage:
#   ./scripts/build-arm64-v8a.sh            # debug APK
#   ./scripts/build-arm64-v8a.sh release    # release APK
#
# The APK is written to:
#   app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
#   app/build/outputs/apk/release/termux-app_apt-android-7-release_arm64-v8a.apk

set -euo pipefail

cd "$(dirname "$0")/.."

BUILD_TYPE="${1:-debug}"

case "$BUILD_TYPE" in
    debug)   GRADLE_TASK="assembleDebug" ;;
    release) GRADLE_TASK="assembleRelease" ;;
    *)       echo "Usage: $0 [debug|release]" >&2; exit 1 ;;
esac

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "ERROR: Set ANDROID_HOME (or ANDROID_SDK_ROOT) to your Android SDK." >&2
    exit 1
fi

# Build only for arm64-v8a: no split APKs, only the aarch64 bootstrap is downloaded and the
# native library is only compiled for that ABI.
export TERMUX_BUILD_ABIS="arm64-v8a"
export TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS="0"
export TERMUX_APP_NAME="${TERMUX_APP_NAME:-OpenCode Termux}"

echo "==> Building $BUILD_TYPE for arm64-v8a"
./gradlew "$GRADLE_TASK" "$@"

echo
echo "==> Built APKs:"
ls -la app/build/outputs/apk/"$BUILD_TYPE"/
