# SmanClaw Desktop App 实现蓝图

## 概述

基于 ZeroClaw 开发跨平台桌面应用（Windows + macOS），支持可视化管理项目和执行编码任务。

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

## 十、实现进度

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

### 待完成

| Task ID | 任务 | 说明 |
|---------|------|------|
| T12 | 完整 E2E 测试 | 需要配置 API key 后测试完整流程 |
| T13 | 应用图标 | 当前使用默认图标，需要替换为正式图标 |

---

## 十一、构建产物

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

## 十二、配置 API Key

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

## 十三、注意事项

1. **API Key 配置**: ✅ 现已支持在 UI 中配置
2. **图标**: 当前使用默认图标，需要替换为正式图标
3. **平台测试**: macOS 已测试，Windows 需要在 Windows 环境下验证
4. **代码签名**: 当前未签名，如需分发给其他用户需要进行代码签名
5. 点击 **Save Settings** 保存

配置将持久化存储，重启应用后仍保留。

### 注意事项

1. **API Key 配置**: ✅ 现已支持在 UI 中配置
2. **图标**: 当前使用占位图标，需要替换为正式图标
3. **平台测试**: macOS 已测试，Windows 需要在 Windows 环境下验证

---

## 十一、快速启动指南

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
