#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
[[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE — copy .env.example and fill in your values"; exit 1; }
source "$ENV_FILE"

# Read version info from gradle.properties for F-Droid reproducible build naming
MG_VERSION_CODE=$(grep '^MG_VERSION_CODE=' "$SCRIPT_DIR/gradle.properties" | cut -d= -f2)
MG_VERSION_NAME=$(grep '^MG_VERSION_NAME=' "$SCRIPT_DIR/gradle.properties" | cut -d= -f2)
# Compute per-ABI version codes: MG_VERSION_CODE * 10 + abiVersionCode
VCODE_ARM32=$(( MG_VERSION_CODE * 10 + 7 ))
VCODE_ARM64=$(( MG_VERSION_CODE * 10 + 8 ))
VCODE_X86=$(( MG_VERSION_CODE * 10 + 3 ))
VCODE_X86_64=$(( MG_VERSION_CODE * 10 + 4 ))

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

# HACK: cache built native artifacts to avoid full NDK rebuild on every run
if [ -d TMessagesProj/jni.bak ]; then
    rm -rf TMessagesProj/jni
    cp -a --reflink=auto TMessagesProj/jni.bak TMessagesProj/jni
fi
./gradlew assembleAfatRelease assembleAfatDebug \
    assembleAfatFdArm32Release assembleAfatFdArm64Release assembleAfatFdX86Release assembleAfatFdX86_64Release
# HACK: update the native artifact cache
rm -rf TMessagesProj/jni.bak
cp -a --reflink=auto TMessagesProj/jni TMessagesProj/jni.bak

APK_DIR=./TMessagesProj_App/build/outputs/apk

sign_apk "$APK_DIR/afat/release/app.apk"                  "$APK_DIR/afat/release/app-release.apk"
sign_apk "$APK_DIR/afat/debug/app.apk"                    "$APK_DIR/afat/debug/app-debug.apk"
sign_apk "$APK_DIR/afatFdArm32/release/afatFdArm32.apk"   "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_ARM32}.apk"
sign_apk "$APK_DIR/afatFdArm64/release/afatFdArm64.apk"   "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_ARM64}.apk"
sign_apk "$APK_DIR/afatFdX86/release/afatFdX86.apk"       "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_X86}.apk"
sign_apk "$APK_DIR/afatFdX86_64/release/afatFdX86_64.apk" "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_X86_64}.apk"

if [[ -n "${1:+x}" ]]; then
    gh release delete "$1" --cleanup-tag --yes || :
    gh release create "$1" \
        "$APK_DIR/afat/debug/app-debug.apk" \
        "$APK_DIR/afat/release/app-release.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_ARM32}.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_ARM64}.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_X86}.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-${VCODE_X86_64}.apk" \
        --generate-notes
fi
