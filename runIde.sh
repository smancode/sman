#!/bin/bash

# SmanAgent 插件启动脚本
# 自动加载 ~/.bashrc 中的环境变量（包括 LLM_API_KEY）

echo "=== SmanAgent 插件启动 ==="
echo ""

# 1. 加载 ~/.bashrc 中的环境变量
echo "📝 加载环境变量..."
if [ -f ~/.bashrc ]; then
    # 方式1: 先 source ~/.bashrc（会执行所有初始化）
    # 但可能会有副作用（如修改 PATH 等）

    # 方式2: 只提取 export 语句（更安全）
    # 从 .bashrc 中提取所有 export VAR=value 形式的变量
    eval "$(grep -h '^export[[:space:]]' ~/.bashrc 2>/dev/null | sed 's/^export //')"

    # 检查 LLM_API_KEY
    if [ -n "$LLM_API_KEY" ]; then
        echo "✅ LLM_API_KEY 已加载 (长度: ${#LLM_API_KEY})"
    else
        echo "⚠️  LLM_API_KEY 未在 ~/.bashrc 中找到"
        echo ""
        echo "请在 ~/.bashrc 中添加:"
        echo "  export LLM_API_KEY=your_api_key_here"
        echo ""
        echo "然后执行: source ~/.bashrc"
        echo ""
    fi
else
    echo "⚠️  ~/.bashrc 不存在"
fi
echo ""

# 2. 检查 Gradle
if [ ! -f "./gradlew" ]; then
    echo "❌ gradlew 不存在"
    exit 1
fi
echo ""

# 3. 准备插件资源（确保最新的 Prompt 文件被加载）
echo "📦 准备插件资源..."
./gradlew prepareSandbox --quiet
echo "✅ 插件资源已准备"
echo ""

# 4. 启动插件
echo "🚀 启动 IntelliJ IDEA 插件..."
echo ""
./gradlew runIde
