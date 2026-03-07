#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/crates/smanclaw-desktop"

PORT_PIDS="$(lsof -ti tcp:5173 || true)"
if [ -n "$PORT_PIDS" ]; then
    echo "Killing process on port 5173: $PORT_PIDS"
    kill $PORT_PIDS || true
    sleep 1
fi

cd "$APP_DIR"
npm run tauri:dev
