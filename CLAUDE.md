# SmanAgent - Claude 开发指南

> 本文档为 Claude AI 助手提供项目上下文和开发指南。

## 项目概述

SmanAgent 是一个 IntelliJ IDEA 插件，集成 ReAct Loop 架构的 AI 代码分析助手。

**核心特性**：
- 基于 GLM-4.7 的智能代码分析
- 本地工具执行（无后端依赖）
- 会话持久化（按项目隔离）
- 流式响应渲染

## 项目结构

```
com.smancode.smanagent/
├── ide/                          # IntelliJ 平台集成
│   ├── components/              # UI 组件
│   │   ├── CliControlBar       # 控制栏
│   │   ├── CliInputArea        # 输入区域
│   │   ├── HistoryPopup        # 历史记录弹窗
│   │   ├── TaskProgressBar     # 任务进度条
│   │   └── WelcomePanel        # 欢迎面板
│   ├── renderer/                # 消息渲染器
│   │   ├── CliMessageRenderer  # CLI 风格渲染
│   │   ├── StyledMessageRenderer # 样式化渲染
│   │   ├── MarkdownRenderer    # Markdown 渲染
│   │   └── TodoRenderer        # Todo 列表渲染
│   ├── service/                 # IDE 服务
│   │   ├── SmanAgentService    # 主服务（单例）
│   │   ├── SessionFileService  # 会话文件服务
│   │   ├── LocalToolExecutor   # 本地工具执行器
│   │   └── PathUtil            # 路径工具
│   └── ui/                      # UI 面板
│       ├── SmanAgentChatPanel  # 聊天面板
│       └── SmanAgentToolWindowFactory # 工具窗口
│
├── smancode/                     # 核心业务逻辑
│   ├── core/                    # ReAct 循环核心
│   │   ├── SmanAgentLoop       # 主循环
│   │   ├── SessionManager      # 会话管理
│   │   ├── SubTaskExecutor     # 子任务执行
│   │   ├── ContextCompactor    # 上下文压缩
│   │   └── StreamingNotificationHandler # 流式通知
│   ├── llm/                     # LLM 服务
│   │   ├── LlmService          # LLM 调用
│   │   └── config/             # LLM 配置
│   └── prompt/                  # 提示词系统
│       ├── PromptDispatcher    # 提示词分发
│       └── DynamicPromptInjector # 动态注入
│
├── tools/                        # 工具系统
│   ├── Tool                    # 工具接口
│   ├── ToolRegistry            # 工具注册表
│   ├── ToolExecutor            # 工具执行器
│   └── ide/                     # 本地工具
│       └── LocalToolAdapter    # 本地工具适配器
│
└── model/                        # 数据模型
    ├── message/                 # 消息模型
    │   ├── Message             # 消息实体
    │   ├── Role                # 角色（USER/ASSISTANT/SYSTEM）
    │   └── TokenUsage          # Token 使用
    ├── part/                    # Part 模型
    │   ├── Part                # Part 基类
    │   ├── TextPart            # 文本 Part
    │   ├── ToolPart            # 工具调用 Part
    │   ├── ReasoningPart       # 推理 Part
    │   ├── TodoPart            # Todo Part
    │   └── ProgressPart        # 进度 Part
    └── session/                 # 会话模型
        ├── Session             # 会话实体
        ├── ProjectInfo         # 项目信息
        └── SessionStatus       # 会话状态
```

## 核心设计原则

### 1. 一体化架构

**无后端依赖**：所有逻辑在插件内完成

```kotlin
// ✅ 正确：本地调用
val response = smanAgentLoop.process(session, userInput) { part ->
    // 处理流式 Part
}

// ❌ 错误：WebSocket 通讯
webSocketClient.send(message)
```

### 2. 本地工具执行

所有工具在 IntelliJ 中本地执行，通过 PSI 访问代码结构

```kotlin
interface Tool {
    fun getName(): String
    fun getDescription(): String
    fun getParameters(): Map<String, ParameterDef>
    fun execute(projectKey: String, params: Map<String, Any>): ToolResult
}
```

### 3. 会话隔离

**按项目隔离存储**：
```
~/.smanunion/sessions/{projectKey}/{sessionId}.json
```

**内存 + 文件双层缓存**：
- 内存：`ConcurrentHashMap<sessionId, Session>`
- 文件：JSON 持久化

### 4. 白名单机制

**严格参数校验**：参数不满足直接抛异常

```kotlin
// ✅ 正确：严格校验
val relativePath = params["relativePath"] as? String
    ?: throw IllegalArgumentException("缺少 relativePath 参数")

// ❌ 错误：兜底处理
val relativePath = params["relativePath"] as? String ?: "default"
```

## 关键流程

### 消息处理流程

```
用户输入
   ↓
SmanAgentService.processMessage()
   ↓
SmanAgentLoop.process()
   ├─ 添加用户消息到会话
   ├─ 构建 System Prompt
   ├─ LLM 调用
   ├─ 解析响应为 Part 列表
   ├─ 处理工具调用
   └─ 保存会话
   ↓
返回响应消息
```

### 工具执行流程

```
LLM 返回工具调用
   ↓
ToolExecutor.execute()
   ├─ 参数校验（白名单）
   ├─ 从 ToolRegistry 获取工具
   ├─ 调用 Tool.execute()
   └─ 返回 ToolResult
   ↓
格式化为 Part
   ↓
追加到响应消息
```

### 会话生命周期

```
创建会话
   ├─ 生成 SessionId
   ├─ 创建 Session 对象
   ├─ 注册到 SessionManager
   └─ 添加到内存缓存
   ↓
处理消息
   ├─ 加载会话（如果未缓存）
   ├─ 执行业务逻辑
   └─ 保存到文件
   ↓
卸载会话
   ├─ 从内存移除
   └─ 保留文件
```

## 本地工具

### 工具列表

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `read_file` | 读取文件 | `simpleName`, `relativePath`, `startLine`, `endLine` |
| `grep_file` | 正则搜索 | `pattern`, `relativePath`, `filePattern` |
| `find_file` | 查找文件 | `pattern`, `filePattern` |
| `call_chain` | 调用链分析 | `method`, `direction`, `depth`, `includeSource` |
| `extract_xml` | 提取 XML | `relativePath`, `tagPattern`, `tagName` |
| `apply_change` | 应用修改 | `relativePath`, `newContent`, `mode`, `description` |

### 添加新工具

1. 在 `LocalToolExecutor.kt` 中添加工具逻辑
2. 在 `LocalToolFactory.kt` 中注册工具
3. 更新 `tool-introduction.md` 提示词

## 开发规范

### Kotlin 规范

1. **不可变优先**：使用 `val` 而非 `var`
2. **数据类**：使用 `data class` 定义模型
3. **Elvis 操作符**：`?:` 处理空值
4. **表达式体**：单行函数使用 `=`

```kotlin
// ✅ 正确
data class Message(val id: String, val content: String)

fun getName(): String = name

val result = value ?: defaultValue

// ❌ 错误
class Message {
    var id: String = ""
    var content: String = ""
}

fun getName(): String {
    return name
}
```

### 异常处理

```kotlin
// ✅ 正确：具体异常
throw IllegalArgumentException("缺少 relativePath 参数")

// ❌ 错误：通用异常
throw Exception("参数错误")
```

### 日志规范

```kotlin
// ✅ 正确：结构化日志
logger.info("处理消息: sessionId={}, input={}", sessionId, userInput)

// ❌ 错误：字符串拼接
logger.info("处理消息: " + sessionId + ", " + userInput)
```

## 测试策略

### 单元测试

- **测试基类**：`MockKTestBase`, `CoroutinesTestBase`
- **Mock 对象**：`MockLlmService`, `DummyTool`
- **测试工厂**：`TestDataFactory`

### 运行测试

```bash
# 所有测试
./gradlew test

# 特定测试
./gradlew test --tests "*LocalToolFactoryTest*"

# 测试报告
open build/reports/tests/test/index.html
```

## 性能优化

### LLM 调用优化

- **端点池轮询**：负载均衡
- **指数退避重试**：避免频繁重试
- **System Prompt 缓存**：每个会话缓存一次

### 工具执行优化

- **PSI 缓存**：利用 IntelliJ 的 PSI 缓存
- **并发执行**：独立工具可并发

## 故障排查

### 常见问题

**1. LLM 调用失败**
- 检查 `LLM_API_KEY` 环境变量
- 检查网络连接
- 查看日志：`Help → Show Log in Explorer`

**2. 工具执行失败**
- 确认项目已打开
- 检查文件路径
- 查看 `LocalToolExecutor` 日志

**3. 会话丢失**
- 检查 `~/.smanunion/sessions/` 目录
- 查看文件权限

**4. 父会话不存在**
- 确保会话已注册到 `SessionManager`
- 检查 `SmanAgentService.getOrCreateSession()`

## 扩展指南

### 添加新 Part 类型

1. 在 `PartModels.kt` 中定义 Part 类
2. 更新 `PartType` 枚举
3. 添加解析逻辑到 `parsePart()`
4. 添加渲染器到 `StyledMessageRenderer`

### 修改 LLM 配置

编辑 `src/main/resources/smanagent.properties`：

```properties
llm.api.key=your_key
llm.base.url=https://open.bigmodel.cn/api/paas/v4/chat/completions
llm.model.name=glm-4-flash
llm.response.max.tokens=4000
```

## 提交前检查

- [ ] 所有测试通过：`./gradlew test`
- [ ] 代码编译无警告：`./gradlew compileKotlin`
- [ ] 插件验证通过：`./gradlew verifyPluginConfiguration`
- [ ] 更新相关文档
- [ ] 遵循 Kotlin 编码规范
