# HTTP 429 错误修复说明

## 问题分析

### 症状
```
HTTP 错误: 429 (Too Many Requests)
```

### 根本原因

对比了 `../smanagent/agent` 项目后发现问题：

**agent 项目（正常工作）**:
```yaml
base-url: https://open.bigmodel.cn/api/coding/paas/v4
model: GLM-4.7
```

**当前项目（429 错误）**:
```properties
llm.base.url=https://open.bigmodel.cn/api/paas/v4/chat/completions
llm.model.name=glm-4.7
```

**关键区别**：
- agent 项目使用 **coding 专用端点** (`/api/coding/paas/v4`)
- 当前项目使用 **通用端点** (`/api/paas/v4/chat/completions`)

**为什么之前没发现这个问题？**
- 之前代码有 bug：URL 拼接错误导致 404，请求根本没有到达正确的 API
- 现在 URL 修复后，请求到达了正确的 API，但使用了错误的端点，导致 429 速率限制

### 429 错误原因

通用端点 (`/api/paas/v4/chat/completions`) 可能有更严格的速率限制，而 coding 专用端点 (`/api/coding/paas/v4`) 针对代码生成场景优化，限制更宽松。

## 解决方案

### 1. 修改 API 端点（主要修复）

**文件**: `src/main/resources/smanagent.properties`

```properties
# 修改前（导致 429）
llm.base.url=https://open.bigmodel.cn/api/paas/v4/chat/completions

# 修改后（与 agent 项目一致）
llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
```

### 2. 改进重试逻辑（额外增强）

**文件**: `src/main/kotlin/com/smancode/smanagent/smancode/llm/LlmService.kt`

对 429 错误使用指数退避策略：
```kotlin
val delay = if (e.message?.contains("429") == true) {
    // 指数退避: 5s, 10s, 20s
    val exponentialDelay = baseDelay * Math.pow(2.0, retryCount.toDouble()).toLong()
    exponentialDelay
} else {
    // 其他错误: 线性退避
    poolConfig.retry.calculateDelay(retryCount)
}
```

### 3. 增加重试延迟

**文件**: `src/main/resources/smanagent.properties`

```properties
# 修改前
llm.retry.base.delay=1000  # 1 秒

# 修改后
llm.retry.base.delay=5000  # 5 秒
```

## 验证清单

- [x] API 端点已修改为 coding 专用端点
- [x] 重试延迟已增加到 5 秒
- [x] 429 错误使用指数退避
- [x] 编译成功
- [x] 插件构建成功

## 使用说明

现在重新运行插件即可：

```bash
./runIde.sh
```

或者如果直接在 IDEA 中运行，确保重新加载配置。

## 总结

这次修复对齐了当前项目与 agent 项目的 LLM 配置，使用相同的 API 端点，确保一致的行为和性能。
