#!/bin/bash

##############################################################################
# SmanAgent 验证服务启动脚本
#
# 用途: 启动 Web 验证服务，用于验证分析结果的正确性
# 端口: 8080 (可通过环境变量 VERIFICATION_PORT 覆盖)
#
# 环境变量:
#   VERIFICATION_PORT - 服务端口（默认 8080）
#   LLM_API_KEY - LLM API 密钥
#   LLM_BASE_URL - LLM 基础 URL
#   LLM_MODEL_NAME - LLM 模型名称
#   BGE_ENABLED - 是否启用 BGE（默认 false）
#   BGE_ENDPOINT - BGE 端点
#   RERANKER_ENABLED - 是否启用 Reranker（默认 false）
#   RERANKER_BASE_URL - Reranker 基础 URL
#   RERANKER_API_KEY - Reranker API 密钥
##############################################################################

set -e  # 遇到错误立即退出
set -u  # 使用未定义变量时退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

# 设置基础目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR" || exit 1

# 配置
PORT=${VERIFICATION_PORT:-8080}
LOG_DIR="$PROJECT_DIR/logs/verification"
LOG_FILE="$LOG_DIR/verification-web.log"
PID_FILE="$LOG_DIR/verification-web.pid"

# 创建日志目录
mkdir -p "$LOG_DIR"

# 检查 Java 版本
log_step "检查 Java 版本..."
if ! command -v java &> /dev/null; then
    log_error "未找到 Java，请安装 Java 17 或更高版本"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    log_error "Java 版本过低: $JAVA_VERSION，需要 Java 17 或更高版本"
    exit 1
fi

log_info "Java 版本: $JAVA_VERSION"

# 检查端口占用
log_step "检查端口 $PORT..."
if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
    log_warn "端口 $PORT 已被占用"
    read -p "是否杀死占用进程并继续？(y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        lsof -ti:$PORT | xargs kill -9 2>/dev/null || true
        log_info "已杀死占用进程"
        sleep 1
    else
        log_error "用户取消操作"
        exit 1
    fi
fi

# 检查端口是否开放
check_port_open() {
    local port=$1
    nc -z localhost "$port" 2>/dev/null || \
        lsof -Pi :"$port" -sTCP:LISTEN -t >/dev/null 2>&1
}

# 检查环境变量和自动检测服务
log_step "检查环境变量和服务..."

if [ -z "${LLM_API_KEY:-}" ]; then
    log_warn "未设置 LLM_API_KEY 环境变量"
    log_warn "专家咨询功能可能无法正常工作"
fi

# 自动检测 BGE 服务（端口 8000）
if [ -z "${BGE_ENABLED:-}" ]; then
    if check_port_open 8000; then
        log_info "检测到 BGE 服务运行在端口 8000，自动启用 BGE 向量化"
        export BGE_ENABLED=true
    else
        log_warn "未检测到 BGE 服务（端口 8000），禁用 BGE 向量化"
        export BGE_ENABLED=false
    fi
else
    if [ "$BGE_ENABLED" = "true" ]; then
        log_info "BGE 向量化已启用（用户配置）"
    else
        log_info "BGE 向量化已禁用（用户配置）"
    fi
fi

# 自动检测 Reranker 服务（端口 8001）
if [ -z "${RERANKER_ENABLED:-}" ]; then
    if check_port_open 8001; then
        log_info "检测到 Reranker 服务运行在端口 8001，自动启用 Reranker"
        export RERANKER_ENABLED=true
    else
        log_warn "未检测到 Reranker 服务（端口 8001），禁用 Reranker"
        export RERANKER_ENABLED=false
    fi
else
    if [ "$RERANKER_ENABLED" = "true" ]; then
        log_info "Reranker 已启用（用户配置）"
    else
        log_info "Reranker 已禁用（用户配置）"
    fi
fi

# 构建 JAR
log_step "构建 JAR..."
./gradlew clean build -x test > /dev/null 2>&1
if [ $? -ne 0 ]; then
    log_error "构建失败，请检查错误"
    echo "运行 './gradlew clean build' 查看详细日志"
    exit 1
fi

log_info "构建成功"

# 查找 JAR 文件
JAR_FILE=$(find "$PROJECT_DIR/build/libs" -name "*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)

if [ -z "$JAR_FILE" ]; then
    log_error "未找到 JAR 文件"
    exit 1
fi

log_info "JAR 文件: $JAR_FILE"

# 启动服务
log_step "启动验证服务..."
echo ""
echo "=========================================="
echo "  SmanAgent 验证服务"
echo "=========================================="
echo "端口: $PORT"
echo "日志: $LOG_FILE"
echo "PID: $PID_FILE"
echo ""
echo "API 端点:"
echo "  - POST http://localhost:$PORT/api/verify/expert_consult"
echo "  - POST http://localhost:$PORT/api/verify/semantic_search"
echo "  - POST http://localhost:$PORT/api/verify/analysis_results"
echo "  - POST http://localhost:$PORT/api/verify/h2_query"
echo ""
echo "健康检查:"
echo "  curl http://localhost:$PORT/actuator/health"
echo ""
echo "按 Ctrl+C 停止服务"
echo "=========================================="
echo ""

# 启动服务（后台运行）
nohup java -Dserver.port=$PORT \
     -Dlogging.file.name="$LOG_FILE" \
     -Dlogging.level.com.smancode.smanagent=INFO \
     -jar "$JAR_FILE" \
     --spring.main.web-application-type=SERVLET \
     > "$LOG_DIR/verification-web.stdout" 2>&1 &

# 保存 PID
JAVA_PID=$!
echo $JAVA_PID > "$PID_FILE"

# 等待服务启动
log_info "等待服务启动..."
sleep 3

# 健康检查
MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if curl -s http://localhost:$PORT/actuator/health >/dev/null 2>&1; then
        log_info "服务启动成功！"
        echo ""
        echo "测试命令:"
        echo "  curl -X POST http://localhost:$PORT/api/verify/expert_consult \\"
        echo "    -H 'Content-Type: application/json' \\"
        echo "    -d '{\"question\": \"测试\", \"projectKey\": \"test\"}'"
        echo ""
        break
    fi

    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo -n "."
    sleep 1
done

echo ""

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    log_error "服务启动超时"
    log_error "请查看日志: $LOG_FILE"
    exit 1
fi

# 清理函数
cleanup() {
    log_step "停止服务..."
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        kill $PID 2>/dev/null || true
        rm -f "$PID_FILE"
        log_info "服务已停止 (PID: $PID)"
    fi
}

# 注册清理函数
trap cleanup EXIT INT TERM

# 保持脚本运行
log_info "服务运行中，按 Ctrl+C 停止..."
wait
