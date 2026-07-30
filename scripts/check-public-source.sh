#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failed=0

if git ls-files | rg -i '(^|/)(API_KEYS|local\.properties|.*\.keystore|.*\.jks|.*\.p12|.*\.p8|.*service-account.*\.json|firebase-adminsdk.*\.json|private-configs\..*)$'; then
  echo "A private configuration or credential file is tracked." >&2
  failed=1
fi

if git grep -n -I -E 'vless://[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}@' -- . ':!scripts/check-public-source.sh'; then
  echo "A complete VLESS URI is present in tracked source." >&2
  failed=1
fi

check_sample() {
  local file="$1"
  jq -e '
    [.outbounds[] | select(.type == "vless")][0] as $vless
    | $vless.server == "192.0.2.1"
      and $vless.server_port == 443
      and $vless.uuid == "00000000-0000-4000-8000-000000000000"
      and $vless.tls.server_name == "example.com"
      and $vless.tls.reality.public_key == "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      and $vless.tls.reality.short_id == "0123456789abcdef"
  ' "$file" >/dev/null
}

for sample in config/sample-vless-config.json config/sample-vless-socks-config.json; do
  if ! check_sample "$sample"; then
    echo "$sample does not contain the approved non-routable placeholders." >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "Public source checks passed."
