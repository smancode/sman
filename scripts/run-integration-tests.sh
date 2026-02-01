#!/bin/bash

##############################################################################
# 运行真实集成测试和 E2E 测试
#
# 用途：测试真实的 BGE API 调用和端到端流程
# 前置条件：
#   1. BGE 服务运行在 http://localhost:8000
#   2. Reranker 服务运行在 http://localhost:8001
#
# 跳过测试：SKIP_INTEGRATION_TESTS=true ./gradlew test
##############################################################################

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

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

# 检查前置条件
check_prerequisites() {
    log_step "检查前置条件..."

    # 检查 BGE 服务
    if ! curl -s http://localhost:8000/health >/dev/null 2>&1; then
        log_error "BGE Embedding 服务未运行"
        log_info "启动方式："
        log_info "  cd /Users/liuchao/projects/vector-services"
        log_info "  ./setup.sh"
        exit 1
    fi
    log_info "✓ BGE Embedding 服务运行中"

    # 检查 Reranker 服务
    if ! curl -s http://localhost:8001/health >/dev/null 2>&1; then
        log_error "BGE Reranker 服务未运行"
        exit 1
    fi
    log_info "✓ BGE Reranker 服务运行中"

    echo ""
}

# 运行测试
run_tests() {
    echo "=========================================="
    echo "  运行真实集成测试和 E2E 测试"
    echo "=========================================="
    echo ""

    # 运行 BGE 集成测试
    log_step "运行 BGE 集成测试..."
    ./gradlew test --tests "*BgeM3ClientIntegrationTest*" 2>&1 | tee /tmp/test-output.log

    # 检查结果
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
        log_info "✓ BGE 集成测试通过"
    else
        log_error "✗ BGE 集成测试失败"
    fi

    echo ""

    # 运行 E2E 测试
    log_step "运行 E2E 测试..."
    ./gradlew test --tests "*RealApiE2ETest*" 2>&1 | tee -a /tmp/test-output.log

    # 检查结果
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
        log_info "✓ E2E 测试通过"
    else
        log_error "✗ E2E 测试失败"
    fi

    echo ""
    echo "=========================================="
    log_info "测试完成！"
    echo "详细日志: /tmp/test-output.log"
    echo "=========================================="

    # 显示测试摘要
    echo ""
    echo "测试摘要:"
    grep -E "BUILD|tests? completed|FAILED|PASSED" /tmp/test-output.log | tail -10
}

# 主函数
main() {
    cd "$(dirname "$0")/../.."

    # 检查是否跳过测试
    if [ "${SKIP_INTEGRATION_TESTS:-false}" = "true" ]; then
        log_warn "SKIP_INTEGRATION_TESTS=true，跳过集成测试"
        exit 0
    fi

    # 检查前置条件
    check_prerequisites

    # 运行测试
    run_tests
}

main "$@"
