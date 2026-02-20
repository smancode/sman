# CLAUDE.md

SmanCode - AI 驱动的智能编程助手插件

---

## 项目概述

SmanCode 是一个 IntelliJ IDEA 插件，实现了基于 ReAct 模式的 AI 编程智能体。核心特性：

- **ReAct 循环**：Reasoning + Acting 交替进行，支持多步工具调用
- **技能系统**：支持加载领域特定技能，扩展 AI 能力
- **三层缓存架构**：L1 内存 + L2 JVector + L3 H2，高效处理大型项目
- **语义搜索**：BGE-M3 向量化 + BGE-Reranker 重排，实现代码语义检索
- **Web 搜索**：集成 Exa AI MCP 服务，支持实时网络搜索
- **断点续传**：IDEA 重启后自动恢复分析状态

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
| Web 搜索 | Exa AI MCP 服务 |
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
│   ├── coordination/   # 代码向量化协调器
│   ├── database/       # 向量存储（JVector + H2 + 三层缓存）
│   ├── executor/       # 分析任务执行器（AnalysisLoopExecutor）
│   ├── guard/          # 死循环防护（DoomLoopGuard, 指数退避, 去重）
│   ├── llm/            # LLM 代码理解服务
│   ├── loop/           # 项目分析主循环（ProjectAnalysisLoop）
│   ├── model/          # 分析模型（AnalysisType, AnalysisState, ProjectMap）
│   ├── paths/          # 项目路径管理
│   ├── persistence/    # 分析状态持久化
│   ├── retry/          # 重试机制（熔断器, 并发限制, 失败记录）
│   ├── scheduler/      # 后台分析调度器
│   ├── service/        # 向量化和上下文注入服务
│   ├── storage/        # 向量仓储
│   └── vectorization/  # BGE-M3 向量化客户端
├── config/             # 配置管理（SmanConfig, SmanCodeProperties）
├── ide/                # IntelliJ 集成
│   ├── action/         # IDE 动作
│   ├── component/      # UI 组件
│   ├── renderer/       # 消息渲染器
│   ├── service/        # IDE 服务（WebSocket, 代码编辑, Git 提交）
│   └── ui/             # 设置对话框、聊天面板
├── model/              # 数据模型（Message, Part, Session）
│   ├── context/        # 上下文模型
│   ├── message/        # 消息模型
│   ├── part/           # Part 类型（Goal, Progress, Reasoning, SubTask, Text, Todo, Tool）
│   └── session/        # 会话模型
├── skill/              # 技能系统
│   ├── SkillInfo.kt    # 技能信息模型
│   ├── SkillLoader.kt  # 技能加载器（从文件系统加载 SKILL.md）
│   ├── SkillRegistry.kt# 技能注册中心
│   └── SkillTool.kt    # 技能工具（允许 LLM 加载专业化技能）
├── smancode/           # ReAct 循环核心
│   ├── core/           # SmanLoop（主循环）、上下文压缩、子任务执行
│   ├── llm/            # LLM 服务
│   └── prompt/         # 提示词管理（动态注入、分发器、加载器）
├── tools/              # 工具系统
│   ├── ide/            # 工具实现
│   │   ├── LocalToolAdapter.kt       # 本地工具适配器
│   │   ├── LocalExpertConsultService.kt # 本地专家咨询服务
│   │   └── WebSearchTool.kt          # Web 搜索工具（Exa AI）
│   └── *.kt            # 工具基类、注册表、执行器
├── util/               # 工具类
└── verification/       # 验证 Web 服务

src/main/resources/
├── META-INF/plugin.xml    # 插件描述符
├── db/analysis-schema.sql # 分析数据库表结构
├── sman.properties        # 配置文件
├── prompts/               # 提示词模板
│   ├── analysis/          # 分析相关提示词（6 种分析类型）
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

### 2. 技能系统（Skill）

位置：`skill/`

允许加载领域特定技能，扩展 AI 能力：

| 组件 | 职责 |
|------|------|
| `SkillLoader` | 从文件系统加载 SKILL.md |
| `SkillRegistry` | 管理所有已加载技能 |
| `SkillTool` | 允许 LLM 加载专业化技能 |

Skill 加载路径（优先级从高到低）：
1. `<project>/.sman/skills/<name>/SKILL.md`
2. `<project>/.claude/skills/<name>/SKILL.md`
3. `~/.claude/skills/<name>/SKILL.md`

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
| `web_search` | Web 搜索（Exa AI） |
| `skill` | 加载技能 |
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

### WebSearch
```properties
websearch.enabled=true
websearch.timeout.seconds=25
```

配置优先级：用户设置 > 环境变量 > 配置文件 > 默认值

---

## 数据存储

每个项目独立存储，路径：`{projectPath}/.sman/`

```
{projectPath}/.sman/
├── analysis.mv.db       # H2 数据库
├── base/                # 基础分析结果
├── cache/               # MD5 缓存
└── md/
    ├── classes/         # 类级分析结果
    └── reports/         # 项目级分析结果
```

### 数据库表

- `analysis_loop_state` - 分析循环状态（断点续传）
- `analysis_result` - 分析结果存储

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
