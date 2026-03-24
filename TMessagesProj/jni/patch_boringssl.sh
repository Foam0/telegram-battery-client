#!/bin/bash
set -e

[ -f boringssl/ssl/aes_ige.c ] && grep -qw AES_ige_encrypt boringssl/include/openssl/aes.h && exit

# Apply AES-IGE mode patch (needed for Telegram's MTProto protocol).
# Base BoringSSL does not include AES-IGE; this adds ssl/aes_ige.c
# and registers it in gen/sources.cmake.
patch -d boringssl -p1 < patches/boringssl/0001-add-AES-IGE-mode.patch

echo "BoringSSL: AES-IGE patch applied"
