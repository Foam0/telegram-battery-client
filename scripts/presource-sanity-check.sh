#!/usr/bin/env bash
# presource-sanity-check.sh — gate for the pre-source release workflow.
#
# Fails the build unless:
#   1. MG_VERSION_NAME in gradle.properties is X.Y.Z.0.N (5-part numeric,
#      penultimate component is 0, N >= 1).
#   2. The tag name matches MG_VERSION_NAME exactly.
#   3. A committed .presource-manifest file at the repo root carries
#      APK_SHA256 / APK_VERSION_NAME / UPSTREAM_REF / UPSTREAM_SHA keys.
#   4. .presource-manifest APK_VERSION_NAME matches APP_VERSION_NAME in
#      gradle.properties.
#   5. MG_VERSION_CODE == APP_VERSION_CODE * 100 (trailing "00"). All
#      X.Y.Z.0.N iterations share the same versionCode so Android lets the
#      in-app updater over-install them; the in-app updater itself uses
#      mgLastInstalledTag + tag comparison to pick the newer iteration.
#
# See AGENTS.md "Pre-source upstream sync".
#
# Usage:
#   scripts/presource-sanity-check.sh <tag-name>

set -euo pipefail

tag="${1:-${GITHUB_REF_NAME:-}}"
[ -n "$tag" ] || { echo "missing tag arg (and no GITHUB_REF_NAME)" >&2; exit 2; }

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

mg_vn=$(sed -n 's/^MG_VERSION_NAME=//p' gradle.properties)
app_vn=$(sed -n 's/^APP_VERSION_NAME=//p' gradle.properties)
mg_vc=$(sed -n 's/^MG_VERSION_CODE=//p' gradle.properties)
app_vc=$(sed -n 's/^APP_VERSION_CODE=//p' gradle.properties)

[ -n "$mg_vn" ] || { echo "MG_VERSION_NAME missing from gradle.properties" >&2; exit 1; }
[ -n "$mg_vc" ] || { echo "MG_VERSION_CODE missing from gradle.properties" >&2; exit 1; }
[ -n "$app_vc" ] || { echo "APP_VERSION_CODE missing from gradle.properties" >&2; exit 1; }

# 1. MG_VERSION_NAME must be X.Y.Z.0.N (5-part numeric, penultimate = 0).
if ! printf '%s' "$mg_vn" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\.0\.[1-9][0-9]*$'; then
    echo "MG_VERSION_NAME='$mg_vn' is not pre-source shape X.Y.Z.0.N (N >= 1)" >&2
    exit 1
fi

# 2. Tag must equal MG_VERSION_NAME
if [ "$tag" != "$mg_vn" ]; then
    echo "tag='$tag' != MG_VERSION_NAME='$mg_vn'" >&2
    exit 1
fi

# 3. .presource-manifest must exist with required keys
manifest='.presource-manifest'
[ -f "$manifest" ] || { echo "missing $manifest (commit it before tagging)" >&2; exit 1; }
for k in APK_SHA256 APK_VERSION_NAME UPSTREAM_REF UPSTREAM_SHA; do
    if ! grep -qE "^${k}=" "$manifest"; then
        echo "missing key $k in $manifest" >&2
        exit 1
    fi
done

# 4. Manifest APK_VERSION_NAME must equal gradle.properties APP_VERSION_NAME
manifest_apk_vn=$(sed -n 's/^APK_VERSION_NAME=//p' "$manifest")
if [ "$manifest_apk_vn" != "$app_vn" ]; then
    echo "manifest APK_VERSION_NAME='$manifest_apk_vn' != APP_VERSION_NAME='$app_vn'" >&2
    exit 1
fi

# 5. MG_VERSION_CODE must equal APP_VERSION_CODE with trailing "00".
# Same versionCode across pre-source iterations is intentional — keeps the
# system PackageInstaller happy across over-installs; iteration ordering is
# resolved by MgUpdateChecker via mgLastInstalledTag + tag comparison.
expected_mg_vc="${app_vc}00"
if [ "$mg_vc" != "$expected_mg_vc" ]; then
    echo "MG_VERSION_CODE='$mg_vc' != APP_VERSION_CODE*100='$expected_mg_vc'" >&2
    exit 1
fi

echo "pre-source sanity check passed: $mg_vn (upstream $app_vn, code $mg_vc, manifest OK)" >&2
