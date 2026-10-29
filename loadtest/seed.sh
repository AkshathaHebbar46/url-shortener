#!/usr/bin/env bash
#
# Seeds the shortener with N distinct URLs and writes the resulting short codes
# to a CSV for JMeter's CSV Data Set Config.
#
# The app MUST be started with rate limiting off, or this stops at 10 requests:
#   ./gradlew bootRun --args='--shortener.rate-limit.enabled=false'
#
# Usage:
#   ./seed.sh                        # 5000 codes into codes.csv
#   COUNT=20000 OUT=big.csv ./seed.sh
#
set -euo pipefail

HOST=${HOST:-http://localhost:8080}
COUNT=${COUNT:-5000}
OUT=${OUT:-codes.csv}
PARALLEL=${PARALLEL:-8}

# Destinations resolve to a public IP without a DNS lookup, so seeding does not
# depend on the network, and the unique path per row keeps rows distinct.
DEST_BASE=${DEST_BASE:-https://93.184.216.34/seed}

echo "seeding $COUNT urls into $OUT via $HOST"
: > "$OUT"

seed_one() {
  local i=$1
  local code
  code=$(curl -fsS -X POST "$HOST/api/v1/shorten" \
      -H 'Content-Type: application/json' \
      -d "{\"longUrl\":\"$DEST_BASE/$i\"}" \
    | sed -n 's/.*"shortCode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

  if [ -z "$code" ]; then
    echo "FAILED at $i — is rate limiting off?" >&2
    return 1
  fi
  echo "$code"
}
export -f seed_one
export HOST DEST_BASE

seq 1 "$COUNT" | xargs -P "$PARALLEL" -I{} bash -c 'seed_one {}' >> "$OUT"

WRITTEN=$(wc -l < "$OUT" | tr -d ' ')
echo "wrote $WRITTEN codes to $OUT"

if [ "$WRITTEN" -ne "$COUNT" ]; then
  echo "WARNING: expected $COUNT, got $WRITTEN. Do not quote a run from a short dataset." >&2
  exit 1
fi
