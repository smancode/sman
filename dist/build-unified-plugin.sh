#!/bin/bash
set -e

echo "=== SmanAgent 统一插件打包脚本 ==="
echo "这个脚本会将后端 JAR 打包到插件中，生成可直接安装的插件包"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

# 检查 Java 版本
echo "检查 Java 版本..."
java -version

# 1. 构建后端 Agent
echo ""
echo "[1/4] 构建后端 Agent..."
cd agent
./gradlew clean bootJar
cd ..

# 检查 JAR 是否生成
AGENT_JAR="agent/build/libs/smanagent-agent-1.0.0.jar"
if [ ! -f "$AGENT_JAR" ]; then
    echo "错误：后端 JAR 构建失败：$AGENT_JAR"
    exit 1
fi
echo "✓ 后端 JAR 构建完成: $AGENT_JAR"

# 2. 复制后端 JAR 到插件 lib 目录
echo ""
echo "[2/4] 复制后端 JAR 到插件 lib 目录..."
mkdir -p ide-plugin/lib
cp "$AGENT_JAR" ide-plugin/lib/
echo "✓ 后端 JAR 已复制到 ide-plugin/lib/"

# 3. 修改插件构建配置（如果还没有修改）
echo ""
echo "[3/4] 检查插件构建配置..."
if ! grep -q "prepareSandbox" ide-plugin/build.gradle.kts; then
    echo "⚠️  警告：ide-plugin/build.gradle.kts 中没有 prepareSandbox 配置"
    echo "   请确保插件构建时会包含 lib/ 目录中的 JAR"
fi

# 4. 构建插件
echo ""
echo "[4/4] 构建插件..."
cd ide-plugin
./gradlew clean buildPlugin
cd ..

# 检查构建产物
PLUGIN_ZIP="ide-plugin/build/distributions/ide-plugin-*.zip"
if ls $PLUGIN_ZIP 1> /dev/null 2>&1; then
    echo ""
    echo "=== 构建成功！==="
    echo ""
    echo "输出文件："
    ls -lh ide-plugin/build/distributions/*.zip
    echo ""
    echo "安装步骤："
    echo "1. 在 IntelliJ IDEA 中：File → Settings → Plugins"
    echo "2. 点击齿轮图标 → Install Plugin from Disk..."
    echo "3. 选择上面的 ZIP 文件"
    echo "4. 重启 IDE"
    echo ""
    echo "环境变量配置（必需）："
    echo "export SMANCODE_LLM_API_KEY=\"your-api-key\""
else
    echo "错误：插件构建失败"
    exit 1
fi
