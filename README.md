# SmanCode - IntelliJ IDEA AI 助手

> 集成 ReAct Loop 的智能代码分析助手，支持多轮对话、本地工具执行、会话持久化和项目分析。

## 特性

- **AI 驱动的代码分析**: 基于 GLM-5 的智能代码理解和分析
- **多轮对话支持**: 完整的会话管理，支持上下文保持
- **本地工具执行**: 多个核心工具直接在 IntelliJ 中执行
- **会话持久化**: 按项目隔离存储会话历史
- **流式响应渲染**: 实时显示 AI 思考过程和执行结果
- **三阶段工作流**: Analyze → Plan → Execute
- **架构师 Agent**: 小步快跑、阶段性评估、增量更新、断点续传
- **技能系统**: 支持加载领域特定技能，扩展 AI 能力
- **Web 搜索**: 集成 Exa AI MCP 服务，支持实时网络搜索

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

编辑 `src/main/resources/sman.properties`：

```properties
# LLM 配置
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
llm.model.name=GLM-5
llm.response.max.tokens=28192

# 向量数据库配置
vector.db.type=JVECTOR
vector.db.dimension=1024
vector.db.l1.cache.size=500

# BGE-M3 向量化配置（可选）
bge.endpoint=http://localhost:8000
bge.model.name=BAAI/bge-m3

# BGE-Reranker 配置（可选）
reranker.enabled=true
reranker.base.url=http://localhost:8001/v1
reranker.model=BAAI/bge-reranker-v2-m3

# 架构师 Agent 配置
architect.agent.enabled=true
architect.agent.max.iterations.per.md=5

# WebSearch 配置
websearch.enabled=true
websearch.timeout.seconds=25
```

#### H2 数据库说明

H2 是纯 Java 实现的嵌入式关系数据库，无需外部安装和配置。

**存储位置**（按项目隔离）：
```
{projectPath}/.sman/analysis.mv.db    # 主数据库文件
```

**快速连接方式**：

1. **使用 IntelliJ IDEA Database 工具**：
```
Database → + → Data Source → H2

JDBC URL: jdbc:h2:{projectPath}/.sman/analysis;MODE=PostgreSQL;AUTO_SERVER=TRUE
User: sa
Password: (留空)
```

#### BGE-M3 和 Reranker 部署（可选）

如需使用语义搜索功能，需要部署 BGE-M3 和 Reranker 服务：

```bash
# 克隆项目
git clone https://github.com/FlagOpen/FlagEmbedding.git
cd FlagEmbedding

# 安装依赖
pip install -r requirements.txt

# 启动 BGE-M3 服务（端口 8000）
python -m FlagEmbedding.bge_m3 serve --port 8000

# 启动 Reranker 服务（端口 8001）
python -m FlagEmbedding.reranker serve --port 8001
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
| `expert_consult` | 语义搜索（BGE + Reranker） | `query`, `topK` |
| `web_search` | Web 搜索（Exa AI） | `query`, `numResults` |
| `skill` | 加载技能 | `skillName` |

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

### 5. Web 搜索

```
>>> 搜索 Spring Boot 3.0 的新特性
```

## 架构师 Agent

架构师 Agent 通过调用 SmanLoop 实现项目分析，核心特性：

| 特性 | 说明 |
|------|------|
| 小步快跑 | 每轮调用 LLM → 收集回答 → 评估完成度 |
| 阶段性评估 | 完成时写入 MD 文件（带时间戳） |
| 增量更新 | 检测文件变更，判断是否需要更新 MD |
| 断点续传 | 状态持久化到 H2，IDEA 重启后自动恢复 |

分析类型（6 种）：
1. `PROJECT_STRUCTURE` - 项目结构分析
2. `TECH_STACK` - 技术栈识别
3. `API_ENTRIES` - API 入口扫描
4. `DB_ENTITIES` - 数据库实体分析
5. `ENUMS` - 枚举分析
6. `CONFIG_FILES` - 配置文件分析

## 技能系统

支持加载领域特定技能，扩展 AI 能力。

Skill 加载路径（优先级从高到低）：
1. `<project>/.sman/skills/<name>/SKILL.md`
2. `<project>/.claude/skills/<name>/SKILL.md`
3. `~/.claude/skills/<name>/SKILL.md`

示例 SKILL.md：
```markdown
# Java Expert

你是一名 Java 专家，精通 Spring Boot、MyBatis 等框架。

## 专长领域
- Spring Boot 应用架构设计
- MyBatis SQL 优化
- Java 并发编程
```

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                    IntelliJ Plugin                          │
│  ┌──────────────┐      ┌──────────────┐                   │
│  │ Chat UI      │◄────►│ SmanService  │                   │
│  └──────────────┘      └───────┬───────┘                   │
│         ▲                      │                           │
│         │              ┌───────┴───────┐                   │
│         │              │ SmanLoop      │                   │
│         │              │ (ReAct)       │                   │
│         │              └───────┬───────┘                   │
│         │                      │                           │
│         │              ┌───────┴────────────────────┐      │
│         │              │                            │      │
│         │         ┌────┴────┐              ┌────────┴──┐   │
│         │         │ LLM     │              │ Tool      │   │
│         └────────►│ Service │◄─────────────│ Registry  │   │
│                   └─────────┘              └──────┬────┘   │
│                                                   │         │
│                            ┌──────────────────────┴──────┐ │
│                            │                             │ │
│                     ┌──────┴─────┐              ┌────────┴┐│
│                     │ LocalTools │              │Architect││
│                     └────────────┘              │Agent    ││
│                                                 └─────────┘│
└─────────────────────────────────────────────────────────────┘
```

### 核心组件

- **SmanService**: 主服务管理器，负责初始化和协调所有组件
- **SmanLoop**: ReAct 循环实现，处理多轮对话
- **LlmService**: LLM 调用服务，支持端点池、重试和缓存
- **ToolRegistry**: 工具注册表，管理所有可用工具
- **LocalToolExecutor**: 本地工具执行器，在 IntelliJ 中执行工具
- **ArchitectAgent**: 架构师 Agent，通过调用 SmanLoop 实现项目分析
- **SkillRegistry**: 技能注册中心，管理所有已加载技能

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

## 技术栈

### 核心技术栈
- **Kotlin**: 1.9.20
- **IntelliJ Platform SDK**: 2023.2
- **Kotlin Coroutines**: 1.7.3（协程支持）
- **kotlinx.serialization**: 1.6.0（JSON 序列化）

### 网络与 HTTP
- **OkHttp**: 4.12.0（HTTP 客户端，用于 LLM/BGE API 调用）

### 数据处理
- **Jackson**: 2.16.0（JSON 处理）
- **jackson-module-kotlin**: 2.16.0（Kotlin Jackson 模块）

### 渲染与日志
- **Flexmark**: 0.64.8（Markdown 渲染）
- **Logback**: 1.4.11（日志框架）

### 数据库与存储
- **H2 Database**: 2.2.224（关系数据库）
- **JVector**: 3.0.0（向量搜索引擎）
- **HikariCP**: 5.0.1（JDBC 连接池）

### 测试框架
- **JUnit**: 5.10.1（测试框架）
- **MockK**: 1.13.8（Kotlin Mock 框架）

### 向量化服务（可选）
- **BGE-M3**: 文本嵌入模型
- **BGE-Reranker**: 结果重排序服务

### 三层缓存架构
- **L1 (Hot)**: 内存 LRU 缓存（默认 500 条）
- **L2 (Warm)**: JVector 向量索引（持久化磁盘）
- **L3 (Cold)**: H2 数据库（持久化存储）

## 故障排查

### LLM 调用失败

1. 检查 API Key 是否正确
2. 检查网络连接
3. 查看日志：`Help → Show Log in Explorer`

### 工具执行失败

1. 确认项目已打开
2. 检查文件是否存在
3. 查看异常堆栈

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
