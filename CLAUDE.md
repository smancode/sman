# CLAUDE.md

SmanCode - AI 驱动的自迭代编程智能体插件

---

## 项目概述

SmanCode 是一个 IntelliJ IDEA 插件，实现了基于 ReAct 模式的 AI 编程智能体。核心特性：

- **ReAct 循环**：Reasoning + Acting 交替进行，支持多步工具调用
- **自进化学习**：后台自动生成问题、探索学习、积累知识（evolution 模块）
- **断点续传**：IDEA 重启后自动恢复自进化循环状态
- **三层缓存架构**：L1 内存 + L2 JVector + L3 H2，高效处理大型项目
- **语义搜索**：BGE-M3 向量化 + BGE-Reranker 重排，实现代码语义检索
- **提示词驱动**：项目分析模块自动运行，生成项目知识库

---

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 (JDK 17+) |
| 平台 | IntelliJ IDEA 2024.1+ |
| HTTP | OkHttp 4.12.0 |
| JSON | Jackson 2.16.0 + kotlinx.serialization 1.6.0 |
| 数据库 | H2 2.2.224 + JVector 3.0.0 + HikariCP |
| 向量化 | BGE-M3 (外部服务) |
| 重排序 | BGE-Reranker-v2-m3 (外部服务) |
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
./gradlew test --tests "*LlmCodeUnderstandingServiceTest*"

# 仅编译
./gradlew compileKotlin

# 插件验证
./gradlew verifyPluginConfiguration

# 运行验证 Web 服务
./gradlew runVerification -Pverification.port=8080
```

---

## 目录结构

```
src/main/kotlin/com/smancode/sman/
├── analysis/           # 项目分析模块
│   ├── coordination/  # 代码向量化协调器
│   ├── database/       # 向量存储（JVector + H2）
│   ├── executor/       # 分析任务执行器
│   ├── llm/            # LLM 代码理解服务
│   ├── paths/          # 项目路径管理
│   ├── scheduler/      # 后台分析调度器
│   └── vectorization/  # BGE-M3 向量化客户端
├── config/             # 配置管理（SmanConfig, SmanCodeProperties）
├── evolution/          # 【核心】自迭代进化模块
│   ├── context/        # 上下文注入器
│   ├── explorer/       # 工具探索器
│   ├── generator/      # 问题生成器（QuestionGenerator）
│   ├── guard/          # 死循环防护（DoomLoopGuard, 配额管理）
│   ├── knowledge/      # 知识范围定义
│   ├── learning/       # 学习记录
│   ├── loop/           # 自进化主循环（SelfEvolutionLoop）
│   ├── memory/         # 学习记录仓储
│   ├── model/          # 进化模型（LearningRecord, EvolutionStatus）
│   ├── persistence/    # 状态持久化（断点续传）
│   ├── recall/         # 多路径召回器
│   └── recorder/       # 学习记录器
├── ide/                # IntelliJ 集成
│   ├── action/         # IDE 动作
│   ├── components/     # UI 组件
│   ├── renderer/       # 消息渲染器
│   ├── service/        # IDE 服务
│   └── ui/             # 设置对话框、聊天面板
├── model/              # 数据模型（Message, Part, Session）
├── smancode/           # ReAct 循环核心
│   ├── core/           # SmanLoop（主循环）
│   ├── llm/            # LLM 服务
│   └── prompt/         # 提示词管理
├── tools/              # 工具系统
│   └── ide/            # 工具实现（expert_consult 等）
├── util/               # 工具类
└── verification/       # 验证 Web 服务

src/main/resources/
├── META-INF/plugin.xml    # 插件描述符
├── db/evolution-schema.sql # 进化数据库表结构
├── sman.properties        # 配置文件
├── prompts/               # 提示词模板
│   ├── analysis/          # 分析相关提示词
│   ├── common/            # 通用提示词
│   └── tools/             # 工具提示词
└── templates/             # 模板文件
```

---

## 核心模块说明

### 1. ReAct 循环（SmanLoop）

位置：`smancode/core/SmanLoop.kt`

核心流程：
1. 接收用户消息
2. 检查上下文压缩需求
3. 调用 LLM 流式处理
4. 解析工具调用（JSON 提取支持多级降级）
5. 在子会话中执行工具（上下文隔离）
6. 推送 Part 到前端

特性：
- **Doom Loop 检测**：自动检测重复工具调用，防止无限循环
- **智能摘要**：历史工具只发送摘要，新工具发送完整结果并要求 LLM 生成摘要
- **多级 JSON 提取**：从直接解析到 LLM 辅助修复，确保最大容错

### 2. 自进化模块（Evolution）

位置：`evolution/`

这是项目的核心特性，实现了自迭代学习智能体：

| 组件 | 职责 |
|------|------|
| `SelfEvolutionLoop` | 后台主循环，持续生成问题并探索学习 |
| `QuestionGenerator` | 基于项目上下文生成好问题 |
| `DoomLoopGuard` | 死循环防护 + 指数退避 + 每日配额 |
| `LearningRecorder` | 总结学习成果并持久化 |
| `LearningRecordRepository` | 学习记录的增删改查 |
| `EvolutionStateRepository` | 状态持久化（断点续传） |

进化阶段（EvolutionPhase）：
```
IDLE → CHECKING_BACKOFF → GENERATING_QUESTION → EXPLORING → SUMMARIZING → PERSISTING
```

#### 断点续传机制

IDEA 重启后自动恢复：

| 恢复内容 | 说明 |
|---------|------|
| 统计信息 | totalIterations, successfulIterations 等 |
| ING 状态 | 当前正在处理的问题和探索进度 |
| 退避状态 | 指数退避状态 |
| 每日配额 | 问题生成和探索配额 |

启动时检测：
- 如果 `currentPhase != IDLE` 且 `!= CHECKING_BACKOFF`，则从中断处恢复
- 否则正常启动

配置项（sman.properties）：
```properties
self.evolution.enabled=false  # 启用自进化
```

### 3. 三层缓存架构

```
L1 (Hot):  内存 LRU 缓存（~500 entries）
L2 (Warm): JVector 向量索引（磁盘持久化）
L3 (Cold): H2 数据库（持久存储）
```

配置项：
```properties
vector.db.type=JVECTOR
vector.db.l1.cache.size=500
```

### 4. 工具系统

可用工具：
| 工具名 | 描述 |
|--------|------|
| `read_file` | 读取文件内容 |
| `grep_file` | 正则搜索 |
| `find_file` | 文件模式搜索 |
| `call_chain` | 调用链分析 |
| `extract_xml` | 提取 XML 标签内容 |
| `apply_change` | 代码修改 |
| `expert_consult` | 语义搜索（BGE + Reranker） |
| `batch` | 批量执行工具 |

工具注册：手动注册，无 Spring DI

---

## 配置说明

配置文件：`src/main/resources/sman.properties`

### LLM 配置
```properties
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
llm.model.name=GLM-5
llm.response.max.tokens=28192
```

### ReAct 配置
```properties
react.max.steps=25
react.enable.streaming=true
```

### 上下文压缩
```properties
compaction.max.tokens=156000
compaction.threshold=128000
```

### BGE-M3 / Reranker
```properties
bge.endpoint=http://localhost:8000
reranker.enabled=true
reranker.base.url=http://localhost:8001/v1
```

### 自进化配置
```properties
self.evolution.enabled=false
self.evolution.deep.analysis.enabled=false
self.evolution.questions.per.iteration=3
self.evolution.max.exploration.steps=5
```

配置优先级：用户设置 > 环境变量 > 配置文件 > 默认值

---

## 数据存储

每个项目独立存储，路径：`{projectPath}/.sman/`

```
{projectPath}/.sman/
├── analysis.mv.db.mv.db # H2 数据库（含进化状态表）
├── base/                 # 基础分析结果
├── cache/                # MD5 缓存
└── md/
    ├── classes/          # 类级分析结果
    └── reports/          # 项目级分析结果
```

### 数据库表

- `learning_records` - 学习记录
- `failure_records` - 失败记录
- `evolution_loop_state` - 进化循环运行状态（含 ING）
- `backoff_state` - 退避状态
- `daily_quota` - 每日配额

---

## 开发规范

### 关键设计决策

1. **无 Spring DI**：工具采用手动注册模式
2. **三层缓存**：防止大型项目内存溢出
3. **增量更新**：基于 MD5 的文件变更检测
4. **上下文隔离**：子会话执行工具，防止 Token 爆炸
5. **流式优先**：实时输出显示
6. **语义搜索直接返回**：`expert_consult` 返回 BGE 结果，不经过 LLM 处理
7. **断点续传**：状态持久化到 H2，IDEA 重启后自动恢复

### 代码风格

- 单一职责原则
- 动态语言 300 行 / 静态语言 500 行限制
- 单文件夹不超过 8 个文件

---

## 环境设置

```bash
# 设置 API Key
export LLM_API_KEY=your_api_key_here

# 启动 BGE-M3 服务（如需语义搜索）
# docker run -p 8000:8000 ...

# 启动 Reranker 服务（如需重排序）
# docker run -p 8001:8001 ...
```

---

## 测试报告

运行测试后查看：`build/reports/tests/test/index.html`
