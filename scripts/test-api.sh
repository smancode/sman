#!/bin/bash
# API 测试脚本
# 测试所有 api_test.http 中的 API 端点

BASE_URL="http://localhost:8080"
PROJECT_KEY="autoloop"
OUTPUT_FILE="docs/http/api_test.md"

# 创建输出目录
mkdir -p docs/http

# 清空输出文件
echo "# API 测试结果" > "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "测试时间: $(date '+%Y-%m-%d %H:%M:%S')" >> "$OUTPUT_FILE"
echo "项目: $PROJECT_KEY" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# 测试计数器
TOTAL=0
PASSED=0
FAILED=0

# 测试函数
test_api() {
    local name="$1"
    local url="$2"
    local data="$3"
    local expected="$4"

    TOTAL=$((TOTAL + 1))
    echo "" >> "$OUTPUT_FILE"
    echo "## 测试 $TOTAL: $name" >> "$OUTPUT_FILE"
    echo "" >> "$OUTPUT_FILE"
    echo "**请求**:" >> "$OUTPUT_FILE"
    echo "\`\`\`bash" >> "$OUTPUT_FILE"
    echo "curl -X POST '$url' \\" >> "$OUTPUT_FILE"
    echo "  -H 'Content-Type: application/json' \\" >> "$OUTPUT_FILE"
    echo "  -d '$data'" >> "$OUTPUT_FILE"
    echo "\`\`\`" >> "$OUTPUT_FILE"
    echo "" >> "$OUTPUT_FILE"

    # 执行请求
    response=$(curl -s -X POST "$url" \
        -H "Content-Type: application/json" \
        -d "$data" \
        -w "\nHTTP_STATUS:%{http_code}")

    # 提取 HTTP 状态码
    http_status=$(echo "$response" | grep "HTTP_STATUS:" | cut -d: -f2)
    body=$(echo "$response" | sed '/HTTP_STATUS:/d')

    echo "**响应状态**: $http_status" >> "$OUTPUT_FILE"
    echo "" >> "$OUTPUT_FILE"
    echo "**响应内容**:" >> "$OUTPUT_FILE"
    echo "\`\`\`json" >> "$OUTPUT_FILE"
    echo "$body" | head -c 2000 >> "$OUTPUT_FILE"
    if [ $(echo "$body" | wc -c) -gt 2000 ]; then
        echo "" >> "$OUTPUT_FILE"
        echo "... (内容过长，已截断)" >> "$OUTPUT_FILE"
    fi
    echo "\`\`\`" >> "$OUTPUT_FILE"
    echo "" >> "$OUTPUT_FILE"

    # 检查状态码
    if [ "$http_status" = "200" ]; then
        echo "✅ **状态**: 成功" >> "$OUTPUT_FILE"
        PASSED=$((PASSED + 1))
    else
        echo "❌ **状态**: 失败 (HTTP $http_status)" >> "$OUTPUT_FILE"
        FAILED=$((FAILED + 1))
    fi

    echo "" >> "$OUTPUT_FILE"
    echo "---" >> "$OUTPUT_FILE"
}

# =============================================================================
# 一、专家咨询 API
# =============================================================================

echo "开始测试专家咨询 API..." >> "$OUTPUT_FILE"

test_api "专家咨询 - 放款接口" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "放款是哪个接口？", "projectKey": "'"$PROJECT_KEY"'", "topK": 3}' \
    "DisburseHandler"

test_api "专家咨询 - 还款接口" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "有哪些还款相关的 Handler？", "projectKey": "'"$PROJECT_KEY"'", "topK": 5}' \
    "RepayHandler"

test_api "专家咨询 - Handler 接口列表" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "项目中有哪些 Handler 接口？", "projectKey": "'"$PROJECT_KEY"'", "topK": 10}' \
    "Handler"

test_api "专家咨询 - 数据库实体" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "项目中有哪些数据库实体？", "projectKey": "'"$PROJECT_KEY"'", "topK": 10}' \
    "实体"

test_api "专家咨询 - 贷款状态" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "贷款状态有哪些？", "projectKey": "'"$PROJECT_KEY"'", "topK": 5}' \
    "LoanStatus"

test_api "专家咨询 - 外调接口" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "项目调用了哪些外部接口？", "projectKey": "'"$PROJECT_KEY"'", "topK": 10}' \
    "外部"

test_api "专家咨询 - 项目结构" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "项目结构是什么样的？", "projectKey": "'"$PROJECT_KEY"'", "topK": 5}' \
    "结构"

test_api "专家咨询 - 技术栈" \
    "$BASE_URL/api/verify/expert_consult" \
    '{"question": "项目用了什么技术栈？", "projectKey": "'"$PROJECT_KEY"'", "topK": 5}' \
    "技术栈"

# =============================================================================
# 二、分析结果查询 API
# =============================================================================

echo "开始测试分析结果查询 API..." >> "$OUTPUT_FILE"

test_api "查询项目结构" \
    "$BASE_URL/api/verify/analysis_results" \
    '{"module": "project_structure", "projectKey": "'"$PROJECT_KEY"'", "page": 0, "size": 5}' \
    "模块"

test_api "查询技术栈" \
    "$BASE_URL/api/verify/analysis_results" \
    '{"module": "tech_stack_detection", "projectKey": "'"$PROJECT_KEY"'", "page": 0, "size": 5}' \
    "技术"

test_api "查询数据库实体" \
    "$BASE_URL/api/verify/analysis_results" \
    '{"module": "db_entity_detection", "projectKey": "'"$PROJECT_KEY"'", "page": 0, "size": 5}' \
    "实体"

test_api "查询 API 入口" \
    "$BASE_URL/api/verify/analysis_results" \
    '{"module": "api_entry_scanning", "projectKey": "'"$PROJECT_KEY"'", "page": 0, "size": 5}' \
    "接口"

test_api "查询外调接口" \
    "$BASE_URL/api/verify/analysis_results" \
    '{"module": "external_api_scanning", "projectKey": "'"$PROJECT_KEY"'", "page": 0, "size": 5}' \
    "外部"

test_api "查询枚举" \
    "$BASE_URL/api/verify/analysis_results" \
    '{"module": "enum_scanning", "projectKey": "'"$PROJECT_KEY"'", "page": 0, "size": 5}' \
    "枚举"

# =============================================================================
# 三、H2 数据库查询 API
# =============================================================================

echo "开始测试 H2 数据库查询 API..." >> "$OUTPUT_FILE"

test_api "查询向量总数" \
    "$BASE_URL/api/verify/execute_sql" \
    '{"sql": "SELECT COUNT(*) as total FROM vector_fragments"}' \
    "total"

test_api "查询向量分布" \
    "$BASE_URL/api/verify/execute_sql" \
    '{"sql": "SELECT SUBSTR(id, 1, CASE WHEN LOCATE('"'"':'"'"', id, 3) > 0 THEN LOCATE('"'"':'"'"', id, 3) - 1 ELSE LENGTH(id) END) as type, COUNT(*) as cnt FROM vector_fragments WHERE project_key = '"'"'"'"$PROJECT_KEY"'"'"'"' AND id LIKE '"'"'%:%'"'"' GROUP BY type ORDER BY cnt DESC"}' \
    "type"

test_api "查询 DisburseHandler" \
    "$BASE_URL/api/verify/execute_sql" \
    '{"sql": "SELECT id, title, content FROM vector_fragments WHERE id LIKE '"'"'%Disburse%'"'"'"}' \
    "Disburse"

test_api "查询 RepayHandler" \
    "$BASE_URL/api/verify/execute_sql" \
    '{"sql": "SELECT id, title, content FROM vector_fragments WHERE id LIKE '"'"'%Repay%'"'"'"}' \
    "Repay"

test_api "查询 LoanStatus 枚举" \
    "$BASE_URL/api/verify/execute_sql" \
    '{"sql": "SELECT id, title, content FROM vector_fragments WHERE id LIKE '"'"'%LoanStatus%'"'"'"}' \
    "LoanStatus"

# =============================================================================
# 测试总结
# =============================================================================

echo "" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "## 测试总结" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "- 总测试数: $TOTAL" >> "$OUTPUT_FILE"
echo "- 通过: $PASSED ✅" >> "$OUTPUT_FILE"
echo "- 失败: $FAILED ❌" >> "$OUTPUT_FILE"
echo "- 成功率: $(awk "BEGIN {printf \"%.1f\", $PASSED*100.0/$TOTAL}")%" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

echo "测试完成！结果已保存到: $OUTPUT_FILE"
echo "总计: $TOTAL, 通过: $PASSED, 失败: $FAILED"
