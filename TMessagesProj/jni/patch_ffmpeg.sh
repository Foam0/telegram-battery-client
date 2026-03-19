#!/bin/bash

set -e

[ -f ffmpeg/include/dav1d/dav1d.h ] && exit

patch -d ffmpeg -p1 < patches/ffmpeg/0001-compilation-magic.patch
patch -d ffmpeg -p1 < patches/ffmpeg/0002-compilation-magic-2.patch

function cp {
	install -D $@
}

cp ffmpeg/libavformat/dv.h ffmpeg/build/arm64-v8a/include/libavformat/dv.h
cp ffmpeg/libavformat/isom.h ffmpeg/build/arm64-v8a/include/libavformat/isom.h
cp ffmpeg/libavformat/dv.h ffmpeg/build/armeabi-v7a/include/libavformat/dv.h
cp ffmpeg/libavformat/isom.h ffmpeg/build/armeabi-v7a/include/libavformat/isom.h
cp ffmpeg/libavformat/dv.h ffmpeg/build/x86/include/libavformat/dv.h
cp ffmpeg/libavformat/isom.h ffmpeg/build/x86/include/libavformat/isom.h
cp ffmpeg/libavformat/dv.h ffmpeg/build/x86_64/include/libavformat/dv.h
cp ffmpeg/libavformat/isom.h ffmpeg/build/x86_64/include/libavformat/isom.h

cp ffmpeg/libavcodec/bytestream.h ffmpeg/build/arm64-v8a/include/libavcodec/bytestream.h
cp ffmpeg/libavcodec/bytestream.h ffmpeg/build/armeabi-v7a/include/libavcodec/bytestream.h
cp ffmpeg/libavcodec/bytestream.h ffmpeg/build/x86/include/libavcodec/bytestream.h
cp ffmpeg/libavcodec/bytestream.h ffmpeg/build/x86_64/include/libavcodec/bytestream.h

cp ffmpeg/libavcodec/get_bits.h ffmpeg/build/arm64-v8a/include/libavcodec/get_bits.h
cp ffmpeg/libavcodec/get_bits.h ffmpeg/build/armeabi-v7a/include/libavcodec/get_bits.h
cp ffmpeg/libavcodec/get_bits.h ffmpeg/build/x86/include/libavcodec/get_bits.h
cp ffmpeg/libavcodec/get_bits.h ffmpeg/build/x86_64/include/libavcodec/get_bits.h

cp ffmpeg/libavcodec/golomb.h ffmpeg/build/arm64-v8a/include/libavcodec/golomb.h
cp ffmpeg/libavcodec/golomb.h ffmpeg/build/armeabi-v7a/include/libavcodec/golomb.h
cp ffmpeg/libavcodec/golomb.h ffmpeg/build/x86/include/libavcodec/golomb.h
cp ffmpeg/libavcodec/golomb.h ffmpeg/build/x86_64/include/libavcodec/golomb.h

cp ffmpeg/libavcodec/vlc.h ffmpeg/build/arm64-v8a/include/libavcodec/vlc.h
cp ffmpeg/libavcodec/vlc.h ffmpeg/build/armeabi-v7a/include/libavcodec/vlc.h
cp ffmpeg/libavcodec/vlc.h ffmpeg/build/x86/include/libavcodec/vlc.h
cp ffmpeg/libavcodec/vlc.h ffmpeg/build/x86_64/include/libavcodec/vlc.h

cp ffmpeg/libavutil/intmath.h ffmpeg/build/arm64-v8a/include/libavutil/intmath.h
cp ffmpeg/libavutil/intmath.h ffmpeg/build/armeabi-v7a/include/libavutil/intmath.h
cp ffmpeg/libavutil/intmath.h ffmpeg/build/x86/include/libavutil/intmath.h
cp ffmpeg/libavutil/intmath.h ffmpeg/build/x86_64/include/libavutil/intmath.h

# Create the merged ffmpeg/include/ directory that voip/CMakeLists.txt expects.
# This mirrors the upstream prebuilt layout (ffmpeg/include/ contains libvpx/,
# dav1d/, and all ffmpeg headers). Use arm64-v8a as the canonical set since
# public API headers are architecture-independent.
unset -f cp  # restore built-in cp
rm -rf ffmpeg/include
cp -r ffmpeg/build/arm64-v8a/include/. ffmpeg/include/
# libvpx installs headers under vpx/; rename to libvpx/ to match upstream layout
cp -r libvpx/build/arm64-v8a/include/vpx ffmpeg/include/libvpx
# dav1d headers
cp -r dav1d/build/arm64-v8a/include/. ffmpeg/include/

echo "ffmpeg/include/ created with libavutil, libavcodec, libvpx, dav1d headers"
