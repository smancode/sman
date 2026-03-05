# SmanClaw Desktop App 实现蓝图

## 概述

基于 ZeroClaw 开发跨平台桌面应用（Windows + macOS），支持可视化管理项目和执行编码任务。

## 0. 当前实现对照（2026-03-05）

### 0.1 Desktop → 主/子 Claw 实际调用链路

1. 前端通过 `orchestrationApi.executeTask` 调用 Tauri 命令 `execute_orchestrated_task`
2. `execute_orchestrated_task` 在 Tauri 层直接调用 `Orchestrator::parse_requirement` 与 `Orchestrator::build_dag`
3. Tauri 层按 DAG 分组循环执行，每个子任务先写入 `.smanclaw/tasks/<subtask-id>.md`
4. 每个子任务由 `SubClawExecutor::run()` 执行 checklist 循环，完成后回写 `- [x]`
5. 执行进度通过事件 `subtask-started` / `subtask-completed` / `orchestration-progress` 推送到前端
6. 全部子任务结束后更新 Task 状态为 completed/failed，并可通过 `get_task_dag` / `get_orchestration_status` 查询

### 0.2 蓝图完成度评估（按主子 Claw 自动化目标）

| 能力项 | 蓝图目标 | 当前状态 | 完成度 |
|---|---|---|---|
| 主控状态机 | Analyzing→Splitting→Dispatching→Polling→Evaluating 闭环 | `smanclaw-core::Orchestrator::handle_request` 已实现，但 desktop 主链路仍走 `execute_orchestrated_task` | ⚠️ 部分实现 |
| 任务拆解 | 基于语义理解拆分子任务 | 已接入“语义优先 + 规则兜底”拆解，仍需提升复杂需求稳定性 | ⚠️ 部分实现 |
| DAG 管理 | 依赖分析与拓扑排序 | `TaskDag` 已实现并用于 orchestrated 命令 | ✅ 已实现 |
| 子 Claw 执行 | 按 task.md checklist 自动执行 | `SubClawExecutor` 已接入 orchestrated 命令并执行 checklist | ✅ 已实现 |
| 并行执行 | 无依赖任务并行执行 | 当前“分组内顺序执行”，仅结构支持并行 | ⚠️ 部分实现 |
| 验收评估 | 按验收标准自动评估（测试/E2E/命令） | orchestrated 主流程已接入 `AcceptanceEvaluator`，当前以基础规则校验为主 | ⚠️ 部分实现 |
| main-task.md 协同 | 主 Claw 输出 main-task.md 并追踪子任务 | orchestrated 主流程已接入 `MainTaskManager`（创建、子任务状态更新、完成回写） | ✅ 已实现 |
| 经验沉淀 | 子任务经验回流并更新技能库 | orchestrated 主流程已接入 `ExperienceSink`，按子任务提取经验并尝试更新技能 | ✅ 已实现 |
| ZeroClaw 驱动子任务 | 子 Claw 通过真实 LLM 执行步骤 | orchestrated 主流程已接入 `ZeroclawStepExecutor`，桥接失败时会降级占位执行 | ⚠️ 部分实现 |

### 0.3 已确认缺口（与“核心自动化愿景”对照）

1. **主链路分叉**：Desktop 当前编排主链路仍在 Tauri `execute_orchestrated_task`，尚未统一切到 `Orchestrator::handle_request`。
2. **并行执行未落地**：虽然 DAG 有 parallel group，但组内仍按顺序执行。
3. **拆解智能度不足**：子任务拆解仍偏规则匹配，语义泛化能力有限。
4. **验收深度不足**：已接入 `AcceptanceEvaluator`，但当前偏基础校验，未形成失败补救再执行闭环。
5. **状态单一事实源未统一**：`tasks.db` 与 `main-task.md` 均在更新，仍需统一状态来源与一致性策略。
6. **ZeroClaw 失败降级策略偏宽松**：桥接不可用时会降级占位执行，可能导致“执行成功”语义偏弱。
7. **交互说明仍可增强**：已增加主 Claw 分阶段状态说明，但前端尚未提供独立“过程解说面板/时间线”。

### 0.5 交互待办（暂缓项）

- 在前端增加“主 Claw 过程说明时间线”，按分析/拆解/分发/执行/验收分阶段展示细节。
- 将子任务测试证据（命令、通过/失败摘要）结构化展示，避免用户只看到最终一句话。
- 支持长任务中的心跳播报与预计剩余步骤提示，降低“无响应等待”感知。

### 0.4 建议的收敛路径（按落地优先级）

1. 将 Desktop orchestrated 入口统一切换到 `Orchestrator::handle_request`，Tauri 层仅做命令与事件编排。
2. 在 DAG 的同组任务引入真正并发执行（`join_all` / `FuturesUnordered`），并保留失败短路策略。
3. 将验收失败结果转成补救子任务并回流 DAG，形成“执行→验收→补救→再验收”闭环。
4. 统一 `tasks.db` 与 `main-task.md` 的状态主数据源，避免双写漂移。
5. 收紧 ZeroClaw 降级策略：桥接失败时显式失败或进入重试，而非占位成功。

### 设计原则

1. **高内聚** - UI 层完全独立，可单独调整
2. **模块化** - 每个模块可独立聚焦修改
3. **TDD 先行** - 测试先于代码编写

---

## 一、项目结构

```
smanclaw-desktop/
├── crates/
│   ├── smanclaw-types/              # 【类型层】共享数据结构
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── task.rs              # Task, TaskStatus, TaskResult
│   │   │   ├── history.rs           # HistoryEntry, Conversation
│   │   │   ├── project.rs           # Project, ProjectConfig
│   │   │   └── events.rs            # ProgressEvent, TaskEvent
│   │   └── Cargo.toml
│   │
│   ├── smanclaw-core/               # 【核心层】业务逻辑（无 UI 依赖）
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── task_manager.rs      # 任务编排、状态管理
│   │   │   ├── history_store.rs     # SQLite 历史存储
│   │   │   ├── project_manager.rs   # 项目发现、配置管理
│   │   │   ├── config.rs            # 应用配置
│   │   │   └── error.rs             # 错误类型
│   │   └── Cargo.toml
│   │
│   ├── smanclaw-ffi/                # 【FFI 层】ZeroClaw 桥接
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── zeroclaw_bridge.rs   # ZeroClaw Agent 封装
│   │   │   ├── task_executor.rs     # 任务执行器
│   │   │   └── progress_stream.rs   # 进度流（事件推送）
│   │   └── Cargo.toml
│   │
│   └── smanclaw-desktop/            # 【桌面层】Tauri 应用
│       ├── src-tauri/
│       │   ├── src/
│       │   │   ├── main.rs          # Tauri 入口
│       │   │   ├── commands.rs      # Tauri 命令（前端调用）
│       │   │   ├── events.rs        # 事件推送（任务进度）
│       │   │   └── setup.rs         # 初始化逻辑
│       │   ├── Cargo.toml
│       │   └── tauri.conf.json
│       │
│       ├── src/                     # 【前端层】Svelte 5
│       │   ├── lib/
│       │   │   ├── stores/          # Svelte stores
│       │   │   │   ├── tasks.ts
│       │   │   │   ├── projects.ts
│       │   │   │   └── settings.ts
│       │   │   ├── api/             # Tauri invoke 封装
│       │   │   │   └── tauri.ts
│       │   │   └── utils/
│       │   ├── components/
│       │   │   ├── ui/              # shadcn-svelte 组件
│       │   │   │   ├── button/
│       │   │   │   ├── input/
│       │   │   │   ├── card/
│       │   │   │   └── ...
│       │   │   ├── layout/
│       │   │   │   ├── Sidebar.svelte
│       │   │   │   ├── Header.svelte
│       │   │   │   └── MainLayout.svelte
│       │   │   ├── chat/
│       │   │   │   ├── ChatWindow.svelte
│       │   │   │   ├── MessageBubble.svelte
│       │   │   │   ├── InputArea.svelte
│       │   │   │   └── CodeBlock.svelte
│       │   │   ├── project/
│       │   │   │   ├── ProjectCard.svelte
│       │   │   │   ├── ProjectList.svelte
│       │   │   │   └── ProjectSettings.svelte
│       │   │   └── task/
│       │   │       ├── TaskProgress.svelte
│       │   │       ├── FileTree.svelte
│       │   │       └── TaskHistory.svelte
│       │   ├── routes/
│       │   │   ├── +layout.svelte
│       │   │   ├── +page.svelte
│       │   │   └── settings/
│       │   │       └── +page.svelte
│       │   ├── app.html
│       │   └── app.css              # Tailwind 全局样式
│       ├── static/
│       │   └── icons/
│       ├── package.json
│       ├── svelte.config.js
│       ├── tailwind.config.js
│       ├── vite.config.ts
│       └── tsconfig.json
│
├── Cargo.toml                       # Workspace 配置
└── README.md
```

---

## 二、模块职责（高内聚）

| 模块 | 职责 | 依赖 | 可替换性 |
|------|------|------|----------|
| `smanclaw-types` | 共享数据结构、事件定义 | `serde` | ❌ 核心不可替换 |
| `smanclaw-core` | 任务管理、历史存储、项目管理 | `smanclaw-types`, `rusqlite` | ❌ 核心不可替换 |
| `smanclaw-ffi` | ZeroClaw 桥接、类型转换 | `smanclaw-types`, `zeroclaw` | ✅ 可替换为 CLI 调用方式 |
| `smanclaw-desktop` (Tauri) | 桌面窗口、命令转发 | `smanclaw-core`, `smanclaw-ffi` | ✅ 可替换为其他 UI 框架 |
| Svelte 前端 | UI 渲染 | 仅依赖 Tauri API | ✅ 可替换为 React/Vue |

---

## 三、模块接口定义

### 3.1 smanclaw-types（共享类型）

```rust
// crates/smanclaw-types/src/task.rs
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: String,
    pub project_id: String,
    pub input: String,
    pub status: TaskStatus,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TaskStatus {
    Pending,
    Running,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    pub task_id: String,
    pub success: bool,
    pub output: String,
    pub error: Option<String>,
    pub files_changed: Vec<FileChange>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileChange {
    pub path: String,
    pub action: FileAction,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum FileAction {
    Created,
    Modified,
    Deleted,
}
```

```rust
// crates/smanclaw-types/src/history.rs
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub id: String,
    pub conversation_id: String,
    pub role: Role,
    pub content: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Role {
    User,
    Assistant,
    System,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Conversation {
    pub id: String,
    pub project_id: String,
    pub title: String,
    pub created_at: i64,
    pub entries: Vec<HistoryEntry>,
}
```

```rust
// crates/smanclaw-types/src/project.rs
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub id: String,
    pub name: String,
    pub path: String,
    pub created_at: i64,
    pub last_accessed: i64,
}
```

```rust
// crates/smanclaw-types/src/events.rs
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ProgressEvent {
    TaskStarted { task_id: String },
    ToolCall { tool: String, args: serde_json::Value },
    FileRead { path: String },
    FileWritten { path: String },
    CommandRun { command: String },
    Progress { message: String, percent: f32 },
    TaskCompleted { result: TaskResult },
    TaskFailed { error: String },
}
```

### 3.2 smanclaw-core（核心逻辑）

```rust
// crates/smanclaw-core/src/task_manager.rs
pub struct TaskManager {
    store: SqliteHistoryStore,
    project_manager: ProjectManager,
}

impl TaskManager {
    pub fn new(db_path: &Path) -> Result<Self>;

    /// 创建新任务
    pub fn create_task(&self, project_id: &str, input: &str) -> Result<Task>;

    /// 获取任务状态
    pub fn get_task(&self, task_id: &str) -> Result<Option<Task>>;

    /// 列出项目下的所有任务
    pub fn list_tasks(&self, project_id: &str) -> Result<Vec<Task>>;

    /// 更新任务状态
    pub fn update_task_status(&self, task_id: &str, status: TaskStatus) -> Result<()>;
}
```

```rust
// crates/smanclaw-core/src/history_store.rs
pub struct SqliteHistoryStore {
    conn: Connection,
}

impl SqliteHistoryStore {
    pub fn new(db_path: &Path) -> Result<Self>;

    /// 保存对话条目
    pub fn save_entry(&self, entry: &HistoryEntry) -> Result<()>;

    /// 加载对话历史
    pub fn load_conversation(&self, conversation_id: &str) -> Result<Vec<HistoryEntry>>;

    /// 列出项目的所有对话
    pub fn list_conversations(&self, project_id: &str) -> Result<Vec<Conversation>>;
}
```

```rust
// crates/smanclaw-core/src/project_manager.rs
pub struct ProjectManager {
    config_dir: PathBuf,
}

impl ProjectManager {
    pub fn new(config_dir: PathBuf) -> Self;

    /// 发现本地项目
    pub fn discover_projects(&self) -> Result<Vec<Project>>;

    /// 添加项目
    pub fn add_project(&self, path: &Path) -> Result<Project>;

    /// 移除项目
    pub fn remove_project(&self, project_id: &str) -> Result<()>;

    /// 获取项目配置
    pub fn get_project(&self, project_id: &str) -> Result<Option<Project>>;
}
```

### 3.3 smanclaw-ffi（ZeroClaw 桥接）

```rust
// crates/smanclaw-ffi/src/zeroclaw_bridge.rs
pub struct ZeroclawBridge {
    config: zeroclaw::Config,
}

impl ZeroclawBridge {
    /// 从项目目录创建桥接
    pub fn from_project(project_path: &Path) -> Result<Self>;

    /// 执行任务（同步）
    pub fn execute_task(&self, input: &str) -> Result<TaskResult>;

    /// 执行任务（流式，返回进度事件）
    pub fn execute_task_stream(
        &self,
        input: &str,
        event_tx: mpsc::Sender<ProgressEvent>,
    ) -> impl Future<Output = Result<TaskResult>>;
}
```

### 3.4 Tauri 命令（前端接口）

```rust
// crates/smanclaw-desktop/src-tauri/src/commands.rs
use tauri::State;

/// 获取所有项目
#[tauri::command]
pub async fn get_projects(
    project_manager: State<'_, Arc<ProjectManager>>,
) -> Result<Vec<Project>, String>;

/// 添加项目
#[tauri::command]
pub async fn add_project(
    path: String,
    project_manager: State<'_, Arc<ProjectManager>>,
) -> Result<Project, String>;

/// 执行任务
#[tauri::command]
pub async fn execute_task(
    project_id: String,
    input: String,
    task_manager: State<'_, Arc<TaskManager>>,
    bridge: State<'_, Arc<ZeroclawBridge>>,
    app: tauri::AppHandle,
) -> Result<Task, String>;

/// 获取对话历史
#[tauri::command]
pub async fn get_conversation(
    conversation_id: String,
    store: State<'_, Arc<SqliteHistoryStore>>,
) -> Result<Vec<HistoryEntry>, String>;

/// 发送消息（追加到对话）
#[tauri::command]
pub async fn send_message(
    conversation_id: String,
    content: String,
    store: State<'_, Arc<SqliteHistoryStore>>,
) -> Result<HistoryEntry, String>;
```

---

## 四、前端设计

### 4.1 UI 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Svelte | 5.x | 前端框架 |
| shadcn-svelte | latest | UI 组件库 |
| Tailwind CSS | 4.x | 样式框架 |
| TypeScript | 5.x | 类型安全 |

### 4.2 配色方案

```
Dark Theme (默认):
├── Background:     #0f0f12
├── Surface:        #1a1a1f
├── Border:         #2a2a32
├── Text Primary:   #f5f5f7
├── Text Secondary: #a1a1aa
├── Accent:         #6366f1 (Indigo)
├── Success:        #22c55e
├── Warning:        #f59e0b
└── Error:          #ef4444

Light Theme:
├── Background:     #fafafa
├── Surface:        #ffffff
├── Border:         #e5e5e5
├── Text Primary:   #18181b
├── Text Secondary: #71717a
├── Accent:         #6366f1
└── ...
```

### 4.3 布局设计

```
┌─────────────────────────────────────────────────────────────────┐
│  ≡  SmanClaw                              🔔  ⚙️  ◻ □ ✕        │  <- 标题栏
├──────────┬──────────────────────────────────────────────────────┤
│          │  ┌─────────────────────────────────────────────────┐ │
│  📁 项目 │  │  💬 历史对话                                    │ │
│          │  │  ┌───────────────────────────────────────────┐  │ │
│  ├─ projA│  │  │ User: 实现登录功能                        │  │ │
│  ├─ projB│  │  │ ─────────────────────────────────────────  │  │ │
│  └─ + 新建│  │  │ Assistant: 好的，我来分析需求...         │  │ │
│          │  │  │                                           │  │ │
│  📋 任务 │  │  │ [进度条 ████████░░ 80%]                   │  │ │
│          │  │  │                                           │  │ │
│  ├─ 运行 │  │  │ • 读取 config.rs ✓                        │  │ │
│  ├─ 完成 │  │  │ • 修改 auth.rs ⏳                         │  │ │
│  └─ 失败 │  │  │ • 运行测试 ⏸                              │  │ │
│          │  │  └───────────────────────────────────────────┘  │ │
│          │  └─────────────────────────────────────────────────┘ │
│          │  ┌─────────────────────────────────────────────────┐ │
│          │  │ 📝 输入需求...                          [发送] │ │
│          │  └─────────────────────────────────────────────────┘ │
└──────────┴──────────────────────────────────────────────────────┘
```

### 4.4 核心组件

| 组件 | 功能 | 动效 |
|------|------|------|
| `Sidebar` | 项目/任务导航 | 展开/收起 slide |
| `ChatWindow` | 对话显示区 | 消息 fade-in |
| `MessageBubble` | 单条消息 | 打字机效果 |
| `ProgressPanel` | 任务进度 | 进度条动画 |
| `FileTree` | 文件变更列表 | 展开/收起 |
| `CodeBlock` | 代码高亮 | 语法高亮 |
| `InputArea` | 输入框 | 自动高度调整 |
| `ProjectCard` | 项目卡片 | hover 缩放 |
| `Toast` | 通知提示 | slide-in/out |

### 4.5 API 层

```typescript
// src/lib/api/tauri.ts
import { invoke } from '@tauri-apps/api/tauri';
import { listen } from '@tauri-apps/api/event';
import type { Task, Project, HistoryEntry, ProgressEvent } from '$lib/types';

export const api = {
  // 项目管理
  getProjects: () => invoke<Project[]>('get_projects'),
  addProject: (path: string) => invoke<Project>('add_project', { path }),
  removeProject: (id: string) => invoke<void>('remove_project', { id }),

  // 任务管理
  executeTask: (projectId: string, input: string) =>
    invoke<Task>('execute_task', { projectId, input }),
  getTask: (taskId: string) => invoke<Task>('get_task', { taskId }),
  listTasks: (projectId: string) => invoke<Task[]>('list_tasks', { projectId }),

  // 对话管理
  getConversation: (conversationId: string) =>
    invoke<HistoryEntry[]>('get_conversation', { conversationId }),
  sendMessage: (conversationId: string, content: string) =>
    invoke<HistoryEntry>('send_message', { conversationId, content }),
};

// 进度事件监听
export function onProgressEvent(callback: (event: ProgressEvent) => void) {
  return listen<ProgressEvent>('progress-event', (e) => callback(e.payload));
}
```

---

## 五、数据流设计

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            用户界面 (Svelte)                              │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                   │
│  │ Sidebar     │    │ ChatWindow  │    │ TaskProgress│                   │
│  │ (项目列表)  │    │ (对话区)    │    │ (进度显示)  │                   │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘                   │
│         │                  │                  │                          │
│         ▼                  ▼                  ▼                          │
│  ┌─────────────────────────────────────────────────────┐                 │
│  │              Svelte Stores (状态管理)               │                 │
│  │  projects.ts  │  tasks.ts  │  conversation.ts       │                 │
│  └────────────────────────┬────────────────────────────┘                 │
└───────────────────────────┼──────────────────────────────────────────────┘
                            │ invoke() / listen()
                            ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         Tauri 命令层 (Rust)                               │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                   │
│  │get_projects │    │execute_task │    │send_message │                   │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘                   │
└─────────┼──────────────────┼──────────────────┼──────────────────────────┘
          │                  │                  │
          ▼                  ▼                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        核心业务层 (smanclaw-core)                         │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                   │
│  │ProjectManager│   │TaskManager  │    │HistoryStore │                   │
│  └─────────────┘    └──────┬──────┘    └─────────────┘                   │
└───────────────────────────┼──────────────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        FFI 桥接层 (smanclaw-ffi)                          │
│  ┌─────────────────────────────────────────────────────┐                 │
│  │              ZeroclawBridge                         │                 │
│  │  • execute_task() → Agent::turn()                   │                 │
│  │  • progress_stream → ProgressEvent                  │                 │
│  └────────────────────────┬────────────────────────────┘                 │
└───────────────────────────┼──────────────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        ZeroClaw 库 (zeroclaw)                             │
│  Agent::turn() → Provider::chat() → Tool::execute() → Memory            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 六、测试策略

| 模块 | 测试类型 | 关键场景 |
|------|----------|----------|
| `smanclaw-types` | 单元测试 | 序列化/反序列化 |
| `smanclaw-core` | 单元测试 + 集成测试 | 任务 CRUD、历史存储、项目发现 |
| `smanclaw-ffi` | 集成测试 | ZeroClaw 调用、进度事件 |
| `smanclaw-desktop` (Tauri) | E2E 测试 | 命令响应、事件推送 |
| 前端 Svelte | 组件测试 | ChatWindow、TaskProgress 渲染 |

---

## 七、任务列表

| Task ID | 模块 | 任务 | 验收标准 | 依赖 |
|---------|------|------|----------|------|
| **T1** | 项目初始化 | 创建 workspace 结构 | `cargo build` 通过 | - |
| **T2** | smanclaw-types | 定义共享类型 | 所有类型可序列化，测试通过 | T1 |
| **T3** | smanclaw-core | 实现 TaskManager | 任务 CRUD 测试通过 | T2 |
| **T4** | smanclaw-core | 实现 HistoryStore | SQLite 存取测试通过 | T2 |
| **T5** | smanclaw-core | 实现 ProjectManager | 项目发现测试通过 | T2 |
| **T6** | smanclaw-ffi | 实现 ZeroclawBridge | 能调用 Agent::turn() | T2 |
| **T7** | smanclaw-ffi | 实现进度事件流 | 事件可推送到前端 | T6 |
| **T8** | Tauri 后端 | 配置 Tauri 项目 | `cargo tauri dev` 启动 | T3-T7 |
| **T9** | Tauri 后端 | 实现所有命令 | 前端可调用所有 API | T8 |
| **T10** | 前端 | 初始化 Svelte 项目 | `npm run dev` 启动 | - |
| **T11** | 前端 | 配置 shadcn-svelte | UI 组件可用 | T10 |
| **T12** | 前端 | 实现 Sidebar | 项目列表显示 | T11 |
| **T13** | 前端 | 实现 ChatWindow | 对话显示、输入 | T11 |
| **T14** | 前端 | 实现 TaskProgress | 进度条、文件列表 | T11 |
| **T15** | 前端 | 实现主题切换 | 暗黑/亮色切换 | T11 |
| **T16** | 集成 | 前后端联调 | 完整流程可用 | T9, T12-T15 |
| **T17** | 打包 | 配置打包脚本 | 生成 Windows/macOS 安装包 | T16 |

---

## 八、开发阶段规划

### Phase 1: 基础框架 (T1-T2)
- 创建 workspace 结构
- 定义共享类型

### Phase 2: 核心逻辑 (T3-T5)
- 实现 TaskManager
- 实现 HistoryStore
- 实现 ProjectManager

### Phase 3: FFI 桥接 (T6-T7)
- 实现 ZeroclawBridge
- 实现进度事件流

### Phase 4: Tauri 后端 (T8-T9)
- 配置 Tauri 项目
- 实现所有命令

### Phase 5: 前端 UI (T10-T15)
- 初始化 Svelte 项目
- 配置 shadcn-svelte
- 实现所有组件

### Phase 6: 集成与打包 (T16-T17)
- 前后端联调
- 配置打包脚本

---

## 九、注意事项

1. **TDD 先行** - 每个模块先写测试，再写实现
2. **一个上下文窗口聚焦一个问题** - 每个 Agent 任务独立执行
3. **模块化实现** - 方便聚焦修改
4. **高内聚低耦合** - UI 层完全独立

---

## 十、核心自动化愿景（Target State）

### 10.1 产品目标

SmanClaw Desktop 的核心价值是**全自动化的编码 Agent 系统**：

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              用户界面 (Svelte)                                │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐      │
│  │ 项目列表    │   │ 对话窗口    │   │ 任务进度    │   │ 文件变更    │      │
│  │ (多项目)    │   │ (需求输入)  │   │ (实时状态)  │   │ (可视化)    │      │
│  └─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘      │
└──────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           主控 Claw (Orchestrator)                            │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │  1. 接收用户需求 → 语义理解                                              │ │
│  │  2. 拆解任务 → 生成子任务列表（依赖分析、拓扑排序）                         │ │
│  │  3. 编排执行 → 分配给子 Claw（并行/串行）                                 │ │
│  │  4. 监控进度 → 收集子 Claw 结果                                          │ │
│  │  5. 评估验收 → 判断是否满足需求                                          │ │
│  │  6. 补全修复 → 如不满足则继续迭代                                        │ │
│  │  7. 通知用户 → 完成或需要人工介入                                        │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
            ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
            │ 子 Claw A   │ │ 子 Claw B   │ │ 子 Claw C   │
            │ (开发任务)  │ │ (开发任务)  │ │ (测试任务)  │
            │             │ │             │ │             │
            │ • 读取代码  │ │ • 读取代码  │ │ • 单元测试  │
            │ • 编写代码  │ │ • 编写代码  │ │ • E2E 测试  │
            │ • 单元测试  │ │ • 单元测试  │ │ • 验收测试  │
            └─────────────┘ └─────────────┘ └─────────────┘
                    │               │               │
                    └───────────────┼───────────────┘
                                    ▼
            ┌─────────────────────────────────────────────────┐
            │                 ZeroClaw 后端                    │
            │  Agent::turn() → Provider → Tools → Memory      │
            └─────────────────────────────────────────────────┘
```

### 10.2 自动化流程（详细）

```
用户输入: "实现用户登录功能"
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 1. 主控 Claw: 需求分析                                        │
│    • 语义理解用户需求                                         │
│    • 查询项目上下文（代码结构、技术栈）                          │
│    • 生成任务拆解方案                                         │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. 主控 Claw: 任务编排                                        │
│    Task 1: 创建 User实体和Repository                         │
│    Task 2: 实现密码加密和校验逻辑                              │
│    Task 3: 实现登录 API 接口                                  │
│    Task 4: 编写单元测试                                       │
│    Task 5: E2E 验收测试                                       │
│                                                              │
│    依赖分析: Task 2 依赖 Task 1, Task 3 依赖 Task 1-2         │
│    拓扑排序: [T1] → [T2] → [T3] → [T4] → [T5]                │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. 子 Claw 执行（自动单元测试）                                │
│                                                              │
│    子 Claw 1 执行 Task 1:                                     │
│    • 读取项目结构                                             │
│    • 编写 User 实体                                           │
│    • 编写 Repository                                          │
│    • 【自动】运行单元测试 → 通过 → 返回结果                     │
│                                                              │
│    子 Claw 2 执行 Task 2:                                     │
│    • 读取现有代码                                             │
│    • 实现密码加密                                             │
│    • 【自动】运行单元测试 → 失败 → 修复 → 通过                  │
│    • 返回结果                                                 │
│                                                              │
│    ... (其他子任务)                                           │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. E2E 验收子 Claw                                            │
│    • 启动 E2E 测试子 Claw                                     │
│    • 运行集成测试                                             │
│    • 验收功能完整性                                           │
│    • 返回验收报告                                             │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. 主控 Claw: 评估与补全                                      │
│    • 分析所有子 Claw 的结果                                   │
│    • 评估是否满足原始需求                                      │
│    • 如果不满足:                                              │
│      - 生成补全任务                                           │
│      - 重新编排执行                                           │
│    • 如果满足:                                                │
│      - 通知用户完成                                           │
│      - 展示变更文件列表                                        │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 6. 用户通知                                                   │
│    • 任务完成                                                 │
│    • 变更文件列表                                             │
│    • 测试覆盖报告                                             │
│    • 如需人工介入则提示                                        │
└─────────────────────────────────────────────────────────────┘
```

### 10.3 关键能力要求

| 能力 | 说明 | 当前状态 |
|------|------|----------|
| **多项目管理** | UI 可加载/切换多个项目 | ✅ 已实现 |
| **需求输入** | UI 输入框发送需求 | ✅ 已实现 |
| **ZeroClaw 后端复用** | 调用 Agent::turn() API | ✅ 已实现 |
| **主控 Claw（任务编排）** | 拆解任务、分配子任务 | ❌ **未实现** |
| **子 Claw 并行执行** | 多个 Agent 实例并行工作 | ❌ **未实现** |
| **自动单元测试** | 子 Claw 完成后自动运行测试 | ❌ **未实现** |
| **E2E 验收 Claw** | 专门的验收测试 Agent | ❌ **未实现** |
| **需求评估与补全** | 主控 Claw 评估并迭代 | ❌ **未实现** |
| **进度实时反馈** | UI 实时展示任务进度 | ⚠️ 部分实现 |

---

## 十一、实现进度

### 已完成

| Task ID | 任务 | 状态 |
|---------|------|------|
| T1 | 创建 workspace 结构 | ✅ 完成 |
| T2 | smanclaw-types 测试 | ✅ 16 tests passed |
| T3 | smanclaw-core 测试 | ✅ 21 tests passed |
| T4 | smanclaw-ffi 测试 | ✅ 编译通过 |
| T5 | 初始化 Tauri 项目 | ✅ 完成 |
| T6 | 初始化 Svelte 前端 | ✅ 完成 |
| T7 | ZeroClaw 真实集成 | ✅ 完成 - 使用真实 Agent::turn() API |
| T8 | 前后端联调 | ✅ cargo check/workspace test 通过 |
| T9 | 前端构建 | ✅ vite build 成功，输出到 src-tauri/web |
| T10 | **API Key 配置功能** | ✅ 完成 - 支持配置 LLM/Embedding/Qdrant |
| T11 | **打包构建** | ✅ 完成 - macOS .app 和 .dmg 已生成 |

### 待完成（基础设施）

| Task ID | 任务 | 说明 |
|---------|------|------|
| T12 | 完整 E2E 测试 | 需要配置 API key 后测试完整流程 |
| T13 | 应用图标 | 当前使用默认图标，需要替换为正式图标 |

### 待实现（核心自动化能力）

| Task ID | 任务 | 优先级 | 说明 |
|---------|------|--------|------|
| **T14** | **主控 Claw（Orchestrator）** | P0 | 实现 `OrchestratorClaw` 负责需求分析、任务拆解、编排调度 |
| **T15** | **任务 DAG 构建** | P0 | 将用户需求拆解为有依赖关系的任务 DAG |
| **T16** | **子 Claw 执行器** | P0 | 实现 `SubClawExecutor` 支持并行执行多个子任务 |
| **T17** | **自动单元测试集成** | P0 | 子 Claw 完成代码后自动运行 `cargo test`/`npm test` |
| **T18** | **E2E 验收 Claw** | P1 | 专门的验收 Agent，运行集成测试验证功能完整性 |
| **T19** | **需求评估与补全循环** | P0 | 主控 Claw 评估结果，不满足则生成补全任务继续执行 |
| **T20** | **进度事件细化** | P1 | 细化 ProgressEvent 支持子任务状态、测试结果等 |
| **T21** | **UI 任务可视化** | P1 | 前端展示任务 DAG、子任务进度、测试结果 |

---

## 十二、差距分析

### 12.1 当前实现 vs 目标状态

| 层级 | 目标状态 | 当前实现 | 差距 |
|------|----------|----------|------|
| **前端** | 多项目管理、任务可视化 | ✅ 多项目、对话界面 | ❌ 缺少任务 DAG 可视化 |
| **Tauri 命令** | 执行任务、进度订阅 | ✅ execute_task、事件推送 | ⚠️ 单任务，无子任务概念 |
| **FFI 桥接** | 多 Agent 实例管理 | ⚠️ 单 Agent 调用 | ❌ 无多 Agent 编排 |
| **ZeroClaw Agent** | 复用现有能力 | ✅ Agent::turn() 可用 | ⚠️ team_orchestration 未集成 |
| **任务编排** | 主控 Claw + 子 Claw | ❌ 未实现 | ❌ 需要新建 orchestrator 模块 |
| **自动测试** | 子任务完成后自动测试 | ❌ 未实现 | ❌ 需要集成测试运行器 |

### 12.2 ZeroClaw 已有但未集成的能力

ZeroClaw 的 `src/agent/team_orchestration.rs` 已实现：
- **TeamTopology**: Single, LeadSubagent, StarTeam, MeshTeam
- **ExecutionPlan**: 任务拓扑排序、并行批次、预算估算
- **OrchestrationBundle**: 完整的编排报告

**但 SmanClaw Desktop 当前未使用这些能力！**

当前 `ZeroclawBridge` 只调用了 `Agent::turn()`，是单 Agent 模式。

### 12.3 需要新增的核心模块

```
smanclaw-desktop/crates/
├── smanclaw-core/
│   ├── orchestrator.rs      # 【新增】主控 Claw 逻辑
│   ├── task_dag.rs          # 【新增】任务 DAG 构建
│   ├── sub_claw_executor.rs # 【新增】子 Claw 执行器
│   └── test_runner.rs       # 【新增】自动测试运行器
│
└── smanclaw-ffi/
    ├── multi_agent_bridge.rs # 【新增】多 Agent 桥接
    └── orchestration_bridge.rs # 【新增】编排能力桥接
```

---

## 十三、构建产物

### macOS

| 文件 | 路径 | 大小 |
|------|------|------|
| **App Bundle** | `target/release/bundle/macos/SmanClaw Desktop.app` | ~42MB |
| **DMG 安装包** | `target/release/bundle/SmanClaw-Desktop-0.1.0-aarch64.dmg` | ~17MB |

### 构建命令

```bash
cd smanclaw-desktop/crates/smanclaw-desktop
npm install
npm run tauri:build
```

### 运行方式

**方式 1：直接运行 App**
```bash
open "target/release/bundle/macos/SmanClaw Desktop.app"
```

**方式 2：安装 DMG**
```bash
# 双击 DMG 文件安装，或：
hdiutil attach "target/release/bundle/SmanClaw-Desktop-0.1.0-aarch64.dmg"
cp -R "/Volumes/SmanClaw Desktop/SmanClaw Desktop.app" "/Applications/"
```

**方式 3：开发模式**
```bash
npm run tauri:dev
```

---

## 十四、核心设计：越用越聪明的项目经验体系

### 14.1 设计理念

**弱者道之用**：第一次用户发起请求时，系统没有任何经验。此时主 Claw 先自己探索项目，生成基础经验，然后才能正确拆分需求和定义验收标准。

**核心理念**：
1. **项目经验是前置条件** - 没有项目知识就无法正确拆分任务
2. **经验需要持续积累** - 子 Claw 完成任务后要沉淀经验
3. **用户经验要吸收** - 对话中用户告诉我们的经验要保存
4. **Skill 是经验的载体** - 经验以 Skill 形式存储，可被调用和更新

### 14.2 主 Claw（大脑） vs 子 Claw（手脚）

**角色分工明确**：

| 角色 | 定位 | 能力 | 身份文件 |
|------|------|------|---------|
| **主 Claw** | 大脑 (Orchestrator) | 思考、规划、编排、验收 | 读取所有身份文件 |
| **子 Claw** | 手脚 (Executor) | 执行具体任务、更新 Skill | 只接收 task.md |

**ZeroClaw 身份文件体系**（ZeroClaw 已实现，参考 `src/agent/prompt.rs`）：

| 文件 | 用途 | 主 Claw | 子 Claw |
|------|------|---------|---------|
| `AGENTS.md` | Agent 基础定义、项目结构 | ✅ 读取 | ❌ 不读取 |
| `SOUL.md` | 核心"灵魂" - 性格、价值观、行为准则 | ✅ 读取 | ❌ 不读取 |
| `TOOLS.md` | 工具使用指南 | ✅ 读取 | ❌ 不读取 |
| `IDENTITY.md` | 身份定义 | ✅ 读取 | ❌ 不读取 |
| `USER.md` | 用户偏好、习惯 | ✅ 读取 | ❌ 不读取 |
| `HEARTBEAT.md` | 心跳/周期性行为 | ✅ 读取 | ❌ 不读取 |
| `BOOTSTRAP.md` | 启动引导 | ✅ 读取 | ❌ 不读取 |
| `MEMORY.md` | 记忆/经验 | ✅ 读写 | ❌ 不读取 |
| `.skills/` | 技能库 | ✅ 读写 | ✅ 可更新 |

**子 Claw 的输入**：
- 只接收 `task.md` 文件
- task.md 包含：任务描述、上下文、验收标准、执行清单
- 子 Claw 完成后更新 task.md 的执行清单状态 `[ ]` → `[x]`
- 子 Claw 可以更新 `.skills/` 中的技能

### 14.3 项目经验体系架构

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                           项目经验体系 (Project Knowledge System)              │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                      经验存储层 (Knowledge Store)                    │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │    │
│  │  │ AGENTS.md    │  │ .skills/     │  │ user_wisdom/ │              │    │
│  │  │ (项目结构)   │  │ (技能库)     │  │ (用户经验)   │              │    │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    ▲                                         │
│                                    │ 读/写                                    │
│                                    │                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                      主控 Claw (Main Orchestrator)                  │    │
│  │                                                                      │    │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             │    │
│  │  │ 项目认知    │───▶│ 需求分析    │───▶│ 任务编排    │             │    │
│  │  │ (理解项目)  │    │ (拆解需求)  │    │ (分配任务)  │             │    │
│  │  └─────────────┘    └─────────────┘    └─────────────┘             │    │
│  │         │                  │                  │                     │    │
│  │         │ 首次探索         │ 查询经验         │ 派发任务            │    │
│  │         ▼                  ▼                  ▼                     │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    │                                         │
│                    ┌───────────────┼───────────────┐                        │
│                    ▼               ▼               ▼                        │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                      子 Claw 执行层 (Sub-Claw Executors)            │    │
│  │                                                                      │    │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             │    │
│  │  │ 子 Claw A   │    │ 子 Claw B   │    │ 子 Claw C   │             │    │
│  │  │ 执行 task.md│    │ 执行 task.md│    │ 执行 task.md│             │    │
│  │  │             │    │             │    │             │             │    │
│  │  │ ┌─────────┐ │    │ ┌─────────┐ │    │ ┌─────────┐ │             │    │
│  │  │ │执行任务 │ │    │ │执行任务 │ │    │ │执行任务 │ │             │    │
│  │  │ │单元测试 │ │    │ │单元测试 │ │    │ │单元测试 │ │             │    │
│  │  │ │更新task │ │    │ │更新task │ │    │ │更新task │ │             │    │
│  │  │ │.md状态  │ │    │ │.md状态  │ │    │ │.md状态  │ │             │    │
│  │  │ │沉淀经验 │ │    │ │沉淀经验 │ │    │ │沉淀经验 │ │             │    │
│  │  │ │更新Skill│ │    │ │更新Skill│ │    │ │更新Skill│ │             │    │
│  │  │ └─────────┘ │    │ └─────────┘ │    │ └─────────┘ │             │    │
│  │  └─────────────┘    └─────────────┘    └─────────────┘             │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    │                                         │
│                                    │ 完成通知                                │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                      验收与经验整合层                                │    │
│  │                                                                      │    │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             │    │
│  │  │ 主 Claw     │    │ E2E 验收    │    │ 经验整合    │             │    │
│  │  │ 轮询检查    │───▶│ 子 Claw     │───▶│ 更新项目    │             │    │
│  │  │ task.md     │    │ 验收测试    │    │ 经验库      │             │    │
│  │  └─────────────┘    └─────────────┘    └─────────────┘             │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 14.4 主控 Claw 工作流程

```
┌─────────────────────────────────────────────────────────────┐
│                  主控 Claw 完整工作流                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  用户输入: "实现用户登录功能"                                │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 0: 检查项目经验                                 │   │
│  │                                                      │   │
│  │   if (AGENTS.md 存在 && .skills/ 存在) {             │   │
│  │     → 加载现有经验，进入阶段 1                       │   │
│  │   } else {                                           │   │
│  │     → 触发项目探索，生成基础经验 (参考 opencode /init)│   │
│  │     → 存储到 AGENTS.md 和 .skills/                   │   │
│  │     → 然后进入阶段 1                                 │   │
│  │   }                                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 1: 需求分析 (基于项目经验)                      │   │
│  │                                                      │   │
│  │   • 读取 AGENTS.md 了解项目结构                      │   │
│  │   • 读取 .skills/ 了解已有能力                       │   │
│  │   • 分析需求影响范围                                 │   │
│  │   • 定义验收标准 (测试用例、验收条件)                │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 2: 任务拆解与编排                               │   │
│  │                                                      │   │
│  │   • 拆分子任务 (基于对项目的理解)                    │   │
│  │   • 分析依赖关系                                     │   │
│  │   • 构建 DAG                                         │   │
│  │   • 为每个子任务生成 task.md 文件                    │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 3: 派发子 Claw 执行                             │   │
│  │                                                      │   │
│  │   并行派发无依赖的子任务:                            │   │
│  │                                                      │   │
│  │   子 Claw 1 ← task_1.md (内容见下方格式)             │   │
│  │   子 Claw 2 ← task_2.md                              │   │
│  │   子 Claw 3 ← task_3.md                              │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 4: 轮询检查子任务状态                           │   │
│  │                                                      │   │
│  │   while (存在未完成的子任务) {                       │   │
│  │     读取各 task_x.md 文件                            │   │
│  │     检查是否全部 [x] 完成                            │   │
│  │     如果完成 → 收集结果，触发经验沉淀                │   │
│  │     如果失败 → 分析原因，生成补救任务                │   │
│  │     等待 N 秒后再次检查                              │   │
│  │   }                                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 5: E2E 验收                                     │   │
│  │                                                      │   │
│  │   • 启动 E2E 验收子 Claw                             │   │
│  │   • 运行集成测试                                     │   │
│  │   • 对照阶段 1 定义的验收标准                        │   │
│  │   • 生成验收报告                                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 6: 评估与补全                                   │   │
│  │                                                      │   │
│  │   if (验收通过 && 满足用户原始需求) {                │   │
│  │     → 整合所有子 Claw 的经验到项目经验库             │   │
│  │     → 通知用户完成                                   │   │
│  │     → 展示变更文件列表和测试报告                     │   │
│  │   } else {                                           │   │
│  │     → 分析差距                                       │   │
│  │     → 生成补全任务                                   │   │
│  │     → 回到阶段 3 继续执行                            │   │
│  │   }                                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 14.5 子任务文件格式 (task.md)

每个子 Claw 收到的 task.md 格式：

```markdown
# Task: 实现用户实体和 Repository

## 任务描述
创建 User 实体类和对应的 Repository 接口，用于用户登录功能。

## 上下文
- 项目类型: Rust + Tauri
- 技术栈: Rust, SQLite
- 相关模块: src/models/, src/repositories/

## 验收标准
- [ ] User 实体包含 id, username, password_hash, created_at 字段
- [ ] UserRepository 提供 find_by_username, save 方法
- [ ] 单元测试覆盖核心逻辑
- [ ] 代码通过 cargo clippy 检查

## 执行清单 (Sub-Claw 更新此区域)
- [ ] 阅读 src/models/ 目录下现有实体结构
- [ ] 创建 src/models/user.rs
- [ ] 创建 src/repositories/user_repository.rs
- [ ] 编写单元测试
- [ ] 运行 cargo test 验证
- [ ] 运行 cargo clippy 检查代码质量

## 经验沉淀区域 (Sub-Claw 完成后填写)
### 新增经验
- (子 Claw 填写本次任务学到的经验)

### 更新的 Skill
- (子 Claw 填写需要更新的 Skill)

### 遇到的问题和解决方案
- (子 Claw 填写遇到的问题及解决方式)
```

### 14.6 Skill 体系设计

#### 14.6.1 Skill 存储结构

```
project-root/
├── .skills/
│   ├── index.json              # Skill 索引
│   ├── coding/
│   │   ├── rust-style.md       # Rust 编码风格
│   │   ├── error-handling.md   # 错误处理模式
│   │   └── testing.md          # 测试规范
│   ├── architecture/
│   │   ├── module-structure.md # 模块结构
│   │   └── dependencies.md     # 依赖管理
│   └── domain/
│       ├── user-module.md      # 用户模块领域知识
│       └── auth-flow.md        # 认证流程
└── AGENTS.md                   # 项目总览 (参考 opencode)
```

#### 14.6.2 Skill 索引格式

```json
{
  "version": "1.0",
  "project_name": "smanclaw",
  "last_updated": "2024-03-04T10:00:00Z",
  "skills": [
    {
      "id": "rust-style",
      "path": "coding/rust-style.md",
      "tags": ["coding", "rust", "style"],
      "learned_from": "initial-exploration",
      "updated_at": "2024-03-04T10:00:00Z"
    },
    {
      "id": "user-auth",
      "path": "domain/auth-flow.md",
      "tags": ["domain", "auth", "user"],
      "learned_from": "task-123",
      "updated_at": "2024-03-04T12:30:00Z"
    }
  ]
}
```

#### 14.6.3 Skill 内容格式

```markdown
# Skill: Rust 编码风格

## 来源
- 初始项目探索 (2024-03-04)
- 任务 #45 补充 (2024-03-05)

## 规范

### 命名约定
- 模块/文件: snake_case
- 类型/Trait: PascalCase
- 函数/变量: snake_case
- 常量: SCREAMING_SNAKE_CASE

### 错误处理
- 使用 `anyhow::Result` 作为返回类型
- 使用 `thiserror` 定义自定义错误
- 不使用 `unwrap()`，使用 `?` 或 `context()`

### 测试
- 单元测试放在同一文件的 `#[cfg(test)] mod tests`
- 集成测试放在 `tests/` 目录

## 示例代码
\`\`\`rust
// 正确示例
pub fn find_user(id: u64) -> Result<Option<User>> {
    let conn = self.pool.get().context("Failed to get connection")?;
    // ...
}
\`\`\`

## 相关任务
- task-001: 初始化项目结构
- task-045: 重构错误处理
```

### 14.7 项目探索 (参考 OpenCode /init)

首次使用项目时，主 Claw 需要探索项目生成基础经验：

```
┌─────────────────────────────────────────────────────────────┐
│                   项目探索流程 (参考 opencode /init)         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  触发条件: AGENTS.md 不存在 或 用户显式请求                  │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 步骤 1: 扫描项目结构                                 │   │
│  │                                                      │   │
│  │   • 列出目录树 (排除 node_modules, target 等)        │   │
│  │   • 识别项目类型 (Rust, Node.js, Python, etc.)       │   │
│  │   • 发现构建配置 (Cargo.toml, package.json, etc.)    │   │
│  │   • 识别框架和依赖                                   │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 步骤 2: 分析代码结构                                 │   │
│  │                                                      │   │
│  │   • 识别模块边界和职责                               │   │
│  │   • 分析目录结构和分层                               │   │
│  │   • 发现配置文件和环境变量                           │   │
│  │   • 查找现有规则文件 (.cursorrules, CLAUDE.md 等)    │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 步骤 3: 提取构建/测试命令                            │   │
│  │                                                      │   │
│  │   • 构建: cargo build, npm run build                │   │
│  │   • 测试: cargo test, npm test                      │   │
│  │   • Lint: cargo clippy, npm run lint                │   │
│  │   • 单测: cargo test <test_name>                    │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 步骤 4: 生成 AGENTS.md                               │   │
│  │                                                      │   │
│  │   内容包括:                                          │   │
│  │   • 项目结构描述                                     │   │
│  │   • 构建/测试/lint 命令                              │   │
│  │   • 代码风格指南                                     │   │
│  │   • 模块职责说明                                     │   │
│  │   • 约束 ~150 行                                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 步骤 5: 初始化 Skill 库                              │   │
│  │                                                      │   │
│  │   创建基础 Skills:                                   │   │
│  │   • .skills/coding/{lang}-style.md                   │   │
│  │   • .skills/architecture/module-structure.md         │   │
│  │   • .skills/index.json                               │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 14.8 用户经验吸收

用户在对话中告诉我们的经验需要被吸收：

```
┌─────────────────────────────────────────────────────────────┐
│                     用户经验吸收流程                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  用户输入: "我们的项目要求所有 API 都要加审计日志"           │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 1. 识别经验陈述                                      │   │
│  │    (通过语义分析，识别用户在传授经验)                 │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 2. 提取经验内容                                      │   │
│  │                                                      │   │
│  │    经验: 所有 API 需要审计日志                       │   │
│  │    分类: coding/api-guidelines                       │   │
│  │    标签: [api, audit, logging]                       │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 3. 存储/更新 Skill                                   │   │
│  │                                                      │   │
│  │    创建/更新: .skills/coding/api-guidelines.md       │   │
│  │    记录来源: user-input-2024-03-04                   │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 4. 确认吸收                                          │   │
│  │                                                      │   │
│  │    响应: "好的，我记住了：所有 API 都要加审计日志。   │   │
│  │           后续开发我会自动遵守这个规范。"             │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 14.9 子 Claw 经验沉淀流程

```
┌─────────────────────────────────────────────────────────────┐
│                  子 Claw 经验沉淀流程                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  子 Claw 完成任务后:                                         │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 1. 总结本次任务经验                                  │   │
│  │                                                      │   │
│  │   • 完成了什么                                       │   │
│  │   • 学到了什么新知识                                 │   │
│  │   • 遇到了什么问题，如何解决                         │   │
│  │   • 有什么可以复用的模式                             │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 2. 判断是否需要更新 Skill                            │   │
│  │                                                      │   │
│  │   if (新知识有复用价值) {                            │   │
│  │     → 创建/更新对应 Skill                            │   │
│  │   }                                                  │   │
│  │   if (发现现有 Skill 需要补充) {                     │   │
│  │     → 更新现有 Skill                                 │   │
│  │   }                                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 3. 更新 Skill 文件                                   │   │
│  │                                                      │   │
│  │   • 追加新内容到相关 Skill                           │   │
│  │   • 更新 index.json                                  │   │
│  │   • 记录来源任务 ID                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 4. 返回完成状态给主 Claw                             │   │
│  │                                                      │   │
│  │   • 任务结果                                         │   │
│  │   • 沉淀的经验列表                                   │   │
│  │   • 更新的 Skill 列表                                │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 14.10 经验体系技术实现要点

| 模块 | 实现要点 |
|------|---------|
| **项目探索** | 参考 opencode /init，扫描目录、分析配置、生成 AGENTS.md |
| **Skill 存储** | 文件系统 + JSON 索引，支持 CRUD 操作 |
| **Skill 更新** | 子 Claw 通过工具调用更新 Skill 文件 |
| **用户经验吸收** | 语义识别 + 自动归类 + 确认机制 |
| **主 Claw 轮询** | 定期读取 task.md 检查 [x] 状态 |
| **验收评估** | 对照阶段 1 定义的验收标准执行 |

---

## 十五、待实现任务清单（更新）

### 基础设施（已完成）

| Task ID | 任务 | 状态 |
|---------|------|------|
| T1-T11 | 项目框架、打包构建 | ✅ 已完成 |

### 核心编排能力（已完成）

| Task ID | 任务 | 状态 |
|---------|------|------|
| T14 | 主控 Claw (Orchestrator) 核心逻辑 | ✅ 已完成 |
| T15 | 任务 DAG 构建 | ✅ 已完成 |
| T16 | 子 Claw 执行器 | ✅ 已完成 |
| T20 | 进度事件细化 | ✅ 已完成 |
| T21 | UI 任务可视化 | ✅ 已完成 |

### 经验体系（已完成）

| Task ID | 任务 | 状态 | 说明 |
|---------|------|------|------|
| **T22** | 项目探索模块 | 已完成 | project_explorer.rs 已实现 |
| **T23** | AGENTS.md 生成器 | 已完成 | 集成在 project_explorer.rs |
| **T24** | Skill 存储系统 | 已完成 | skill_store.rs 已实现 |
| **T25** | 子 Claw 经验沉淀 | 已完成 | experience_sink.rs 已实现 |
| **T26** | 用户经验吸收 | 已完成 | user_experience.rs 已实现 |
| **T27** | Skill 更新工具 | 已完成 | 通过 experience_sink.rs 实现 |
| **T28** | 主 Claw 轮询机制 | 已完成 | task_poller.rs 已实现 |
| **T29** | task.md 生成器 | 已完成 | task_generator.rs 已实现 |
| **T30** | 验收评估模块 | 已完成 | acceptance_evaluator.rs 结构完整 |

### 核心运行时（待实现）

| Task ID | 任务 | 优先级 | 说明 |
|---------|------|--------|------|
| **T31** | E2E 验收 Claw | P1 | 专门的验收测试 Agent |
| **T35** | llm_client.rs | P0 | 统一的 LLM 调用层 |
| **T36** | orchestrator.rs 重写 | P0 | 真正的编排逻辑 |
| **T37** | sub_claw_executor.rs 重写 | P0 | 真正的执行逻辑 |
| **T38** | runtime.rs | P1 | 事件驱动的主循环 |
| **T39** | 验收逻辑增强 | P1 | acceptance_evaluator 真实验证 |
| **T40** | 身份定义文件 | P2 | SOUL/USER/AGENTS.md |

---

## 十六、快速启动指南

### 环境要求

- Rust 1.70+
- Node.js 18+
- npm 或 pnpm

### 编译和运行

```bash
# 进入项目目录
cd smanclaw-desktop

# 运行测试
cargo test --workspace

# 启动开发模式
cd crates/smanclaw-desktop
npm install
npm run tauri dev
```

### 打包发布

```bash
# 构建
cargo tauri build

# 输出位置
# macOS: src-tauri/target/release/bundle/dmg/
# Windows: src-tauri/target/release/bundle/msi/
```

---

## 十七、配置 API Key

1. 启动应用后，点击侧边栏的 **Settings** 按钮
2. 在 **LLM Configuration** 区填写：
   - **API URL**: 例如 `https://api.openai.com/v1` 或你的私有部署地址
   - **API Key**: 你的 API Key
   - **Default Model**: 例如 `gpt-4o`
3. 点击 **Test** 按钮验证连接
4. 可选：配置 **Embedding** 和 **Qdrant**（用于向量召回）
5. 点击 **Save Settings** 保存

配置将持久化存储，重启应用后仍保留。

---

## 十八、两层循环机制评估

### 18.1 大循环状态

| 环节 | 模块 | 实现状态 | 问题 |
|------|------|---------|------|
| 主Claw编排 | orchestrator.rs | 框架占位 | 无真正的编排逻辑 |
| 子Claw执行 | sub_claw_executor.rs | 框架占位 | 无实际调用LLM的逻辑 |
| 主Claw评估 | acceptance_evaluator.rs | 结构完整 | evaluate_criterion() 返回 Pending |

### 18.2 小循环状态

task_poller.rs 设计合理，可以工作。

### 18.3 核心缺失

- 事件驱动的主循环
- LLM 调用层
- 状态机（MainTask 状态流转）

---

## 十九、核心差距分析与补齐计划

### 19.1 当前实现 vs 目标状态

| 能力 | 模块 | 状态 | 缺失 |
|------|------|------|------|
| 项目探索 | project_explorer.rs | 完整 | - |
| 技能存储 | skill_store.rs | 完整 | - |
| 任务生成 | task_generator.rs | 完整 | - |
| 主任务管理 | main_task.rs | 完整 | - |
| 任务轮询 | task_poller.rs | 完整 | - |
| 经验提取 | experience_sink.rs | 完整 | - |
| 用户偏好 | user_experience.rs | 完整 | - |
| 验收评估 | acceptance_evaluator.rs | 部分实现 | 无真实验证逻辑 |
| **编排器** | orchestrator.rs | 未实现 | 核心编排逻辑 |
| **LLM调用** | - | 未实现 | 调用模型的能力 |
| **子Claw执行** | sub_claw_executor.rs | 未实现 | 实际执行逻辑 |

### 19.2 补齐计划

| 优先级 | 模块 | 说明 |
|--------|------|------|
| P0 | llm_client.rs | 统一的LLM调用层 |
| P0 | orchestrator.rs 重写 | 真正的编排逻辑 |
| P0 | sub_claw_executor.rs 重写 | 真正的执行逻辑 |
| P1 | runtime.rs | 事件驱动的主循环 |
| P1 | 验收增强 | 实际的验证逻辑 |
| P2 | SOUL/USER/AGENTS.md | 身份定义文件 |

---

## 二十、运行时层设计

### 20.1 架构图

```
+-------------------------------------------------------------+
|                        Runtime 层                            |
|  +-----------------------------------------------------+    |
|  |                    Runtime                          |    |
|  |  * event_loop() - 主事件循环                         |    |
|  |  * state_machine() - 状态机                          |    |
|  |  * user_feedback() - 用户反馈                        |    |
|  +-----------------------------------------------------+    |
+-------------------------------------------------------------+
                            |
              +-------------+-------------+
              v             v             v
+-----------------+ +-------------+ +-----------------+
|   LLM Client    | | Orchestrator | | SubClawExecutor |
|  * call_llm()   | |  * 编排逻辑  | |  * 执行逻辑     |
|  * stream()     | |  * 状态机    | |  * checkbox循环 |
+-----------------+ +-------------+ +-----------------+
```

### 20.2 LLM Client 接口

```rust
pub trait LLMClient {
    async fn complete(&self, request: CompletionRequest) -> Result<CompletionResponse>;
    async fn stream(&self, request: CompletionRequest) -> Result<impl Stream<Item = String>>;
}
```

### 20.3 Orchestrator 状态机

```
Idle -> Analyzing -> Splitting -> Dispatching -> Polling -> Evaluating -> Completed
                                    ^              |
                                    +--------------+ (不通过则重新派发)
```

### 20.4 SubClaw 执行循环

```rust
loop {
    let task = read_task_md(task_path)?;
    let next_step = find_first_unchecked(&task)?;

    match next_step {
        Some(step) => {
            let result = llm_client.execute(step).await?;
            if result.success {
                mark_checked(task_path, step)?;
            }
        }
        None => break, // 全部完成
    }
}
```

### 20.5 验收策略

| 验收方式 | 实现难度 | 可靠性 | 适用场景 |
|---------|---------|--------|---------|
| LLM判断 | 中 | 中 | 功能性描述 |
| 测试通过 | 低 | 高 | 有测试用例 |
| 命令执行 | 低 | 高 | 可命令验证 |
| 文件检查 | 低 | 高 | 文件存在性 |
| 内容匹配 | 低 | 高 | 代码/配置内容 |
