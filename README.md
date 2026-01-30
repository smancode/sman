# SmanAgent - IntelliJ IDEA AI 助手

> 集成 ReAct Loop 的智能代码分析助手，支持多轮对话、本地工具执行、会话持久化和项目分析。

## 特性

- **AI 驱动的代码分析**: 基于 GLM-4.7 的智能代码理解和分析
- **多轮对话支持**: 完整的会话管理，支持上下文保持
- **本地工具执行**: 6 个核心工具直接在 IntelliJ 中执行
- **会话持久化**: 按项目隔离存储会话历史
- **流式响应渲染**: 实时显示 AI 思考过程和执行结果
- **三阶段工作流**: Analyze → Plan → Execute
- **🆕 项目分析能力**: 12 个分析模块，全面理解项目结构

## 版本信息

- **版本**: 2.0.0
- **兼容性**: IntelliJ IDEA 2023.2+
- **语言**: Kotlin 1.9.20

## 快速开始

### 前置要求

- IntelliJ IDEA 2023.2 或更高版本
- JDK 17 或更高版本
- GLM API Key（从 [智谱 AI](https://open.bigmodel.cn/) 获取）

### 安装

1. 克隆项目：
```bash
git clone git@github.com:smancode/sman.git
cd sman
git checkout union
```

2. 配置 API Key：
```bash
export LLM_API_KEY=your_api_key_here
```

或在 IDE 中设置：
- Run → Edit Configurations...
- 在 Environment variables 中添加：`LLM_API_KEY=your_api_key_here`

3. 构建并运行：
```bash
./gradlew runIde
```

### 配置

编辑 `src/main/resources/smanagent.properties`：

```properties
# LLM 配置
llm.api.key=your_api_key_here
llm.base.url=your_llm_base_url_here
llm.model.name=your_llm_model_name_here
llm.response.max.tokens=as_llm_supports
llm.retry.max=3
llm.retry.base.delay=1000

# BGE-M3 向量化配置
bge.enabled=true
bge.endpoint=your_bge_endpoint
bge.model.name=bge-m3
bge.batch.size=10
bge.timeout.seconds=30

# Reranker 配置
reranker.enabled=true
reranker.base.url=your_reranker_endpoint
reranker.api.key=your_reranker_api_key
reranker.model=bge-reranker-v2-m3
reranker.top.k=5

# 其他配置
max.cache.size=100
project.session.prefix=local
```

## 可用工具

| 工具 | 描述 | 参数 |
|------|------|------|
| `read_file` | 读取文件内容 | `simpleName`, `relativePath`, `startLine`, `endLine` |
| `grep_file` | 正则搜索文件内容 | `pattern`, `relativePath`, `filePattern` |
| `find_file` | 按文件名模式查找文件 | `pattern`, `filePattern` |
| `call_chain` | 分析方法调用链 | `method`, `direction`, `depth`, `includeSource` |
| `extract_xml` | 提取 XML 标签内容 | `relativePath`, `tagPattern`, `tagName` |
| `apply_change` | 应用代码修改 | `relativePath`, `newContent`, `mode`, `description` |

## 使用示例

### 1. 分析代码结构

```
>>> 分析 PaymentService 的职责
```

### 2. 查找调用关系

```
>>> 找出所有调用 PaymentService.processPayment 的地方
```

### 3. 代码重构

```
>>> 将 PaymentService 中的 validatePayment 方法提取到独立的 ValidationService
```

### 4. 理解业务逻辑

```
>>> 解释支付流程的实现逻辑
```

## 项目分析模块

SmanAgent 提供 12 个项目分析模块，全面理解项目结构：

| 模块 | 功能 | 实现文件 |
|------|------|---------|
| **项目结构扫描** | 识别模块、包、分层架构 | `ProjectStructureScanner.kt` |
| **技术栈识别** | 检测框架、数据库、中间件 | `TechStackDetector.kt` |
| **AST 扫描** | 提取类、方法、字段信息 | `PsiAstScanner.kt` |
| **DB 实体扫描** | 识别数据库实体和关系 | `DbEntityScanner.kt` |
| **入口扫描** | 识别 HTTP/API 入口 | `ApiEntryScanner.kt` |
| **外调接口扫描** | 识别 Feign/Retrofit/HTTP 客户端 | `ExternalApiScanner.kt` |
| **Enum 扫描** | 提取枚举类和常量 | `EnumScanner.kt` |
| **公共类扫描** | 识别工具类和帮助类 | `CommonClassScanner.kt` |
| **XML 代码扫描** | 解析 MyBatis Mapper 和配置 | `XmlCodeScanner.kt` |
| **案例 SOP** | 生成标准操作流程文档 | `CaseSopGenerator.kt` |
| **语义化向量化** | 代码向量化，支持语义搜索 | `CodeVectorizationService.kt` |
| **代码走读** | 生成架构分析和核心逻辑报告 | `CodeWalkthroughGenerator.kt` |

详细设计文档：[docs/design/](docs/design/)

### 分析能力演示

```
>>> 分析这个项目的整体架构
>>> 生成 UserController 的代码走读报告
>>> 找出所有调用外部 API 的地方
>>> 生成用户注册模块的 SOP 文档
```

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                    IntelliJ Plugin                          │
│  ┌──────────────┐      ┌──────────────┐                   │
│  │ Chat UI      │◄────►│ SmanAgent    │                   │
│  └──────────────┘      │   Service     │                   │
│         ▲              └───────┬───────┘                   │
│         │                      │                           │
│         │              ┌───────┴───────┐                   │
│         │              │ SmanAgent     │                   │
│         │              │   Loop        │                   │
│         │              └───────┬───────┘                   │
│         │                      │                           │
│         │              ┌───────┴────────────────────┐      │
│         │              │                            │      │
│         │         ┌────┴────┐              ┌────────┴──┐   │
│         │         │ LLM     │              │ Tool      │   │
│         └────────►│ Service │◄─────────────│ Registry  │   │
│                   └─────────┘              └──────┬────┘   │
│                                                   │         │
│                                            ┌──────┴─────┐  │
│                                            │ LocalTools │  │
│                                            └────────────┘  │
│                                                   │         │
│                                            ┌──────┴──────┐ │
│                                            │Project      │ │
│                                            │Analysis     │ │
│                                            │Modules      │ │
│                                            └─────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 核心组件

- **SmanAgentService**: 主服务管理器，负责初始化和协调所有组件
- **SmanAgentLoop**: ReAct 循环实现，处理多轮对话
- **LlmService**: LLM 调用服务，支持端点池、重试和缓存
- **ToolRegistry**: 工具注册表，管理所有可用工具
- **LocalToolExecutor**: 本地工具执行器，在 IntelliJ 中执行工具
- **SessionFileService**: 会话文件服务，负责持久化
- **项目分析模块**: 12 个分析器，全面理解项目结构

## 开发

### 运行测试

```bash
# 运行所有测试
./gradlew test

# 运行特定测试
./gradlew test --tests "*LocalToolFactoryTest*"

# 查看测试报告
open build/reports/tests/test/index.html
```

### 构建插件

```bash
# 构建插件包
./gradlew buildPlugin

# 输出: build/distributions/smanagent-2.0.0.zip
```

### 代码检查

```bash
# 编译检查
./gradlew compileKotlin

# 插件验证
./gradlew verifyPluginConfiguration
```

## 会话存储

会话按项目隔离存储在：

```
~/.smanunion/sessions/{projectKey}/{sessionId}.json
```

示例：
```
~/.smanunion/sessions/MyProject/0130_080000_ABC123.json
```

## 技术栈

### 核心技术栈
- **Kotlin**: 1.9.20
- **IntelliJ Platform SDK**: 2023.2
- **Kotlin Coroutines**: 1.7.3（协程支持）
- **kotlinx.serialization**: 1.6.0（JSON 序列化）

### 网络与 HTTP
- **OkHttp**: 4.12.0（HTTP 客户端，用于 LLM/BGE API 调用）
- **Java-WebSocket**: 1.5.4（WebSocket 支持，可选）

### 数据处理
- **Jackson**: 2.16.0（JSON 处理）
- **jackson-module-kotlin**: 2.16.0（Kotlin Jackson 模块）
- **org.json**: 20231013（JSON 解析）

### 渲染与日志
- **Flexmark**: 0.64.8（Markdown 渲染，支持表格、GFM 等）
- **Flexmark Extensions**: 表格、删除线、自动链接等扩展
- **Logback**: 1.4.11（日志框架）
- **SLF4J**: 2.0.9（日志门面）

### 数据库与存储
- **H2 Database**: 2.2.22（关系数据库，用于 L3 冷数据存储）
- **JVector**: 3.0.0（向量搜索引擎，用于 L2 温数据索引）
- **HikariCP**: 5.0.1（JDBC 连接池）

### 测试框架
- **JUnit**: 5.10.1（测试框架）
- **MockK**: 1.13.8（Kotlin Mock 框架）
- **Mockito-Kotlin**: 5.1.0（Kotlin Mockito 包装）
- **Kotlin Test**: 1.9.20（Kotlin 测试工具）
- **Kotlin Coroutines Test**: 1.7.3（协程测试）
- **Spring Boot Test**: 3.2.0（Spring 测试支持，可选）

### 向量化服务（可选，需自行配置）
- **BGE-M3**: 文本嵌入模型（通过 HTTP API 调用）
- **BGE-Reranker**: 结果重排序服务（通过 HTTP API 调用）

### 分层缓存架构
**三层缓存设计（防止内存爆炸）**：
- **L1 (Hot)**: 内存 LRU 缓存（默认 100 条）
- **L2 (Warm)**: JVector 向量索引（持久化磁盘）
- **L3 (Cold)**: H2 数据库（持久化存储）

**AST 分层缓存**：
- **L1**: 内存缓存（最新解析的 AST）
- **L2**: 磁盘文件缓存（序列化对象）
- **L3**: 实时解析（从源代码）

## 测试覆盖

| 模块 | 测试数量 | 覆盖率 |
|------|---------|--------|
| LLM 服务 | 3 | ~80% |
| ReAct 循环 | 4 | ~70% |
| 工具系统 | 6 | ~75% |
| 会话管理 | 5 | ~85% |
| 数据模型 | 3 | ~80% |
| 项目分析 | 175 | ~75% |
| **总计** | **196** | **~76%** |

## 故障排查

### LLM 调用失败

1. 检查 API Key 是否正确
2. 检查网络连接
3. 查看日志：`Help → Show Log in Explorer`

### 工具执行失败

1. 确认项目已打开
2. 检查文件是否存在
3. 查看异常堆栈

### UI 无响应

1. 检查是否在后台线程执行
2. 查看线程状态
3. 重启 IDE

### 向量化服务不可用

1. 检查 BGE 配置是否正确
2. 验证 BGE 端点是否可访问
3. 查看日志中的详细错误信息

## 贡献

欢迎贡献！请遵循以下步骤：

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 许可证

Apache License 2.0

## 联系方式

- **GitHub**: https://github.com/smancode/sman
- **分支**: union
