# Claude Code 集成方案

## 问题

VSCode的Claude Code CLI是**交互式工具**，不支持stdio模式，无法直接集成到Agent进程池。

## 解决方案

### 方案1：直接使用 Anthropic API ⭐ 推荐

**优势**：
- ✅ 完全控制，无需外部进程
- ✅ 稳定可靠，无需担心进程崩溃
- ✅ 支持流式响应
- ✅ Windows/Linux/macOS 通用

**实现**：

```java
@Service
public class ClaudeAIService {
    private final OkHttpClient httpClient;
    private final String apiKey = System.getenv("ANTHROPIC_API_KEY");

    public String chat(String prompt, String projectContext) {
        // 直接调用 Anthropic API
        String requestBody = """
            {
                "model": "claude-sonnet-4-20250514",
                "max_tokens": 4096,
                "messages": [
                    {"role": "user", "content": "%s"}
                ]
            }
            """.formatted(prompt);

        Request request = new Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.body().string();
        }
    }
}
```

**配置**：
```yaml
anthropic:
  api-key: ${ANTHROPIC_API_KEY}
  model: claude-sonnet-4-20250514
  max-tokens: 4096
```

### 方案2：增强Mock脚本（临时方案）

如果暂时没有API Key，可以让Mock更智能：

```bash
#!/bin/bash
# /tmp/claude-code-smart-mock

while IFS= read -r line; do
    # 解析请求
    if echo "$line" | grep -q "JavaFileService"; then
        cat << 'EOF'
## 分析结果

根据代码搜索和调用链分析：

### 1. JavaFileService 的职责
- 文件读取操作
- 异常处理和重试机制
- 文件路径解析

### 2. 关键发现
- 当前重试次数：1次
- 建议：根据业务需求调整

### 3. 相关代码
- FileReadUtil.java: readWithRetry()
- FileExceptionHandler.java: handleReadError()

=====END_OF_RESPONSE=====
EOF
    elif echo "$line" | grep -q "重试"; then
        cat << 'EOF'
## 增加重试功能的实现方案

### 当前问题
文件读取异常时只重试1次，可能导致失败

### 建议修改
```java
// 位置：src/main/java/io/FileReader.java
// 当前代码
int maxRetries = 1;

// 修改为
int maxRetries = 3;  // 增加到3次
```

### 风险评估
- 影响范围：文件读取逻辑
- 测试建议：验证网络抖动场景

=====END_OF_RESPONSE=====
EOF
    else
        cat << 'EOF'
我需要更多信息来分析这个问题。请提供：
1. 具体的类名或方法名
2. 遇到的错误或异常
3. 期望的行为

=====END_OF_RESPONSE=====
EOF
    fi
done
```

### 方案3：自定义 Claude CLI Wrapper

创建一个包装脚本，调用Anthropic API：

```bash
#!/bin/bash
# /tmp/claude-code-api-wrapper

# 调用 Anthropic API
API_KEY="${ANTHROPIC_API_KEY}"

while IFS= read -r line; do
    # 发送到API
    RESPONSE=$(curl -s https://api.anthropic.com/v1/messages \
        -H "x-api-key: $API_KEY" \
        -H "anthropic-version: 2023-06-01" \
        -H "content-type: application/json" \
        -d "{
            \"model\": \"claude-sonnet-4-20250514\",
            \"max_tokens\": 4096,
            \"messages\": [{\"role\": \"user\", \"content\": \"$line\"}]
        }")

    # 解析并返回响应
    echo "$RESPONSE" | jq -r '.content[0].text'
    echo "=====END_OF_RESPONSE====="
done
```

## 推荐实施步骤

### 第一阶段：使用智能Mock（1-2天）
1. 实现增强的Mock脚本
2. 支持多种请求模式
3. 添加项目上下文感知

### 第二阶段：集成API（1周）
1. 申请 Anthropic API Key
2. 实现 ClaudeAIService
3. 替换进程池为直接API调用
4. 添加错误处理和重试

### 第三阶段：优化（持续）
1. 添加向量搜索集成
2. 实现上下文缓存
3. 支持流式响应

## 配置选项

**application.yml**：
```yaml
claude-code:
  # 选择实现方式
  implementation: api  # api | mock | wrapper

  # API配置
  api:
    enabled: true
    api-key: ${ANTHROPIC_API_KEY}
    model: claude-sonnet-4-20250514
    max-tokens: 4096
    timeout: 120

  # Mock配置（测试用）
  mock:
    enabled: false
    script-path: /tmp/claude-code-smart-mock
```

## 下一步

请确认：
1. ✅ 是否有 Anthropic API Key？
2. ✅ 优先使用哪个方案？
3. ✅ 是否需要我实现API集成？
