# SmanCode - 面向业务的智能编程助手

> 版本: v1.0 | 更新日期: 2026-02-27
>
> IntelliJ IDEA 插件，实现自迭代项目理解、用户习惯学习的 AI 编程智能体

## 核心特性

- **自迭代项目理解**：Agent 自主理解项目，像拼图一样持续完善项目知识
- **用户习惯学习**：自动学习你的代码风格和偏好，越用越懂你
- **一切基于 Markdown**：简单可靠，用户可见可编辑，天然支持 Git
- **语义代码搜索**：BGE-M3 向量化 + Reranker 重排，精准定位代码
- **ReAct 多步推理**：Reasoning + Acting 交替，支持复杂任务分解

## 快速开始

### 前置要求

- IntelliJ IDEA 2024.1+
- JDK 17+
- GLM API Key（从 [智谱 AI](https://open.bigmodel.cn/) 获取）

### 安装

```bash
# 克隆项目
git clone git@github.com:smancode/sman.git
cd sman

# 配置 API Key
export LLM_API_KEY=your_api_key_here

# 构建并运行
./gradlew runIde
```

### 配置

编辑 `src/main/resources/sman.properties`：

```properties
# LLM 配置
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
llm.model.name=GLM-5

# BGE-M3 向量化（可选，用于语义搜索）
bge.endpoint=http://localhost:8000

# Reranker（可选，用于搜索重排）
reranker.enabled=true
reranker.base.url=http://localhost:8001/v1
```

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         应用层 (app/)                            │
├─────────────────────────────────────────────────────────────────┤
│                         领域层 (domain/)                         │
│  puzzle/（自迭代理解）| memory/（习惯学习）| react/（ReAct 循环） │
├─────────────────────────────────────────────────────────────────┤
│                       基础设施层 (infra/)                        │
│  llm/（LLM 调用）| vector/（向量存储）| storage/（持久化）       │
├─────────────────────────────────────────────────────────────────┤
│  tools/（工具层）| shared/（共享层）                            │
└─────────────────────────────────────────────────────────────────┘
```

## 核心模块

### 自迭代知识进化系统

```
┌─────────────────────────────────────────────────────────────────┐
│                    自迭代知识进化系统                             │
├─────────────────────────────────────────────────────────────────┤
│  【主动发现】              【被动响应】                          │
│  GapDetector              KnowledgeEvolutionLoop                │
│       │                         │                               │
│       ▼                         ▼                               │
│  发现空白 ──────────────────► 触发进化                           │
│       │                         │                               │
│       ▼                         ▼                               │
│  PuzzleCoordinator ◄─────── 执行分析                            │
│       │                         │                               │
│       ▼                         ▼                               │
│  TaskQueue → TaskExecutor → 生成 Puzzle                         │
└─────────────────────────────────────────────────────────────────┘
```

### 数据存储

一切基于 Markdown，存储在项目 `.sman/` 目录：

```
{projectPath}/.sman/
├── MEMORY.md              # 项目记忆（用户偏好、业务规则）
├── puzzles/               # 项目知识拼图
│   ├── status.json        # 拼图状态汇总
│   ├── PUZZLE_STRUCTURE.md
│   ├── PUZZLE_TECH.md
│   ├── PUZZLE_API.md
│   ├── PUZZLE_DATA.md
│   └── flow/              # 业务流程拼图
├── queue/                 # 待分析任务队列
└── cache/                 # 缓存（不入 Git）
    └── vectors/           # 向量索引
```

## 可用工具

| 工具 | 描述 |
|------|------|
| `read_file` | 读取文件内容 |
| `edit_file` | 编辑文件 |
| `find_file` | 查找文件 |
| `grep` | 正则搜索 |
| `semantic_search` | 语义搜索（BGE + Reranker） |
| `call_chain` | 调用链分析 |
| `analyze_flow` | 流程分析 |
| `web_search` | Web 搜索 |
| `skill` | 加载技能 |

## 使用示例

```
>>> 分析支付流程的实现
>>> 找出所有调用 PaymentService 的地方
>>> 我喜欢用 val 而不是 var，记住这个偏好
>>> 解释这个项目的整体架构
```

## 开发

```bash
# 运行测试
./gradlew test

# 构建插件
./gradlew buildPlugin

# 代码检查
./gradlew compileKotlin
```

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 (JDK 17+) |
| 平台 | IntelliJ IDEA 2024.1+ |
| 向量存储 | JVector 3.0.0 |
| 向量化 | BGE-M3 (外部服务) |
| 重排序 | BGE-Reranker-v2-m3 |
| 持久化 | Markdown 文件 |
| 测试 | JUnit 5 + MockK |

## 文档索引

| 文档 | 内容 |
|------|------|
| [CLAUDE.md](CLAUDE.md) | 项目架构和开发指南 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构文档 |
| [docs/PRD-SelfIteration-System.md](docs/PRD-SelfIteration-System.md) | 自迭代系统 PRD |
| [docs/QUICK_START.md](docs/QUICK_START.md) | 快速启动指南 |
| [docs/TASKS.md](docs/TASKS.md) | 任务追踪 |
| [docs/EVAL-Evolution-Loop.md](docs/EVAL-Evolution-Loop.md) | 评估日志 |

## 实施路线

| Phase | 内容 | 状态 |
|-------|------|------|
| 0 | 目录结构重构 | ✅ 已完成 |
| 1 | Markdown 数据层 | ✅ 已完成 |
| 2 | 自迭代项目理解（核心） | ✅ 已完成 |
| 3 | 用户习惯学习 | 🔲 规划中 |
| 4 | Edit 容错 | 🔲 规划中 |
| 5 | 主动服务 | 🔲 规划中 |
| 6 | 沙盒验证 | 🔲 规划中 |

## 许可证

Apache License 2.0
