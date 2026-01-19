#!/bin/bash

# SmanAgent 启动脚本
# 支持：内存优化、错误恢复、健康检查、日志管理

set -e

# 强制使用Java 21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH=$JAVA_HOME/bin:$PATH

# 配置
APP_NAME="smanagent-agent"
APP_PORT=8080
JAR_FILE="build/libs/${APP_NAME}-1.0.0.jar"
PID_FILE="app.pid"
LOG_FILE="logs/app.log"
HEALTH_CHECK_URL="http://localhost:$APP_PORT/api/test/health"

# 内存配置（支持智能代码分析）
JAVA_OPTS="-Xms512m -Xmx2g"                  # 初始512MB，最大2GB内存
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"          # 使用G1垃圾收集器
JAVA_OPTS="$JAVA_OPTS -XX:MaxGCPauseMillis=200"  # 最大GC暂停200ms
JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError"  # OOM时生成堆转储
JAVA_OPTS="$JAVA_OPTS -XX:HeapDumpPath=logs/"    # 堆转储路径
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"     # 字符编码
JAVA_OPTS="$JAVA_OPTS -Dsun.jnu.encoding=UTF-8"  # JNU编码
JAVA_OPTS="$JAVA_OPTS -Dspring.profiles.active=default"  # Spring配置

echo "🤖 SmanAgent - 智能代码分析系统"
echo "=================================================="
echo "📅 启动时间: $(date)"
echo "💾 内存配置: 初始512MB，最大2GB"
echo "🔧 垃圾收集器: G1GC"
echo ""

# 1. 环境检查
echo "🔍 环境检查..."

# 检查Java版本
if ! command -v java &> /dev/null; then
    echo "❌ Java未安装或不在PATH中"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
echo "☕ Java版本: $JAVA_VERSION"

# 2. 创建必要目录
echo "📁 创建日志目录..."
mkdir -p logs
mkdir -p logs/gc  # GC日志目录

# 3. 设置必要的环境变量（如果未设置）
echo "🔧 配置环境变量..."
if [ -z "$GLM_API_KEY" ]; then
    echo "⚠️  GLM_API_KEY 未设置，使用默认值"
    export GLM_API_KEY=""
fi

if [ -z "$PROJECT_PATH" ]; then
    echo "⚠️  PROJECT_PATH 未设置，使用默认值"
    export PROJECT_PATH="/path/to/your/project"
fi

# 4. 编译项目
echo "📦 编译项目（增量编译）..."
if ./gradlew build -x test; then
    echo "✅ 编译成功"
else
    echo "❌ 编译失败，退出启动"
    exit 1
fi

# 4. 端口检查和清理
echo "🔍 检查端口 $APP_PORT..."
PORT_PID=$(lsof -ti:$APP_PORT 2>/dev/null || echo "")

if [ ! -z "$PORT_PID" ]; then
    echo "⚠️  端口 $APP_PORT 被进程 $PORT_PID 占用"
    echo "🔪 正在终止占用端口的进程..."
    kill -9 $PORT_PID
    sleep 3
    echo "✅ 已清理端口占用"
fi

# 5. 清理旧进程
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat $PID_FILE)
    if ps -p $OLD_PID > /dev/null 2>&1; then
        echo "🔪 发现旧进程 $OLD_PID，正在终止..."
        kill -9 $OLD_PID
        sleep 3
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
echo "📍 日志文件: $LOG_FILE"
echo "📍 PID文件: $PID_FILE"
echo "📍 内存配置: $JAVA_OPTS"

# 添加GC日志配置 - Java 21兼容版本
GC_OPTS="-Xlog:gc:logs/gc/gc.log:time,tags -Xlog:gc+heap=info -XX:+UseG1GC"
JAVA_OPTS="$JAVA_OPTS $GC_OPTS"

# 启动应用
nohup java $JAVA_OPTS -jar $JAR_FILE > $LOG_FILE 2>&1 &
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
        echo "🎉 系统已准备好处理代码分析任务"
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
echo "   内存: 初始512MB，最大2GB"
echo "   垃圾收集器: G1GC"
echo "   日志文件: $LOG_FILE"
echo "   GC日志: logs/gc/gc.log"
echo ""
echo "🔧 管理命令:"
echo "   查看日志: tail -f $LOG_FILE"
echo "   查看GC日志: tail -f logs/gc/gc.log"
echo "   停止应用: ./stop.sh"
echo "   健康检查: curl $HEALTH_CHECK_URL"
echo ""
echo "🎯 SmanAgent 启动完成！"
