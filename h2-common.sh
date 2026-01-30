#!/bin/bash

# H2 工具共享函数库
# 被 h2-shell.sh, h2-console.sh, h2-list.sh 使用

# 常量定义
DEFAULT_PROJECT="smanunion"
H2_VERSION="2.2.224"
H2_JAR_DEFAULT_PATH="$HOME/.gradle/caches/modules-2/files-2.1/com.h2database/h2/$H2_VERSION/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-$H2_VERSION.jar"
SMANUNION_DIR="$HOME/.smanunion"
DB_FILE_NAME="analysis.mv.db"

# 查找 H2 JAR 文件
find_h2_jar() {
    # 优先使用默认路径（更快）
    if [ -f "$H2_JAR_DEFAULT_PATH" ]; then
        echo "$H2_JAR_DEFAULT_PATH"
        return 0
    fi

    # 如果默认路径不存在，尝试查找
    local jar=$(find ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2 \
        -name "h2-*.jar" \
        ! -name "*javadoc*" \
        ! -name "*sources*" \
        -type f 2>/dev/null | head -1)

    if [ -n "$jar" ]; then
        echo "$jar"
        return 0
    fi

    return 1
}

# 检查 H2 JAR 是否可用，如果不可用则退出
check_h2_jar_or_exit() {
    local h2_jar=$1

    if [ -z "$h2_jar" ]; then
        echo "错误: 找不到 H2 JAR 文件"
        echo "请先运行: ./gradlew build"
        exit 1
    fi
}

# 获取项目数据库目录
get_project_db_dir() {
    local project_key=$1
    echo "$SMANUNION_DIR/$project_key"
}

# 获取项目数据库文件路径
get_project_db_file() {
    local project_key=$1
    echo "$(get_project_db_dir "$project_key")/$DB_FILE_NAME"
}

# 检查数据库文件是否存在
db_file_exists() {
    local project_key=$1
    local db_file=$(get_project_db_file "$project_key")
    [ -f "$db_file" ]
}

# 打印横线分隔符
print_separator() {
    local title=${1:-""}
    if [ -n "$title" ]; then
        echo "=========================================="
        echo "$title"
        echo "=========================================="
    else
        echo "=========================================="
    fi
}

# 列出所有有数据库的项目
list_projects_with_db() {
    local count=0

    for project_dir in "$SMANUNION_DIR"/*; do
        if [ -d "$project_dir" ]; then
            local db_file="$project_dir/$DB_FILE_NAME"
            if [ -f "$db_file" ]; then
                count=$((count + 1))
                local project_name=$(basename "$project_dir")
                echo "$project_name"
            fi
        fi
    done

    echo "$count"
}

# 获取第一个项目名称（用于示例）
get_first_project() {
    for project_dir in "$SMANUNION_DIR"/*; do
        if [ -d "$project_dir" ]; then
            local db_file="$project_dir/$DB_FILE_NAME"
            if [ -f "$db_file" ]; then
                basename "$project_dir"
                return 0
            fi
        fi
    done
    return 1
}

# 打印项目不存在警告
print_db_not_found_warning() {
    local project_key=$1
    local db_file=$(get_project_db_file "$project_key")

    print_separator "警告: 数据库文件不存在"
    echo "项目: $project_key"
    echo "预期路径: $db_file"
    echo ""
    echo "可能的原因:"
    echo "  1. 项目还未运行过，数据库未创建"
    echo "  2. 项目名称不正确"
    echo ""
    echo "查看已有项目:"
    if [ -d "$SMANUNION_DIR" ]; then
        ls -la "$SMANUNION_DIR" 2>/dev/null | grep "^d" | awk '{print "  " $NF}' | grep -v "^\.$" | grep -v "^\..$" || echo "  (无任何项目)"
    else
        echo "  (无任何项目)"
    fi
    print_separator
    echo ""
}
