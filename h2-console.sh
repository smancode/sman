#!/bin/bash

# H2 Console 启动脚本
# 用于连接到 SmanAgent 的 H2 数据库
#
# 使用方法:
#   ./h2-console.sh [projectKey]
#
# 示例:
#   ./h2-console.sh smanunion      # 连接到 smanunion 项目
#   ./h2-console.sh myproject      # 连接到 myproject 项目
#   ./h2-console.sh                # 默认连接到 smanunion 项目

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
JDBC_URL="jdbc:h2:$DB_DIR/analysis"

# 检查数据库文件
if ! db_file_exists "$PROJECT_KEY"; then
    print_db_not_found_warning "$PROJECT_KEY"
    echo "继续启动 Console（连接时会自动创建数据库）..."
    echo ""
fi

# 打印启动信息
print_separator "H2 Console 启动中..."
echo "项目: $PROJECT_KEY"
echo "数据库目录: $DB_DIR"
echo "数据库文件: $DB_FILE"
echo ""
echo "连接信息:"
echo "  JDBC URL: $JDBC_URL"
echo "  用户名: sa"
echo "  密码: (留空)"
echo ""
echo "提示: Console 会在浏览器中自动打开"
echo "      或手动访问 http://localhost:8082"
echo ""
echo "按 Ctrl+C 停止服务器"
print_separator
echo ""

# 启动 H2 Console
java -cp "$H2_JAR" org.h2.tools.Console \
    -driver "org.h2.Driver" \
    -url "$JDBC_URL" \
    -user "sa" \
    -webPort 8082
