# SmanClaw Desktop App 实现蓝图

## 概述

基于 ZeroClaw 开发跨平台桌面应用（Windows + macOS），支持可视化管理项目和执行编码任务。

## 0. 当前实现对照（2026-03-09）

### 0.1 Desktop → 主/子 Claw 实际调用链路

1. 前端已接入自动路由决策链路：  
   - 路由判定入口：`conversationApi.decideRoute` → Tauri `decide_message_route`（LLM + fallback）  
   - direct 执行：`conversationApi.sendMessage` → Tauri `send_message` → `chat_execution::send_message`  
   - orchestrated 执行：`orchestrationApi.executeTask` → Tauri `execute_orchestrated_task`
2. `execute_orchestrated_task` 在 Tauri 层完成子任务拆解（语义拆解优先，规则兜底）并调用 `Orchestrator::build_dag`
3. Tauri 运行时按并行组执行 DAG：为每个子任务生成 `.sman/tasks/task-<main-task>-<seq>.md`，并用 `SubClawExecutor::run()` 执行 checklist
4. 执行过程会同步更新 `MainTaskManager` 状态（Planning → Executing → Verifying → Completed/Failed）并落库 `TaskManager`
5. 编排进度通过 `subtask-started` / `subtask-completed` / `orchestration-progress` / `task-dag` / `test-result` 推送到前端
6. 聊天执行过程通过 `progress` 事件推送 `tool_call`/`file_read`/`file_written`/`command_run`/`progress`，并包含 5 分钟心跳播报
7. 验收阶段由 `AcceptanceEvaluator` 执行；失败会自动生成补救子任务并最多进行 2 轮补救，再写回最终状态
8. 编排拆解和路由提示词已接入项目知识注入：自动读取 `.sman/skills`、`.sman/paths`、`CLAUDE.md`、`.claude/skills` 与 `.sman/AGENT.md` 参与决策
9. 首次扫描项目时会自动生成 `.sman/AGENT.md`（若不存在），后续执行复用该缓存，避免重复全量分析
10. 编排任务已绑定会话 ID：提交 orchestrated 任务时写入用户消息，任务收敛后写入“任务完成/任务失败”摘要，保证会话连续性
11. 聊天窗口已按项目维度维护消息缓存与切换回填，修复“切换项目后历史记录丢失”问题
12. 运行目录已迁移为 `.sman`，历史会话库对 `.smanclaw/history.db` 保留兼容迁移

### 0.2 蓝图完成度评估（按主子 Claw 自动化目标）

| 能力项 | 蓝图目标 | 当前状态 | 完成度 |
|---|---|---|---|
| 主控状态机 | Analyzing→Splitting→Dispatching→Polling→Evaluating 闭环 | Desktop 端已形成 Planning/Executing/Verifying 与补救闭环，并新增自动路由判定；但仍未统一到单一后端状态机入口 | ⚠️ 部分实现 |
| 任务拆解 | 基于语义理解拆分子任务 | 已接入“语义优先 + 规则兜底”拆解，并进行 DAG 可执行性校验 | ✅ 已实现 |
| DAG 管理 | 依赖分析与拓扑排序 | `TaskDag` 已实现并用于 orchestrated 命令 | ✅ 已实现 |
| 子 Claw 执行 | 按 task.md checklist 自动执行 | `SubClawExecutor` 已接入 orchestrated 命令并执行 checklist | ✅ 已实现 |
| 并行执行 | 无依赖任务并行执行 | orchestrated 主流程已在 parallel group 内并发执行子任务（基于 JoinSet 聚合结果） | ✅ 已实现 |
| 验收评估 | 按验收标准自动评估（测试/E2E/命令） | 已接入 `AcceptanceEvaluator`，并形成“验收失败→补救子任务→再验收”闭环；校验维度仍偏基础规则 | ⚠️ 部分实现 |
| main-task.md 协同 | 主 Claw 输出 main-task.md 并追踪子任务 | orchestrated 主流程已接入 `MainTaskManager`（创建、子任务状态更新、完成回写） | ✅ 已实现 |
| 经验沉淀 | 子任务经验回流并更新技能库 | orchestrated 主流程已接入自动沉淀链路，支持 skills / paths / memory 生成与回流 | ✅ 已实现 |
| 路由决策 | 自动判断 direct / orchestrated | 已接入 LLM 路由提示词与 fallback 规则，前端按路由结果切换执行链路 | ✅ 已实现 |
| 项目知识注入 | skills/paths 参与执行上下文 | 已接入 `.sman/skills`、`.sman/paths`、`CLAUDE.md`、`.claude/skills`、`.sman/AGENT.md` 检索与提示词注入 | ✅ 已实现 |
| ZeroClaw 驱动子任务 | 子 Claw 通过真实 LLM 执行步骤 | orchestrated 主流程已接入 `ZeroclawStepExecutor`；桥接初始化失败时对应任务会失败并上报 | ⚠️ 部分实现 |
| 进度反馈 | 长任务过程可见且避免“无响应等待” | 聊天链路已展示工具调用/文件/命令/心跳，并在 `task_completed` 时优先收敛最终输出 | ✅ 已实现 |

### 0.3 已确认缺口（与“核心自动化愿景”对照）

1. **后端执行入口仍分叉**：虽然前端已自动路由，但 direct 与 orchestrated 仍是两条后端执行路径，尚未收敛为单一状态机入口。
2. **并发控制待增强**：组内并行已落地，但并发上限、失败短路和资源隔离策略仍可细化。
3. **拆解泛化能力待提升**：复杂需求下语义拆解稳定性仍需增强。
4. **验收深度待增强**：当前以内容匹配为主，端到端与场景化验证覆盖不足。
5. **状态单一事实源未统一**：`tasks.db` 与 `main-task.md` 均在更新，仍需明确权威来源与一致性策略。
6. **过程可视化待结构化**：已具备事件流，但“阶段说明 + 证据汇总”展示仍可继续优化。
7. **执行入口统一仍待完成**：虽已补齐会话持久化与编排状态收敛，但 direct/orchestrated 后端入口尚未统一。

### 0.4 TODO 清单（按优先级，含完成定义）

#### P0（本周应完成）

- [ ] **统一后端入口状态机**：将 direct 与 orchestrated 收敛到统一入口（建议单一 `handle_request` 语义入口），避免双路径漂移。  
  **完成定义**：同一入口内完成路由、执行、状态更新和失败收敛；旧入口仅保留兼容转发层。
- [ ] **定义状态主数据源**：明确 `tasks.db` 与 `main-task.md` 的权威关系与同步方向。  
  **完成定义**：写入策略文档化并落地到代码，出现冲突时有确定性仲裁规则与回放策略。
- [ ] **验收增强到命令级与场景级**：将当前“文本匹配验收”升级为“命令执行 + 关键场景回归”。  
  **完成定义**：至少覆盖成功、失败、超时、补救后再验收 4 类场景，并能输出结构化证据。

#### P1（下一迭代）

- [ ] **并行执行策略加固**：补充并发上限、失败短路、重试与资源隔离策略。  
  **完成定义**：可配置并发上限，失败策略可选（立即短路/继续执行），并有回归测试覆盖。
- [ ] **前端过程可视化结构化**：补齐主 Claw 分阶段时间线与证据面板。  
  **完成定义**：展示分析/拆解/分发/执行/验收阶段，且每阶段有可追溯证据摘要。
- [ ] **补救轮次可解释化**：显示每轮失败原因、修正点和回归结果。  
  **完成定义**：用户可在 UI 上看到“第 N 轮为何失败、如何修复、修复后验证结果”。

#### P2（优化项）

- [ ] **路由质量观测**：统计 `decide_message_route` 命中率与误判样本。  
  **完成定义**：形成可追踪指标（direct/orchestrated 分布、回退率、人工纠偏率）。
- [ ] **长任务预估反馈**：在现有心跳基础上增加“剩余步骤/阶段”预估。  
  **完成定义**：预估文案不与真实状态冲突，任务结束时自动清理。

### 0.5 建议的收敛路径（按落地优先级）

1. 统一后端执行入口，收敛到同一个主控状态机（建议汇入 `Orchestrator::handle_request` 语义入口）。
2. 细化并行执行策略，补充并发限流、失败短路与重试策略。
3. 强化验收深度，将内容校验扩展到命令执行、回归测试与关键场景验证。
4. 统一 `tasks.db` 与 `main-task.md` 的状态主数据源，避免双写漂移。
5. 加强过程可视化，输出阶段、证据与补救轨迹的结构化摘要。

### 0.6 复杂编码任务的落地原则（主 Claw 调子 Claw）

1. **主 Claw 只做编排，不做实现细节**：负责需求理解、任务拆解、依赖分析、验收与补救。
2. **子任务必须是独立上下文可完成单元**：每个子任务包含明确输入、输出、边界和验收标准，不依赖与其他子 Claw 的实时对话。
3. **允许顺序与依赖，不要求子 Claw 相互通信**：通过 DAG 关系表达先后依赖，依赖结果通过主 Claw 汇总后再下发。
4. **上下文最小化下发**：子 Claw 仅接收完成当前任务必需信息（目标、相关文件、约束、测试命令、验收标准）。
5. **确定性监控优先**：主 Claw 用事件、任务状态、测试结果、分支/文件变化做客观监控，减少无效轮询和高成本追问。
6. **失败重试必须带“原因修正”**：补救轮次不是原样重跑，而是基于失败证据重写子任务说明并缩小问题边界。

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
    /// 从项目目录和设置创建桥接
    pub fn from_project_with_settings(
        project_path: &Path,
        settings: &AppSettings,
    ) -> Result<Self>;

    /// 执行任务（流式，返回进度事件）
    pub fn execute_task_stream(
        &self,
        task_id: &str,
        input: &str,
        event_tx: mpsc::Sender<ProgressEvent>,
    ) -> impl Future<Output = Result<TaskResult>>;
}
```

### 3.4 Tauri 命令（前端接口）

```rust
// crates/smanclaw-desktop/src-tauri/src/setup.rs
use tauri::State;

/// 获取所有项目
#[tauri::command]
pub async fn get_projects(...) -> TauriResult<Vec<Project>>;

/// 添加项目
#[tauri::command]
pub async fn add_project(...) -> TauriResult<Project>;

/// 单任务执行（直接 Agent）
#[tauri::command]
pub async fn execute_task(...) -> TauriResult<Task>;

/// 编排任务执行（主/子 Claw）
#[tauri::command]
pub async fn execute_orchestrated_task(...) -> TauriResult<OrchestratedTaskResult>;

/// 编排状态查询
#[tauri::command]
pub async fn get_task_dag(...) -> TauriResult<Option<TaskDagResponse>>;

#[tauri::command]
pub async fn get_orchestration_status(...) -> TauriResult<Option<OrchestrationProgress>>;

/// 对话相关
#[tauri::command]
pub async fn list_conversations(...) -> TauriResult<Vec<Conversation>>;

#[tauri::command]
pub async fn create_conversation(...) -> TauriResult<Conversation>;

#[tauri::command]
pub async fn send_message(...) -> TauriResult<HistoryEntry>;
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
// src/lib/api/tauri.ts（节选）
export const taskApi = {
  execute: (request: ExecuteTaskRequest) =>
    safeInvoke<ExecuteTaskResponse>("execute_task", {
      project_id: request.projectId,
      input: request.prompt,
    }),
};

export const conversationApi = {
  sendMessage: (conversationId: string, content: string) =>
    safeInvoke<HistoryEntryRecord>("send_message", {
      conversation_id: conversationId,
      content,
    }),
};

export const orchestrationApi = {
  executeTask: (projectId: string, input: string) =>
    safeInvoke<OrchestratedTaskResult>("execute_orchestrated_task", {
      project_id: projectId,
      input,
    }),
  getTaskDag: (taskId: string) =>
    safeInvoke<TaskDagResponse | null>("get_task_dag", { task_id: taskId }),
  getStatus: (taskId: string) =>
    safeInvoke<OrchestrationProgress | null>("get_orchestration_status", {
      task_id: taskId,
    }),
};

// 事件监听（实际事件名）
listen<ProgressEvent>("progress", ...);
listen<OrchestrationProgressEvent>("orchestration-progress", ...);
listen<SubTaskStartedEvent>("subtask-started", ...);
listen<SubTaskCompletedEvent>("subtask-completed", ...);
listen<TestResultEvent>("test-result", ...);
listen<TaskDagEvent>("task-dag", ...);
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
│  │get_projects │ │execute_orchestrated_task│ │send_message│              │
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
│  │  • execute_task_stream() → Agent::turn()            │                 │
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
│    每个子任务均生成独立任务包:                                 │
│    • 目标定义（要完成什么）                                    │
│    • 上下文边界（可读/可改文件）                                │
│    • 依赖输入（来自前置任务的结果摘要）                          │
│    • 验收标准（测试命令/行为标准）                              │
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
| **主控 Claw（任务编排）** | 拆解任务、分配子任务 | ✅ 已实现（入口：`execute_orchestrated_task`） |
| **子 Claw 并行执行** | 多个子任务并行工作 | ✅ 已实现（DAG 并行组 + JoinSet） |
| **自动验收与补救** | 验收失败后自动补救并再验收 | ✅ 已实现（最多 2 轮补救） |
| **需求评估与补全** | 主控 Claw 评估并迭代 | ⚠️ 部分实现（已补救闭环，评估深度待加强） |
| **进度实时反馈** | UI 实时展示任务进度 | ✅ 已实现（聊天进度流 + 编排事件流） |

### 10.4 子任务独立上下文契约（新增）

每个子 Claw 接到的任务必须满足以下契约：

- **目标**：单一可验证目标，不混入多个语义目标。
- **边界**：限定可修改路径与禁止变更范围。
- **输入**：仅包含必要背景、前置结果摘要、关键约束。
- **输出**：必须产出变更摘要、测试结果、风险说明。
- **验收**：至少一个可执行验证（lint/typecheck/test/命令断言）。

该契约保证“子任务可独立完成 + 主 Claw 可组合验收”，从而实现复杂任务的稳定并行化。

---

## 十一、实现进度

### 11.1 已落地能力（截至 2026-03-08）

| 模块 | 关键能力 | 当前状态 |
|------|---------|---------|
| Tauri 命令层 | `execute_task` + `execute_orchestrated_task` 双执行模式 | ✅ 已实现 |
| 编排执行层 | 语义拆解 + DAG + 并行组执行 + 子任务事件 | ✅ 已实现 |
| 验收闭环 | `AcceptanceEvaluator` + 自动补救子任务 + 再验收 | ✅ 已实现 |
| 经验沉淀 | 用户输入经验、子任务执行经验回流 Skill | ✅ 已实现 |
| 前端可视化 | 子任务状态、并行分组、编排进度展示 | ✅ 已实现 |
| 聊天进度体验 | 工具调用/文件/命令过程展示 + 心跳播报 + 完成收敛 | ✅ 已实现 |

### 11.2 仍需增强项

| 项目 | 说明 | 优先级 |
|------|------|------|
| 拆解质量 | 复杂需求场景下语义拆解稳定性与泛化能力 | P0 |
| 验收深度 | 由内容匹配扩展到命令级与场景级验证 | P0 |
| 主链路统一 | 聊天入口与编排入口的主状态机统一 | P1 |
| 过程面板 | 结构化展示阶段、证据、补救轨迹 | P1 |

---

## 十二、差距分析

### 12.1 当前实现 vs 目标状态

| 层级 | 目标状态 | 当前实现 | 差距 |
|------|----------|----------|------|
| **前端** | 多项目管理、任务可视化 | ✅ 多项目、对话、DAG/子任务进度 | ⚠️ 缺少更细颗粒阶段面板 |
| **Tauri 命令** | 执行任务、进度订阅 | ✅ 双执行模式 + 事件流 | ⚠️ 主链路仍双入口 |
| **FFI 桥接** | 复用 ZeroClaw 能力 | ✅ `ZeroclawBridge` + `ZeroclawStepExecutor` | ⚠️ 多模型/多代理策略仍可增强 |
| **任务编排** | 主控 Claw + 子 Claw | ✅ 已实现并落地到 orchestrated 路径 | ⚠️ 统一到单入口尚未完成 |
| **自动验收** | 子任务完成后自动验证 | ✅ 已实现且支持补救重试 | ⚠️ 验收维度仍偏基础 |

### 12.2 ZeroClaw 已有但未集成的能力

ZeroClaw 的 `src/agent/team_orchestration.rs` 已实现：
- **TeamTopology**: Single, LeadSubagent, StarTeam, MeshTeam
- **ExecutionPlan**: 任务拓扑排序、并行批次、预算估算
- **OrchestrationBundle**: 完整的编排报告

SmanClaw Desktop 当前主要采用 `Agent::turn()` 与 `ZeroclawStepExecutor` 驱动执行，尚未直接集成 team orchestration 全套拓扑能力。

### 12.3 需要新增的核心模块

```
smanclaw-desktop/crates/
├── smanclaw-core/
│   ├── orchestrator.rs      # 已存在并被编排链路使用
│   ├── task_dag.rs          # 已存在并被编排链路使用
│   ├── sub_claw_executor.rs # 已存在并被编排链路使用
│   └── acceptance_evaluator.rs # 已存在并被验收链路使用
│
└── smanclaw-ffi/
    ├── zeroclaw_bridge.rs        # 已存在
    └── zeroclaw_step_executor.rs # 已存在
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
| `.sman/MEMORY.md` | 记忆/经验 | ✅ 读写 | ❌ 不读取 |
| `.sman/skills` + `.sman/paths` | 技能与流程库 | ✅ 读写 | ✅ 可更新 |

**子 Claw 的输入**：
- 只接收 `task.md` 文件
- task.md 包含：任务描述、上下文、验收标准、执行清单
- 子 Claw 完成后更新 task.md 的执行清单状态 `[ ]` → `[x]`
- 子 Claw 可以更新 `.sman/skills` 与 `.sman/paths` 中的内容

### 14.3 项目经验体系架构

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                           项目经验体系 (Project Knowledge System)              │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                      经验存储层 (Knowledge Store)                    │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │    │
│  │  │ AGENTS.md    │  │ .sman/       │  │ user_wisdom/ │              │    │
│  │  │ (项目结构)   │  │ (skills/paths)│ │ (用户经验)   │              │    │
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
│  │   if (AGENTS.md 存在 && .sman/skills 存在) {         │   │
│  │     → 加载现有经验，进入阶段 1                       │   │
│  │   } else {                                           │   │
│  │     → 触发项目探索，生成基础经验 (参考 opencode /init)│   │
│  │     → 存储到 AGENTS.md 和 .sman/skills               │   │
│  │     → 然后进入阶段 1                                 │   │
│  │   }                                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                                        │
│                    ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 阶段 1: 需求分析 (基于项目经验)                      │   │
│  │                                                      │   │
│  │   • 读取 AGENTS.md 了解项目结构                      │   │
│  │   • 读取 .sman/skills 与 .sman/paths 了解已有能力    │   │
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
├── .sman/
│   ├── skills/
│   │   ├── index.json              # 技能索引（原子写）
│   │   ├── coding/rust-style.md
│   │   └── architecture/module-structure.md
│   ├── paths/
│   │   ├── index.json              # 流程索引（原子写）
│   │   └── auto/operation-task-api-hardening-*.md
│   └── MEMORY.md                   # 对话经验汇总
└── AGENTS.md                       # 项目总览 (参考 opencode)
```

#### 14.6.2 Skill 索引格式

```json
{
  "version": "1.0",
  "project_name": "smanclaw",
  "last_updated": 1709546400,
  "skills": [
    {
      "id": "rust-style",
      "path": "coding/rust-style.md",
      "tags": ["coding", "rust", "style"],
      "learned_from": "initial-exploration",
      "updated_at": 1709546400
    },
    {
      "id": "user-auth",
      "path": "domain/auth-flow.md",
      "tags": ["domain", "auth", "user"],
      "learned_from": "task-123",
      "updated_at": 1709555400
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
│  │   • .sman/skills/coding/{lang}-style.md              │   │
│  │   • .sman/skills/architecture/module-structure.md    │   │
│  │   • .sman/skills/index.json                          │   │
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
│  │    创建/更新: .sman/skills/coding/api-guidelines.md  │   │
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

## 十五、落地进展与后续增强（更新）

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
| **T30** | 验收评估模块 | 已完成 | acceptance_evaluator.rs 已接入主流程并参与补救闭环 |

### 核心运行时（待增强）

| Task ID | 任务 | 优先级 | 说明 |
|---------|------|--------|------|
| **T31** | 主链路统一 | P0 | 聊天与编排入口收敛到统一状态机入口 |
| **T35** | 验收深度增强 | P0 | 增加命令级、回归测试级、场景级验证能力 |
| **T36** | 任务拆解增强 | P0 | 提升复杂需求下语义拆解稳定性 |
| **T37** | 并发执行策略增强 | P1 | 并发限流、失败短路、重试策略 |
| **T38** | 过程可视化增强 | P1 | 阶段时间线、证据摘要、补救轨迹 |
| **T39** | 状态单一事实源收敛 | P1 | 明确 tasks.db 与 main-task.md 的权威状态来源 |
| **T40** | Team Orchestration 评估接入 | P2 | 评估并引入 ZeroClaw 多代理拓扑能力 |

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
| 主Claw编排 | orchestrator.rs + `execute_orchestrated_task` | 已落地 | 入口仍未与聊天链路统一 |
| 子Claw执行 | sub_claw_executor.rs + zeroclaw_step_executor.rs | 已落地 | 复杂任务稳定性仍需提升 |
| 主Claw评估 | acceptance_evaluator.rs | 已落地 | 校验维度仍偏基础内容匹配 |

### 18.2 小循环状态

`task_poller.rs` 与 `SubClawExecutor::run()` 已形成可执行小循环：读取 task.md → 定位未勾选步骤 → 执行 → 回写勾选。

### 18.3 核心缺失

- 聊天入口与编排入口的统一主循环
- 验收阶段的深度验证能力（命令级/场景级）
- 复杂需求下的高质量语义拆解能力

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
| 验收评估 | acceptance_evaluator.rs | 部分实现 | 真实命令/场景验证覆盖不足 |
| **编排器** | orchestrator.rs | 已实现 | 需统一为单入口主链路 |
| **LLM调用** | zeroclaw_bridge.rs / zeroclaw_step_executor.rs | 已实现 | 失败重试与策略编排可继续增强 |
| **子Claw执行** | sub_claw_executor.rs | 已实现 | 复杂拆解任务下成功率需提升 |

### 19.2 补齐计划

| 优先级 | 模块 | 说明 |
|--------|------|------|
| P0 | 编排主链路统一 | 将聊天/编排入口收敛到统一状态机入口 |
| P0 | 验收增强 | 引入命令执行、测试结果、场景验证证据 |
| P0 | 拆解增强 | 提升复杂需求下语义拆解稳定性 |
| P1 | 并发策略增强 | 增加并发限流、失败短路、重试策略 |
| P1 | 过程可视化增强 | 前端展示阶段、证据、补救轨迹 |
| P2 | Team Orchestration 集成 | 评估并接入 ZeroClaw 多代理拓扑能力 |

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
