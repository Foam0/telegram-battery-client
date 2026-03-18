#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
[[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE — copy .env.example and fill in your values"; exit 1; }
source "$ENV_FILE"

# AGP already produces zipaligned release APKs — sign directly, no re-alignment.
sign_apk() {
    local input="$1"
    local output="$2"
    "${BUILD_TOOLS}/apksigner" sign \
        --ks "$KS" \
        --ks-pass "pass:$KS_PASS" \
        --key-pass "pass:$KS_PASS" \
        --out "$output" \
        "$input"
}

./gradlew assembleAfatRelease assembleAfatDebug \
    assembleAfatFdArm32Release assembleAfatFdArm64Release assembleAfatFdX86Release assembleAfatFdX86_64Release

APK_DIR=./TMessagesProj_App/build/outputs/apk

sign_apk "$APK_DIR/afat/release/app.apk"                  "$APK_DIR/afat/release/app-release.apk"
sign_apk "$APK_DIR/afat/debug/app.apk"                    "$APK_DIR/afat/debug/app-debug.apk"
sign_apk "$APK_DIR/afatFdArm32/release/afatFdArm32.apk"   "$APK_DIR/Mercurygram-armeabi-v7a.apk"
sign_apk "$APK_DIR/afatFdArm64/release/afatFdArm64.apk"   "$APK_DIR/Mercurygram-arm64-v8a.apk"
sign_apk "$APK_DIR/afatFdX86/release/afatFdX86.apk"       "$APK_DIR/Mercurygram-x86.apk"
sign_apk "$APK_DIR/afatFdX86_64/release/afatFdX86_64.apk" "$APK_DIR/Mercurygram-x86_64.apk"

if [[ -n "${1:+x}" ]]; then
    gh release delete "$1" --cleanup-tag --yes || :
    gh release create "$1" \
        "$APK_DIR/afat/debug/app-debug.apk" \
        "$APK_DIR/afat/release/app-release.apk" \
        "$APK_DIR/Mercurygram-armeabi-v7a.apk" \
        "$APK_DIR/Mercurygram-arm64-v8a.apk" \
        "$APK_DIR/Mercurygram-x86.apk" \
        "$APK_DIR/Mercurygram-x86_64.apk" \
        --generate-notes
fi
