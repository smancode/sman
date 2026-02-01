#!/bin/bash

##############################################################################
# Autoloop 项目分析和测试脚本
#
# 功能：
#   1. 分析 autoloop 项目（9 个步骤）
#   2. 启动验证服务
#   3. 运行所有 HTTP 测试
##############################################################################

set -e

cd "$(dirname "$0")/.."

echo "=========================================="
echo "  Autoloop 项目完整分析"
echo "=========================================="
echo ""

# ================================
# 第一步：分析 autoloop 项目
# ================================
echo "[分析] 分析 autoloop 项目..."

# 使用 gradle 运行 SimpleAnalysisCli
./gradlew run --main-class="com.smancode.smanagent.analysis.cli.SimpleAnalysisCli" \
    --args="../autoloop" 2>&1 || echo "注意：分析需要在 IDEA 中运行"

echo ""

# ================================
# 第二步：启动验证服务
# ================================
echo "[服务] 启动验证服务..."

# 杀化：直接用 gradle bootRun 启动（如果有 Spring Boot 插件）
# 或者使用 java -cp

echo "验证服务需要在 IDEA 中启动"
echo "请在 IDEA 中打开 VerificationWebService.kt 并运行"
echo ""

# 等待服务启动
echo "请手动启动验证服务后按 Enter 继续..."
read

# 检查服务
if curl -s http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "✓ 验证服务已启动"
else
    echo "✗ 验证服务未启动"
    exit 1
fi

echo ""

# ================================
# 第三步：运行 HTTP 测试
# ================================
echo "[测试] 运行 HTTP 测试..."
echo ""

# 测试计数
PASS=0
FAIL=0

# 1. 健康检查
echo "测试 1: 健康检查"
if curl -s http://localhost:8080/actuator/health | grep -q "UP"; then
    echo "  ✓ 通过"
    PASS=$((PASS + 1))
else
    echo "  ✗ 失败"
    FAIL=$((FAIL + 1))
fi

# 2. 分析结果查询
MODULES=("project_structure" "tech_stack" "ast_scanning" "db_entities" "api_entries" "external_apis" "enums" "common_classes" "xml_code")
for module in "${MODULES[@]}"; do
    echo "测试: 查询 $module"
    response=$(curl -s -X POST http://localhost:8080/api/verify/analysis_results \
        -H "Content-Type: application/json" \
        -d "{\"module\": \"$module\", \"projectKey\": \"autoloop\"}")

    if [ -n "$response" ] && ! echo "$response" | grep -q "error\|Error"; then
        echo "  ✓ 通过"
        PASS=$((PASS + 1))
    else
        echo "  ✗ 失败"
        FAIL=$((FAIL + 1))
    fi
done

# 3. 语义搜索测试
QUERIES=("还款入口是哪个" "用户登录验证" "放款流程和入口")
for query in "${QUERIES[@]}"; do
    echo "测试: 搜索 '$query'"
    response=$(curl -s -X POST http://localhost:8080/api/verify/semantic_search \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\", \"projectKey\": \"autoloop\", \"topK\": 5, \"enableRerank\": true}")

    if [ -n "$response" ] && ! echo "$response" | grep -q "error\|Error"; then
        echo "  ✓ 通过"
        PASS=$((PASS + 1))
    else
        echo "  ✗ 失败"
        FAIL=$((FAIL + 1))
    fi
done

echo ""
echo "=========================================="
echo "  测试完成"
echo "=========================================="
echo "通过: $PASS"
echo "失败: $FAIL"
echo "=========================================="
