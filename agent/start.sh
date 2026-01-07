#!/bin/bash

# SiliconMan Agent 启动脚本
# 基于 Claude Code 的代码分析 Agent
# 支持：进程池管理、健康检查、日志管理

set -e

# ========================================
# Java 环境配置
# ========================================
# 自动检测 JAVA_HOME（支持 macOS 和 Linux）
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS: 使用 /usr/libexec/java_home 自动查找 JDK 21
    export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null)
    # 如果找不到 JDK 21，尝试查找任何可用的 JDK
    if [ -z "$JAVA_HOME" ]; then
        export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null)
    fi
else
    # Linux: 常见路径
    if [ -d "/usr/lib/jvm/java-21-openjdk" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk"
    elif [ -d "/usr/lib/jvm/java-21" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21"
    else
        export JAVA_HOME="/usr/lib/jvm/default-java"
    fi
fi

# 获取脚本所在目录的绝对路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 配置
APP_NAME="sman-agent"
APP_PORT=8080
JAR_FILE="build/libs/${APP_NAME}-1.0.0.jar"
PID_FILE="app.pid"
LOG_FILE="logs/app.log"
HEALTH_CHECK_URL="http://localhost:$APP_PORT/api/claude-code/health"

# 编译模式（增量/全量）
BUILD_MODE="${BUILD_MODE:-incremental}"  # 默认增量编译
if [ "$1" = "--clean" ] || [ "$1" = "-c" ]; then
    BUILD_MODE="clean"
    echo "🧹 使用全量编译模式"
else
    echo "🚀 使用增量编译模式（使用 --clean 参数可强制全量编译）"
fi
PID_FILE="app.pid"
LOG_FILE="logs/app.log"
HEALTH_CHECK_URL="http://localhost:$APP_PORT/api/claude-code/health"

# 内存配置（本地开发，3个worker）
JAVA_OPTS="-Xms512m -Xmx1g"              # 初始512MB，最大1GB内存
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"       # 使用G1垃圾收集器
JAVA_OPTS="$JAVA_OPTS -XX:MaxGCPauseMillis=200"  # 最大GC暂停200ms
JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError"  # OOM时生成堆转储
JAVA_OPTS="$JAVA_OPTS -XX:HeapDumpPath=logs/"  # 堆转储路径
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"     # 字符编码
JAVA_OPTS="$JAVA_OPTS -Dsun.jnu.encoding=UTF-8"  # JNU编码
JAVA_OPTS="$JAVA_OPTS -Dspring.profiles.active=dev"  # Spring配置

echo "🚀 SiliconMan Agent - 启动中..."
echo "=================================================="
echo "📅 启动时间: $(date)"
echo "🎯 基础架构: Claude Code 进程池 + 向量搜索"
echo "💾 内存配置: 初始512MB，最大1GB"
echo "🔧 垃圾收集器: G1GC"
echo "👥 Worker数量: 3个（本地开发配置）"
echo ""

# 1. 环境检查
echo "🔍 环境检查..."

# 检查Java版本
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "❌ Java 未安装或 JAVA_HOME 配置错误: $JAVA_HOME"
    echo "请检查 JAVA_HOME 配置并确保该路径存在"
    exit 1
fi

JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
echo "☕ $JAVA_VERSION"
echo "📍 JAVA_HOME: $JAVA_HOME"

# 检查内存
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    AVAILABLE_MEMORY=$(vm_stat | grep "Pages free" | awk '{print $3}' | sed 's/\.//')
    AVAILABLE_MEMORY=$((AVAILABLE_MEMORY * 4096 / 1024 / 1024))
else
    # Linux
    AVAILABLE_MEMORY=$(free -m 2>/dev/null | awk 'NR==2{print $7}' || echo "unknown")
fi

if [ "$AVAILABLE_MEMORY" != "unknown" ] && [ "$AVAILABLE_MEMORY" -lt 2048 ]; then
    echo "⚠️  警告: 可用内存不足2GB ($AVAILABLE_MEMORY MB)，可能影响性能"
fi

# 2. 创建必要目录
echo "📁 创建日志目录..."
mkdir -p logs
mkdir -p logs/gc  # GC日志目录
mkdir -p data/sessions  # 会话存储
mkdir -p data/vector-index  # 向量索引

# 3. 编译项目
echo "📦 编译项目..."
# start.sh 已在 agent 目录，无需切换目录

if [ "$BUILD_MODE" = "clean" ]; then
    # 全量编译
    echo "🧹 执行全量编译..."
    if ./gradlew clean build -x test; then
        echo "✅ 编译成功"
    else
        echo "❌ 编译失败，退出启动"
        exit 1
    fi
else
    # 增量编译
    echo "⚡ 执行增量编译..."
    if ./gradlew build -x test; then
        echo "✅ 编译成功"
    else
        echo "❌ 编译失败，尝试全量编译..."
        # 增量编译失败时，自动回退到全量编译
        if ./gradlew clean build -x test; then
            echo "✅ 全量编译成功"
        else
            echo "❌ 编译失败，退出启动"
            exit 1
        fi
    fi
fi

# 4. 端口检查和清理
echo "🔍 检查端口 $APP_PORT..."
PORT_PID=$(lsof -ti:$APP_PORT 2>/dev/null || echo "")

if [ ! -z "$PORT_PID" ]; then
    echo "⚠️  端口 $APP_PORT 被进程 $PORT_PID 占用"
    echo "🔪 正在终止占用端口的进程..."
    kill -9 $PORT_PID
    sleep 2
    echo "✅ 已清理端口占用"
fi

# 5. 清理旧进程
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat $PID_FILE)
    if ps -p $OLD_PID > /dev/null 2>&1; then
        echo "🔪 发现旧进程 $OLD_PID，正在终止..."
        kill -9 $OLD_PID
        sleep 2
        echo "✅ 已清理旧进程"
    fi
    rm -f $PID_FILE
fi

# 6. JAR文件检查
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR文件不存在: $JAR_FILE"
    echo "请确保编译成功"
    exit 1
fi

JAR_SIZE=$(ls -lh "$JAR_FILE" | awk '{print $5}')
echo "📦 JAR文件大小: $JAR_SIZE"

# 7. 启动应用
echo "🚀 启动应用..."
echo "📍 JAR文件: $JAR_FILE"
echo "📍 工作目录: $(pwd)"
echo "📍 日志文件: $LOG_FILE"
echo "📍 PID文件: $PID_FILE"

# 添加GC日志配置
GC_OPTS="-Xlog:gc:logs/gc/gc.log:time,tags -Xlog:gc+heap=info -XX:+UseG1GC"
JAVA_OPTS="$JAVA_OPTS $GC_OPTS"

# 检测 Claude Code CLI 路径
echo "🔍 检测 Claude Code CLI..."
CLAUDE_CODE_PATH=$(which claude 2>/dev/null || echo "")

if [ -z "$CLAUDE_CODE_PATH" ]; then
    echo "⚠️  警告: 未找到 claude 命令，请确保 Claude Code CLI 已安装"
    echo "   安装方法: npm install -g @anthropic-ai/claude-code"
else
    echo "✅ 找到 Claude Code (Git Bash 路径): $CLAUDE_CODE_PATH"

    # 🔥 关键：在 Windows 上，需要将 Git Bash 路径转换为 Windows 路径
    # /c/nvm4w/nodejs/claude -> C:\nvm4w\nodejs\claude.cmd
    if [[ "$CLAUDE_CODE_PATH" == /c/* ]]; then
        # Git Bash 路径转换：/c/path -> C:\path
        CLAUDE_CODE_PATH="C:\\$(echo $CLAUDE_CODE_PATH | sed 's|^/c/||' | sed 's|/|\\|g')"
        # 添加 .cmd 扩展名（Windows 需要）
        CLAUDE_CODE_PATH="${CLAUDE_CODE_PATH}.cmd"
        echo "🔄 转换为 Windows 路径: $CLAUDE_CODE_PATH"
    fi

    # 将路径传递给 Spring Boot（通过环境变量）
    export CLAUDE_CODE_PATH="$CLAUDE_CODE_PATH"
fi

# 启动应用
nohup "$JAVA_HOME/bin/java" $JAVA_OPTS -jar $JAR_FILE > $LOG_FILE 2>&1 &
APP_PID=$!

# 保存PID
echo $APP_PID > $PID_FILE

echo "✅ 应用启动成功！"
echo "📊 进程ID: $APP_PID"
echo "🌐 访问地址: http://localhost:$APP_PORT"
echo "📋 查看日志: tail -f $LOG_FILE"
echo "🛑 停止应用: ./stop.sh"

# 8. 健康检查
echo ""
echo "⏳ 等待应用启动（最多60秒）..."
for i in {1..60}; do
    if curl -s $HEALTH_CHECK_URL > /dev/null 2>&1; then
        echo "✅ 应用启动成功，健康检查通过"
        echo "🎉 SiliconMan Agent 已准备好处理代码分析任务"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "❌ 应用启动超时，请检查日志"
        echo "📋 查看启动日志: tail -f $LOG_FILE"
        exit 1
    fi
    printf "."
    sleep 1
done

# 9. 显示系统状态
echo ""
echo "📊 系统状态:"
echo "   进程ID: $APP_PID"
echo "   端口: $APP_PORT"
echo "   Worker数量: 3个"
echo "   内存: 初始512MB，最大1GB"
echo "   垃圾收集器: G1GC"
echo "   日志文件: $LOG_FILE"
echo "   GC日志: logs/gc/gc.log"
echo ""
echo "🔧 管理命令:"
echo "   查看日志: tail -f $LOG_FILE"
echo "   查看GC日志: tail -f logs/gc/gc.log"
echo "   查看进程池状态: curl http://localhost:$APP_PORT/api/claude-code/pool/status"
echo "   停止应用: ./stop.sh"
echo "   健康检查: curl $HEALTH_CHECK_URL"
echo ""
echo "🎯 SiliconMan Agent 启动完成！"
echo "📖 架构文档: ../docs/md/architecture-qa.md"
