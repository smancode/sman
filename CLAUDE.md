# CLAUDE.md

SmanCode - 面向业务的智能编程助手

---

## 项目定位

SmanCode 是一个 IntelliJ IDEA 插件，实现**面向业务的 AI 编程智能体**。

**核心差异化**：
- **自迭代项目理解**：Agent 自主理解项目，生成项目专属知识，持续迭代完善
- **用户习惯学习**：自动学习用户偏好，越用越懂你
- **一切基于 Markdown**：简单可靠，用户可见可编辑，Git 友好
- **分层项目分析**：L0~L4 五层分析器，从结构到深度逐层理解

---

## 核心架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         应用层 (ide/)                            │
│  ui/ | renderer/ | listener/ | service/ | action/               │
├─────────────────────────────────────────────────────────────────┤
│                       项目分析层 (analysis/)                      │
│  ┌──────────────┬──────────────┬──────────────┬──────────────┐  │
│  │  结构分析    │  模块分析    │  场景分析    │  深度分析    │  │
│  │  L0/L1       │  L2          │  L3          │  L4          │  │
│  └──────────────┴──────────────┴──────────────┴──────────────┘  │
│  loop/ | scheduler/ | executor/ | retry/ | guard/               │
├─────────────────────────────────────────────────────────────────┤
│                       基础设施层 (infra/)                        │
│  llm/（LLM 调用）| vector/（BGE+JVector）| storage/（Markdown） │
├─────────────────────────────────────────────────────────────────┤
│                         工具层 (tools/)                          │
│  ide/ | puzzle/                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 目录结构

```
src/main/kotlin/com/smancode/sman/
│
├── ide/                        # IDE 集成层
│   ├── ui/                     # UI 组件
│   ├── renderer/               # 消息渲染
│   ├── listener/               # 事件监听
│   ├── service/                # IDE 服务
│   ├── action/                 # 用户动作
│   ├── component/              # 通用组件
│   └── util/                   # 工具类
│
├── analysis/                   # 项目分析层（核心）
│   │
│   ├── L0StructureAnalyzer.kt  # L0: 项目结构分析
│   ├── L1ModuleAnalyzer.kt     # L1: 模块分析
│   ├── L3ScenarioAnalyzer.kt   # L3: 场景分析
│   ├── CallGraphAnalyzer.kt    # 调用图分析
│   ├── ControlFlowAnalyzer.kt  # 控制流分析
│   ├── DataFlowAnalyzer.kt     # 数据流分析
│   │
│   ├── loop/                   # 分析循环
│   │   ├── ProjectAnalysisLoop.kt
│   │   └── Analysis接力StateMachine.kt
│   │
│   ├── scheduler/              # 任务调度
│   │   ├── IntelligentAnalysisScheduler.kt
│   │   └── ProjectAnalysisScheduler.kt
│   │
│   ├── executor/               # 任务执行
│   ├── retry/                  # 重试机制
│   ├── guard/                  # 防护机制
│   │
│   ├── database/               # 数据库分析
│   │   ├── JVectorStore.kt     # 向量存储
│   │   ├── TieredVectorStore.kt # 三层缓存
│   │   └── model/              # 数据模型
│   │
│   ├── vectorization/          # 向量化
│   │   ├── BgeM3Client.kt      # BGE-M3 客户端
│   │   └── TokenEstimator.kt   # Token 估算
│   │
│   ├── llm/                    # LLM 代码理解
│   │   └── LlmCodeUnderstandingService.kt
│   │
│   ├── entry/                  # 入口扫描
│   ├── enum/                   # 枚举扫描
│   ├── common/                 # 公共类扫描
│   ├── external/               # 外部 API 扫描
│   ├── techstack/              # 技术栈检测
│   └── structure/              # 结构扫描
│
├── infra/                      # 基础设施层
│   ├── llm/                    # LLM 调用
│   ├── vector/                 # 向量服务
│   └── storage/                # 存储服务
│
├── tools/                      # 工具层
│   ├── ide/                    # IDE 工具
│   │   ├── WebSearchTool.kt    # Web 搜索
│   │   ├── LocalToolAdapter.kt # 本地工具适配
│   │   └── TavilySearchProvider.kt
│   └── puzzle/                 # 拼图工具
│
└── config/                     # 配置
    └── SmanCodeProperties.kt
```

---

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 (JDK 17+) |
| 平台 | IntelliJ IDEA 2024.1+ |
| HTTP | OkHttp 4.12.0 |
| JSON | Jackson 2.16.0 + kotlinx.serialization 1.6.0 |
| 向量存储 | JVector 3.0.0 + 三层缓存 (L1/L2/L3) |
| 向量化 | BGE-M3 (外部服务, 1024 维) |
| 重排序 | BGE-Reranker-v2-m3 (外部服务) |
| 持久化 | Markdown 文件 + H2 (可选) |
| WebSearch | Exa AI MCP + Tavily (降级) |
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

### 1. 分层项目分析（analysis/）

**核心理念**：从宏观到微观，五层分析器逐层深入。

**分析层级**：
| 层级 | 名称 | 职责 | 输出 |
|------|------|------|------|
| L0 | 结构分析 | 项目结构、模块发现 | 模块列表、技术栈 |
| L1 | 模块分析 | 模块内部结构 | 包依赖、分层 |
| L2 | 代码分析 | 类/方法级别 | 调用图、数据流 |
| L3 | 场景分析 | 业务场景 | 入口→核心链路 |
| L4 | 深度分析 | 算法/协议细节 | 关键实现 |

**自迭代循环**：
```
发现空白 → 选择任务 → 执行分析 → 验证结果 → 更新拼图 → 发现新空白
```

**调度器**：
- `IntelligentAnalysisScheduler`：智能分析调度
- `Analysis接力StateMachine`：分析接力状态机

### 2. 向量存储系统（analysis/database/）

**三层缓存架构**：
| 层级 | 存储 | 特点 | 命中率 |
|------|------|------|--------|
| L1 | 内存 LRU | 热点数据，毫秒级 | ~60% |
| L2 | JVector 磁盘 | 温数据，秒级 | ~35% |
| L3 | BGE-M3 向量化 | 冷数据，需计算 | ~5% |

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
| `analyze_flow` | 流程分析 |
| `web_search` | Web 搜索（Exa + Tavily 降级） |
| `load_puzzle` | 加载项目拼图 |
| `search_puzzles` | 搜索拼图知识 |

---

## 配置说明

配置文件：`src/main/resources/sman.properties`

```properties
# LLM 配置
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
llm.model.name=GLM-5
llm.timeout.read=600000

# BGE-M3 向量化
bge.endpoint=http://localhost:8000
bge.dimension=1024
bge.truncation.strategy=TAIL

# Reranker
reranker.enabled=true
reranker.base.url=http://localhost:8001/v1
reranker.threshold=0.1

# WebSearch（Exa AI MCP + Tavily 降级）
websearch.enabled=true
websearch.tavily.api.key=

# 项目分析
analysis.force.refresh=true
analysis.llm.vectorization.enabled=true
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

当前项目 WebSearch 支持 **双重搜索源**：

| 搜索源 | 优先级 | 说明 |
|--------|--------|------|
| Exa AI MCP | 主 | 免费托管服务，无需 API Key |
| Tavily | 降级 | 1000 次/月免费，需配置 API Key |

**工作流程**：
```
用户提问 → LLM 判断需要搜索 → 调用 web_search 工具
         → WebSearchTool 优先 Exa
         → Exa 限流时自动降级到 Tavily
         → 返回搜索结果 → 格式化返回给 LLM
```

**代码位置**：
- `src/main/kotlin/com/smancode/sman/tools/ide/WebSearchTool.kt`
- `src/main/kotlin/com/smancode/sman/tools/ide/TavilySearchProvider.kt`

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
| `docs/COMPARISON-Evolution-vs-Direct.md` | 自迭代 vs 直接分析对比 |
| `docs/IMPLEMENTATION_PROGRESS.md` | 实施进度 |
| `docs/CONFIGURATION_GUIDE.md` | 配置指南 |
| `docs/TESTING.md` | 测试指南 |

---

## 实施路线

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | 知识注入（核心） | ✅ 已完成 |
| 2 | 增强代码理解 | 🔲 进行中 |
| 3 | 用户习惯学习 | ✅ 已完成 |
| 4 | Edit 容错增强 | 🔲 规划中 |
| 5 | 主动服务 | 🔲 规划中 |

---

## 当前状态

**自迭代知识进化系统已达到可用状态**，核心能力验证通过：

| 能力 | 状态 | 证据 |
|------|------|------|
| 上下文推理 | ✅ | 能基于现有知识深度推理 |
| 模式识别 | ✅ | 识别 Saga、状态机、策略模式 |
| 跨领域分析 | ✅ | 技术+业务综合分析 |
| 质量稳定 | ✅ | 解析正确，质量评分稳定 |
| 用户偏好注入 | ✅ | PreferenceInjector 集成完成 |

详见：`docs/EVAL-Evolution-Loop.md`、`docs/COMPARISON-Evolution-vs-Direct.md`
