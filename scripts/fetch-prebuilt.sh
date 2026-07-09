#!/usr/bin/env bash
# Fetch the 9 prebuilt service jars from the rolling `prebuilt-latest` release
# into their target/ slots. Lets machines without Maven (or with the wrong JDK
# major) run scripts/run-all.sh without a local build.
set -euo pipefail

REPO="${HELIX_REPO:-sidgamingshah-arch/cps-los}"
TAG="${HELIX_PREBUILT_TAG:-prebuilt-latest}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

services=(config counterparty origination risk decision portfolio copilot limit gateway)

for s in "${services[@]}"; do
  url="https://github.com/${REPO}/releases/download/${TAG}/${s}-service.jar"
  target="${ROOT}/${s}-service/target/${s}-service.jar"
  mkdir -p "$(dirname "$target")"
  echo "fetching ${s}-service.jar"
  curl -L -fsS -o "$target" "$url"
done

echo "All 9 jars fetched to */target/. Now run: bash scripts/run-all.sh"
