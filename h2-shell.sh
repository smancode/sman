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

# 加载共享函数
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/h2-common.sh"

# 查找 H2 JAR
H2_JAR=$(find_h2_jar)
check_h2_jar_or_exit "$H2_JAR"

# 获取项目参数
PROJECT_KEY=${1:-$DEFAULT_PROJECT}
DB_DIR=$(get_project_db_dir "$PROJECT_KEY")
DB_FILE=$(get_project_db_file "$PROJECT_KEY")
JDBC_URL="jdbc:h2:$DB_DIR/analysis;MODE=PostgreSQL"

# 检查数据库文件
if ! db_file_exists "$PROJECT_KEY"; then
    print_db_not_found_warning "$PROJECT_KEY"
    echo "继续连接（会自动创建数据库）..."
    echo ""
fi

# 打印连接信息
print_separator "连接到 H2 数据库..."
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
print_separator
echo ""

# 启动 H2 Shell
java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "$JDBC_URL" \
    -user "sa"
