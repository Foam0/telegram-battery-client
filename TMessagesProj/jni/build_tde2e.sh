#!/bin/bash
# Build tde2e and tdutils from tdlib/td submodule.
# Must run AFTER build_boringssl.sh (requires BoringSSL headers and libs).
# Run from TMessagesProj/jni/ with NDK and NINJA_PATH env vars set.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNI_DIR="$SCRIPT_DIR"
TD_DIR="$JNI_DIR/td"
TDE2E_OUT_DIR="$JNI_DIR/tde2e/build"
BORINGSSL_DIR="$JNI_DIR/boringssl"

# ── Prerequisites ──────────────────────────────────────────────────────────────

if [[ ! -d "$TD_DIR" ]] || [[ -z "$(ls -A "$TD_DIR" 2>/dev/null)" ]]; then
    echo "Error: td submodule is empty. Run 'git submodule update --init TMessagesProj/jni/td' first."
    exit 1
fi

if [[ ! -f "$BORINGSSL_DIR/build/arm64-v8a/ssl/libssl.a" ]]; then
    echo "Error: BoringSSL not built. Run build_boringssl.sh first."
    exit 1
fi

if [[ -z "$NDK" ]]; then
    echo "Error: NDK environment variable is not set."
    exit 1
fi

if [[ -z "$NINJA_PATH" ]]; then
    echo "Error: NINJA_PATH environment variable is not set."
    exit 1
fi

# ── Step 1: Host build — regenerate td/td/generate/auto/ ─────────────────────
# TD_GENERATE_SOURCE_FILES=ON compiles the TL code generators and runs them,
# writing e2e_api.cpp/.h/.hpp (and all other TL output) into the source tree at
# td/td/generate/auto/. cmake returns early after this, before OpenSSL is needed.

echo "=== tde2e: generating TL source files (host build) ==="
HOST_BUILD_DIR="$TD_DIR/build-tde2e-host"
rm -rf "$HOST_BUILD_DIR"
mkdir -p "$HOST_BUILD_DIR"
cd "$HOST_BUILD_DIR"
cmake -DCMAKE_BUILD_TYPE=Release -DTD_GENERATE_SOURCE_FILES=ON "$TD_DIR"
cmake --build .
cd "$JNI_DIR"

# ── Step 2: Cross-compile for each Android ABI ────────────────────────────────
# BoringSSL is OpenSSL-compatible. We set OPENSSL_INCLUDE/CRYPTO/SSL vars directly
# so cmake's FindOpenSSL.cmake uses them without searching (bypassing the NDK
# toolchain's CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY restriction).

mkdir -p "$TDE2E_OUT_DIR"

for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
    [ -f "$TDE2E_OUT_DIR/$ABI/libtde2e.a" ] && continue

    echo "=== tde2e: building $ABI ==="
    BUILD_DIR="$TD_DIR/build-tde2e-$ABI"

    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    cmake -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
          -DANDROID_ABI="$ABI" \
          -DANDROID_PLATFORM=android-21 \
          -DANDROID_STL=c++_static \
          -DTD_E2E_ONLY=ON \
          -DOPENSSL_INCLUDE_DIR="$BORINGSSL_DIR/include" \
          -DOPENSSL_CRYPTO_LIBRARY="$BORINGSSL_DIR/build/$ABI/crypto/libcrypto.a" \
          -DOPENSSL_SSL_LIBRARY="$BORINGSSL_DIR/build/$ABI/ssl/libssl.a" \
          -GNinja -DCMAKE_MAKE_PROGRAM="$NINJA_PATH" \
          "$TD_DIR"

    cmake --build .

    # Locate built static libraries (paths vary by cmake binary-dir layout)
    LIBTDE2E=$(find "$BUILD_DIR" -name "libtde2e.a" | head -1)
    LIBTDUTILS=$(find "$BUILD_DIR" -name "libtdutils.a" | head -1)

    if [[ -z "$LIBTDE2E" ]]; then
        echo "Error: libtde2e.a not found in $BUILD_DIR"
        exit 1
    fi
    if [[ -z "$LIBTDUTILS" ]]; then
        echo "Error: libtdutils.a not found in $BUILD_DIR"
        exit 1
    fi

    mkdir -p "$TDE2E_OUT_DIR/$ABI"
    cp "$LIBTDE2E"   "$TDE2E_OUT_DIR/$ABI/libtde2e.a"
    cp "$LIBTDUTILS" "$TDE2E_OUT_DIR/$ABI/libtdutils.a"

    cd "$JNI_DIR"
done

echo "=== tde2e: done ==="
