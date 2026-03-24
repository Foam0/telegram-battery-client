#!/bin/bash

set -e

# Apply patches to ffmpeg source (idempotent)
if ! grep -q ff_mov_get_lpcm_codec_id ffmpeg/libavformat/isom.h 2>/dev/null; then
    patch -d ffmpeg -p1 < patches/ffmpeg/0001-compilation-magic.patch
    patch -d ffmpeg -p1 < patches/ffmpeg/0002-compilation-magic-2.patch
fi

# Determine which ABIs to process (default: all 4)
if [ $# -eq 0 ]; then
    ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
else
    ABIS=("$@")
fi

function cp {
	install -D $@
}

# Copy private headers into each ABI's build include directory
for ABI in "${ABIS[@]}"; do
    cp ffmpeg/libavformat/dv.h   "ffmpeg/build/${ABI}/include/libavformat/dv.h"
    cp ffmpeg/libavformat/isom.h "ffmpeg/build/${ABI}/include/libavformat/isom.h"
    cp ffmpeg/libavcodec/bytestream.h "ffmpeg/build/${ABI}/include/libavcodec/bytestream.h"
    cp ffmpeg/libavcodec/get_bits.h   "ffmpeg/build/${ABI}/include/libavcodec/get_bits.h"
    cp ffmpeg/libavcodec/golomb.h     "ffmpeg/build/${ABI}/include/libavcodec/golomb.h"
    cp ffmpeg/libavcodec/vlc.h        "ffmpeg/build/${ABI}/include/libavcodec/vlc.h"
    cp ffmpeg/libavutil/intmath.h     "ffmpeg/build/${ABI}/include/libavutil/intmath.h"
done

# Create the merged ffmpeg/include/ directory that voip/CMakeLists.txt expects.
# Only done once; use the first available ABI as the canonical header source since
# public API headers are architecture-independent.
if [ ! -f ffmpeg/include/dav1d/dav1d.h ]; then
    unset -f cp  # restore built-in cp
    CANONICAL_ABI="${ABIS[0]}"
    rm -rf ffmpeg/include
    cp -r "ffmpeg/build/${CANONICAL_ABI}/include/." ffmpeg/include/
    # libvpx installs headers under vpx/; rename to libvpx/ to match upstream layout
    cp -r "libvpx/build/${CANONICAL_ABI}/include/vpx" ffmpeg/include/libvpx
    # dav1d headers
    cp -r "dav1d/build/${CANONICAL_ABI}/include/." ffmpeg/include/
    echo "ffmpeg/include/ created with libavutil, libavcodec, libvpx, dav1d headers"
fi
