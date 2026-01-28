#!/bin/bash

# 测试 LLM API 连接
# 用法: ./test_llm_api.sh

echo "=== SmanAgent LLM API 测试 ==="
echo ""

# 检查环境变量
if [ -z "$LLM_API_KEY" ]; then
    echo "❌ 错误: LLM_API_KEY 环境变量未设置"
    echo ""
    echo "请先设置环境变量:"
    echo "  export LLM_API_KEY=your_api_key_here"
    echo ""
    exit 1
fi

echo "✅ API Key 已设置 (长度: ${#LLM_API_KEY})"
echo ""

# 测试 API 端点
API_URL="https://open.bigmodel.cn/api/paas/v4/chat/completions"

echo "📡 测试 API 端点: $API_URL"
echo ""

# 构建测试请求
curl -s -X POST "$API_URL" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -d '{
    "model": "glm-4-flash",
    "messages": [
      {"role": "user", "content": "你好，请回复\"测试成功\""}
    ],
    "max_tokens": 50
  }' > /tmp/llm_test_response.json

# 检查响应
if [ $? -eq 0 ]; then
    echo "✅ HTTP 请求成功"
    echo ""

    # 解析响应
    HTTP_CODE=$(cat /tmp/llm_test_response.json | jq -r '.object // "error"')

    if [ "$HTTP_CODE" = "chat.completion" ]; then
        echo "✅ API 调用成功"
        echo ""
        echo "📝 响应内容:"
        cat /tmp/llm_test_response.json | jq -r '.choices[0].message.content'
        echo ""
        echo "📊 完整响应:"
        cat /tmp/llm_test_response.json | jq '.'
        exit 0
    else
        echo "❌ API 返回错误:"
        cat /tmp/llm_test_response.json | jq '.'
        exit 1
    fi
else
    echo "❌ HTTP 请求失败"
    exit 1
fi
