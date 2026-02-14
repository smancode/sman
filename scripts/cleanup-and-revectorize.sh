#!/bin/bash
# 清理包含 thinking 标签的向量数据并重新向量化脚本
#
# 用法: ./scripts/cleanup-and-revectorize.sh [project-path]
# 示例: ./scripts/cleanup-and-revectorize.sh /Users/liuchao/projects/autoloop

set -e

PROJECT_PATH=${1:-"/Users/liuchao/projects/autoloop"}
SMAN_DIR="$PROJECT_PATH/.sman"

echo "=========================================="
echo "  清理向量数据并重新向量化"
echo "=========================================="
echo "项目路径: $PROJECT_PATH"
echo "数据目录: $SMAN_DIR"
echo ""

# 检查目录是否存在
if [ ! -d "$SMAN_DIR" ]; then
    echo "错误: .sman 目录不存在: $SMAN_DIR"
    exit 1
fi

# 确认删除
echo "即将删除以下数据:"
echo "  - H2 数据库: $SMAN_DIR/analysis.mv.db.mv.db"
echo "  - MD 文件: $SMAN_DIR/md/"
echo "  - Base 文件: $SMAN_DIR/base/"
echo "  - Cache 文件: $SMAN_DIR/cache/"
echo ""
read -p "确认删除？(y/n) " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "已取消"
    exit 0
fi

# 删除数据
echo ""
echo "[1/2] 删除向量数据..."

# 删除 H2 数据库
if [ -f "$SMAN_DIR/analysis.mv.db.mv.db" ]; then
    rm -f "$SMAN_DIR/analysis.mv.db.mv.db"
    echo "  ✓ 已删除 H2 数据库"
fi

# 删除 md 目录
if [ -d "$SMAN_DIR/md" ]; then
    rm -rf "$SMAN_DIR/md"
    echo "  ✓ 已删除 md 目录"
fi

# 删除 base 目录
if [ -d "$SMAN_DIR/base" ]; then
    rm -rf "$SMAN_DIR/base"
    echo "  ✓ 已删除 base 目录"
fi

# 删除 cache 目录
if [ -d "$SMAN_DIR/cache" ]; then
    rm -rf "$SMAN_DIR/cache"
    echo "  ✓ 已删除 cache 目录"
fi

echo ""
echo "[2/2] 数据清理完成！"
echo ""
echo "下一步："
echo "  1. 在 IntelliJ IDEA 中右键点击项目"
echo "  2. 选择 'SmanCode' -> 'Run Analysis'"
echo "  3. 等待分析完成，向量化将自动进行"
echo ""
echo "=========================================="
