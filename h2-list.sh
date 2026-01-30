#!/bin/bash

# 列出所有 SmanAgent 项目及其数据库信息

# 加载共享函数
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/h2-common.sh"

# 检查目录是否存在
if [ ! -d "$SMANUNION_DIR" ]; then
    echo "未找到 .smanunion 目录"
    echo "请先运行 SmanAgent 插件"
    exit 1
fi

print_separator "SmanAgent 项目列表"
echo ""

# 列出所有项目（只显示有数据库文件的项目）
PROJECT_COUNT=0
for PROJECT_DIR in "$SMANUNION_DIR"/*; do
    if [ -d "$PROJECT_DIR" ]; then
        PROJECT_NAME=$(basename "$PROJECT_DIR")
        DB_FILE="$PROJECT_DIR/$DB_FILE_NAME"

        if [ -f "$DB_FILE" ]; then
            PROJECT_COUNT=$((PROJECT_COUNT + 1))

            echo "【项目 $PROJECT_COUNT】$PROJECT_NAME"
            echo "  路径: $PROJECT_DIR"

            FILE_SIZE=$(du -h "$DB_FILE" | cut -f1)
            MOD_TIME=$(stat -f "%Sm" -t "%Y-%m-%d %H:%M:%S" "$DB_FILE" 2>/dev/null || stat -c "%y" "$DB_FILE" 2>/dev/null | cut -d'.' -f1)
            echo "  数据库: $FILE_SIZE, 更新: $MOD_TIME"
            echo ""
        fi
    fi
done

# 显示汇总和连接方式
if [ $PROJECT_COUNT -eq 0 ]; then
    echo "（无任何项目数据库）"
    echo ""
    echo "提示: 运行 SmanAgent 插件后会自动创建项目数据库"
else
    print_separator
    echo "共 $PROJECT_COUNT 个项目"
    echo ""
    echo "连接方式:"
    echo "  ./h2-shell.sh <项目名>     # 命令行模式"
    echo "  ./h2-console.sh <项目名>   # Web 界面"
    echo ""
    echo "示例:"
    FIRST_PROJECT=$(get_first_project)
    if [ -n "$FIRST_PROJECT" ]; then
        echo "  ./h2-shell.sh $FIRST_PROJECT"
    fi
    print_separator
fi

