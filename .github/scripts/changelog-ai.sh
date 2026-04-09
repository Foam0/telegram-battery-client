#!/bin/bash
# changelog-ai.sh — produce a user-facing changelog for a tag/ref range.
#
# Usage:
#   changelog-ai.sh <prev-ref> <curr-ref> [<out-path>]
#   changelog-ai.sh ''         <curr-ref> [<out-path>]   # first release
#
# Wraps changelog-diff.sh + OpenRouter
# (https://openrouter.ai/api/v1/chat/completions). The model is read from
# $OPENROUTER_MODEL (default `openrouter/free` — OpenRouter's free
# auto-router). If the configured model returns 404 / "model not found"
# (OpenRouter occasionally retires models), the request is retried once
# against `openrouter/free` so a stale pin doesn't poison a release.
# Auth via $OPENROUTER_API_KEY; when unset the AI call is skipped entirely
# (one less guaranteed 401) and the grep-based fallback runs immediately.
# On AI failure / empty body, falls back to the === CODE FEATURES === block
# bulleted, then to a maintenance-release line when even that is empty.
# Output goes to <out-path> if given, else stdout. Diagnostics go to stderr
# so callers can capture only the body.

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

DEFAULT_MODEL="openrouter/free"
PRIMARY_MODEL="${OPENROUTER_MODEL:-$DEFAULT_MODEL}"
OPENROUTER_URL="https://openrouter.ai/api/v1/chat/completions"

# Retry on transient errors: HTTP 429 (rate-limited — observed on free tiers
# when the upstream provider hits its concurrency cap), HTTP 502/503/504
# (gateway hiccups), and curl-side network errors (DNS, TLS, timeout).
# 401/403 (bad/missing key) and other 4xx bail immediately — won't recover
# on retry. 404 / "model not found" trigger the outer model-fallback loop
# (try $OPENROUTER_MODEL first, then $DEFAULT_MODEL). Backoff is short on
# purpose — release pipeline is on the critical path, transient blips
# usually clear inside 30-60s. On every non-recoverable empty response we
# log HTTP status + truncated body so post-mortems don't require local
# reproduction.
AI_MAX_ATTEMPTS="${AI_MAX_ATTEMPTS:-3}"
AI_BACKOFFS=(0 15 45)  # cumulative wait BEFORE attempt N (0 = no wait first try)

RESP=''
HTTP=''
BODY=''

call_model() {
    # $1 = model id. Sets RESP, HTTP. Returns 0 on HTTP 200, 1 otherwise
    # (caller distinguishes model-not-found vs hard fail from HTTP code).
    local model="$1"
    jq -n \
        --rawfile sys "$PROMPT_FILE" \
        --rawfile diff "$DIFF_FILE" \
        --arg model "$model" \
        '{
          model: $model,
          messages: [
            {role: "system", content: $sys},
            {role: "user", content: $diff}
          ]
        }' > "$REQ_FILE"

    local attempt RAW
    for attempt in $(seq 1 "$AI_MAX_ATTEMPTS"); do
        sleep "${AI_BACKOFFS[$((attempt - 1))]:-60}"
        RAW=$(curl -sL --max-time 120 -X POST \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $OPENROUTER_API_KEY" \
            --data-binary "@$REQ_FILE" \
            -w '\n%{http_code}' \
            "$OPENROUTER_URL") || RAW=''
        if [[ -z "$RAW" ]]; then
            HTTP='000'
            RESP=''
        else
            HTTP=$(printf '%s' "$RAW" | tail -n1)
            RESP=$(printf '%s' "$RAW" | sed '$d')
        fi
        case "$HTTP" in
            200)
                return 0
                ;;
            429|502|503|504|000)
                if [[ "$attempt" -lt "$AI_MAX_ATTEMPTS" ]]; then
                    echo "OpenRouter [$model] attempt $attempt/$AI_MAX_ATTEMPTS: HTTP $HTTP — retrying" >&2
                    continue
                fi
                echo "OpenRouter [$model] attempt $attempt/$AI_MAX_ATTEMPTS: HTTP $HTTP (exhausted)" >&2
                return 1
                ;;
            *)
                # 400/401/403/404/etc — won't get better on retry. Surface
                # the status; caller decides whether to swap models.
                echo "OpenRouter [$model] attempt $attempt: HTTP $HTTP (non-retryable)" >&2
                return 1
                ;;
        esac
    done
    return 1
}

is_model_not_found() {
    # OpenRouter returns 404 for unknown models, and occasionally 400 with
    # an error.message naming the model. Treat both as "swap to fallback".
    if [[ "$HTTP" == "404" ]]; then
        return 0
    fi
    if [[ "$HTTP" == "400" ]]; then
        local msg
        msg=$(printf '%s' "$RESP" | jq -r '.error.message // empty' 2>/dev/null || true)
        if [[ "$msg" == *[Mm]odel* ]]; then
            return 0
        fi
    fi
    return 1
}

if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
    echo "OPENROUTER_API_KEY not set — skipping AI call, using grep-based fallback" >&2
else
    if call_model "$PRIMARY_MODEL"; then
        :
    elif [[ "$PRIMARY_MODEL" != "$DEFAULT_MODEL" ]] && is_model_not_found; then
        echo "model '$PRIMARY_MODEL' rejected (HTTP $HTTP) — retrying with $DEFAULT_MODEL" >&2
        call_model "$DEFAULT_MODEL" || true
    fi
    BODY=$(printf '%s' "$RESP" | jq -r '.choices[0].message.content // empty' 2>/dev/null) || BODY=''
fi

if [[ -z "$BODY" ]]; then
    echo "AI changelog empty — using grep-based fallback" >&2
    if [[ -n "${OPENROUTER_API_KEY:-}" ]]; then
        echo "  last HTTP=$HTTP, response excerpt:" >&2
        printf '%s' "$RESP" | head -c 500 | sed 's/^/    /' >&2
        echo >&2
    fi
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
