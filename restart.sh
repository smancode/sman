#!/bin/bash
# rs.sh - 重启 SMAN

set -e

echo "🛑 停止旧的 OpenClaw..."
pkill -f "node.*openclaw.*18790" 2>/dev/null || true

echo "⏳ 等待进程结束..."
sleep 2

# 确认端口已释放
if lsof -i :18790 >/dev/null 2>&1; then
    echo "⚠️  端口 18790 仍被占用，强制清理..."
    lsof -ti :18790 | xargs kill -9 2>/dev/null || true
    sleep 1
fi

echo "🚀 启动 SMAN..."
cd "$(dirname "$0")"
pnpm run tauri dev
