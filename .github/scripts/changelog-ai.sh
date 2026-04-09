#!/bin/bash
# changelog-ai.sh — produce a user-facing changelog for a tag/ref range.
#
# Usage:
#   changelog-ai.sh <prev-ref> <curr-ref> [<out-path>]
#   changelog-ai.sh ''         <curr-ref> [<out-path>]   # first release
#
# Wraps changelog-diff.sh + Kilo (kilo-auto/free). Falls back to the
# === CODE FEATURES === block bulleted, then to a maintenance-release line
# when even that is empty. Output goes to <out-path> if given, else stdout.
# Diagnostics go to stderr so callers can capture only the body.

set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 <prev-ref> <curr-ref> [<out-path>]" >&2
    exit 1
fi

PREV_REF="$1"
CURR_REF="$2"
OUT_PATH="${3:-}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
SCRIPT_DIR="$REPO_ROOT/.github/scripts"
PROMPT_FILE="$SCRIPT_DIR/changelog-prompt.md"
DIFF_FILE="$(mktemp)"
REQ_FILE="$(mktemp)"
trap 'rm -f "$DIFF_FILE" "$REQ_FILE"' EXIT

if [[ -n "$PREV_REF" ]]; then
    bash "$SCRIPT_DIR/changelog-diff.sh" "$PREV_REF" "$CURR_REF" > "$DIFF_FILE"
else
    bash "$SCRIPT_DIR/changelog-diff.sh" "$CURR_REF" > "$DIFF_FILE"
fi
echo "Diff size: $(wc -c < "$DIFF_FILE") bytes" >&2

jq -n \
    --rawfile sys "$PROMPT_FILE" \
    --rawfile diff "$DIFF_FILE" \
    '{
      model: "kilo-auto/free",
      messages: [
        {role: "system", content: $sys},
        {role: "user", content: $diff}
      ]
    }' > "$REQ_FILE"

# Retry Kilo on transient errors: HTTP 429 (rate-limited — observed on
# the kilo-auto/free tier when the upstream provider hits its concurrency
# cap), HTTP 502/503/504 (gateway hiccups), and curl-side network errors
# (DNS, TLS, timeout). Backoff is short on purpose — the release pipeline
# is already on the critical path, and a transient blip usually clears
# inside 30-60s. On every non-recoverable empty response we log the HTTP
# status + a truncated body so post-mortems don't require reproducing the
# call locally.
KILO_MAX_ATTEMPTS="${KILO_MAX_ATTEMPTS:-3}"
KILO_BACKOFFS=(0 15 45)  # cumulative wait BEFORE attempt N (0 = no wait first try)
RESP=''
HTTP=''
for attempt in $(seq 1 "$KILO_MAX_ATTEMPTS"); do
    sleep "${KILO_BACKOFFS[$((attempt - 1))]:-60}"
    # -w '\n%{http_code}' separates body and status with a final newline;
    # tail -1 → status, head -n -1 → body. Works whether the body is
    # 0-byte or many KB.
    RAW=$(curl -sL --max-time 120 -X POST \
        -H "Content-Type: application/json" \
        --data-binary "@$REQ_FILE" \
        -w '\n%{http_code}' \
        https://api.kilo.ai/api/gateway/v1/chat/completions) || RAW=''
    if [[ -z "$RAW" ]]; then
        HTTP='000'
        RESP=''
    else
        HTTP=$(printf '%s' "$RAW" | tail -n1)
        RESP=$(printf '%s' "$RAW" | sed '$d')
    fi
    case "$HTTP" in
        200)
            break
            ;;
        429|502|503|504|000)
            # transient — retry if attempts remain
            if [[ "$attempt" -lt "$KILO_MAX_ATTEMPTS" ]]; then
                echo "Kilo attempt $attempt/$KILO_MAX_ATTEMPTS: HTTP $HTTP — retrying" >&2
                continue
            fi
            echo "Kilo attempt $attempt/$KILO_MAX_ATTEMPTS: HTTP $HTTP (exhausted)" >&2
            ;;
        *)
            # 400/401/4xx etc — won't get better on retry, bail out
            echo "Kilo attempt $attempt: HTTP $HTTP (non-retryable)" >&2
            break
            ;;
    esac
done

BODY=$(printf '%s' "$RESP" | jq -r '.choices[0].message.content // empty' 2>/dev/null) || BODY=''

if [[ -z "$BODY" ]]; then
    echo "AI changelog empty — using grep-based fallback" >&2
    # Surface enough of the actual server response that a post-mortem
    # doesn't require replaying the curl. Avoids "AI changelog empty"
    # being the only signal in CI logs.
    echo "  last HTTP=$HTTP, response excerpt:" >&2
    printf '%s' "$RESP" | head -c 500 | sed 's/^/    /' >&2
    echo >&2
    FEATURES=$(awk '
        /^=== CODE FEATURES ===$/ { capture=1; next }
        /^=== / { capture=0 }
        capture && NF { print }
    ' "$DIFF_FILE")
    if [[ -z "$FEATURES" || "$FEATURES" == "(none)" ]]; then
        BODY="- Maintenance release: no user-facing changes."
    else
        BODY=$(printf '%s\n' "$FEATURES" | sed 's/^/- /')
    fi
else
    echo "AI changelog: $(printf '%s' "$BODY" | wc -c) bytes" >&2
fi

if [[ -n "$OUT_PATH" ]]; then
    printf '%s\n' "$BODY" > "$OUT_PATH"
    echo "Final changelog: $(wc -c < "$OUT_PATH") bytes, $(wc -l < "$OUT_PATH") lines" >&2
else
    printf '%s\n' "$BODY"
fi
