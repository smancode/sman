#!/bin/bash
# SmanWeb Stop Script
# 停止 OpenClaw Gateway (18790) 和 SmanWeb 开发服务器

echo "🛑 Stopping SmanWeb services..."

# 杀掉 18790 端口的进程
if lsof -ti:18790 > /dev/null 2>&1; then
    echo "  Stopping OpenClaw Gateway (port 18790)..."
    kill $(lsof -ti:18790) 2>/dev/null || true
else
    echo "  OpenClaw Gateway not running"
fi

# 杀掉 5173 端口的进程
if lsof -ti:5173 > /dev/null 2>&1; then
    echo "  Stopping SmanWeb dev server (port 5173)..."
    kill $(lsof -ti:5173) 2>/dev/null || true
else
    echo "  SmanWeb dev server not running"
fi

sleep 1
echo ""
echo "✅ All services stopped"
