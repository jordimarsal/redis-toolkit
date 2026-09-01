#!/usr/bin/env bash
set -u
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
TIMEOUT_S="${SMOKE_TIMEOUT_S:-60}"

fail() { echo "SMOKE FAIL: $*" >&2; exit "${2:-1}"; }

deadline=$(( $(date +%s) + TIMEOUT_S ))
while :; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY_URL/metrics" || true)
  if [ "$code" = "200" ]; then break; fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    fail "gateway not ready after ${TIMEOUT_S}s at $GATEWAY_URL (check 'docker compose logs gateway')" 2
  fi
  sleep 1
done

body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT

resp=$(curl -s -D - -o "$body_file" -X POST "$GATEWAY_URL/v1/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"stub","prompt":"smoke"}')
code=$(printf '%s\n' "$resp" | head -1 | tr -d '\r' | awk '{print $2}')
limit_header=$(printf '%s\n' "$resp" | grep -i '^x-ratelimit-limit:' | tail -1 | tr -d '\r' | awk '{print $2}' || true)
[ "$code" = "200" ] || fail "phase1 expected HTTP 200, got '$code'"
[ -n "${limit_header:-}" ] || fail "phase1 missing X-RateLimit-Limit header"
grep -q . "$body_file" || fail "phase1 empty completion body"
echo "phase1 OK: 200 + X-RateLimit-Limit=$limit_header"

seen_429=0
for i in $(seq 1 65); do
  resp=$(curl -s -D - -o /dev/null -X POST "$GATEWAY_URL/v1/completions" \
    -H 'Content-Type: application/json' \
    -d "{\"model\":\"stub\",\"prompt\":\"smoke-$i\"}")
  code=$(printf '%s\n' "$resp" | head -1 | tr -d '\r' | awk '{print $2}')
  if [ "$code" = "429" ]; then
    retry_after=$(printf '%s\n' "$resp" | grep -i '^retry-after:' | tail -1 | tr -d '\r' | awk '{print $2}' || true)
    case "${retry_after:-}" in
      ''|*[!0-9]*) fail "phase2 Retry-After not a positive integer: '${retry_after:-<absent>}'" ;;
    esac
    [ "$retry_after" -gt 0 ] || fail "phase2 Retry-After must be > 0, got '$retry_after'"
    seen_429=1
    echo "phase2 OK: 429 + Retry-After=$retry_after (request #$i)"
    break
  fi
done
[ "$seen_429" = "1" ] || fail "no 429 observed within 65 requests over the limit"

echo "SMOKE PASS"
