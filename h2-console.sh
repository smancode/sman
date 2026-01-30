#!/bin/bash

# H2 Console 启动脚本
# 用于连接到 SmanAgent 的 H2 数据库

# H2 JAR 路径（自动查找）
H2_JAR=""
if [ -f "$HOME/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar" ]; then
    H2_JAR="$HOME/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar"
else
    # 如果默认路径不存在，尝试查找
    H2_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2 -name "h2-*.jar" ! -name "*javadoc*" ! -name "*sources*" -type f | head -1)
fi

if [ -z "$H2_JAR" ]; then
    echo "错误: 找不到 H2 JAR 文件"
    echo "请先运行: ./gradlew build"
    exit 1
fi

# 获取项目名称
PROJECT_KEY=${1:-"smanunion"}

# 数据库路径
DB_PATH="$HOME/.smanunion/$PROJECT_KEY/analysis"

echo "=========================================="
echo "H2 Console 启动中..."
echo "=========================================="
echo "项目: $PROJECT_KEY"
echo "数据库: $DB_PATH"
echo "H2 JAR: $H2_JAR"
echo ""
echo "启动后请访问:"
echo "  JDBC URL: jdbc:h2:$DB_PATH"
echo "  用户名: sa"
echo "  密码: (留空)"
echo ""
echo "按 Ctrl+C 停止服务器"
echo "=========================================="
echo ""

# 启动 H2 Console
java -cp "$H2_JAR" org.h2.tools.Console \
    -driver "org.h2.Driver" \
    -url "jdbc:h2:$DB_PATH" \
    -user "sa"
