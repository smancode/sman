#!/bin/bash

##############################################################################
# 启动 SmanAgent 验证服务
#
# 这是一个独立的 Spring Boot Web 服务，提供 REST API 用于查询分析结果
##############################################################################

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 配置
PORT=${VERIFICATION_PORT:-8080}
LOG_DIR="$(dirname "$0")/../logs/verification"
LOG_FILE="$LOG_DIR/verification-service.log"
PID_FILE="$LOG_DIR/verification-service.pid"

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# 创建日志目录
mkdir -p "$LOG_DIR"

# 检查端口占用
log_step "检查端口 $PORT..."
if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
    log_warn "端口 $PORT 已被占用，停止占用进程..."
    lsof -ti:$PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

cd "$(dirname "$0")/.."

# 显示启动信息
log_step "启动 SmanAgent 验证服务"
echo ""
echo "=========================================="
echo "  SmanAgent 验证服务"
echo "=========================================="
echo "端口: $PORT"
echo "日志: $LOG_FILE"
echo ""
echo "API 端点:"
echo "  - GET  http://localhost:$PORT/actuator/health"
echo "  - POST http://localhost:$PORT/api/verify/analysis_results"
echo "  - POST http://localhost:$PORT/api/verify/expert_consert"
echo "  - POST http://localhost:$PORT/api/verify/semantic_search"
echo ""
echo "按 Ctrl+C 停止服务"
echo "=========================================="
echo ""

# 使用 Gradle 启动
log_info "使用 Gradle 启动服务..."

# 后台运行 Gradle
nohup ./gradlew :run --args="--spring.main.web-application-type=SERVLET -Dserver.port=$PORT" \
    > "$LOG_FILE" 2>&1 &

JAVA_PID=$!
echo $JAVA_PID > "$PID_FILE"

log_info "服务已启动 (PID: $JAVA_PID)"
log_info "等待服务初始化..."

# 等待服务启动
MAX_RETRIES=90
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if curl -s http://localhost:$PORT/actuator/health >/dev/null 2>&1; then
        log_info "服务启动成功！"
        echo ""
        echo "测试命令:"
        echo "  curl http://localhost:$PORT/actuator/health"
        echo ""
        break
    fi

    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo -n "."
    sleep 2
done

echo ""

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    log_error "服务启动超时"
    log_error "请查看日志: $LOG_FILE"
    tail -30 "$LOG_FILE"
    exit 1
fi

# 清理函数
cleanup() {
    log_step "停止服务..."
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        kill $PID 2>/dev/null || true
        rm -f "$PID_FILE"
        log_info "服务已停止"
    fi
    # 也停止 Gradle 守护进程
    pkill -f "gradle.*run" 2>/dev/null || true
}

# 注册清理函数
trap cleanup EXIT INT TERM

# 保持脚本运行
log_info "服务运行中，按 Ctrl+C 停止..."
wait
