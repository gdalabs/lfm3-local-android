#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set Android SDK 36, NDK 28.2.13676358 and CMake 3.31.6}"
if [[ ! -d third_party/llama.cpp ]]; then python3 scripts/fetch-upstream.py; fi
./gradlew --no-daemon "$@" :android-app:assembleDebug :android-app:testDebugUnitTest :android-app:lintDebug
