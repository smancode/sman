# SmanCode 架构文档

> 版本: v1.0
> 日期: 2026-02-27

---

## 一、项目概述

**SmanCode** 是一个 IntelliJ IDEA 插件，实现**面向业务的 AI 编程智能体**。

### 1.1 核心差异化

- **自迭代项目理解**：Agent 自主理解项目，生成项目专属知识，持续迭代完善
- **用户习惯学习**：自动学习用户偏好，越用越懂你
- **一切基于 Markdown**：简单可靠，用户可见可编辑，Git 友好

### 1.2 技术栈

| 组件 | 技术 |
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

## 二、分层架构

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

## 三、核心模块

### 3.1 自迭代项目理解（domain/puzzle/）

**核心理念**：项目理解 = 拼图游戏

```
项目代码 → 发现空白 → 执行分析 → 验证结果 → 更新拼图 → 发现新空白 → ...
```

**核心组件**：

| 模块 | 职责 | 代码位置 |
|------|------|----------|
| KnowledgeEvolutionLoop | 核心进化循环 | `domain/puzzle/KnowledgeEvolutionLoop.kt` |
| GapDetector | 空白检测器 | `domain/puzzle/GapDetector.kt` |
| PuzzleCoordinator | 协调器 | `domain/puzzle/PuzzleCoordinator.kt` |
| TaskExecutor | 任务执行器 | `domain/puzzle/TaskExecutor.kt` |
| DoomLoopGuard | 死循环防护 | `domain/puzzle/DoomLoopGuard.kt` |
| RecoveryService | 中断恢复 | `domain/puzzle/RecoveryService.kt` |

**拼图类型**：

| 类型 | 描述 | 输出文件 |
|------|------|----------|
| STRUCTURE | 项目结构、模块划分 | `PUZZLE_STRUCTURE.md` |
| TECH_STACK | 技术栈、依赖关系 | `PUZZLE_TECH.md` |
| API | API 入口、调用入口 | `PUZZLE_API.md` |
| DATA | 数据模型、表关系 | `PUZZLE_DATA.md` |
| FLOW | 业务流程、调用链 | `PUZZLE_FLOW_*.md` |
| RULE | 业务规则、约束条件 | `PUZZLE_RULES.md` |

### 3.2 数据存储（基于 Markdown）

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
    └── vectors/           # 向量索引
```

### 3.3 向量搜索

**三层缓存架构**：
- L1: 内存缓存（最快）
- L2: JVector 索引（持久化）
- L3: H2 数据库（可选）

**搜索流程**：
```
Query → BGE-M3 向量化 → JVector 检索 → Reranker 重排 → 返回结果
```

---

## 四、目录结构

```
src/main/kotlin/com/smancode/sman/
│
├── app/                        # 应用层
│   ├── SmanPlugin.kt          # 插件入口
│   └── ServiceLocator.kt      # 服务定位器
│
├── domain/                     # 领域层（核心业务逻辑）
│   ├── puzzle/                # 自迭代项目理解系统
│   │   ├── KnowledgeEvolutionLoop.kt
│   │   ├── GapDetector.kt
│   │   ├── PuzzleCoordinator.kt
│   │   ├── TaskExecutor.kt
│   │   ├── EvolutionPromptBuilder.kt
│   │   ├── EvolutionResponseParser.kt
│   │   └── TaskGapMapper.kt
│   │
│   ├── memory/                # 用户习惯学习系统
│   │   └── ...
│   │
│   └── session/               # 会话管理
│       └── ...
│
├── infra/                      # 基础设施层
│   ├── llm/                   # LLM 调用
│   │   └── LlmService.kt
│   │
│   ├── vector/                # 向量存储
│   │   ├── BgeM3Client.kt
│   │   ├── RerankerClient.kt
│   │   └── JVectorStore.kt
│   │
│   └── storage/               # 持久化
│       ├── PuzzleStore.kt
│       ├── MemoryStore.kt
│       └── TaskQueueStore.kt
│
├── tools/                      # 工具层
│   ├── file/
│   ├── search/
│   └── code/
│
└── shared/                     # 共享层
    ├── model/
    ├── config/
    └── util/
```

---

## 五、核心流程

### 5.1 知识进化循环

```
1. 观察 (Observe) → 加载现有知识，分析触发原因
2. 假设 (Hypothesize) → 生成分析假设
3. 计划 (Plan) → 拆解为具体任务
4. 执行 (Execute) → 调用 LLM 分析
5. 评估 (Evaluate) → 验证结果质量
6. 合并 (Integrate) → 更新 Puzzle 知识库
```

### 5.2 触发方式

```kotlin
sealed class Trigger {
    data class UserQuery(val query: String) : Trigger()    // 用户提问
    data class FileChange(val files: List<String>) : Trigger()  // 文件变更
    data class Scheduled(val reason: String) : Trigger()   // 定时触发
    data class Manual(val reason: String) : Trigger()      // 手动触发
}
```

---

## 六、配置说明

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
```

---

## 七、构建命令

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

## 八、变更历史

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v1.0 | 2026-02-27 | 初始版本，基于当前实现状态更新 |
