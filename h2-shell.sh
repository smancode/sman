#!/bin/bash

# H2 Shell 连接脚本
# 直接在命令行连接到 H2 数据库
#
# 使用方法:
#   ./h2-shell.sh [projectKey]
#
# 示例:
#   ./h2-shell.sh smanunion      # 连接到 smanunion 项目
#   ./h2-shell.sh myproject      # 连接到 myproject 项目
#   ./h2-shell.sh                # 默认连接到 smanunion 项目

# H2 JAR 路径（自动查找）
H2_JAR=""
if [ -f "$HOME/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar" ]; then
    H2_JAR="$HOME/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar"
else
    # 如果默认路径不存在，尝试查找
    H2_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2 -name "h2-*.jar" ! -name "*javadoc*" ! -name "*sources*" -type f 2>/dev/null | head -1)
fi

if [ -z "$H2_JAR" ]; then
    echo "错误: 找不到 H2 JAR 文件"
    echo "请先运行: ./gradlew build"
    exit 1
fi

# 获取项目名称
PROJECT_KEY=${1:-"smanunion"}

# 数据库路径（使用完整路径，H2 不支持 ~）
DB_DIR="$HOME/.smanunion/$PROJECT_KEY"
DB_FILE="$DB_DIR/analysis.mv.db"

# 检查数据库文件是否存在
if [ ! -f "$DB_FILE" ]; then
    echo "=========================================="
    echo "警告: 数据库文件不存在"
    echo "=========================================="
    echo "项目: $PROJECT_KEY"
    echo "预期路径: $DB_FILE"
    echo ""
    echo "查看已有项目:"
    ls -la ~/.smanunion/ 2>/dev/null || echo "  (无任何项目)"
    echo ""
    echo "继续连接（会自动创建数据库）..."
    echo "=========================================="
    echo ""
fi

# H2 JDBC URL 必须使用完整路径（不能包含 .mv.db 后缀）
JDBC_URL="jdbc:h2:$DB_DIR/analysis;MODE=PostgreSQL"

echo "=========================================="
echo "连接到 H2 数据库..."
echo "=========================================="
echo "项目: $PROJECT_KEY"
echo "数据库目录: $DB_DIR"
echo "数据库文件: $DB_FILE"
echo ""
echo "JDBC URL: $JDBC_URL"
echo ""
echo "常用命令:"
echo "  SHOW TABLES;              -- 查看所有表"
echo "  SELECT * FROM config;     -- 查询配置"
echo "  SELECT COUNT(*) FROM vector_fragments;  -- 查询向量片段"
echo "  exit 或 quit              -- 退出"
echo ""
echo "=========================================="
echo ""

# 启动 H2 Shell
java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "$JDBC_URL" \
    -user "sa"
