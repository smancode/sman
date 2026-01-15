#!/bin/bash

# 测试 read_file 工具是否返回 relativePath

echo "=== 测试 read_file 工具 ==="
echo ""
echo "测试步骤："
echo "1. 启动后端服务"
echo "2. 在 IDE 中触发 read_file 工具（使用 simpleName 参数）"
echo "3. 检查前端日志中是否有：relativePath=agent/src/main/java/... "
echo "4. 检查后端日志中是否有：has relativePath=true"
echo ""
echo "预期结果："
echo "- 前端日志应显示：relativePath=<相对路径>"
echo "- 后端日志应显示：has relativePath=true"
echo "- 后端日志应显示：【IDE返回relativePath】<相对路径>"
echo ""
