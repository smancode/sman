# LLM API 测试指南

## 修复内容

### 1. 环境变量占位符支持
修复了 `SmanAgentConfig.kt` 中 `llmApiKey` 的加载逻辑，现在支持 `${ENV_VAR_NAME}` 格式的环境变量占位符。

**配置文件** (`smanagent.properties`):
```properties
llm.api.key=${LLM_API_KEY}
```

**代码会自动解析**并从环境变量中读取实际的 API Key。

### 2. API URL 路径修复
修复了 `LlmService.kt` 中的 URL 拼接问题：

**修复前**:
```kotlin
.url(endpoint.baseUrl + "/chat/completions")
// 结果: https://xxx/api/paas/v4/chat/completions/chat/completions ❌
```

**修复后**:
```kotlin
.url(endpoint.baseUrl!!)
// 结果: https://xxx/api/paas/v4/chat/completions ✅
```

## 测试步骤

### 方式 1: 使用测试脚本（推荐）

```bash
# 1. 设置环境变量
export LLM_API_KEY=your_actual_api_key_here

# 2. 运行测试脚本
./test_llm_api.sh
```

### 方式 2: 在 IntelliJ IDEA 中测试

1. **设置环境变量**:
   - Run → Edit Configurations...
   - 选择 `SmanAgent [runIde]`
   - 在 Environment variables 中添加:
     ```
     LLM_API_KEY=your_actual_api_key_here
     ```

2. **运行插件**:
   ```bash
   ./gradlew runIde
   ```

3. **在插件中测试**:
   - 打开 SiliconMan 工具窗口
   - 发送测试消息: "你好"

### 方式 3: 手动 curl 测试

```bash
# 设置环境变量
export LLM_API_KEY=your_actual_api_key_here

# 测试 API
curl -X POST "https://open.bigmodel.cn/api/paas/v4/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -d '{
    "model": "glm-4-flash",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "max_tokens": 50
  }'
```

## 验证清单

- [ ] 环境变量已设置
- [ ] API Key 有效（非空、正确格式）
- [ ] 测试脚本运行成功
- [ ] 插件能正常启动
- [ ] 插件能成功调用 LLM API
- [ ] 无 401/404 错误

## 常见问题

### Q: 401 Unauthorized 错误
**原因**: API Key 无效或未设置
**解决**:
1. 检查环境变量是否正确设置: `echo $LLM_API_KEY`
2. 确认 API Key 格式正确（通常以特定前缀开头）
3. 使用测试脚本验证 API Key

### Q: 404 Not Found 错误
**原因**: API URL 路径错误
**解决**:
- 已修复代码中的 URL 拼接问题
- 配置文件中使用完整 URL: `https://open.bigmodel.cn/api/paas/v4/chat/completions`

### Q: 环境变量在 IDEA 中不生效
**原因**: IDEA 不会自动加载 ~/.bashrc 中的环境变量
**解决**: 在 IDEA 的 Run Configuration 中手动添加环境变量

## 下一步

测试通过后，可以：
1. 运行完整的单元测试: `./gradlew test`
2. 构建插件: `./gradlew buildPlugin`
3. 在实际项目中使用插件
