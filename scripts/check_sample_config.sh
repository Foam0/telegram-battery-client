#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SING_BOX_VERSION="${SING_BOX_VERSION:-v1.13.14}"
SRC="$ROOT/third_party/sing-box"
BIN="$ROOT/build/sing-box"

mkdir -p "$ROOT/third_party" "$ROOT/build"
if [[ ! -d "$SRC/.git" ]]; then
  git clone --depth 1 --branch "$SING_BOX_VERSION" https://github.com/SagerNet/sing-box.git "$SRC"
else
  git -C "$SRC" fetch --depth 1 origin "refs/tags/$SING_BOX_VERSION:refs/tags/$SING_BOX_VERSION" >/dev/null 2>&1 || true
  git -C "$SRC" checkout -f "$SING_BOX_VERSION" >/dev/null
fi

TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0"
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=$SING_BOX_VERSION -X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid= -checklinkname=0"

(
  cd "$SRC"
  go build -trimpath -buildvcs=false -tags "$TAGS" -ldflags "$LDFLAGS" -o "$BIN" ./cmd/sing-box
)

"$BIN" check -c "$ROOT/config/sample-vless-config.json"
"$BIN" check -c "$ROOT/config/sample-vless-socks-config.json"
