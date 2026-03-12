#!/bin/bash

set -e

MODE="${1:-app}"
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "🛑 停止旧的 OpenClaw..."
pkill -f "node.*openclaw.*18790" 2>/dev/null || true

echo "⏳ 等待进程结束..."
sleep 2

if lsof -i :18790 >/dev/null 2>&1; then
  echo "⚠️  端口 18790 仍被占用，强制清理..."
  lsof -ti :18790 | xargs kill -9 2>/dev/null || true
  sleep 1
fi

OPENCLAW_DIR="$HOME/project/openclaw"
if [ ! -d "$OPENCLAW_DIR" ]; then
  OPENCLAW_DIR="$HOME/projects/openclaw"
fi

NODE_BIN="$(command -v node || true)"
if [ -z "$NODE_BIN" ] && [ -x "/opt/homebrew/bin/node" ]; then
  NODE_BIN="/opt/homebrew/bin/node"
fi

SMAN_LOCAL_DIR="${SMAN_LOCAL_DIR:-$ROOT_DIR/.smanlocal}"
OPENCLAW_HOME="$SMAN_LOCAL_DIR/openclaw-home"
OPENCLAW_CONFIG="$OPENCLAW_HOME/.openclaw/openclaw.json"
FIXED_GATEWAY_TOKEN="sman-31244d65207dcced"

if [ "$MODE" = "gateway" ]; then
  if [ ! -d "$OPENCLAW_DIR" ]; then
    echo "❌ 未找到 OpenClaw 目录: $HOME/project/openclaw 或 $HOME/projects/openclaw"
    exit 1
  fi
  if [ ! -f "$OPENCLAW_DIR/openclaw.mjs" ]; then
    echo "❌ 未找到入口文件: $OPENCLAW_DIR/openclaw.mjs"
    exit 1
  fi
  if [ -z "$NODE_BIN" ]; then
    echo "❌ 未找到 node，请先安装 Node.js 或加入 PATH"
    exit 1
  fi
  mkdir -p "$OPENCLAW_HOME/.openclaw"
  GATEWAY_TOKEN="$FIXED_GATEWAY_TOKEN"
  "$NODE_BIN" -e "const fs=require('node:fs');const p=process.argv[1];const t=process.argv[2];let c={};try{if(fs.existsSync(p)){c=JSON.parse(fs.readFileSync(p,'utf8'));}}catch{};c.gateway=c.gateway||{};c.gateway.auth=c.gateway.auth||{};c.gateway.auth.mode='token';c.gateway.auth.token=t;fs.mkdirSync(require('node:path').dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(c,null,2));" "$OPENCLAW_CONFIG" "$GATEWAY_TOKEN"
  echo "🏠 OpenClaw HOME: $OPENCLAW_HOME"
  echo "🚀 从 $OPENCLAW_DIR 启动 OpenClaw Gateway..."
  cd "$OPENCLAW_DIR"
  echo "✅ 启动命令: $NODE_BIN openclaw.mjs gateway --port 18790 --allow-unconfigured --dev --token <hidden>"
  HOME="$OPENCLAW_HOME" \
  USERPROFILE="$OPENCLAW_HOME" \
  OPENCLAW_HOME="$OPENCLAW_HOME" \
  OPENCLAW_GATEWAY_TOKEN="$GATEWAY_TOKEN" \
  exec "$NODE_BIN" openclaw.mjs gateway --port 18790 --allow-unconfigured --dev --token "$GATEWAY_TOKEN"
fi

echo "🚀 启动 SMAN 前端与桌面端（18790 由 sidecar 自动拉起）..."
cd "$ROOT_DIR"
pnpm run tauri dev
