#!/bin/bash

##############################################################################
# SmanAgent 验证服务 API 测试脚本
#
# 用途: 测试所有验证服务的 API 端点
# 项目: autoloop
##############################################################################

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置
BASE_URL="http://localhost:8080"
PROJECT_KEY="autoloop"

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

log_test() {
    echo -e "${BLUE}[TEST]${NC} $1"
}

# 检查服务是否运行
check_service() {
    log_test "检查服务状态..."
    if curl -s "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
        log_info "服务运行正常"
        return 0
    else
        log_error "服务未运行，请先启动验证服务"
        log_info "启动方式: ./scripts/verification-web.sh"
        return 1
    fi
}

# 执行测试
run_test() {
    local test_name="$1"
    local url="$2"
    local data="$3"

    log_test "$test_name"

    if [ -z "$data" ]; then
        curl -s "${url}" | jq '.' 2>/dev/null || curl -s "${url}"
    else
        curl -s -X POST "${url}" \
            -H 'Content-Type: application/json' \
            -d "${data}" | jq '.' 2>/dev/null || \
        curl -s -X POST "${url}" \
            -H 'Content-Type: application/json' \
            -d "${data}"
    fi
    echo ""
    echo ""
}

# 主函数
main() {
    echo "=========================================="
    echo "  SmanAgent 验证服务 API 测试"
    echo "=========================================="
    echo "项目: $PROJECT_KEY"
    echo "服务: $BASE_URL"
    echo ""

    # 检查服务
    check_service || exit 1

    echo "=========================================="
    echo "开始测试..."
    echo "=========================================="
    echo ""

    # 1. 健康检查
    run_test "1. 健康检查" "${BASE_URL}/actuator/health" ""

    # 2. 分析结果查询
    run_test "2. 项目结构" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "project_structure", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "3. 技术栈" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "tech_stack", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "4. AST 扫描" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "ast_scanning", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "5. 数据库实体" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "db_entities", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "6. API 入口" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "api_entries", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "7. 外调接口" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "external_apis", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "8. 枚举" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "enums", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "9. 公共类" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "common_classes", "projectKey": "'"$PROJECT_KEY"'"}'

    run_test "10. XML 代码" \
        "${BASE_URL}/api/verify/analysis_results" \
        '{"module": "xml_code", "projectKey": "'"$PROJECT_KEY"'"}'

    # 11. 专家咨询
    run_test "11. 专家咨询：API 入口" \
        "${BASE_URL}/api/verify/expert_consert" \
        '{"question": "项目中有哪些 API 入口？", "projectKey": "'"$PROJECT_KEY"'", "topK": 10}'

    run_test "12. 专家咨询：数据库实体" \
        "${BASE_URL}/api/verify/expert_consert" \
        '{"question": "项目中有哪些数据库实体？", "projectKey": "'"$PROJECT_KEY"'", "topK": 10}'

    run_test "13. 专家咨询：项目架构" \
        "${BASE_URL}/api/verify/expert_consert" \
        '{"question": "这个项目的整体架构是什么？", "projectKey": "'"$PROJECT_KEY"'", "topK": 10}'

    run_test "14. 专家咨询：外调接口" \
        "${BASE_URL}/api/verify/expert_consert" \
        '{"question": "项目调用了哪些外部接口？", "projectKey": "'"$PROJECT_KEY"'", "topK": 10}'

    # 15. 语义搜索
    run_test "15. 语义搜索：还款入口和类型" \
        "${BASE_URL}/api/verify/semantic_search" \
        '{"query": "还款入口是哪个，有哪些还款类型", "projectKey": "'"$PROJECT_KEY"'", "topK": 10, "enableRerank": true, "rerankTopN": 5}'

    run_test "16. 语义搜索：用户登录" \
        "${BASE_URL}/api/verify/semantic_search" \
        '{"query": "用户登录验证", "projectKey": "'"$PROJECT_KEY"'", "topK": 10, "enableRerank": true}'

    run_test "17. 语义搜索：放款流程" \
        "${BASE_URL}/api/verify/semantic_search" \
        '{"query": "放款流程和入口", "projectKey": "'"$PROJECT_KEY"'", "topK": 10, "enableRerank": true}'

    # 18. H2 查询
    run_test "18. 查询向量数据" \
        "${BASE_URL}/api/verify/query_vectors" \
        '{"projectKey": "'"$PROJECT_KEY"'", "page": 0, "size": 10}'

    run_test "19. 查询项目列表" \
        "${BASE_URL}/api/verify/query_projects" \
        '{"page": 0, "size": 10}'

    run_test "20. 查询分析步骤" \
        "${BASE_URL}/api/verify/execute_sql" \
        '{"sql": "SELECT * FROM analysis_step WHERE project_key = ? ORDER BY start_time DESC", "params": {"projectKey": "'"$PROJECT_KEY"'"}, "limit": 50}'

    echo "=========================================="
    log_info "测试完成！"
    echo "=========================================="
}

main "$@"
