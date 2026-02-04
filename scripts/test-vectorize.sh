#!/bin/bash
# 测试向量化 API

echo "启动验证服务..."
cd /Users/liuchao/projects/smanunion
./gradlew runVerification &
VERIFICATION_PID=$!

echo "等待服务启动（30秒）..."
sleep 30

echo "1. 测试服务状态..."
curl -s http://localhost:8080/api/verify/execute_sql -X POST -H 'Content-Type: application/json' -d '{"sql": "SELECT 1"}'

echo ""
echo "2. 执行向量化..."
curl -X POST http://localhost:8080/api/verify/vectorize_from_md \
  -H 'Content-Type: application/json' \
  -d '{"projectKey": "autoloop", "projectPath": "/Users/liuchao/projects/autoloop"}'

echo ""
echo "3. 等待5秒..."
sleep 5

echo ""
echo "4. 查询 RepayHandler 向量..."
curl -s http://localhost:8080/api/verify/execute_sql -X POST -H 'Content-Type: application/json' -d '{"sql": "SELECT id, title FROM vector_fragments WHERE id LIKE '\''%RepayHandler%'\'' OR id LIKE '\''%Repay%'\'' LIMIT 10"}'

echo ""
echo "5. 测试专家咨询..."
curl -s -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "还款入口是哪个", "projectKey": "autoloop", "topK": 5}'

echo ""
echo "停止服务..."
kill $VERIFICATION_PID 2>/dev/null
