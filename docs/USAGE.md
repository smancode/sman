# SmanUnion 使用指南

## 快速开始

### 1. 安装插件

1. 下载插件：`build/distributions/smanunion-2.0.0.zip` (21MB)
2. 在 IntelliJ IDEA 中：`File` → `Settings` → `Plugins` → ⚙️ → `Install Plugin from Disk`
3. 选择下载的 zip 文件并安装
4. 重启 IDEA

### 2. 配置 API Key

插件需要 LLM API Key 才能工作：

```bash
# 设置环境变量（推荐方式）
export LLM_API_KEY="your-glm-4-flash-api-key"

# 或在插件设置中配置
# Settings → Tools → SmanAgent → API Key
```

> **获取 API Key**：访问 [智谱AI开放平台](https://open.bigmodel.cn/) 注册并获取 API Key

### 3. 使用插件

1. 打开工具窗口：`View` → `Tool Windows` → `SmanAgent Chat`
2. 或使用快捷键：`Cmd+Shift+A` → 搜索 "SmanAgent"
3. 输入问题，例如：
   - `分析这个项目的代码结构`
   - `ReadFileTool.execute 方法做了什么？`
   - `支付流程是怎么实现的？`

---

## 功能特性

### 🤖 AI 驱动代码分析

基于 **ReAct 循环** 的智能代码分析：
- 理解用户意图
- 自动选择合适的工具
- 逐步执行分析任务
- 推送实时进度

### 💬 多轮对话支持

- 完整的会话管理
- 上下文保持
- 历史记录保存

### 🔧 本地工具集成

插件内置 12+ 代码分析工具：

| 工具 | 功能 | 使用场景 |
|------|------|----------|
| `read_file` | 读取文件内容 | 查看具体代码 |
| `grep_file` | 文件内容搜索 | 搜索代码片段 |
| `search` | 智能搜索 | **万能入口，90%情况用这个** |
| `read_class` | 读取类定义 | 分析类结构 |
| `call_chain` | 调用链分析 | 理解方法调用关系 |
| `text_search` | 文本搜索 | 全项目搜索 |

### 📊 流式输出

实时推送分析进度：
- 确认消息
- 工具调用通知
- 阶段性结论
- 最终总结

---

## 对话示例

### 示例 1：分析单个方法

```
你: ReadFileTool.execute 方法分析一下

AI: 收到，已理解需求

→ 调用工具: read_file
   参数: src/main/kotlin/com/smancode/smanagent/tools/ReadFileTool.kt

✓ 工具完成: read_file
   返回了文件内容，共 120 行

📊 阶段性结论 1:
已读取 ReadFileTool.kt 文件。ReadFileTool.execute 方法负责读取指定文件的内容...

📋 完整结论
ReadFileTool.execute 方法是一个文件读取工具的核心实现...
```

### 示例 2：搜索代码模式

```
你: 搜索项目中所有使用 LlmService 的地方

AI: 正在搜索 LlmService 使用情况

→ 调用工具: grep_file
   参数: pattern="LlmService"

✓ 工具完成: grep_file
   找到 15 个匹配

📊 阶段性结论 1:
找到 15 处使用 LlmService 的代码...

📋 完整结论
LlmService 在项目中主要用于以下场景：
1. SmanAgentLoop 中调用 LLM
2. StreamingNotificationHandler 生成确认消息
...
```

---

## 高级功能

### /commit 命令

快速提交代码：

```
你: /commit

AI: 准备提交代码...

📝 拟定提交信息:
feat: 实现本地服务集成

- 创建 SmanAgentService 管理后端服务
- 实现 ChatPanel 与 SmanAgentLoop 的集成
- 添加 Part 转换逻辑

是否确认提交？[y/n]
```

### 工具窗口快捷键

| 快捷键 | 功能 |
|--------|------|
| `Cmd+Shift+A` | 搜索并打开 SmanAgent |
| `Enter` | 发送消息 |
| `Shift+Enter` | 换行（不发送） |
| `Cmd+K` | 清空输入框 |

---

## 配置选项

### API 配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `API Key` | 智谱AI API Key | 从环境变量读取 |
| `Base URL` | LLM API 地址 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| `Model Name` | 模型名称 | `glm-4-flash` |

### 高级配置

在 `SmanCodeProperties` 中可以配置：

```kotlin
// ReAct 循环最大步数
react.maxSteps = 10

// 上下文压缩阈值
contextCompaction.maxTokens = 8000
```

---

## 故障排查

### 问题 1：API Key 无效

**症状**：错误提示 "401 Unauthorized"

**解决**：
1. 检查 API Key 是否正确
2. 确认账户有可用额度
3. 尝试重新生成 API Key

### 问题 2：插件无响应

**症状**：输入消息后无任何反应

**解决**：
1. 检查网络连接
2. 查看日志：`Help` → `Show Log in Explorer`
3. 尝试重启 IDEA

### 问题 3：工具执行失败

**症状**：显示 "工具失败" 消息

**解决**：
1. 确认项目已正确打开
2. 检查文件路径是否正确
3. 查看具体错误信息

---

## 开发信息

### 架构

- **前端**：Kotlin + Swing (ChatPanel)
- **后端**：Kotlin (SmanAgentLoop)
- **通信**：直接本地调用（无 WebSocket）
- **LLM**：智谱AI GLM-4-Flash

### 项目结构

```
src/main/kotlin/com/smancode/smanagent/
├── ide/              # 前端 UI
│   ├── ui/          # ChatPanel, Settings
│   └── service/     # SmanAgentService
├── smancode/        # 后端核心
│   ├── core/        # SmanAgentLoop, SessionManager
│   ├── llm/         # LlmService
│   └── prompt/      # Prompt 管理
├── tools/           # 工具系统
└── model/           # 数据模型
```

### 构建插件

```bash
# 编译
./gradlew compileKotlin

# 构建插件
./gradlew buildPlugin

# 运行测试
./gradlew test

# 在沙盒中运行
./gradlew runIde
```

---

## 版本历史

### v2.0.0 (2025-01-28)

- 🚀 重大整合：合并 SmanAgent ide-plugin 和 agent
- ✨ 单模块架构：移除 WebSocket，改为本地调用
- 🔧 Kotlin 转换：将 Java 后端代码转换为 Kotlin
- 📦 依赖优化：移除 Spring 依赖，使用 OkHttp
- 🛠️ 完整集成：实现 ChatPanel 与 SmanAgentLoop 的集成

---

## 反馈与支持

- **问题反馈**：[GitHub Issues](https://github.com/your-repo/issues)
- **文档**：`docs/ARCHITECTURE.md`
- **示例项目**：`examples/`

---

**享受智能代码分析！** 🚀
