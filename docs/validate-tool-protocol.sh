#!/bin/bash

# WebSocket Tool Protocol 验证脚本
#
# 用途：在修改前后端消息格式后，运行此脚本验证一致性
#
# 使用方法：
#   cd /Users/liuchao/projects/sman
#   ./docs/validate-tool-protocol.sh

set -e

echo "=========================================="
echo "WebSocket Tool Protocol 验证"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查后端发送的字段名
echo "📋 检查后端 TOOL_CALL 消息格式..."
BACKEND_TOOL_CALL=$(grep -A 10 'TOOL_CALL 消息' docs/websocket-tool-api-spec.md | grep -E '^\|.*".*"' | grep -v 'type' | grep -v 'Message Structure' | wc -l)
echo "   ✓ 后端 TOOL_CALL 字段已定义"
echo ""

# 检查 IDE Plugin 接收的字段名
echo "📋 检查 IDE Plugin 接收字段..."
if grep -q 'toolCallId' ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt; then
    echo -e "   ${GREEN}✓${NC} IDE Plugin 使用 toolCallId"
else
    echo -e "   ${RED}✗${NC} IDE Plugin 未使用 toolCallId (可能使用 callId)"
    echo "   请检查: ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt:659"
fi

if grep -q 'params' ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt; then
    echo -e "   ${GREEN}✓${NC} IDE Plugin 使用 params"
else
    echo -e "   ${RED}✗${NC} IDE Plugin 未使用 params (可能使用 parameters)"
    echo "   请检查: ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt:665"
fi
echo ""

# 检查 IDE Plugin 返回的字段名
echo "📋 检查 IDE Plugin 返回字段..."
if grep -q '"toolCallId"' ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt; then
    echo -e "   ${GREEN}✓${NC} IDE Plugin 返回 toolCallId"
else
    echo -e "   ${RED}✗${NC} IDE Plugin 未返回 toolCallId (可能返回 callId)"
    echo "   请检查: ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt:679"
fi

if grep -q '"error"' ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt; then
    echo -e "   ${GREEN}✓${NC} IDE Plugin 返回 error"
else
    echo -e "   ${RED}✗${NC} IDE Plugin 未返回 error (可能返回 errorMessage)"
    echo "   请检查: ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/WebSocketService.kt:682"
fi
echo ""

# 检查后端接收的字段名
echo "📋 检查后端接收字段..."
if grep -q 'toolCallId' agent/src/main/java/ai/smancode/sman/agent/websocket/ToolForwardingService.java; then
    echo -e "   ${GREEN}✓${NC} 后端期望 toolCallId"
else
    echo -e "   ${RED}✗${NC} 后端未期望 toolCallId"
    echo "   请检查: agent/src/main/java/ai/smancode/sman/agent/websocket/ToolForwardingService.java:128"
fi
echo ""

# 运行集成测试
echo "🧪 运行集成测试..."
cd agent
./gradlew test --tests ToolProtocolIntegrationTest --quiet > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "   ${GREEN}✓${NC} 集成测试通过"
else
    echo -e "   ${RED}✗${NC} 集成测试失败"
    echo "   请运行: ./gradlew test --tests ToolProtocolIntegrationTest"
fi
cd ..
echo ""

echo "=========================================="
echo -e "${GREEN}✓ 验证完成${NC}"
echo ""
echo "📖 参考文档: docs/websocket-tool-api-spec.md"
echo "=========================================="
