#!/bin/bash

# SiliconMan Agent 工具测试脚本
# 测试 semantic_search, vector_search, call_chain 等核心工具

BASE_URL="http://localhost:8080"
PROJECT_KEY="autoloop"

echo "========================================="
echo "SiliconMan Agent 工具测试"
echo "========================================="
echo ""

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试函数
test_tool() {
    local tool_name=$1
    local params=$2

    echo -e "${YELLOW}测试工具: ${tool_name}${NC}"
    echo "参数: ${params}"

    response=$(curl -s -X POST "${BASE_URL}/api/claude-code/tools/execute" \
        -H "Content-Type: application/json" \
        -d "{
            \"tool\": \"${tool_name}\",
            \"params\": ${params},
            \"projectKey\": \"${PROJECT_KEY}\",
            \"sessionId\": \"test-session\"
        }")

    # 检查响应
    if echo "$response" | grep -q '"success":true'; then
        echo -e "${GREEN}✅ 成功${NC}"
        echo "$response" | jq '.result' 2>/dev/null || echo "$response"
    else
        echo -e "${RED}❌ 失败${NC}"
        echo "$response" | jq '.' 2>/dev/null || echo "$response"
    fi

    echo ""
    echo "----------------------------------------"
    echo ""
}

# 1. 健康检查
echo "1️⃣  健康检查"
health=$(curl -s "${BASE_URL}/api/claude-code/health")
if [ "$health" = "OK" ]; then
    echo -e "${GREEN}✅ 服务正常运行${NC}"
else
    echo -e "${RED}❌ 服务未启动${NC}"
    exit 1
fi
echo ""

# 2. 测试 semantic_search (向量语义搜索)
test_tool "semantic_search" '{
    "recallQuery": "文件过滤",
    "recallTopK": 10,
    "rerankQuery": "按扩展名过滤文件",
    "rerankTopN": 5,
    "enableReranker": false
}'

# 3. 测试 vector_search (向量搜索别名)
test_tool "vector_search" '{
    "query": "FileFilter",
    "top_k": 5
}'

# 4. 测试 call_chain (调用链分析)
test_tool "call_chain" '{
    "method": "FileFilter.accept",
    "direction": "both",
    "depth": 2
}'

# 5. 测试 read_class (读取类结构)
test_tool "read_class" '{
    "className": "FileFilter",
    "mode": "structure"
}'

# 6. 测试 text_search (文本搜索)
test_tool "text_search" '{
    "keyword": "public.*filter",
    "regex": true,
    "limit": 10
}'

echo "========================================="
echo "测试完成"
echo "========================================="
