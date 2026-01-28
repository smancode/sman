#!/bin/bash

# 测试 GLM API 调用

echo "=== 测试 GLM API ==="
echo ""

# 加载环境变量
if [ -f ~/.bashrc ]; then
    eval "$(grep -h '^export[[:space:]]' ~/.bashrc 2>/dev/null | sed 's/^export //')"
fi

# 检查 API Key
if [ -z "$LLM_API_KEY" ]; then
    echo "❌ LLM_API_KEY 未设置"
    exit 1
fi

echo "✅ API Key 已设置 (长度: ${#LLM_API_KEY})"
echo ""

# 测试 1: coding 端点
echo "📡 测试 1: coding 端点"
echo "URL: https://open.bigmodel.cn/api/coding/paas/v4/chat/completions"
echo ""

curl -s -X POST "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -d '{
    "model": "glm-4-flash",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "max_tokens": 50
  }' > /tmp/glm_test_1.json

echo "响应:"
cat /tmp/glm_test_1.json | jq '.' 2>/dev/null || cat /tmp/glm_test_1.json
echo ""
echo ""

# 测试 2: 通用端点
echo "📡 测试 2: 通用端点"
echo "URL: https://open.bigmodel.cn/api/paas/v4/chat/completions"
echo ""

curl -s -X POST "https://open.bigmodel.cn/api/paas/v4/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -d '{
    "model": "glm-4-flash",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "max_tokens": 50
  }' > /tmp/glm_test_2.json

echo "响应:"
cat /tmp/glm_test_2.json | jq '.' 2>/dev/null || cat /tmp/glm_test_2.json
echo ""
echo ""

# 对比结果
echo "=== 结果对比 ==="
echo ""

HTTP_1=$(cat /tmp/glm_test_1.json | jq -r '.object // error' 2>/dev/null)
HTTP_2=$(cat /tmp/glm_test_2.json | jq -r '.object // error' 2>/dev/null)

echo "Coding 端点: $HTTP_1"
echo "通用端点:   $HTTP_2"
echo ""

if [ "$HTTP_1" = "chat.completion" ]; then
    echo "✅ Coding 端点可用"
else
    echo "❌ Coding 端点失败: $HTTP_1"
fi

if [ "$HTTP_2" = "chat.completion" ]; then
    echo "✅ 通用端点可用"
else
    echo "❌ 通用端点失败: $HTTP_2"
fi
