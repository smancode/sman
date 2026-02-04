#!/bin/bash
# 直接运行向量化恢复脚本

cd /Users/liuchao/projects/smanunion

# 运行 Gradle 任务
./gradlew runVerification --quiet &
VERIFICATION_PID=$!

echo "验证服务启动中... PID: $VERIFICATION_PID"

# 等待服务启动
sleep 30

# 测试服务是否启动
curl -s http://localhost:8080/api/verify/execute_sql -X POST -H 'Content-Type: application/json' -d '{"sql": "SELECT 1"}' > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "服务已启动，开始向量化..."

    # 调用向量化 API
    curl -s -X POST 'http://localhost:8080/api/verify/vectorize_from_md' \
        -H 'Content-Type: application/json' \
        -d '{
            "projectKey": "autoloop",
            "projectPath": "/Users/liuchao/projects/autoloop"
        }'

    echo ""
    echo "向量化完成"
else
    echo "服务启动失败"
fi

# 停止服务
kill $VERIFICATION_PID 2>/dev/null
