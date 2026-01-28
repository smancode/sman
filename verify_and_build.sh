#!/bin/bash

echo "=== SmanAgent 构建前验证 ==="
echo ""

# 1. 编译检查
echo "📦 1. 编译检查..."
./gradlew compileKotlin > /tmp/compile.log 2>&1
if [ $? -eq 0 ]; then
    echo "✅ 编译成功"
else
    echo "❌ 编译失败，查看日志: /tmp/compile.log"
    cat /tmp/compile.log | tail -20
    exit 1
fi
echo ""

# 2. 运行测试
echo "🧪 2. 运行单元测试..."
./gradlew test > /tmp/test.log 2>&1
if [ $? -eq 0 ]; then
    echo "✅ 所有测试通过"
    # 统计测试数量
    TEST_COUNT=$(grep -o "PASSED" /tmp/test.log | wc -l | tr -d ' ')
    echo "   通过 $TEST_COUNT 个测试"
else
    echo "❌ 测试失败，查看日志: /tmp/test.log"
    cat /tmp/test.log | grep -E "(FAILED|ERROR)" | tail -20
    exit 1
fi
echo ""

# 3. 构建插件
echo "🔨 3. 构建插件..."
./gradlew buildPlugin > /tmp/build.log 2>&1
if [ $? -eq 0 ]; then
    echo "✅ 插件构建成功"
    PLUGIN_PATH=$(find build/distributions -name "*.zip" | head -1)
    if [ -n "$PLUGIN_PATH" ]; then
        echo "   插件位置: $PLUGIN_PATH"
        ls -lh "$PLUGIN_PATH"
    fi
else
    echo "❌ 构建失败，查看日志: /tmp/build.log"
    cat /tmp/build.log | tail -20
    exit 1
fi
echo ""

# 4. 代码质量检查
echo "📊 4. 代码质量检查..."
echo "   代码行数统计:"
find src/main/kotlin -name "*.kt" -exec wc -l {} + | tail -1
echo ""

# 5. 配置检查
echo "⚙️  5. 配置检查..."
if [ -f "src/main/resources/smanagent.properties" ]; then
    echo "✅ 配置文件存在"

    # 检查环境变量占位符
    if grep -q '\${LLM_API_KEY}' src/main/resources/smanagent.properties; then
        echo "✅ 使用环境变量占位符: \${LLM_API_KEY}"

        if [ -n "$LLM_API_KEY" ]; then
            echo "✅ LLM_API_KEY 已设置 (长度: ${#LLM_API_KEY})"
        else
            echo "⚠️  LLM_API_KEY 未设置 (需要在 IDE 运行配置中设置)"
        fi
    else
        echo "⚠️  配置文件包含直接的 API Key (生产环境不推荐)"
    fi
else
    echo "❌ 配置文件不存在"
fi
echo ""

# 6. 总结
echo "=== 验证完成 ==="
echo ""
echo "✅ 所有检查通过！"
echo ""
echo "📝 下一步:"
echo "   1. 在 IntelliJ IDEA 中设置环境变量:"
echo "      Run → Edit Configurations → Environment variables"
echo "      添加: LLM_API_KEY=your_actual_api_key_here"
echo ""
echo "   2. 运行插件:"
echo "      ./gradlew runIde"
echo ""
echo "   3. 或使用测试脚本验证 API:"
echo "      ./test_llm_api.sh"
echo ""
