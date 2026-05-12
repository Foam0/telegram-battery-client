#!/bin/bash
# changelog-diff.sh — extract structured diff between two tags for changelog generation.
#
# Usage:
#   changelog-diff.sh <previous-tag> <current-tag>              # compare two releases
#   changelog-diff.sh <current-tag>                             # first release (all as ADDED)
#   changelog-diff.sh --presource <previous-tag> <current-tag>  # pre-source release mode
#   changelog-diff.sh --presource <current-tag>                 # pre-source, first release
#
# Output is structured text consumed by the changelog LLM prompt or by the
# pre-release workflow body composer.

set -euo pipefail

PRESOURCE=0
if [[ "${1:-}" == "--presource" ]]; then
    PRESOURCE=1
    shift
fi

if [[ $# -eq 2 ]]; then
    PREV_TAG="$1"
    CURR_TAG="$2"
elif [[ $# -eq 1 ]]; then
    PREV_TAG=""
    CURR_TAG="$1"
else
    echo "Usage: $0 [--presource] [previous-tag] <current-tag>" >&2
    exit 1
fi

# shellcheck source=../../scripts/lib/mg-paths.sh
. "$(git rev-parse --show-toplevel)/scripts/lib/mg-paths.sh"
MG_PATHS=("${MG_OWNED_PATHS[@]}")
SHARED_CONFIG="${MG_HOOK_FILES[0]}"

mg_subjects() {
    local ref="$1"
    if [[ $PRESOURCE -eq 1 ]]; then
        git log --format='%s' "$ref" | grep -E '^\[(MG|TF|UP)\]' | sort
    else
        git log --format='%s' "$ref" | grep -E '^\[(MG|TF)\]' | sort
    fi
}

presource_manifest_field() {
    local ref="$1"
    local key="$2"
    git show "${ref}:.presource-manifest" 2>/dev/null | sed -n "s/^${key}=//p"
}

upstream_version() {
    local ref="$1"
    git show "${ref}:gradle.properties" 2>/dev/null | grep '^APP_VERSION_NAME=' | cut -d= -f2
}

mg_version() {
    local ref="$1"
    git show "${ref}:gradle.properties" 2>/dev/null | grep '^MG_VERSION_NAME=' | cut -d= -f2
}

CURR_UPSTREAM=$(upstream_version "$CURR_TAG")
CURR_MG=$(mg_version "$CURR_TAG")

if [[ $PRESOURCE -eq 1 ]]; then
    echo "RELEASE: ${CURR_MG:-$CURR_TAG} (PRE-SOURCE)"
    APK_SHA=$(presource_manifest_field "$CURR_TAG" APK_SHA256)
    APK_URL=$(presource_manifest_field "$CURR_TAG" APK_URL)
    UP_REF=$(presource_manifest_field "$CURR_TAG" UPSTREAM_REF)
    UP_SHA=$(presource_manifest_field "$CURR_TAG" UPSTREAM_SHA)
    [[ -n "$APK_SHA" ]] && echo "APK_SHA256: $APK_SHA"
    [[ -n "$APK_URL" ]] && echo "APK_URL: $APK_URL"
    [[ -n "$UP_REF" ]] && echo "UPSTREAM_REF: $UP_REF"
    [[ -n "$UP_SHA" ]] && echo "UPSTREAM_SHA: $UP_SHA"
    echo "UPSTREAM_APK_VERSION: $CURR_UPSTREAM"
else
    echo "RELEASE: ${CURR_MG:-$CURR_TAG}"
fi

if [[ -n "$PREV_TAG" ]]; then
    PREV_UPSTREAM=$(upstream_version "$PREV_TAG")
    echo "PREVIOUS: $PREV_TAG"
    echo "UPSTREAM_CURRENT: $CURR_UPSTREAM"
    echo "UPSTREAM_PREVIOUS: $PREV_UPSTREAM"
    if [[ "$CURR_UPSTREAM" != "$PREV_UPSTREAM" ]]; then
        echo "UPSTREAM_CHANGED: true"
    else
        echo "UPSTREAM_CHANGED: false"
    fi
    echo ""

    echo "=== CURRENT FEATURES ==="
    mg_subjects "$CURR_TAG" || true
    echo ""

    echo "=== DIFF STAT ==="
    git diff --stat "$PREV_TAG" "$CURR_TAG" || true
    echo ""

    echo "=== MG CODE DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- "${MG_PATHS[@]}" || true
    echo ""

    echo "=== CONFIG DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- "$SHARED_CONFIG" || true
    echo ""

    echo "=== VERSION DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- gradle.properties || true
else
    echo "UPSTREAM_CURRENT: $CURR_UPSTREAM"
    echo ""

    echo "=== CURRENT FEATURES ==="
    mg_subjects "$CURR_TAG" || true
    echo ""

    echo "=== VERSION DIFF ==="
    git show "${CURR_TAG}:gradle.properties" 2>/dev/null || true
fi
