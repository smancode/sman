# SmanClaw Desktop - Multi-Agent Coding System

## 项目概述

SmanClaw Desktop 是一个基于多智能体协作的编程助手桌面应用。采用 **"大道至简，弱者道之用"** 的设计哲学，用最简单的 Markdown 文件驱动复杂的多智能体协作流程。

### 核心架构：Main Claw + Sub Claws

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户需求                                 │
└─────────────────────────────────────────────────────────────────┘
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│                      Main Claw (主控)                            │
│  - 解析用户意图                                                   │
│  - 拆解任务为 SubTasks                                            │
│  - 构建 DAG 依赖图                                                │
│  - 分发给 Sub Claws 执行                                          │
│  - 汇总结果、验收                                                 │
└─────────────────────────────────────────────────────────────────┘
                                ↓
┌──────────────┬──────────────┬──────────────┬──────────────────┐
│  Sub Claw 1  │  Sub Claw 2  │  Sub Claw 3  │  Sub Claw N...   │
│  (编码)      │  (测试)      │  (文档)      │  (其他)          │
└──────────────┴──────────────┴──────────────┴──────────────────┘
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│                    ZeroClaw (执行引擎)                           │
│  - 实际的 LLM 调用                                               │
│  - Tool 执行                                                    │
│  - 文件读写、Shell 命令                                          │
└─────────────────────────────────────────────────────────────────┘
```

### 两层循环机制

1. **大循环 (Main Loop)**: Main Claw 编排 → Sub Claws 执行 → Acceptance Evaluator 验收
2. **小循环 (Task Loop)**: 每个 Sub Claw 内部的 checkbox 轮询，基于 task.md 的 `- [ ]` 状态

---

## 与 ZeroClaw 的关系

### 核心原则：站在巨人肩膀上

**ZeroClaw 是执行引擎，SmanClaw 是编排层。**

| 层级 | 职责 | 实现 |
|-----|------|------|
| **SmanClaw Desktop** | UI、项目管理、用户交互 | Tauri + Svelte |
| **SmanClaw Core** | 多智能体编排、任务拆解、DAG 管理 | Rust |
| **ZeroClaw** | LLM 调用、Tool 执行、Provider 管理 | Rust (外部依赖) |

### 关键桥接：ZeroclawBridge + ZeroclawStepExecutor

```rust
// crates/smanclaw-ffi/src/zeroclaw_step_executor.rs
pub struct ZeroclawStepExecutor {
    bridge: Arc<ZeroclawBridge>,
}

impl StepExecutor for ZeroclawStepExecutor {
    async fn execute(&self, prompt: &str) -> Result<String, String> {
        // 委托给 ZeroClaw 执行实际的 LLM 调用
        self.bridge.execute_task_async(prompt).await
            .map(|result| result.output)
            .map_err(|e| e.to_string())
    }
}
```

**关键点**：
- `ZeroclawBridge` 封装 ZeroClaw 的配置和调用
- `ZeroclawStepExecutor` 实现 `StepExecutor` trait，让 Sub Claw 可以统一调用
- Settings（LLM URL、API Key、Model）从 SmanClaw 传递给 ZeroClaw

### 复用 ZeroClaw 的能力

| ZeroClaw 能力 | SmanClaw 使用方式 |
|--------------|------------------|
| Provider (LLM) | 通过 `ZeroclawBridge` 复用 |
| Tools (Shell, File, etc.) | 直接复用 |
| Memory | 可选复用 |
| Security Policy | 继承 ZeroClaw 的安全策略 |

**不要重复造轮子**：
- LLM 调用逻辑 → ZeroClaw 已实现
- Tool 执行框架 → ZeroClaw 已实现
- Provider 抽象 → ZeroClaw 已实现

SmanClaw 专注于：
- 多智能体编排逻辑
- 任务拆解与 DAG 管理
- 验收评估
- UI 和用户体验

---

## 项目结构

```
smanclaw-desktop/
├── crates/
│   ├── smanclaw-desktop/          # Tauri 前端 (Svelte)
│   │   ├── src/                   # Svelte UI 组件
│   │   └── src-tauri/             # Tauri 后端命令
│   │
│   ├── smanclaw-core/             # 核心业务逻辑
│   │   ├── orchestrator.rs        # 多智能体编排
│   │   ├── sub_claw_executor.rs   # Sub Claw 执行器
│   │   ├── acceptance_evaluator.rs # 验收评估器
│   │   ├── runtime.rs             # 运行时事件循环
│   │   ├── identity.rs            # 身份文件 (SOUL.md, USER.md, AGENTS.md)
│   │   └── llm_client.rs          # LLM 客户端抽象（用于测试）
│   │
│   ├── smanclaw-ffi/              # FFI 桥接层
│   │   ├── zeroclaw_bridge.rs     # ZeroClaw 桥接
│   │   ├── zeroclaw_step_executor.rs # StepExecutor 实现
│   │   └── orchestration_bridge.rs   # 编排桥接
│   │
│   └── smanclaw-types/            # 共享类型定义
│       ├── settings.rs            # AppSettings, LlmSettings
│       ├── task.rs                # Task, SubTask, TaskDag
│       └── events.rs              # 进度事件
│
└── docs/
    └── desktop-app-implementation-plan.md  # 实现计划
```

---

## 默认配置

### LLM 设置

- **默认 API URL**: `https://open.bigmodel.cn/api/coding/paas/v4` (智谱 GLM)
- **默认模型**: `GLM-5`

用户只需填写 API Key 即可开始使用。

### 向量存储（可选）

- Embedding 和 Qdrant 配置是可选的
- 如果未配置，语义记忆功能自动跳过
- 配置后在 UI 隐藏，后端自动处理

---

## 身份文件

SmanClaw 使用简单的 Markdown 文件定义身份上下文：

| 文件 | 用途 |
|-----|------|
| `SOUL.md` | 系统核心价值观和行为准则 |
| `USER.md` | 用户偏好、工作习惯、项目背景 |
| `AGENTS.md` | 各个 Agent 的角色定义和职责 |

这些文件由 `IdentityFiles` 管理，在执行时合并为 System Prompt。

---

## 开发指南

### 启动开发环境

```bash
cd crates/smanclaw-desktop
npm run tauri dev
```

### 运行测试

```bash
cargo test --workspace
```

### 构建发布版

```bash
npm run tauri build
```

---

## 关键依赖

| 依赖 | 版本 | 用途 |
|-----|------|------|
| ZeroClaw | workspace | LLM 执行引擎 |
| Tauri | 2.x | 桌面应用框架 |
| Svelte | 5.x | 前端框架 |
| Tokio | 1.x | 异步运行时 |
| Serde | 1.x | 序列化 |

---

## 扩展阅读

- [ZeroClaw CLAUDE.md](../../CLAUDE.md) - ZeroClaw 项目协议
- [实现计划](docs/desktop-app-implementation-plan.md) - 详细实现计划
- [弱者道之用](docs/weakness-is-the-way.md) - 设计哲学
