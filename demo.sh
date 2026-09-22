#!/usr/bin/env bash
# jev-guard demo: assumes the app is already running on 127.0.0.1:8080
#   terminal 1:  mvn spring-boot:run          (mock mode, no key needed)
#   terminal 2:  ./demo.sh
set -e
BASE=http://127.0.0.1:8080

echo "== 1. simulate 5 transfer events =="
curl -s -X POST "$BASE/v1/simulate?count=5" | head -c 2000
echo; echo

echo "== 2. metrics (latency measured, not promised) =="
curl -s "$BASE/v1/metrics"
echo; echo

echo "== 3. latest decisions (addresses masked) =="
curl -s "$BASE/v1/decisions?limit=3" | head -c 2000
echo
