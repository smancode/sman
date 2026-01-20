#!/bin/bash

# 打包 Java 文件脚本
# 用法: ./bzip-java.sh

ZIP_FILE="smanagent-java-only.zip"

echo "======================================"
echo "Java 文件打包脚本"
echo "======================================"
echo ""

# 清理旧文件
echo "[1/2] 清理旧的 zip 文件..."
rm -f ${ZIP_FILE}
if [ $? -eq 0 ]; then
    echo "  清理完成"
else
    echo "  清理失败"
    exit 1
fi
echo ""

# 新建打包
echo "[2/2] 打包 Java 文件到 ${ZIP_FILE}..."
find src/ -name '*.java' | zip -q ${ZIP_FILE} -@
if [ $? -eq 0 ]; then
    echo "  打包完成"
    echo ""
    echo "======================================"
    echo "打包成功！"
    echo "文件: ${ZIP_FILE}"
    echo "大小: $(ls -lh ${ZIP_FILE} | awk '{print $5}')"
    echo "包含文件数: $(unzip -l ${ZIP_FILE} | tail -1 | awk '{print $2}')"
    echo "======================================"
else
    echo "  打包失败"
    exit 1
fi
