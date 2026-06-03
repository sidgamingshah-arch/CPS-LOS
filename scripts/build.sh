#!/usr/bin/env bash
# Build all backend service jars and the frontend bundle.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
echo ">> Building backend (Maven)…"
mvn -q -DskipTests package
echo ">> Building frontend (Vite)…"
cd frontend && npm install && npm run build
echo ">> Done. Run backend with scripts/run-all.sh (or docker compose up --build)."
