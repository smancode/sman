#!/bin/bash

##############################################################################
# Autoloop 项目完整分析脚本
#
# 功能：
#   1. 执行 9 个分析步骤
#   2. 验证分析结果
#   3. 启动验证服务
#   4. 运行所有 HTTP 测试
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

# 配置
AUTOLOOP_PATH="../autoloop"
PROJECT_KEY="autoloop"
VECTOR_DB_DIR="$HOME/.smanunion/$PROJECT_KEY"
REPORT_FILE="docs/AUTOLOOP_ANALYSIS_REPORT_$(date +%Y%m%d_%H%M%S).md"
HTTP_TEST_FILE="http/rest.http"

cd "$(dirname "$0")/.."

echo "=========================================="
echo "  Autoloop 项目完整分析"
echo "=========================================="
echo "项目路径: $AUTOLOOP_PATH"
echo "项目 Key: $PROJECT_KEY"
echo ""

# ================================
# 第一步：检查前置条件
# ================================
log_step "检查前置条件..."

# 检查 autoloop 项目
if [ ! -d "$AUTOLOOP_PATH" ]; then
    log_error "autoloop 项目不存在: $AUTOLOOP_PATH"
    exit 1
fi
log_info "✓ autoloop 项目存在"

# 检查 BGE 服务
if ! curl -s http://localhost:8000/health >/dev/null 2>&1; then
    log_error "BGE Embedding 服务未运行"
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

# ================================
# 第二步：执行项目分析
# ================================
log_step "执行项目分析（9 个步骤）..."

# 使用 gradle 运行分析任务
# 注意：这需要在 IDEA 中运行，或者我们需要创建一个独立的 main 类
log_warn "项目分析需要在 IDEA 中执行"
log_info "请在 IDEA 中："
log_info "  1. 打开 autoloop 项目"
log_info "  2. 点击 '项目分析' 按钮"
log_info "  3. 等待分析完成"
log_info ""
log_info "或者等待我们创建独立的分析工具..."

# TODO: 实现独立的分析工具
echo ""

# ================================
# 第三步：验证分析结果
# ================================
log_step "验证分析结果..."

    db_file="$VECTOR_DB_DIR/analysis.mv.db"

if [ ! -f "$db_file" ]; then
    log_warn "分析数据库不存在: $db_file"
    log_info "请先在 IDEA 中运行项目分析"
else
    log_info "✓ 分析数据库存在"

    # 查询分析结果
    java -cp ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar \
      org.h2.tools.Shell \
      -url "jdbc:h2:$VECTOR_DB_DIR/analysis" \
      -user sa \
      -password "" \
      -sql "SELECT step_name, status FROM analysis_step WHERE project_key = '$PROJECT_KEY'" 2>/dev/null || true
fi

echo ""

# ================================
# 第四步：启动验证服务
# ================================
log_step "启动验证服务..."

# 杀掉旧进程
lsof -ti:8080 | xargs kill -9 2>/dev/null || true
sleep 1

# 使用 Spring Boot 插件启动
# 由于没有 bootRun 任务，我们用其他方式
log_warn "验证服务需要在 IDEA 中启动"
log_info "请在 IDEA 中："
log_info "  1. 打开 VerificationWebService.kt"
log_info "  2. 右键 → Run 'VerificationWebService'"
log_info "  3. 等待服务启动（端口 8080）"

echo ""
echo "等待验证服务启动（按 Enter 继续）..."
read

# 检查服务是否启动
if ! curl -s http://localhost:8080/actuator/health >/dev/null 2>&1; then
    log_error "验证服务未启动"
    exit 1
fi

log_info "✓ 验证服务已启动"
echo ""

# ================================
# 第五步：运行 HTTP 测试
# ================================
log_step "运行 HTTP 测试..."

# 测试统计
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# 从 http/rest.http 提取并执行测试
# 1. 健康检查
log_info "测试 1: 健康检查"
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if curl -s http://localhost:8080/actuator/health | grep -q "UP"; then
    log_info "✓ 健康检查通过"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    log_error "✗ 健康检查失败"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi

# 2. 分析结果查询（9 个模块）
MODULES=("project_structure" "tech_stack" "ast_scanning" "db_entities" "api_entries" "external_apis" "enums" "common_classes" "xml_code")
for module in "${MODULES[@]}"; do
    log_info "测试 $((TOTAL_TESTS + 1)): 查询 $module"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(curl -s -X POST http://localhost:8080/api/verify/analysis_results \
        -H "Content-Type: application/json" \
        -d "{\"module\": \"$module\", \"projectKey\": \"$PROJECT_KEY\"}")

    if echo "$response" | grep -q "success\|data\|result"; then
        log_info "✓ $module 查询成功"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        log_error "✗ $module 查询失败"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
done

# 3. 语义搜索测试
SEARCH_QUERIES=(
    "还款入口是哪个"
    "用户登录验证"
    "放款流程和入口"
)

for query in "${SEARCH_QUERIES[@]}"; do
    log_info "测试 $((TOTAL_TESTS + 1)): 语义搜索 '$query'"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(curl -s -X POST http://localhost:8080/api/verify/semantic_search \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\", \"projectKey\": \"$PROJECT_KEY\", \"topK\": 5, \"enableRerank\": true}")

    if echo "$response" | grep -q "recallResults\|rerankResults"; then
        log_info "✓ 搜索 '$query' 成功"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        log_error "✗ 搜索 '$query' 失败"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
done

echo ""

# ================================
# 第六步：生成报告
# ================================
log_step "生成测试报告..."

cat > "$REPORT_FILE" <<EOF
# Autoloop 项目分析验证报告

**测试时间**: $(date '+%Y-%m-%d %H:%M:%S')
**项目路径**: $AUTOLOOP_PATH
**项目 Key**: $PROJECT_KEY

## 测试结果摘要

| 指标 | 数值 |
|------|------|
| 总测试数 | $TOTAL_TESTS |
| 通过 | $PASSED_TESTS |
| 失败 | $FAILED_TESTS |
| 成功率 | $(echo "scale=1; $PASSED_TESTS * 100 / $TOTAL_TESTS" | bc)% |

## 测试详情

### 前置条件检查

| 检查项 | 状态 |
|--------|------|
| autoloop 项目存在 | ✅ |
| BGE 服务 (8000) | ✅ |
| Reranker 服务 (8001) | ✅ |

### 分析步骤验证

| 步骤 | 描述 | 状态 |
|------|------|------|
| 1 | project_structure | ⏳ |
| 2 | tech_stack | ⏳ |
| 3 | ast_scanning | ⏳ |
| 4 | db_entities | ⏳ |
| 5 | api_entries | ⏳ |
| 6 | external_apis | ⏳ |
| 7 | enums | ⏳ |
| 8 | common_classes | ⏳ |
| 9 | xml_code | ⏳ |

### HTTP API 测试

| # | 测试 | 状态 |
|---|------|------|
EOF

# 添加测试结果到报告
test_num=1
echo "| $test_num | 健康检查 | ${PASSED_TESTS:-0} |" >> "$REPORT_FILE"

for module in "${MODULES[@]}"; do
    test_num=$((test_num + 1))
    echo "| $test_num | 查询 $module | ⏳ |" >> "$REPORT_FILE"
done

for query in "${SEARCH_QUERIES[@]}"; do
    test_num=$((test_num + 1))
    echo "| $test_num | 搜索 '$query' | ⏳ |" >> "$REPORT_FILE"
done

cat >> "$REPORT_FILE" <<EOF

## 问题记录

EOF

log_info "✓ 报告已生成: $REPORT_FILE"
echo ""

# ================================
# 总结
# ================================
echo "=========================================="
echo "  测试完成"
echo "=========================================="
echo "总测试数: $TOTAL_TESTS"
echo "通过: $PASSED_TESTS"
echo "失败: $FAILED_TESTS"
echo "成功率: $(echo "scale=1; $PASSED_TESTS * 100 / $TOTAL_TESTS" | bc)%"
echo ""
echo "详细报告: $REPORT_FILE"
echo "=========================================="

if [ $FAILED_TESTS -eq 0 ]; then
    exit 0
else
    exit 1
fi
