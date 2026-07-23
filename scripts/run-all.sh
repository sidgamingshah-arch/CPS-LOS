#!/usr/bin/env bash
# Launch all Helix services locally against SQLite. Logs to /tmp/helix-*.log.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HELIX_DATA_DIR="${HELIX_DATA_DIR:-$ROOT/data}"
mkdir -p "$HELIX_DATA_DIR"
# Separate, externalised LLM endpoint config (helix.llm.*). Absolute path so it
# resolves no matter where this script is launched from. Provider defaults to
# `none` (deterministic, offline) inside the file; set HELIX_LLM_* env vars (or
# edit config/llm.yml) to point at a real model — env always overrides the file.
export HELIX_LLM_CONFIG="${HELIX_LLM_CONFIG:-$ROOT/config/llm.yml}"
# G7: enable the test-only fail-closed-posture outage-simulation hook in the local/e2e stack
# (prod default is false; the endpoint does not exist unless this is true).
export HELIX_RBAC_SIMULATE_OUTAGE_ENABLED="${HELIX_RBAC_SIMULATE_OUTAGE_ENABLED:-true}"
# Notify: enable the test-only enqueue-with-schedule hook in the local/e2e stack
# (prod default is false; the endpoint does not exist unless this is true).
export HELIX_NOTIFY_TEST_ENQUEUE_ENABLED="${HELIX_NOTIFY_TEST_ENQUEUE_ENABLED:-true}"

declare -A SERVICES=(
  [config-service]=8081
  [counterparty-service]=8082
  [origination-service]=8083
  [risk-service]=8084
  [decision-service]=8085
  [portfolio-service]=8086
  [copilot-service]=8087
  [limit-service]=8088
  [workflow-service]=8089
  [gateway-service]=8080
)

start() {
  local svc="$1" port="$2"
  echo "starting $svc on $port"
  SERVER_PORT="$port" java -jar "$ROOT/$svc/target/$svc.jar" > "/tmp/helix-$svc.log" 2>&1 &
  echo $! > "/tmp/helix-$svc.pid"
}

# config first (others fall back gracefully if it lags)
start config-service 8081
for svc in counterparty-service origination-service risk-service decision-service portfolio-service copilot-service limit-service workflow-service gateway-service; do
  start "$svc" "${SERVICES[$svc]}"
done

echo "All services launching. Waiting for health..."
for port in 8081 8082 8083 8084 8085 8086 8087 8088 8089 8080; do
  curl -s --retry 60 --retry-connrefused --retry-delay 1 "http://localhost:$port/actuator/health" >/dev/null && echo "  :$port UP"
done
echo "Helix is up. Gateway at http://localhost:8080"
