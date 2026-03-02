# CLAUDE.md

SmanCode - 面向业务的智能编程助手

---

## 项目定位

SmanCode 是一个 IntelliJ IDEA 插件，实现**面向业务的 AI 编程智能体**。

**核心差异化**：
- **自迭代项目理解**：Agent 自主理解项目，生成项目专属知识，持续迭代完善
- **用户习惯学习**：自动学习用户偏好，越用越懂你
- **一切基于 Markdown**：简单可靠，用户可见可编辑，Git 友好

---

## 核心架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         应用层 (app/)                            │
│  SmanPlugin → ServiceLocator                                    │
├─────────────────────────────────────────────────────────────────┤
│                         领域层 (domain/)                         │
│  ┌──────────────┬──────────────┬──────────────┬──────────────┐  │
│  │   puzzle/    │   memory/    │   session/   │    react/    │  │
│  │ 自迭代理解    │ 习惯学习     │ 会话管理     │ ReAct 循环   │  │
│  └──────────────┴──────────────┴──────────────┴──────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                       基础设施层 (infra/)                        │
│  ┌──────────────┬──────────────┬──────────────┬──────────────┐  │
│  │    llm/      │   vector/    │   storage/   │     ide/     │  │
│  │  LLM 调用    │ BGE+JVector  │ Markdown+H2  │ IntelliJ 集成│  │
│  └──────────────┴──────────────┴──────────────┴──────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                         工具层 (tools/)                          │
│  file/ | search/ | code/ | shell/ | skill/ | batch/             │
├─────────────────────────────────────────────────────────────────┤
│                         共享层 (shared/)                         │
│  model/ | config/ | util/                                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 目录结构（目标架构）

```
src/main/kotlin/com/smancode/sman/
│
├── app/                        # 应用层
│   ├── SmanPlugin.kt          # 插件入口
│   ├── SmanApplication.kt     # 生命周期管理
│   └── ServiceLocator.kt      # 服务定位器
│
├── domain/                     # 领域层（核心业务逻辑）
│   │
│   ├── puzzle/                # 【核心】自迭代项目理解系统
│   │   ├── Puzzle.kt          # 拼图块模型
│   │   ├── PuzzleType.kt      # 拼图类型（结构/技术/入口/数据/流程/规则）
│   │   ├── PuzzleCoordinator.kt  # 协调器（主入口）
│   │   ├── GapDetector.kt     # 空白发现
│   │   ├── TaskScheduler.kt   # 任务调度
│   │   └── analyzer/          # 各类型分析器
│   │
│   ├── memory/                # 用户习惯学习系统
│   │   ├── Memory.kt          # 记忆模型
│   │   ├── MemoryService.kt   # 记忆服务
│   │   ├── FeedbackCollector.kt  # 反馈收集
│   │   └── PreferenceInjector.kt # 偏好注入
│   │
│   ├── session/               # 会话管理
│   │   ├── Session.kt
│   │   ├── SessionManager.kt
│   │   └── ContextCompactor.kt
│   │
│   └── react/                 # ReAct 循环
│       ├── ReactLoop.kt       # 主循环
│       ├── ToolExecutor.kt    # 工具执行
│       ├── ResponseParser.kt  # 响应解析
│       └── DoomLoopGuard.kt   # 死循环防护
│
├── infra/                      # 基础设施层
│   ├── llm/                   # LLM 调用
│   │   ├── LlmClient.kt
│   │   └── StreamHandler.kt
│   │
│   ├── vector/                # 向量存储
│   │   ├── BgeM3Client.kt     # BGE-M3 向量化
│   │   ├── RerankerClient.kt  # Reranker 重排
│   │   ├── JVectorStore.kt    # JVector 索引
│   │   └── TieredCache.kt     # 三层缓存
│   │
│   ├── storage/               # 持久化
│   │   ├── MarkdownStore.kt   # Markdown 存储
│   │   ├── PuzzleRepository.kt
│   │   └── MemoryRepository.kt
│   │
│   └── ide/                   # IntelliJ 集成
│       ├── SmanToolWindow.kt
│       ├── ChatPanel.kt
│       └── SettingsDialog.kt
│
├── tools/                      # 工具层
│   ├── file/                  # 文件操作
│   │   ├── ReadFileTool.kt
│   │   ├── EditFileTool.kt
│   │   └── FindFileTool.kt
│   ├── search/                # 搜索相关
│   │   ├── GrepTool.kt
│   │   ├── SemanticSearchTool.kt
│   │   └── WebSearchTool.kt
│   ├── code/                  # 代码分析
│   │   ├── CallChainTool.kt
│   │   └── AnalyzeFlowTool.kt
│   └── ToolRegistry.kt        # 工具注册表
│
└── shared/                     # 共享层
    ├── model/                 # 数据模型
    │   ├── Message.kt
    │   ├── Part.kt
    │   └── parts/
    ├── config/                # 配置
    │   ├── SmanConfig.kt
    │   └── LlmConfig.kt
    └── util/                  # 工具类
```

---

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 (JDK 17+) |
| 平台 | IntelliJ IDEA 2024.1+ |
| HTTP | OkHttp 4.12.0 |
| JSON | Jackson 2.16.0 + kotlinx.serialization 1.6.0 |
| 向量存储 | JVector 3.0.0 + 三层缓存 |
| 向量化 | BGE-M3 (外部服务) |
| 重排序 | BGE-Reranker-v2-m3 (外部服务) |
| 持久化 | Markdown 文件 + H2 (可选) |
| 测试 | JUnit 5 + MockK |

---

## 构建命令

```bash
# 构建插件
./gradlew buildPlugin

# 开发模式运行 IDE
./gradlew runIde

# 运行所有测试
./gradlew test

# 运行指定测试
./gradlew test --tests "*PuzzleCoordinatorTest*"
```

---

## 核心模块说明

### 1. 自迭代项目理解（domain/puzzle/）

**核心理念**：项目理解 = 拼图游戏，Agent 在后台持续填充拼图。

**拼图类型**：
| 类型 | 描述 | 输出文件 |
|------|------|----------|
| 结构拼图 | 项目结构、模块划分 | `PUZZLE_STRUCTURE.md` |
| 技术拼图 | 技术栈、依赖关系 | `PUZZLE_TECH.md` |
| 入口拼图 | API 入口、调用入口 | `PUZZLE_API.md` |
| 数据拼图 | 数据模型、表关系 | `PUZZLE_DATA.md` |
| **流程拼图** | 业务流程、调用链 | `PUZZLE_FLOW_*.md` |
| 规则拼图 | 业务规则、约束条件 | `PUZZLE_RULES.md` |

**自迭代循环**：
```
发现空白 → 选择任务 → 执行分析 → 验证结果 → 更新拼图 → 发现新空白
```

### 2. 用户习惯学习（domain/memory/）

**核心流程**：
```
用户交互 → 反馈收集 → 偏好提取 → 存储到 MEMORY.md → 下次注入
```

**记忆文件**：`{project}/.sman/MEMORY.md`

### 3. 数据存储（一切基于 Markdown）

```
{projectPath}/.sman/
├── MEMORY.md              # 项目记忆（用户可见可编辑）
├── puzzles/               # 拼图块存储
│   ├── status.json        # 拼图状态汇总
│   ├── PUZZLE_*.md        # 各类型拼图
│   └── flow/              # 流程拼图详情
├── queue/                 # 任务队列
│   └── pending.json
└── cache/                 # 缓存（不入 Git）
    ├── md5.json
    └── vectors/           # 向量索引（可重建）
```

### 4. 可用工具

| 工具 | 描述 |
|------|------|
| `read_file` | 读取文件内容 |
| `edit_file` | 编辑文件 |
| `find_file` | 查找文件 |
| `grep` | 正则搜索 |
| `semantic_search` | 语义搜索（BGE + Reranker） |
| `call_chain` | 调用链分析 |
| `analyze_flow` | 流程分析（新增） |
| `web_search` | Web 搜索 |
| `skill` | 加载技能 |
| `batch` | 批量执行 |

---

## 配置说明

配置文件：`src/main/resources/sman.properties`

```properties
# LLM 配置
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
llm.model.name=GLM-5

# BGE-M3 向量化
bge.endpoint=http://localhost:8000

# Reranker
reranker.enabled=true
reranker.base.url=http://localhost:8001/v1

# WebSearch
websearch.enabled=true
```

---

## AI Agent 规范

### 时间规范（重要）

**AI Agent 内部没有可靠的当前时间**。任何需要使用时间的场景（WebSearch、日志分析、代码生成等），**必须先执行 `date` 命令获取当前时间**。

```
规则：
1. 涉及时间判断时，先执行 date 命令
2. 搜索最新信息时，在查询中加入正确年份
3. 不要依赖模型内部的"知识"来判断当前日期
```

**示例**：
```bash
# 先获取时间
date
# 输出：Sat Feb 28 14:23:45 CST 2026

# 然后在搜索中使用正确年份
web_search "Spring Boot 3.4 新特性 2026"
```

### WebSearch 实现说明

当前项目 WebSearch 使用 **Exa AI MCP 服务**：

| 项目 | 说明 |
|------|------|
| 端点 | `https://mcp.exa.ai/mcp` |
| 认证 | 无需 API Key（免费托管服务） |
| 原理 | AI 原生搜索引擎，语义向量搜索 |

**工作流程**：
```
用户提问 → LLM 判断需要搜索 → 调用 web_search 工具
         → WebSearchTool 发送 MCP 请求到 Exa
         → Exa 返回语义搜索结果 → 格式化返回给 LLM
```

**代码位置**：`src/main/kotlin/com/smancode/sman/tools/ide/WebSearchTool.kt`

**备选方案**（生产环境推荐）：
| 服务商 | 特点 | 价格 |
|--------|------|------|
| Tavily | 专为 AI Agent 设计 | 1000 次/月免费 |
| Serper | Google 结果代理，快 | 高性价比 |
| Perplexity | 事实准确 | 按量计费 |

---

## 开发规范

### 代码规范

| 规范 | 限制 |
|------|------|
| 单文件行数 | ≤ 300 行（Kotlin） |
| 单目录文件数 | ≤ 8 个 |
| 目录层级 | ≤ 3 层 |

### 命名规范

| 类型 | 命名 |
|------|------|
| 服务 | `*Service` |
| 仓库 | `*Repository` |
| 工具 | `*Tool` |
| 协调器 | `*Coordinator` |

---

## 相关文档

| 文档 | 内容 |
|------|------|
| `docs/ARCHITECTURE.md` | 架构文档 |
| `docs/PRD-SelfIteration-System.md` | 自迭代系统 PRD |
| `docs/QUICK_START.md` | 快速启动指南 |
| `docs/TASKS.md` | 任务追踪 |
| `docs/EVAL-Evolution-Loop.md` | 评估日志 |
| `docs/ANALYSIS-Project-Comparison.md` | 与 OpenCode/OpenClaw 对比 |
| `docs/DESIGN-Markdown-Storage.md` | Markdown 存储设计 |
| `docs/DESIGN-User-Preference-Learning.md` | 用户习惯学习设计 |
| `docs/DESIGN-Sandbox-Verification.md` | 沙盒验证设计 |

---

## 实施路线

| Phase | 内容 | 状态 |
|-------|------|------|
| 0 | 目录结构重构 | ✅ 已完成 |
| 1 | Markdown 数据层 | ✅ 已完成 |
| 2 | 自迭代项目理解 | ✅ 已完成 |
| 3 | 用户习惯学习 | 🔲 规划中 |
| 4 | Edit 容错 | 🔲 规划中 |
| 5 | 主动服务 | 🔲 规划中 |
| 6 | 沙盒验证（最低优先级） | 🔲 规划中 |

---

## 当前状态

**自迭代知识进化系统已达到可用状态**，核心能力验证通过：

| 能力 | 状态 | 证据 |
|------|------|------|
| 上下文推理 | ✅ | 能基于现有知识深度推理 |
| 模式识别 | ✅ | 识别 Saga、状态机、策略模式 |
| 跨领域分析 | ✅ | 技术+业务综合分析 |
| 质量稳定 | ✅ | 解析正确，质量评分稳定 |

详见：`docs/EVAL-Evolution-Loop.md`
