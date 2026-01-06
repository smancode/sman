#!/bin/bash

# SiliconMan Agent 停止脚本
# 功能：检查进程、优雅停止、强制终止、清理worker进程

# 配置
APP_NAME="sman-agent"
APP_PORT=8080
PID_FILE="app.pid"
LOG_FILE="logs/app.log"

echo "🛑 停止 SiliconMan Agent..."
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

    # 清理可能残留的worker进程
    echo "🧹 清理残留的Claude Code worker进程..."
    pkill -f "claude-code-mock" 2>/dev/null || true
    echo "✅ Worker进程清理完成"

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

        # 清理worker进程
        echo "🧹 清理Claude Code worker进程..."
        pkill -f "claude-code-mock" 2>/dev/null || true
        echo "✅ Worker进程清理完成"

        exit 0
    fi

    echo "⏳ 等待进程停止... ($((WAIT_TIME + 1))/$MAX_WAIT)"
    sleep 1
    WAIT_TIME=$((WAIT_TIME + 1))
done

# 5. 强制停止 (SIGKILL)
echo "⚠️  优雅停止超时，强制终止进程..."
kill -9 $APP_PID

# 清理worker进程
echo "🧹 强制清理Claude Code worker进程..."
pkill -9 -f "claude-code-mock" 2>/dev/null || true

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

# 7. 清理可能残留的worker工作目录
echo "🧹 清理worker工作目录..."
WORK_DIR_BASE="data/claude-code-workspaces"
if [ -d "$WORK_DIR_BASE" ]; then
    WORKER_COUNT=$(ls -1 "$WORK_DIR_BASE" 2>/dev/null | wc -l)
    echo "📁 发现 $WORKER_COUNT 个worker工作目录"
    echo "💡 保留工作目录以便下次快速启动"
    echo "   如需完全清理: rm -rf $WORK_DIR_BASE"
fi

# 8. 清理可能残留的向量索引锁文件
echo "🧹 清理索引锁文件..."
find data -name "*.lock" -type f -delete 2>/dev/null || true

echo "🏁 SiliconMan Agent 已完全停止"
echo "📋 查看最后日志: tail $LOG_FILE"
echo ""
echo "💡 提示:"
echo "   - Worker工作目录已保留，下次启动可复用"
echo "   - 会话历史已保存在: data/sessions/"
echo "   - 向量索引已保存在: data/vector-index/"
