#!/usr/bin/env bash
# presource-finalize.sh — wrap up a pre-source cycle once upstream/master ships
# the matching source.
#
# Steps:
#   1. Verify upstream/master has been fetched and APP_VERSION_NAME on
#      upstream/master matches the pre-source APP_VERSION_NAME.
#   2. Delete pre-source/<version> branch (local + optionally remote).
#   3. Drop build/presource-patches/<version>.patch into a release note manifest.
#   4. Run 'git rebase upstream/master' on Mercurygram.
#
# This is destructive: do not run on uncommitted changes. The script aborts if
# the working tree is dirty.
#
# Usage:
#   scripts/presource-finalize.sh <upstream-version> [--push]

set -euo pipefail

[ $# -ge 1 ] || { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
UPSTREAM_VER="$1"; shift
PUSH=0
while [ $# -gt 0 ]; do
    case "$1" in
        --push) PUSH=1 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

# Refuse on dirty tree
if ! git diff --quiet || ! git diff --quiet --cached; then
    echo "working tree is dirty; commit or stash first" >&2
    exit 1
fi

branch="pre-source/$UPSTREAM_VER"

git fetch upstream
upstream_app_vn=$(git show "upstream/master:gradle.properties" 2>/dev/null | sed -n 's/^APP_VERSION_NAME=//p')
if [ "$upstream_app_vn" != "$UPSTREAM_VER" ]; then
    echo "upstream/master APP_VERSION_NAME='$upstream_app_vn' != '$UPSTREAM_VER'" >&2
    echo "upstream source for this version not landed yet — abort" >&2
    exit 1
fi

archive_dir="build/presource-archive/$UPSTREAM_VER"
mkdir -p "$archive_dir"
if [ -d "build/presource-patches" ]; then
    cp -a build/presource-patches/. "$archive_dir/"
    echo "archived patches -> $archive_dir" >&2
fi

if git rev-parse --verify "$branch" >/dev/null 2>&1; then
    git branch -D "$branch"
    echo "deleted local branch $branch" >&2
fi
if [ "$PUSH" -eq 1 ] && git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
    git push origin --delete "$branch"
    echo "deleted remote branch origin/$branch" >&2
fi

git checkout Mercurygram
git rebase upstream/master
echo "rebased Mercurygram onto upstream/master ($(git rev-parse --short upstream/master))" >&2
echo "verify with: ./scripts/check-mg-translations.sh && ./gradlew assembleAfatFdArm64Debug" >&2
