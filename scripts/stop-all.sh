#!/usr/bin/env bash
# Stop all Helix services started by run-all.sh.
for svc in config-service counterparty-service origination-service risk-service decision-service portfolio-service gateway-service; do
  if [ -f "/tmp/helix-$svc.pid" ]; then
    pid="$(cat "/tmp/helix-$svc.pid")"
    kill -9 "$pid" 2>/dev/null && echo "stopped $svc ($pid)" || true
    rm -f "/tmp/helix-$svc.pid"
  fi
done
# Belt and suspenders.
pkill -9 -f 'helix' 2>/dev/null || true
for svc in config counterparty origination risk decision portfolio gateway; do
  pkill -9 -f "$svc-service.jar" 2>/dev/null || true
done
echo "stop-all complete"
