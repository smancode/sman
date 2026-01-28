# SmanAgent 配置指南

## 配置文件位置

配置文件位于：`src/main/resources/smanagent.properties`

**重要**：所有配置都已从硬编码迁移到配置文件，不再有任何硬编码的配置值。

## 配置项说明

### LLM API 配置

| 配置项 | 说明 | 默认值 | 环境变量 |
|--------|------|--------|----------|
| `llm.api.key` | LLM API Key | - | `LLM_API_KEY` |
| `llm.base.url` | LLM API 端点 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | - |
| `llm.model.name` | 模型名称 | `glm-4-flash` | - |
| `llm.max.tokens` | 最大 Token 数 | `8192` | - |

### 重试配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `llm.retry.max` | 最大重试次数 | `3` |
| `llm.retry.base.delay` | 重试基础延迟（毫秒） | `1000` |

### 超时配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `llm.timeout.connect` | 连接超时（毫秒） | `30000` |
| `llm.timeout.read` | 读取超时（毫秒） | `60000` |
| `llm.timeout.write` | 写入超时（毫秒） | `30000` |

### ReAct 循环配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `react.max.steps` | 最大步数 | `10` |
| `react.enable.streaming` | 启用流式输出 | `true` |

### 上下文压缩配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `compaction.max.tokens` | 压缩最大 Token 数 | `100000` |
| `compaction.threshold` | 压缩阈值（Token 数） | `80000` |
| `compaction.enable.intelligent` | 启用智能压缩 | `true` |

### 模型参数配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `llm.temperature` | 温度参数 | `0.7` |
| `llm.default.max.tokens` | 默认最大 Token 数 | `4096` |

## 配置方式

配置优先级：**配置文件 > 环境变量 > 默认值**

### 方式 1：配置文件（推荐）

编辑 `src/main/resources/smanagent.properties`：

```properties
# 直接配置 API Key
llm.api.key=your_actual_api_key_here
llm.base.url=https://open.bigmodel.cn/api/paas/v4/chat/completions
llm.model.name=glm-4-flash
llm.max.tokens=8192

# 重试配置
llm.retry.max=3
llm.retry.base.delay=1000

# 超时配置（毫秒）
llm.timeout.connect=30000
llm.timeout.read=60000
llm.timeout.write=30000

# ReAct 循环配置
react.max.steps=10
react.enable.streaming=true

# 上下文压缩配置
compaction.max.tokens=100000
compaction.threshold=80000
compaction.enable.intelligent=true

# 模型参数配置
llm.temperature=0.7
llm.default.max.tokens=4096
```

**注意**：直接在配置文件中填写 API Key 仅用于本地开发，**不要将包含真实 API Key 的配置文件提交到版本控制系统**。

### 方式 2：环境变量 + 配置文件

在 `smanagent.properties` 中使用环境变量占位符：

```properties
# 使用环境变量
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/paas/v4/chat/completions
```

然后在 IDE 或系统中设置环境变量：

**IDE 配置：**
1. Run → Edit Configurations...
2. 选择你的插件运行配置
3. 在 Environment variables 中添加：
   ```
   LLM_API_KEY=your_api_key_here
   ```

**系统环境变量：**
```bash
export LLM_API_KEY=your_api_key_here
```

**或在 `~/.zshrc` 或 `~/.bash_profile` 中添加：**
```bash
export LLM_API_KEY=your_api_key_here
```

### 方式 3：纯环境变量

如果不想使用配置文件，可以完全依赖环境变量（需要有 `LLM_API_KEY` 环境变量）。

## 不同环境配置

### 开发环境

```properties
# 开发环境可以使用默认配置
llm.model.name=glm-4-flash
llm.max.tokens=8192
react.max.steps=10
```

### 生产环境

```properties
# 生产环境使用更强大的模型
llm.model.name=glm-4-plus
llm.max.tokens=16384
llm.retry.max=5
react.max.steps=15
compaction.max.tokens=200000
```

### 调试环境

```properties
# 调试环境启用详细日志
llm.retry.max=1
llm.retry.base.delay=500
react.max.steps=5
react.enable.streaming=false
```

## 配置迁移说明

所有之前硬编码在代码中的配置值都已迁移到 `smanagent.properties`：

1. **LlmService.kt**: 超时配置（connectTimeout, readTimeout, writeTimeout）
2. **SmanCodeProperties.kt**: ReAct 循环配置、上下文压缩配置、模型参数配置
3. **SmanAgentService.kt**: LLM 连接池配置

**迁移前**：
```kotlin
// 硬编码在代码中
.connectTimeout(30, TimeUnit.SECONDS)
.readTimeout(60, TimeUnit.SECONDS)
.writeTimeout(30, TimeUnit.SECONDS)

var maxSteps: Int = 10
var enableStreaming: Boolean = true
var temperature: Double = 0.7
```

**迁移后**：
```properties
# 在配置文件中
llm.timeout.connect=30000
llm.timeout.read=60000
llm.timeout.write=30000

react.max.steps=10
react.enable.streaming=true
llm.temperature=0.7
```

## 安全建议

1. **不要提交 API Key**：将 `smanagent.properties` 添加到 `.gitignore`
2. **使用环境变量**：在生产环境使用环境变量
3. **分离配置**：为不同环境创建不同的配置文件

## .gitignore 配置

```
# 配置文件（包含敏感信息）
src/main/resources/smanagent.properties

# 示例配置
!src/main/resources/smanagent.properties.example
```

创建示例配置文件 `smanagent.properties.example`：

```properties
# ==================== LLM API 配置 ====================
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/paas/v4/chat/completions
llm.model.name=glm-4-flash
llm.max.tokens=8192

# ==================== 重试配置 ====================
llm.retry.max=3
llm.retry.base.delay=1000

# ==================== 超时配置（毫秒）===================
llm.timeout.connect=30000
llm.timeout.read=60000
llm.timeout.write=30000

# ==================== ReAct 循环配置 ====================
react.max.steps=10
react.enable.streaming=true

# ==================== 上下文压缩配置 ====================
compaction.max.tokens=100000
compaction.threshold=80000
compaction.enable.intelligent=true

# ==================== 模型参数配置 ====================
llm.temperature=0.7
llm.default.max.tokens=4096
```

