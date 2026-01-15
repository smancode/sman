#!/bin/bash

cd /Users/liuchao/projects/smanagent/ide-plugin

# 编译并运行测试
echo "编译测试..."
./gradlew compileTestKotlin --quiet

echo ""
echo "========================================"
echo "  运行 LocalToolExecutor 单元测试"
echo "========================================"
echo ""

# 使用 kotlin 编译器运行脚本
kotlinc -d /tmp/test \
    -cp "$(find ~/.gradle/caches -name 'kotlin-stdlib*.jar' | head -1)" \
    src/test/kotlin/com/smancode/smanagent/ide/service/LocalToolExecutorTest.kt \
    && kotlin -cp /tmp/test com.smancode.smanagent.ide.service.LocalToolExecutorTestKt
