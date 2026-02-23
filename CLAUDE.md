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
| `docs/项目战略分析-三项目对比.md` | 与 OpenCode/OpenClaw 对比 |
| `docs/核心能力-自迭代项目理解系统.md` | Phase 2 详细设计 |
| `docs/设计方案-Markdown驱动架构.md` | Phase 1 详细设计 |
| `docs/设计方案-用户习惯学习.md` | Phase 3 详细设计 |
| `docs/阶段0-项目结构重构方案.md` | 目录结构重构方案 |

---

## 实施路线

| Phase | 内容 | 时间 |
|-------|------|------|
| 0 | 目录结构重构 | 1 周 |
| 1 | Markdown 数据层 | 2-3 周 |
| 2 | 自迭代项目理解 | 4-5 周 |
| 3 | 用户习惯学习 | 3-4 周 |
| 4 | Edit 容错 | 2 周 |
| 5 | 主动服务 | 2-3 周 |
| 6 | 沙盒验证（最低优先级） | 3-4 周 |
