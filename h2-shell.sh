#!/bin/bash

# H2 Shell 连接脚本
# 直接在命令行连接到 H2 数据库

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
echo "连接到 H2 数据库..."
echo "=========================================="
echo "项目: $PROJECT_KEY"
echo "数据库: $DB_PATH"
echo ""
echo "输入 SQL 命令，输入 'exit' 或 'quit' 退出"
echo "=========================================="
echo ""

# 启动 H2 Shell
java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "jdbc:h2:$DB_PATH;MODE=PostgreSQL" \
    -user "sa"
