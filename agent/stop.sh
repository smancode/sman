#!/bin/bash

# SmanAgent 停止脚本
# 功能：检查进程、优雅停止、强制终止

# 配置
APP_NAME="smanagent-agent"
APP_PORT=8080
PID_FILE="app.pid"
LOG_FILE="logs/app.log"

echo "🛑 停止 SmanAgent..."
echo "📅 停止时间: $(date)"

# 1. 检查PID文件
if [ ! -f "$PID_FILE" ]; then
    echo "⚠️  PID文件不存在: $PID_FILE"
    echo "🔍 尝试通过端口查找进程..."

    # 通过端口查找进程
    PORT_PID=$(lsof -ti:$APP_PORT 2>/dev/null || echo "")
    if [ ! -z "$PORT_PID" ]; then
        echo "📍 发现占用端口 $APP_PORT 的进程: $PORT_PID"
        echo $PORT_PID > $PID_FILE
    else
        echo "✅ 没有发现运行中的应用进程"
        exit 0
    fi
fi

# 2. 读取PID
APP_PID=$(cat $PID_FILE)
echo "📊 检查进程ID: $APP_PID"

# 3. 检查进程是否存在
if ! ps -p $APP_PID > /dev/null 2>&1; then
    echo "⚠️  进程 $APP_PID 不存在或已停止"
    rm -f $PID_FILE
    echo "✅ 清理PID文件完成"
    exit 0
fi

echo "🔍 发现运行中的进程: $APP_PID"

# 4. 优雅停止 (SIGTERM)
echo "🤝 尝试优雅停止进程..."
kill -TERM $APP_PID

# 等待进程优雅退出
WAIT_TIME=0
MAX_WAIT=10

while [ $WAIT_TIME -lt $MAX_WAIT ]; do
    if ! ps -p $APP_PID > /dev/null 2>&1; then
        echo "✅ 进程已优雅停止"
        rm -f $PID_FILE
        echo "🧹 清理PID文件完成"
        exit 0
    fi

    echo "⏳ 等待进程停止... ($((WAIT_TIME + 1))/$MAX_WAIT)"
    sleep 1
    WAIT_TIME=$((WAIT_TIME + 1))
done

# 5. 强制停止 (SIGKILL)
echo "⚠️  优雅停止超时，强制终止进程..."
kill -9 $APP_PID

# 再次检查
sleep 2
if ! ps -p $APP_PID > /dev/null 2>&1; then
    echo "✅ 进程已强制停止"
    rm -f $PID_FILE
    echo "🧹 清理PID文件完成"
else
    echo "❌ 进程停止失败，请手动处理"
    echo "💡 手动终止命令: kill -9 $APP_PID"
    exit 1
fi

# 6. 检查端口是否释放
echo "🔍 检查端口 $APP_PORT 是否已释放..."
sleep 1

if lsof -i:$APP_PORT > /dev/null 2>&1; then
    echo "⚠️  端口 $APP_PORT 仍被占用，可能需要额外清理"
    REMAINING_PID=$(lsof -ti:$APP_PORT 2>/dev/null || echo "")
    if [ ! -z "$REMAINING_PID" ]; then
        echo "🔪 清理剩余进程: $REMAINING_PID"
        kill -9 $REMAINING_PID
    fi
else
    echo "✅ 端口 $APP_PORT 已释放"
fi

echo "🏁 SmanAgent 已完全停止"
echo "📋 最后日志: tail $LOG_FILE"
