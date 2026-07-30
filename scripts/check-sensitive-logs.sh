#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

patterns=(
  'bytesToHex\(SharedConfig\.pushAuthKey\)'
  'UnifiedPush endpoint ='
  'FileLog\.[deiw]\([^\\n]*(phone =|short phone =|contact =|proxy_pass|proxy_password|vless://|sms code|SMS code|auth key|currentFile=| file=|message text =)'
)

failed=0
for pattern in "${patterns[@]}"; do
  if rg -n --glob '*.java' --glob '*.kt' --glob '!**/build/**' "$pattern" TMessagesProj TMessagesProj_App; then
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "Sensitive logging pattern found. Redact the value or gate it behind a safe diagnostic message." >&2
  exit 1
fi
