#!/bin/bash

##############################################################################
# SmanAgent - 执行项目分析并运行测试
#
# 用途: 对指定项目执行分析，启动 Web 服务，运行所有测试
# 目标项目: autoloop
##############################################################################

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
TARGET_PROJECT="../autoloop"
TARGET_PROJECT_KEY="autoloop"
PORT=8080

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

# 清理函数
cleanup() {
    log_step "清理资源..."
    if [ -n "$VERIFICATION_PID" ]; then
        kill $VERIFICATION_PID 2>/dev/null || true
        log_info "已停止验证服务 (PID: $VERIFICATION_PID)"
    fi
}

# 注册清理函数
trap cleanup EXIT INT TERM

# 主函数
main() {
    echo "=========================================="
    echo "  SmanAgent 项目分析 + 测试"
    echo "=========================================="
    echo "目标项目: $TARGET_PROJECT"
    echo "项目 Key: $TARGET_PROJECT_KEY"
    echo "服务端口: $PORT"
    echo ""

    cd "$PROJECT_DIR"

    # ========== 第一步：执行项目分析 ==========
    log_step "第一步：执行项目分析"
    log_info "目标: $TARGET_PROJECT"

    # 由于项目分析需要在 IntelliJ IDEA 中执行（依赖 PSI），
    # 我们直接通过 Kotlin 脚本调用分析服务
    log_info "准备执行分析..."
    log_warn "注意：项目分析需要在 IntelliJ IDEA 环境中执行"
    log_warn "请确保已通过 runIde 启动了插件"

    # 检查是否已有分析结果
    if [ -f "$HOME/.smanunion/vector_data/$TARGET_PROJECT_KEY/data.mv.db" ]; then
        log_info "发现已有分析结果数据库"
    else
        log_warn "未找到分析结果数据库"
        log_warn "请先在 IntelliJ IDEA 中对 autoloop 项目执行分析"
    fi

    # ========== 第二步：启动 Web 验证服务 ==========
    log_step "第二步：启动 Web 验证服务"

    # 检查端口占用
    if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
        log_warn "端口 $PORT 已被占用，尝试停止占用进程..."
        lsof -ti:$PORT | xargs kill -9 2>/dev/null || true
        sleep 2
    fi

    # 启动服务（后台运行）
    log_info "启动验证服务..."
    nohup ./gradlew runIde \
        -Dserver.port=$PORT \
        -Dspring.main.web-application-type=SERVLET \
        > logs/verification/verification-web.stdout 2>&1 &

    VERIFICATION_PID=$!
    echo $VERIFICATION_PID > logs/verification/verification-web.pid

    log_info "验证服务已启动 (PID: $VERIFICATION_PID)"
    log_info "日志: logs/verification/verification-web.stdout"

    # 等待服务启动
    log_info "等待服务启动..."
    MAX_RETRIES=60
    RETRY_COUNT=0

    while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
        if curl -s http://localhost:$PORT/actuator/health >/dev/null 2>&1; then
            log_info "服务启动成功！"
            break
        fi

        RETRY_COUNT=$((RETRY_COUNT + 1))
        echo -n "."
        sleep 2
    done

    echo ""

    if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
        log_error "服务启动超时"
        log_error "请查看日志: logs/verification/verification-web.stdout"
        exit 1
    fi

    # ========== 第三步：执行测试用例 ==========
    log_step "第三步：执行测试用例"
    log_info "运行 25 个 API 测试..."

    # 创建测试结果文件
    TEST_RESULTS="$PROJECT_DIR/logs/verification/test-results-$(date +%Y%m%d_%H%M%S).txt"
    mkdir -p "$(dirname "$TEST_RESULTS")"

    echo "========================================" > "$TEST_RESULTS"
    echo "API 测试结果" >> "$TEST_RESULTS"
    echo "时间: $(date)" >> "$TEST_RESULTS"
    echo "========================================" >> "$TEST_RESULTS"
    echo "" >> "$TEST_RESULTS"

    # 测试计数器
    TOTAL_TESTS=25
    PASSED_TESTS=0
    FAILED_TESTS=0

    # 1. 健康检查
    log_step "测试 1/$TOTAL_TESTS: 健康检查"
    if curl -s http://localhost:$PORT/actuator/health | jq -e '.status == "UP"' >/dev/null; then
        log_info "✅ 健康检查通过"
        echo "1. 健康检查: ✅ PASS" >> "$TEST_RESULTS"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        log_error "❌ 健康检查失败"
        echo "1. 健康检查: ❌ FAIL" >> "$TEST_RESULTS"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
    echo "" >> "$TEST_RESULTS"

    # 2-10. 分析结果查询
    MODULES=("project_structure" "tech_stack" "ast_scanning" "db_entities" "api_entries" "external_apis" "enums" "common_classes" "xml_code")
    for i in "${!MODULES[@]}"; do
        MODULE="${MODULES[$i]}"
        TEST_NUM=$((i + 2))
        log_step "测试 $TEST_NUM/$TOTAL_TESTS: 查询 $MODULE"

        RESPONSE=$(curl -s -X POST http://localhost:$PORT/api/verify/analysis_results \
            -H 'Content-Type: application/json' \
            -d "{\"module\": \"$MODULE\", \"projectKey\": \"$TARGET_PROJECT_KEY\"}")

        if echo "$RESPONSE" | jq -e '.module == "'"$MODULE"'"' >/dev/null 2>&1; then
            log_info "✅ $MODULE 查询成功"
            echo "$TEST_NUM. $MODULE: ✅ PASS" >> "$TEST_RESULTS"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            log_warn "⚠️  $MODULE 查询返回空或错误"
            echo "$TEST_NUM. $MODULE: ⚠️  EMPTY/ERROR" >> "$TEST_RESULTS"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
        echo "" >> "$TEST_RESULTS"
    done

    # 11-14, 20-23. 专家咨询
    QUESTIONS=(
        "项目中有哪些 API 入口？"
        "项目中有哪些数据库实体？它们之间的关系是什么？"
        "这个项目的整体架构是什么？使用了哪些技术栈？"
        "项目调用了哪些外部接口？"
        "项目中有哪些枚举类型？分别代表什么含义？"
        "项目中有哪些公共类和工具类？"
        "项目中有哪些 XML 配置文件？"
        "项目的主要功能模块有哪些？"
    )

    for i in "${!QUESTIONS[@]}"; do
        QUESTION="${QUESTIONS[$i]}"
        TEST_NUM=$((11 + i))
        log_step "测试 $TEST_NUM/$TOTAL_TESTS: 专家咨询 - $QUESTION"

        RESPONSE=$(curl -s -X POST http://localhost:$PORT/api/verify/expert_consert \
            -H 'Content-Type: application/json' \
            -d "{\"question\": \"$QUESTION\", \"projectKey\": \"$TARGET_PROJECT_KEY\", \"topK\": 10}")

        if echo "$RESPONSE" | jq -e '.answer' >/dev/null 2>&1; then
            log_info "✅ 专家咨询成功"
            echo "$TEST_NUM. 专家咨询 ($QUESTION): ✅ PASS" >> "$TEST_RESULTS"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            log_warn "⚠️  专家咨询返回空或错误"
            echo "$TEST_NUM. 专家咨询 ($QUESTION): ⚠️  ERROR" >> "$TEST_RESULTS"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
        echo "" >> "$TEST_RESULTS"
    done

    # 15-17, 24-25. 语义搜索
    SEARCH_QUERIES=(
        "还款入口是哪个，有哪些还款类型"
        "用户登录验证"
        "放款流程和入口"
        "用户管理和权限控制"
        "借款申请流程"
    )

    for i in "${!SEARCH_QUERIES[@]}"; do
        QUERY="${SEARCH_QUERIES[$i]}"
        TEST_NUM=$((15 + i))
        log_step "测试 $TEST_NUM/$TOTAL_TESTS: 语义搜索 - $QUERY"

        RESPONSE=$(curl -s -X POST http://localhost:$PORT/api/verify/semantic_search \
            -H 'Content-Type: application/json' \
            -d "{\"query\": \"$QUERY\", \"projectKey\": \"$TARGET_PROJECT_KEY\", \"topK\": 10, \"enableRerank\": true}")

        if echo "$RESPONSE" | jq -e '.query == "'"$QUERY"'"' >/dev/null 2>&1; then
            log_info "✅ 语义搜索成功"
            echo "$TEST_NUM. 语义搜索 ($QUERY): ✅ PASS" >> "$TEST_RESULTS"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            log_warn "⚠️  语义搜索返回空或错误"
            echo "$TEST_NUM. 语义搜索 ($QUERY): ⚠️  ERROR" >> "$TEST_RESULTS"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
        echo "" >> "$TEST_RESULTS"
    done

    # 18-19. H2 查询
    log_step "测试 18/$TOTAL_TESTS: 查询向量数据"
    if curl -s -X POST http://localhost:$PORT/api/verify/query_vectors \
        -H 'Content-Type: application/json' \
        -d "{\"projectKey\": \"$TARGET_PROJECT_KEY\", \"page\": 0, \"size\": 10}" | jq '.' >/dev/null 2>&1; then
        log_info "✅ 查询向量数据成功"
        echo "18. 查询向量数据: ✅ PASS" >> "$TEST_RESULTS"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        log_warn "⚠️  查询向量数据失败"
        echo "18. 查询向量数据: ❌ FAIL" >> "$TEST_RESULTS"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
    echo "" >> "$TEST_RESULTS"

    log_step "测试 19/$TOTAL_TESTS: 查询项目列表"
    if curl -s -X POST http://localhost:$PORT/api/verify/query_projects \
        -H 'Content-Type: application/json' \
        -d '{"page": 0, "size": 10}' | jq '.' >/dev/null 2>&1; then
        log_info "✅ 查询项目列表成功"
        echo "19. 查询项目列表: ✅ PASS" >> "$TEST_RESULTS"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        log_warn "⚠️  查询项目列表失败"
        echo "19. 查询项目列表: ❌ FAIL" >> "$TEST_RESULTS"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
    echo "" >> "$TEST_RESULTS"

    # ========== 第四步：输出测试报告 ==========
    log_step "第四步：测试报告"
    echo "" >> "$TEST_RESULTS"
    echo "========================================" >> "$TEST_RESULTS"
    echo "测试汇总" >> "$TEST_RESULTS"
    echo "========================================" >> "$TEST_RESULTS"
    echo "总测试数: $TOTAL_TESTS" >> "$TEST_RESULTS"
    echo "通过: $PASSED_TESTS" >> "$TEST_RESULTS"
    echo "失败: $FAILED_TESTS" >> "$TEST_RESULTS"
    echo "通过率: $(awk "BEGIN {printf \"%.1f\", ($PASSED_TESTS/$TOTAL_TESTS)*100}")%" >> "$TEST_RESULTS"

    echo ""
    echo "=========================================="
    echo "  测试完成"
    echo "=========================================="
    echo "总测试数: $TOTAL_TESTS"
    echo "通过: $PASSED_TESTS"
    echo "失败: $FAILED_TESTS"
    echo "通过率: $(awk "BEGIN {printf \"%.1f\", ($PASSED_TESTS/$TOTAL_TESTS)*100}")%"
    echo ""
    echo "详细结果: $TEST_RESULTS"
    echo "=========================================="

    # 显示详细结果
    cat "$TEST_RESULTS"
}

main "$@"
