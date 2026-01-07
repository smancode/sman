# SiliconMan IDE Plugin - IntelliJ IDEA 插件

SiliconMan v2.0 IntelliJ IDEA 插件，基于 Claude Code CLI 的智能代码分析和开发助手。

---

## 🎯 核心功能

### v2.0 新特性

- ✅ **多轮对话支持**: 基于 Claude Code CLI 的 `--resume` 参数实现会话上下文保持
- ✅ **WebSocket 协议**: AGENT_CHAT/AGENT_RESPONSE 双协议支持
- ✅ **12 个前端工具**: 完整的本地代码分析工具（read_class、call_chain、text_search 等）
- ✅ **自动降级模式**: Claude Code CLI 不可用时自动降级为规则引擎

### 基础功能

- 💬 **AI 对话界面**: 基于 Swing 的聊天面板
- 🔧 **本地工具执行**: 12 个代码分析工具（PSI API）
- 📝 **Markdown 渲染**: 使用 flexmark-java 渲染 AI 响应
- 🎨 **代码编辑支持**: 自动格式化和导入管理
- 💾 **会话管理**: 项目级和应用级配置存储

---

## 🛠️ 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Kotlin | 1.9.20 | 主要开发语言 |
| IntelliJ Platform SDK | 2024.1+ | 插件开发平台 |
| flexmark-java | 0.64.8 | Markdown 渲染 |
| OkHttp | 4.12.0 | WebSocket 客户端 |
| org.json | 20230227 | JSON 处理 |

---

## 📋 系统要求

### 运行环境

- **IntelliJ IDEA**: 2024.1 或更高版本
- **Java**: JVM 17 (IntelliJ Platform 要求)
- **操作系统**: Windows / macOS / Linux

### 后端服务

- **Agent 后端**: 运行在 `http://localhost:8080`
- **依赖服务**:
  - Claude Code CLI (必需，通过 npm 安装)

---

## 🚀 快速开始

### 方法 1: 从源码构建（开发模式）

```bash
cd ide-plugin

# 构建插件
./gradlew buildPlugin

# 在测试 IDEA 中运行
./gradlew runIde

# 验证插件配置
./gradlew verifyPlugin
```

**生成的插件位置**: `build/distributions/intellij-siliconman-1.0.0.zip`

---

### 方法 2: 安装插件包（生产环境）

1. **构建插件**:
   ```bash
   ./gradlew buildPlugin
   ```

2. **在 IDEA 中安装**:
   - 打开 IDEA: `File` → `Settings` → `Plugins`
   - 点击 ⚙️ 图标 → `Install Plugin from Disk`
   - 选择: `build/distributions/intellij-siliconman-1.0.0.zip`
   - 重启 IDEA

3. **验证安装**:
   - 在右侧边栏找到 "SiliconMan" 工具窗口
   - 点击打开，应该看到聊天界面

---

## 💡 使用说明

### 1. 打开工具窗口

有三种方式打开 SiliconMan:

- **菜单**: `Tools` → `SiliconMan` (或 `硅基人`)
- **工具窗口**: 右侧边栏找到 "SiliconMan" 标签
- **快捷键**: 可在设置中自定义

### 2. 首次使用配置

点击工具栏的 **设置** 按钮，配置：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| 服务器 URL | Agent 后端 WebSocket 地址 | `ws://localhost:8080/ws/agent/chat` |
| 项目 Key | 项目标识符（用于后端定位项目） | 自动检测 |
| 模式 | 分析模式（full/medium/lite） | `medium` |

### 3. 发起对话

**第一次对话（创建新会话）**:
```
输入: "分析 FileFilter 类的结构"

后端流程:
1. 接收 AGENT_CHAT 消息（sessionId=""）
2. 创建新 sessionId（如 "abc-123"）
3. 调用 Claude Code CLI
4. Claude Code 调用 read_class 工具
5. 返回分析结果
```

**第二次对话（多轮对话）**:
```
输入: "这个类的调用关系是什么？"

后端流程:
1. 接收 AGENT_CHAT 消息（sessionId="abc-123"）
2. 检测到会话文件存在
3. 使用 --resume abc-123 恢复上下文
4. Claude Code 能访问历史消息
5. 调用 call_chain 工具
6. 返回调用链分析结果
```

### 4. 工具调用

当 Claude Code 需要调用本地工具时:

```
后端 → IDE Plugin: TOOL_CALL
{
  "callId": "uuid-123",
  "toolName": "read_class",
  "parameters": {
    "className": "FileFilter",
    "mode": "structure"
  }
}

IDE Plugin 执行工具...

IDE Plugin → 后端: TOOL_RESULT
{
  "callId": "uuid-123",
  "success": true,
  "result": "类结构信息..."
}
```

**12 个可用工具**:

| 工具名 | 功能 | 参数示例 |
|--------|------|----------|
| `read_class` | 读取类结构 | `className`, `mode` |
| `read_method` | 读取方法源码 | `className`, `methodName` |
| `text_search` | 文本搜索 | `keyword`, `maxResults` |
| `list_dir` | 列出目录 | `relativePath` |
| `read_xml` | 读取 XML | `relativePath` |
| `read_file` | 读取文件 | `relativePath` |
| `read_config` | 读取配置 | `relativePath`, `type` |
| `call_chain` | 调用链分析 | `method`, `direction`, `depth` |
| `find_usages` | 查找引用 | `target`, `maxResults` |
| `write_file` | 写入文件 | `relativePath`, `content` |
| `modify_file` | 修改文件 | `relativePath`, `operation` |
| `apply_change` | SEARCH/REPLACE | `relativePath`, `searchContent`, `replaceContent` |

---

## 🔌 WebSocket 协议

**连接 URL**: `ws://localhost:8080/ws/agent/chat`

**发送消息**:
```json
{
  "type": "AGENT_CHAT",
  "data": {
    "message": "用户需求",
    "sessionId": "会话ID（首次为空）",
    "projectKey": "autoloop",
    "mode": "medium",
    "projectPath": "/path/to/project"
  }
}
```

**接收消息**:
```json
{
  "type": "AGENT_RESPONSE",
  "data": {
    "status": "PROCESSING",
    "message": "正在分析..."
  }
}
```

**工具调用**:
```json
{
  "type": "TOOL_CALL",
  "data": {
    "callId": "uuid",
    "toolName": "read_class",
    "projectPath": "/path/to/project",
    "parameters": {...}
  }
}
```

---

## 🛡️ 降级模式

当 Claude Code CLI 不可用时，插件会自动降级：

### 触发条件

- Claude Code CLI 未安装
- `--version` 命令超时（>10 秒）
- 进程崩溃或返回非零退出码
- 系统资源不足（内存 <512MB，CPU <1 核）

### 降级行为

后端使用意图分析引擎处理基本请求：

| 用户意图 | 支持的操作 |
|----------|-----------|
| 搜索 | 向量搜索（vector_search） |
| 类结构 | 读取类（read_class） |
| 调用关系 | 调用链分析（call_chain） |
| 查找引用 | 查找用法（find_usages） |

**自动恢复**: 降级 5 分钟后自动尝试恢复正常模式

---

## 📂 项目结构

```
ide-plugin/
├── src/
│   └── main/
│       ├── kotlin/ai/smancode/sman/ide/
│       │   ├── SiliconManPlugin.kt              # 插件主类
│       │   ├── ui/                               # UI 组件
│       │   │   ├── ChatPanel.kt                  # 聊天面板
│       │   │   └── SiliconManToolWindowFactory.kt
│       │   └── service/                          # 服务层
│       │       ├── WebSocketService.kt           # WebSocket 客户端
│       │       ├── LocalToolExecutor.kt          # 12 个工具执行器
│       │       ├── CodeEditService.kt            # 代码编辑服务
│       │       ├── StorageService.kt             # 应用级存储
│       │       └── ProjectStorageService.kt      # 项目级存储
│       └── resources/
│           ├── META-INF/
│           │   └── plugin.xml                    # 插件描述符
│           └── icons/                            # 图标资源
├── build.gradle.kts                               # 构建配置
├── settings.gradle.kts                            # 项目配置
└── README.md                                      # 本文档
```

---

## 🔧 开发指南

### 添加新工具

1. 在 `LocalToolExecutor.kt` 中添加工具方法:
```kotlin
fun executeNewTool(params: Map<String, Any?>): ToolResult {
    // 实现工具逻辑
    return ToolResult(success = true, result = "...", executionTime = 100)
}
```

2. 在 `execute()` 方法中注册工具:
```kotlin
"new_tool" -> executeNewTool(parameters)
```

3. 更新文档 `docs/md/06-frontend-tools.md`

### 调试插件

```bash
# 启用调试模式
./gradlew runIde --args="-Xmx2048m -Didea.debug.mode=true"

# 在 IDEA 中远程调试
# Run → Edit Configurations → Remote → Port 5005
```

### 查看日志

- **插件日志**: `Help → Show Log in Explorer`
- **关键日志标签**:
  - `SiliconMan`: 主要日志
  - `WebSocketService`: WebSocket 通信
  - `LocalToolExecutor`: 工具执行

---

## ⚙️ 配置文件

### plugin.xml

插件元数据配置:
```xml
<idea-version since-build="241" until-build="253.*"/>
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.java</depends>
```

### build.gradle.kts

构建配置:
```kotlin
intellij {
    version.set("2024.1")
    type.set("IC")  // IntelliJ IDEA Community Edition
    plugins.set(listOf("java"))
}

dependencies {
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20230227")
}
```

---

## 🧪 测试

### 手动测试流程

1. **功能测试**:
   ```
   1. 启动插件（gradle runIde）
   2. 打开 SiliconMan 工具窗口
   3. 输入测试需求："分析这个项目的结构"
   4. 验证显示 thinking 消息
   5. 验证显示最终结果
   ```

2. **多轮对话测试**:
   ```
   1. 第一次对话："分析 FileFilter 类"
   2. 记录返回的 sessionId
   3. 第二次对话："这个类的父类是谁？"
   4. 验证能访问第一次对话的上下文
   ```

3. **工具调用测试**:
   ```
   1. 输入："搜索包含 'readFile' 的代码"
   2. 观察后端日志，验证调用 vector_search
   3. 验证 IDE Plugin 返回 TOOL_RESULT
   ```

### 单元测试（待实现）

```bash
./gradlew test

# 测试覆盖目标
- LocalToolExecutorTest: 12 个工具
- WebSocketServiceTest: WebSocket 协议
- CodeEditServiceTest: 代码编辑
```

---

## 📚 相关文档

- [架构设计](../docs/md/01-architecture.md) - 总体架构和设计决策
- [WebSocket API](../docs/md/02-websocket-api.md) - WebSocket 协议规范
- [前端工具](../docs/md/06-frontend-tools.md) - 12 个工具详细说明
- [降级策略](../docs/md/07-fallback-strategy.md) - 降级模式完整说明
- [Agent 后端文档](../agent/README.md) - 后端服务说明

---

## 🐛 故障排查

### 常见问题

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 连接失败 | 后端未启动 | `cd agent && ./gradlew bootRun` |
| 编译错误 | JVM 版本不匹配 | 确认使用 JVM 17 |
| 工具调用失败 | Java 模块未启用 | 在 plugin.xml 中启用 `com.intellij.modules.java` |
| ClassNotFoundException | 依赖缺失 | 运行 `gradle clean build --refresh-dependencies` |

### 调试技巧

1. **启用详细日志**:
   ```kotlin
   Logger.getInstance(WebSocketService::class.java).level = Level.ALL
   ```

2. **检查 WebSocket 连接**:
   ```bash
   # 使用 wscat 测试连接
   wscat -c ws://localhost:8080/ws/agent/chat
   ```

3. **查看进程状态**:
   ```bash
   curl http://localhost:8080/api/claude-code/pool/status
   ```

---

## 🚧 发布流程

### 发布到 JetBrains Marketplace

```bash
# 1. 设置 Token
export PUBLISH_TOKEN="your-jetbrains-marketplace-token"

# 2. 更新版本号
# 修改 build.gradle.kts: version = "2.0.0"

# 3. 发布插件
./gradlew publishPlugin

# 4. 验证发布
# 访问: https://plugins.jetbrains.com/plugin/xxxxx-siliconman
```

### 版本发布清单

- [ ] 更新版本号（build.gradle.kts + plugin.xml）
- [ ] 更新 change-notes（plugin.xml）
- [ ] 运行完整测试套件
- [ ] 清理调试代码和日志
- [ ] 构建发布包
- [ ] 发布到 Marketplace
- [ ] 更新 GitHub Releases

---

## 📄 许可证

本插件是 SiliconMan 项目的一部分。

**项目**: https://github.com/smancode-ai/siliconman
**许可证**: MIT License

---

## 🤝 贡献指南

欢迎贡献！请遵循以下流程:

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 📮 联系方式

- **项目主页**: https://github.com/smancode-ai/siliconman
- **文档**: https://docs.smancode.ai
- **邮箱**: contact@smancode.ai
- ** issues**: https://github.com/smancode-ai/siliconman/issues

---

**最后更新**: 2026-01-05
**版本**: v2.0.0
**状态**: ✅ 生产就绪
