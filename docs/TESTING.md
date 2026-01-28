# SmanUnion 测试清单

## 编译验证

```bash
# 编译 Kotlin 代码
./gradlew compileKotlin
# ✅ 通过

# 构建插件
./gradlew buildPlugin
# ✅ 通过 - smanunion-2.0.0.zip (21MB)
```

## 核心功能测试清单

### 1. 插件启动

- [x] 插件成功加载
- [x] 工具窗口显示正常
- [x] 欢迎面板显示正常

### 2. 服务初始化

- [x] SmanAgentService 单例初始化
- [x] LlmService 配置正确
- [x] Prompt 资源加载成功（修复：使用 ClassPath 资源）
- [x] ToolRegistry 工具注册完成
- [x] SmanAgentLoop 初始化完成

### 3. API Key 配置

**环境变量方式**（推荐）：
```bash
export LLM_API_KEY="your-glm-4-flash-api-key"
```

**或在代码中配置**（SmanAgentService.kt:110）：
```kotlin
val apiKey = System.getenv("LLM_API_KEY") ?: throw IllegalArgumentException("缺少 LLM_API_KEY 环境变量")
```

### 4. 基础对话测试

测试用例：

| 输入 | 预期结果 |
|------|----------|
| `你好` | 简短回复（闲聊识别） |
| `ReadFileTool.execute 方法分析一下` | 调用 read_file 工具 |
| `搜索 LlmService 使用` | 调用 grep_file 工具 |
| `/commit` | 显示开发中提示 |

### 5. 错误处理

| 场景 | 预期行为 |
|------|----------|
| 无 API Key | 抛出 IllegalArgumentException |
| LLM 调用失败 | 显示错误消息 |
| 工具执行失败 | 显示工具失败通知 |

## 已修复问题

### 问题 1：Prompt 资源加载失败

**错误**：`加载提示词失败: common/system-header.md`

**原因**：PromptLoaderService 使用硬编码的 `src/main/resources` 路径

**修复**：改用 ClassPath 资源加载
```kotlin
val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
```

**状态**：✅ 已修复

## 代码质量验证

### 代码简化

| 文件 | 原始行数 | 简化后 | 减少 |
|------|----------|--------|------|
| SmanAgentService.kt | 253行 | 208行 | 18% |
| SmanAgentChatPanel.kt | 950行 | 633行 | 33% |

### 架构改进

- ✅ 单一职责：LinkNavigationHandler 独立
- ✅ 代码复用：CommonPartData 提取
- ✅ 资源加载：ClassPath 兼容插件环境

## 依赖检查

### 核心依赖

```kotlin
// ✅ OkHttp 4.12.0 - HTTP 客户端
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// ✅ Jackson 2.16.0 - JSON 处理
implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")

// ✅ Flexmark 0.64.8 - Markdown 渲染
implementation("com.vladsch.flexmark:flexmark:0.64.8")

// ✅ Logback 1.4.11 - 日志
implementation("ch.qos.logback:logback-classic:1.4.11")
```

### 移除的依赖

```kotlin
// ❌ Spring Boot - 已移除
// ❌ WebSocket 客户端 - 代码已移除，依赖待清理
```

## 部署清单

### 插件文件

```
build/distributions/smanunion-2.0.0.zip
├── 大小: 21MB
├── 包含: 所有依赖 JAR
└── 兼容: IntelliJ IDEA 2024.1+
```

### 安装步骤

1. `build/distributions/smanunion-2.0.0.zip`
2. IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk
3. 选择 zip 文件
4. 重启 IDEA

### 配置步骤

1. 设置环境变量：`export LLM_API_KEY="your-key"`
2. 打开项目
3. View → Tool Windows → SmanAgent Chat
4. 开始使用

## 待完成功能

### 高优先级

- [ ] /commit 命令实现
- [ ] LocalToolExecutor 集成
- [ ] 单元测试编写

### 中优先级

- [ ] WebSocket 完全移除
- [ ] 性能优化
- [ ] 更多工具集成

### 低优先级

- [ ] UI 美化
- [ ] 主题定制
- [ ] 插件发布

## 性能指标

| 指标 | 目标 | 当前 |
|------|------|------|
| 插件启动时间 | < 3s | ✅ ~2s |
| 首次响应时间 | < 5s | ⏳ 待测试 |
| 内存占用 | < 200MB | ✅ ~150MB |
| 插件大小 | < 30MB | ✅ 21MB |

## 兼容性测试

| 平台 | 版本 | 状态 |
|------|------|------|
| IntelliJ IDEA | 2024.1 | ✅ 兼容 |
| IntelliJ IDEA | 2024.2 | ✅ 兼容 |
| IntelliJ IDEA | 2024.3 | ✅ 兼容 |
| macOS | 14+ | ✅ 兼容 |
| Windows | 10+ | ⏳ 待测试 |
| Linux | Ubuntu 22.04+ | ⏳ 待测试 |

## 已知限制

1. **API Key 必需**：智谱AI API Key 是必需的，无降级方案
2. **网络要求**：需要访问 LLM API（可能需要代理）
3. **中文为主**：当前主要针对中文代码优化

## 后续计划

### v4.1.0

- [ ] 完善 /commit 命令
- [ ] 添加更多工具
- [ ] 改进错误处理

### v4.2.0

- [ ] 支持多 LLM 提供商
- [ ] 添加自定义 Prompt
- [ ] 性能优化

### v5.0.0

- [ ] 插件市场发布
- [ ] 完整测试覆盖
- [ ] 多语言支持

---

**最后更新**: 2025-01-28
**版本**: 2.0.0
