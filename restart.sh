#!/bin/bash
# SmanWeb Restart Script
# 启动测试用 OpenClaw Gateway (18790) 和 SmanWeb 开发服务器

set -e

OPENCLAW_DIR=~/projects/openclaw
SMANWEB_DIR=~/projects/smanweb
GATEWAY_PORT=18790
GATEWAY_TOKEN="sman-31244d65207dcced"

echo "🦞 SmanWeb Restart Script"
echo "=========================="

# 杀掉已存在的进程
echo ""
echo "📋 Cleaning up existing processes..."

# 杀掉 18790 端口的进程
if lsof -ti:$GATEWAY_PORT > /dev/null 2>&1; then
    echo "  Killing process on port $GATEWAY_PORT..."
    kill $(lsof -ti:$GATEWAY_PORT) 2>/dev/null || true
    sleep 1
fi

# 杀掉 5173 端口的进程 (Vite dev server)
if lsof -ti:5173 > /dev/null 2>&1; then
    echo "  Killing Vite dev server on port 5173..."
    kill $(lsof -ti:5173) 2>/dev/null || true
    sleep 1
fi

# 启动 OpenClaw Gateway
echo ""
echo "🚀 Starting OpenClaw Gateway on port $GATEWAY_PORT..."

cd "$OPENCLAW_DIR"

# 在后台启动 Gateway
OPENCLAW_GATEWAY_PORT=$GATEWAY_PORT \
OPENCLAW_GATEWAY_AUTH_MODE=token \
OPENCLAW_GATEWAY_AUTH_TOKEN=$GATEWAY_TOKEN \
OPENCLAW_GATEWAY_BIND=loopback \
nohup pnpm start > /tmp/openclaw-gateway-18790.log 2>&1 &

GATEWAY_PID=$!
echo "  Gateway PID: $GATEWAY_PID"
echo "  Log: /tmp/openclaw-gateway-18790.log"

# 等待 Gateway 启动
echo "  Waiting for Gateway to start..."
sleep 3

# 检查 Gateway 是否启动成功
if lsof -ti:$GATEWAY_PORT > /dev/null 2>&1; then
    echo "  ✅ Gateway is running on port $GATEWAY_PORT"
else
    echo "  ❌ Gateway failed to start. Check log: /tmp/openclaw-gateway-18790.log"
    cat /tmp/openclaw-gateway-18790.log | tail -20
    exit 1
fi

# 启动 SmanWeb
echo ""
echo "🌐 Starting SmanWeb dev server..."

cd "$SMANWEB_DIR"

# 在后台启动 Vite
nohup pnpm dev > /tmp/smanweb-dev.log 2>&1 &

SMANWEB_PID=$!
echo "  SmanWeb PID: $SMANWEB_PID"
echo "  Log: /tmp/smanweb-dev.log"

# 等待 Vite 启动
echo "  Waiting for SmanWeb to start..."
sleep 3

# 检查 SmanWeb 是否启动成功
if lsof -ti:5173 > /dev/null 2>&1; then
    echo "  ✅ SmanWeb is running on port 5173"
else
    echo "  ❌ SmanWeb failed to start. Check log: /tmp/smanweb-dev.log"
    cat /tmp/smanweb-dev.log | tail -20
    exit 1
fi

echo ""
echo "=========================="
echo "✅ All services started!"
echo ""
echo "🔗 Open SmanWeb: http://localhost:5173"
echo ""
echo "📋 Connection Settings:"
echo "   Gateway URL: ws://127.0.0.1:$GATEWAY_PORT"
echo "   Token: $GATEWAY_TOKEN"
echo ""
echo "📝 Logs:"
echo "   Gateway: /tmp/openclaw-gateway-18790.log"
echo "   SmanWeb: /tmp/smanweb-dev.log"
echo ""
echo "🛑 To stop: ./stop.sh"
