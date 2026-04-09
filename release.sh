#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
[[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE — copy .env.example and fill in your values"; exit 1; }
source "$ENV_FILE"

TAG="${1:-}"
[[ -n "$TAG" ]] || { echo "Usage: $0 <tag>  (e.g. $0 12.7.3.5)"; exit 1; }
# release.sh ships only 4-dotted stable tags; 5-dotted snapshots are
# beta.yml's territory. Reject early so PREV_TAG enumeration (4-dotted
# only) can't silently fall back to first-release mode.
[[ "$TAG" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || { echo "Tag '$TAG' is not 4-dotted (X.Y.Z.M). Snapshots are published by beta.yml."; exit 1; }

# Sign via Gradle directly (not apksigner) to preserve zero-padding in ZIP
# extra fields, which is required for F-Droid reproducible builds.

# HACK: cache built native artifacts to avoid full NDK rebuild on every run
if [ -d TMessagesProj/jni.bak ]; then
    rm -rf TMessagesProj/jni
    cp -a --reflink=auto TMessagesProj/jni.bak TMessagesProj/jni
fi
./gradlew assembleAfatRelease assembleAfatDebug \
    assembleAfatFdArm32Release assembleAfatFdArm64Release assembleAfatFdX86Release assembleAfatFdX86_64Release \
    -PMG_BUILD_TAG="$TAG" \
    -PRELEASE_STORE_FILE="$KS" \
    -PRELEASE_STORE_PASSWORD="$KS_PASS" \
    -PRELEASE_KEY_PASSWORD="$KS_KEY_PASS" \
    -PRELEASE_KEY_ALIAS="$KS_KEY_ALIAS"
# HACK: update the native artifact cache
rm -rf TMessagesProj/jni.bak
cp -a --reflink=auto TMessagesProj/jni TMessagesProj/jni.bak

APK_DIR=./TMessagesProj_App/build/outputs/apk

cp "$APK_DIR/afat/release/app.apk"                  "$APK_DIR/Mercurygram-${TAG}-release.apk"
cp "$APK_DIR/afat/debug/app.apk"                    "$APK_DIR/Mercurygram-${TAG}-debug.apk"
cp "$APK_DIR/afatFdArm32/release/afatFdArm32.apk"   "$APK_DIR/Mercurygram-${TAG}-armeabi-v7a.apk"
cp "$APK_DIR/afatFdArm64/release/afatFdArm64.apk"   "$APK_DIR/Mercurygram-${TAG}-arm64-v8a.apk"
cp "$APK_DIR/afatFdX86/release/afatFdX86.apk"       "$APK_DIR/Mercurygram-${TAG}-x86.apk"
cp "$APK_DIR/afatFdX86_64/release/afatFdX86_64.apk" "$APK_DIR/Mercurygram-${TAG}-x86_64.apk"

# Find previous Mercurygram release tag (4-part version format)
PREV_TAG=$(git tag -l '[0-9]*' --sort=-version:refname \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' \
    | grep -A1 "^${TAG}$" | tail -1)

# Generate commit diff
if [ "$PREV_TAG" = "$TAG" ] || [ -z "$PREV_TAG" ]; then
    bash "$SCRIPT_DIR/.github/scripts/changelog-diff.sh" "$TAG" > /tmp/mg-diff.txt
else
    bash "$SCRIPT_DIR/.github/scripts/changelog-diff.sh" "$PREV_TAG" "$TAG" > /tmp/mg-diff.txt
fi

# Generate changelog via GitHub Models API (same model as CI)
GITHUB_TOKEN=$(gh auth token 2>/dev/null || true)
BODY=""
if [ -n "$GITHUB_TOKEN" ]; then
    DIFF=$(cat /tmp/mg-diff.txt)
    SYSTEM=$(cat "$SCRIPT_DIR/.github/scripts/changelog-prompt.md")
    BODY=$(curl -sL -X POST \
        -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Content-Type: application/json" \
        https://models.github.ai/inference/chat/completions \
        -d "$(jq -n --arg sys "$SYSTEM" --arg diff "$DIFF" '{
            model: "openai/gpt-4.1-mini",
            messages: [
                {role: "system", content: $sys},
                {role: "user", content: $diff}
            ]
        }')" | jq -r '.choices[0].message.content // empty' 2>/dev/null || true)
fi

if [ -z "$BODY" ]; then
    # Fallback: format ADDED [MG] lines as bullets
    BODY=$(grep -A100 '=== ADDED \[MG\] ===' /tmp/mg-diff.txt \
        | grep '^\[MG\]' | sed 's/^\[MG\] /- /' || echo "- See commit history for changes.")
fi

# Guard re-runs: confirm before destroying an existing published release.
# RELEASE_REPLACE=1 skips the prompt for non-interactive runs.
if gh release view "$TAG" >/dev/null 2>&1; then
    if [[ -z "${RELEASE_REPLACE:-}" ]]; then
        read -rp "Release $TAG exists. Delete and replace? [y/N] " ans </dev/tty
        [[ "$ans" =~ ^[Yy]$ ]] || { echo "aborted"; exit 1; }
    fi
    gh release delete "$TAG" --cleanup-tag --yes
fi
gh release create "$TAG" \
    "$APK_DIR/Mercurygram-${TAG}-release.apk" \
    "$APK_DIR/Mercurygram-${TAG}-debug.apk" \
    "$APK_DIR/Mercurygram-${TAG}-armeabi-v7a.apk" \
    "$APK_DIR/Mercurygram-${TAG}-arm64-v8a.apk" \
    "$APK_DIR/Mercurygram-${TAG}-x86.apk" \
    "$APK_DIR/Mercurygram-${TAG}-x86_64.apk" \
    --notes "$BODY"
