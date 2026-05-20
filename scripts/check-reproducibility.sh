#!/usr/bin/env bash
# check-reproducibility.sh — verify Mercurygram builds reproducibly using the
# EXACT toolchain F-Droid's app-build CI uses. No hand-rolled build steps.
#
# Mirrors fdroiddata's .gitlab-ci.yml app-build job verbatim:
#   * container image: registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
#     ("tied to the buildserver … same provisioning as the production
#      buildserver"); the Android SDK + build env live in this image.
#   * fdroidserver itself is NOT taken from the image — fdroiddata CI overlays a
#     fdroidserver checkout on PATH/PYTHONPATH. We overlay the same way, pinned
#     to !1825 (drizzt/fdroidserver @ mercurygram-v2-only-graft) because
#     Mercurygram is signed v2/v3-only and stock fdroidserver cannot graft its
#     developer signature for `fdroid verify` until that MR merges. Once merged:
#       FDROIDSERVER_REPO=https://gitlab.com/fdroid/fdroidserver.git \
#       FDROIDSERVER_REF=master scripts/check-reproducibility.sh ...
#   * build environment variables come from /etc/profile.d/bsenv.sh, exactly
#     as the CI build job does.
# diffoscope is run OUTSIDE the build image (a separate throwaway container):
#   it is not part of the build, and the buildserver image must stay pristine.
#
# Two modes:
#
#   determinism [<ref>]            (default; ref defaults to the working tree)
#       Build the chosen source TWICE, then diffoscope the two unsigned APKs.
#       PASS iff byte-identical. Per-change / per-PR guard against the
#       nondeterminism that has bitten us before. Uncommitted *tracked* changes
#       are carried into a throwaway commit (submodule gitlinks preserved) so
#       dirty trees / feature branches are checkable before they ship. Does not
#       need !1825.
#
#   verify <versionCode>
#       Authoritative F-Droid reproducible-build check: `fdroid build` the
#       fdroiddata recipe at its pinned commit, then `fdroid verify` against
#       the binary published on https://f-droid.org/repo (developer-signature
#       graft — needs !1825). PASS iff `fdroid verify` reports a match. Use for
#       an already-shipped tag as a release regression guard (it WILL differ
#       for unreleased local changes — expected; use determinism then).
#
#   verify <github-apk-url> [ref]
#       Same authoritative path, but the comparison APK is downloaded from a
#       GitHub Release URL (Release-flavor beta APK; Debug APKs are NOT
#       reproducible by design — BuildVars.MG_BUILD_TIMESTAMP). Recipe's last
#       Builds entry is overridden to the given ref and the APK's versionCode;
#       fdroid build runs as usual, then apksigcopier grafts the GitHub
#       signature so the comparison covers the full APK byte-for-byte. ref
#       defaults to HEAD; pass the tag/sha matching the beta build.
#
# Usage:
#   scripts/check-reproducibility.sh                 # determinism, working tree
#   scripts/check-reproducibility.sh determinism HEAD
#   scripts/check-reproducibility.sh verify 6666048
#   scripts/check-reproducibility.sh verify \
#       https://github.com/.../releases/download/12.6.4.4.15/Mercurygram-12.6.4.4.15-arm64-v8a.apk \
#       12.6.4.4.15
#
# Requires: podman, git, network. Heavy: full native build x2.

set -euo pipefail

APPID=it.belloworld.mercurygram
# The image fdroiddata CI builds apps in (.gitlab-ci.yml).
BUILDSERVER_IMAGE="${BUILDSERVER_IMAGE:-registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie}"
FDROIDDATA_RAW="${FDROIDDATA_RAW:-https://gitlab.com/fdroid/fdroiddata/-/raw/master}"
# fdroidserver overlay, pinned to MR !1825 until it lands upstream.
FDROIDSERVER_REPO="${FDROIDSERVER_REPO:-https://gitlab.com/drizzt/fdroidserver.git}"
FDROIDSERVER_REF="${FDROIDSERVER_REF:-mercurygram-v2-only-graft}"
IMG=mg-repro-buildserver:local        # buildserver + fdroidserver overlay
DIFF_IMG=mg-repro-diffoscope:local    # separate, build-independent

repo_root=$(git rev-parse --show-toplevel)
# Optional local fdroiddata checkout; default = sibling of this repo. Skips
# the curl fetch when present. Override with FDROIDDATA_DIR.
FDROIDDATA_DIR="${FDROIDDATA_DIR:-$(dirname "$repo_root")/fdroiddata}"
mode="${1:-determinism}"
# Work dir must sit on a roomy filesystem: a full native build + two APKs need
# several GB, far more than a typical /tmp tmpfs. Override with MG_REPRO_WORK.
work_base="${MG_REPRO_WORK:-$HOME/.cache/mg-repro}"
mkdir -p "$work_base"
work=$(mktemp -d "$work_base/run.XXXXXX")
trap '[ -n "${MG_REPRO_KEEP:-}" ] && echo "work kept: $work" || rm -rf "$work"' EXIT

log() { echo "=== $(date -Is) :: $* ==="; }
die() { echo "error: $*" >&2; exit 2; }
command -v podman >/dev/null || die "podman not found"

# --- image 1: buildserver + fdroidserver(!1825) overlay (fdroiddata-CI style) -
log "build container image (FROM $BUILDSERVER_IMAGE + fdroidserver overlay)"
mkdir -p "$work/ctx"
cat > "$work/ctx/Containerfile" <<EOF
FROM $BUILDSERVER_IMAGE
# fdroiddata CI overlays a fdroidserver checkout on PATH/PYTHONPATH instead of
# using the one in the image. Same here, pinned to !1825. Retry: a shallow
# fetch has flaked with truncated-body errors.
RUN for i in 1 2 3 4 5; do \\
      git clone "$FDROIDSERVER_REPO" /opt/fdroidserver \\
        && git -C /opt/fdroidserver checkout "$FDROIDSERVER_REF" && break; \\
      echo "clone attempt \$i failed, retrying"; rm -rf /opt/fdroidserver; sleep 5; \\
    done && test -d /opt/fdroidserver/.git
# fdroidserver runs the recipe's sudo: block; container runs as root.
RUN test -x "\$(command -v sudo)" || { \\
      printf '#!/bin/sh\\nexec "\$@"\\n' > /usr/local/bin/sudo \\
      && chmod +x /usr/local/bin/sudo; }
# Buildserver image ships ANDROID_HOME=/opt/android-sdk skeleton but no SDK
# packages (production buildserver provisions them on demand). Install the
# exact versions the fdroiddata recipe pins via sdkmanager (image already has
# Java 21, curl, unzip; recipe sudo: installs Java 17 from bookworm at build
# time, so we don't add it here).
RUN mkdir -p /opt/android-sdk/cmdline-tools && \\
    curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \\
    unzip -q /tmp/clt.zip -d /opt/android-sdk/cmdline-tools && \\
    mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && \\
    yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true && \\
    /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --install \\
      "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;21.4.7075529" >/dev/null
EOF
podman build --network=host -t "$IMG" "$work/ctx" >/dev/null

# --- image 2: diffoscope only, kept entirely separate from the build env -----
log "build comparison image (diffoscope, outside the build env)"
mkdir -p "$work/dctx"
cat > "$work/dctx/Containerfile" <<'EOF'
FROM docker.io/library/debian:trixie-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends diffoscope unzip \
    && rm -rf /var/lib/apt/lists/*
EOF
podman build --network=host -t "$DIFF_IMG" "$work/dctx" >/dev/null

# Minimal fdroiddata tree: only the REAL recipe file is needed (cloning all of
# fdroiddata pulls multi-GB of every app's screenshots). `fdroid build` creates
# build/ unsigned/ tmp/ itself; config.yml points it at the in-image SDK.
mkdir -p "$work/fdroiddata/metadata"
recipe="$work/fdroiddata/metadata/${APPID}.yml"
if [ -f "$FDROIDDATA_DIR/metadata/${APPID}.yml" ]; then
    log "use local fdroiddata recipe ($FDROIDDATA_DIR)"
    cp "$FDROIDDATA_DIR/metadata/${APPID}.yml" "$recipe"
else
    log "fetch fdroiddata recipe for $APPID"
    curl -fsSL "${FDROIDDATA_RAW}/metadata/${APPID}.yml" \
        -o "$recipe" || die "could not fetch recipe metadata/${APPID}.yml"
fi
[ -s "$recipe" ] || die "recipe metadata/${APPID}.yml empty/missing"
printf 'sdk_path: %s\n' "${ANDROID_SDK_IN_IMAGE:-/opt/android-sdk}" \
    > "$work/fdroiddata/config.yml"

# Run fdroid inside the buildserver image, exactly as fdroiddata CI does:
# source the buildserver env, overlay the pinned fdroidserver on PATH/PYTHONPATH.
fdroid_in_container() {
    podman run --rm --network=host -v "$work":/w:z -w /w/fdroiddata "$IMG" \
        bash -euo pipefail -c '
            test -f /etc/profile.d/bsenv.sh && . /etc/profile.d/bsenv.sh
            export PATH=/opt/fdroidserver:$PATH
            export PYTHONPATH=/opt/fdroidserver${PYTHONPATH:+:$PYTHONPATH}
            git config --global --add safe.directory "*"
            fdroid '"$*"
}

# diffoscope two APK files directly (zip-aware), excluding signature blocks
# and filesystem dir mtimes (those come from the extractor, not the APK).
diffoscope_apks() {  # $1 $2 = apk filenames under $work
    podman run --rm -v "$work":/w:z "$DIFF_IMG" \
        diffoscope --exclude-directory-metadata=yes \
            --exclude 'META-INF/.*\.(RSA|SF|DSA)$' \
            --exclude 'META-INF/MANIFEST\.MF$' \
            "/w/$1" "/w/$2"
}

case "$mode" in
  determinism)
    ref="${2:-}"
    log "snapshot source under test (submodule gitlinks preserved)"
    git clone --quiet --no-local "$repo_root" "$work/src"
    if [ -n "$ref" ]; then
        git -C "$work/src" checkout --quiet "$ref"
    else
        git -C "$work/src" checkout --quiet "$(git -C "$repo_root" rev-parse HEAD)"
        if ! git -C "$repo_root" diff --quiet HEAD; then
            git -C "$repo_root" diff --binary HEAD \
                | git -C "$work/src" apply --index --binary
        fi
    fi
    # Commit on a real branch, then mirror-clone into a bare repo. A plain
    # `git bundle` works for `git clone` but fdroidserver's GitVcs doesn't
    # treat a bundle file as a fetchable Repo (vcs.gotorevision silently
    # no-ops -> empty build/<appid> -> SOURCE_DATE_EPOCH None -> TypeError).
    # A bare repo with HEAD set behaves like a normal git URL.
    git -C "$work/src" checkout --quiet -B repro
    git -C "$work/src" -c user.email=repro@local -c user.name=repro \
        commit --quiet --allow-empty -m 'repro snapshot'
    snap_sha=$(git -C "$work/src" rev-parse HEAD)
    git clone --quiet --mirror "$work/src" "$work/fdroiddata/${APPID}.git"
    git -C "$work/fdroiddata/${APPID}.git" symbolic-ref HEAD refs/heads/repro

    # Override the recipe's latest Builds entry to point at the snapshot
    # commit (and the local bare repo). versionCode/versionName are left
    # untouched: the snapshot's gradle.properties produces the latest entry's
    # versionCode, and fdroidserver post-build-validates APK vc against it.
    # Inserting a synthetic entry with a different vc fails that check.
    REPRO_VC=$(podman run --rm -i -v "$work":/w:z "$IMG" python3 - \
        "/w/fdroiddata/metadata/${APPID}.yml" "/w/fdroiddata/${APPID}.git" \
        "$snap_sha" <<'PY'
import sys, yaml
recipe, bundle, sha = sys.argv[1], sys.argv[2], sys.argv[3]
with open(recipe) as f:
    m = yaml.safe_load(f)
m['RepoType'] = 'git'
m['Repo'] = bundle
m['Builds'][-1]['commit'] = sha
with open(recipe, 'w') as f:
    yaml.safe_dump(m, f, sort_keys=False, default_flow_style=False)
print(m['Builds'][-1]['versionCode'])
PY
    )
    [ -n "$REPRO_VC" ] || die "could not determine versionCode from recipe"
    log "recipe: overrode latest build commit -> ${snap_sha:0:12}, vc=$REPRO_VC"

    for tag in a b; do
        log "fdroid build #$tag ($APPID:$REPRO_VC @ ${snap_sha:0:12})"
        # Pre-populate build/<appid> from the bare repo. Works around an
        # fdroidserver bug on Python 3.13: set_FDroidPopen_env is called
        # BEFORE prepare_source clones, so get_source_date_epoch returns None
        # for the missing dir and `os.environ[...] = None` raises TypeError.
        # Pre-cloning makes the function return a real timestamp; fdroidserver
        # then reuses the existing checkout in prepare_source.
        podman run --rm -v "$work":/w:z -w /w/fdroiddata "$IMG" bash -euo pipefail -c "
            rm -rf build/${APPID}
            git clone --quiet /w/fdroiddata/${APPID}.git build/${APPID}
            git -C build/${APPID} checkout --quiet ${snap_sha}
            git -C build/${APPID} submodule update --init --recursive --depth 1 \
                >/dev/null 2>&1 || git -C build/${APPID} submodule update --init --recursive
        " >> "$work/build-$tag.log" 2>&1 \
            || { tail -30 "$work/build-$tag.log"; die "pre-clone for build #$tag failed"; }
        # --on-server: trigger recipe's sudo: block (installs Java 17 from
        # bookworm, build deps); --test: write APK to tmp/. Mirrors
        # fdroiddata CI's "fdroid build" job invocation.
        fdroid_in_container "build --on-server --test --no-tarball --refresh-scanner $APPID:$REPRO_VC" \
            >> "$work/build-$tag.log" 2>&1 \
            || { tail -80 "$work/build-$tag.log"; die "fdroid build #$tag failed (full log: $work/build-$tag.log)"; }
        cp "$work"/fdroiddata/tmp/${APPID}_${REPRO_VC}.apk "$work/out-$tag.apk"
        rm -f "$work"/fdroiddata/tmp/${APPID}_*.apk
    done

    log "compare the two builds (diffoscope, outside the build env, sigs excluded)"
    if diffoscope_apks out-a.apk out-b.apk; then
        log "RESULT: REPRODUCIBLE — two independent fdroidserver builds are byte-identical"
        exit 0
    else
        log "RESULT: NOT REPRODUCIBLE — see diffoscope output above"
        exit 1
    fi
    ;;

  verify)
    arg2="${2:-}"
    [ -n "$arg2" ] || die "usage: $0 verify <versionCode> | verify <github-apk-url> [ref]"

    if [[ "$arg2" =~ ^[0-9]+$ ]]; then
        # ---- versionCode form: compare against f-droid.org-published APK ----
        vc="$arg2"
        log "fdroid build $APPID:$vc (pinned recipe commit)"
        fdroid_in_container "build --no-tarball --skip-scan $APPID:$vc" \
            > "$work/build.log" 2>&1 \
            || { tail -50 "$work/build.log"; die "fdroid build failed (full log: $work/build.log)"; }
        log "fdroid verify $APPID:$vc against https://f-droid.org/repo (v2/v3 graft, !1825)"
        rc=0
        fdroid_in_container "verify --verbose $APPID:$vc" || rc=$?
        cat "$work"/fdroiddata/verified/${APPID}_${vc}.apk.json 2>/dev/null || true
        if [ "$rc" -eq 0 ]; then
            log "RESULT: VERIFIED — local build matches the F-Droid-published APK"
            exit 0
        fi
        log "RESULT: MISMATCH — local build differs from published APK (see JSON above)"
        exit 1
    fi

    # ---- URL form: compare against a GitHub Release APK ----
    # Mirrors fdroiddata CI's reproducible-build job for a Release-flavor APK
    # built outside of F-Droid (beta channel publishes these per push). Flow:
    #  1. download the GitHub APK
    #  2. read its versionCode from the AndroidManifest (aapt2 dump badging)
    #  3. snapshot source at the matching ref, push to a bare repo
    #  4. override the recipe's last Builds entry (commit + versionCode +
    #     Repo) to point at that snapshot
    #  5. fdroid build --on-server --test (same path as determinism mode)
    #  6. apksigcopier copies the GitHub signature onto the local unsigned APK
    #  7. diffoscope full APKs — PASS only if every byte matches
    url="$arg2"
    ref="${3:-HEAD}"
    case "$url" in https://*|http://*) ;; *) die "verify arg must be a versionCode or a http(s) URL" ;; esac

    log "download GitHub APK: $url"
    curl -fsSL "$url" -o "$work/github.apk" || die "download failed"

    log "extract versionCode from GitHub APK manifest (aapt2 dump badging)"
    apk_vc=$(podman run --rm -v "$work":/w:z "$IMG" \
        /opt/android-sdk/build-tools/35.0.0/aapt2 dump badging /w/github.apk \
        | sed -n "s/^package: .*versionCode='\([0-9]\+\)'.*/\1/p" | head -1)
    [ -n "$apk_vc" ] || die "could not extract versionCode from $url"
    log "GitHub APK versionCode: $apk_vc"

    log "snapshot source at ref=$ref (carries uncommitted tracked changes when ref=HEAD)"
    git clone --quiet --no-local "$repo_root" "$work/src"
    git -C "$work/src" checkout --quiet "$(git -C "$repo_root" rev-parse "$ref")"
    if [ "$ref" = HEAD ] && ! git -C "$repo_root" diff --quiet HEAD; then
        git -C "$repo_root" diff --binary HEAD | git -C "$work/src" apply --index --binary
    fi
    git -C "$work/src" checkout --quiet -B repro
    git -C "$work/src" -c user.email=repro@local -c user.name=repro \
        commit --quiet --allow-empty -m 'repro snapshot'
    snap_sha=$(git -C "$work/src" rev-parse HEAD)
    git clone --quiet --mirror "$work/src" "$work/fdroiddata/${APPID}.git"
    git -C "$work/fdroiddata/${APPID}.git" symbolic-ref HEAD refs/heads/repro

    # Override the recipe's last Builds entry: commit -> snapshot, versionCode
    # -> the GitHub APK's vc (this beta vc may not exist in the recipe at all
    # since the recipe only tracks F-Droid-released stable vc's). Repo ->
    # local bare repo.
    REPRO_VC=$(podman run --rm -i -v "$work":/w:z "$IMG" python3 - \
        "/w/fdroiddata/metadata/${APPID}.yml" "/w/fdroiddata/${APPID}.git" \
        "$snap_sha" "$apk_vc" <<'PY'
import sys, yaml
recipe, bundle, sha, vc = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
with open(recipe) as f:
    data = yaml.safe_load(f)
data['Repo'] = bundle
last = data['Builds'][-1]
last['commit'] = sha
last['versionCode'] = vc
with open(recipe, 'w') as f:
    yaml.dump(data, f, sort_keys=False)
print(vc)
PY
    )

    log "fdroid build --on-server --test $APPID:$REPRO_VC (rebuild GitHub APK from source)"
    podman run --rm --network=host -v "$work":/w:z -w /w/fdroiddata "$IMG" \
        bash -euo pipefail -c "
            test -f /etc/profile.d/bsenv.sh && . /etc/profile.d/bsenv.sh
            export PATH=/opt/fdroidserver:\$PATH
            export PYTHONPATH=/opt/fdroidserver\${PYTHONPATH:+:\$PYTHONPATH}
            git config --global --add safe.directory '*'
            # Pre-clone the bare repo to dodge fdroidserver's py3.13 bug:
            # set_FDroidPopen_env runs before prepare_source, so
            # get_source_date_epoch returns None when build/<appid> doesn't
            # yet exist -> TypeError when assigning None to os.environ[].
            mkdir -p build
            git clone --quiet /w/fdroiddata/${APPID}.git build/${APPID}
            git -C build/${APPID} checkout --quiet ${snap_sha}
            git -C build/${APPID} submodule update --init --recursive --depth 1 \
                >/dev/null 2>&1 || git -C build/${APPID} submodule update --init --recursive
            fdroid build --on-server --test --no-tarball --refresh-scanner $APPID:$REPRO_VC
        " > "$work/build.log" 2>&1 \
        || { tail -80 "$work/build.log"; die "fdroid build failed (full log: $work/build.log)"; }
    cp "$work"/fdroiddata/tmp/${APPID}_${REPRO_VC}.apk "$work/local.apk"

    log "graft GitHub signature onto local build (apksigcopier — same tool fdroid verify uses)"
    podman run --rm -v "$work":/w:z "$IMG" \
        apksigcopier copy /w/github.apk /w/local.apk /w/local_signed.apk \
        || die "apksigcopier graft failed — local APK content does not match GitHub APK"

    log "diffoscope github.apk vs local_signed.apk (full APK byte-comparison)"
    if diffoscope_apks github.apk local_signed.apk; then
        log "RESULT: VERIFIED — GitHub APK matches local F-Droid-env build (signature grafted via apksigcopier)"
        exit 0
    fi
    log "RESULT: MISMATCH — see diffoscope output above"
    exit 1
    ;;

  *)
    die "unknown mode '$mode' (expected: determinism | verify)"
    ;;
esac
