# SmanClaw Desktop App 设计文档

## 概述

基于 ZeroClaw 开发一个 Windows 桌面应用。用户双击桌面图标，弹出一个 Chat 窗口，输入需求后 ZeroClaw 执行编码任务。

## 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                    SmanClaw Desktop App (主控 GUI)              │
│                        (Rust + Tauri + WebView)                        │
│                                                                 │
│  功能：                                                          │
│  - 桌面图标双击唤醒                                           │
│  - 弹出 Chat 窗口                                            │
│  - 用户输入需求                                                │
│  - 展示历史对话                                                │
│  - 调用 ZeroClaw CLI/API                                     │
│  - 展示子 ZeroClaw 状态                                     │
│  - 生成报告                                                   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            │ CLI / FFI
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ZeroClaw 子进程 × N (执行者)                   │
│                                                                 │
│  每个子进程：                                                   │
│  - 独立 workspace (--workspace)                               │
│  - 绑定到特定项目 (--project)                                │
│  - 执行具体编码任务                                           │
│  - 完成后自动退出                                              │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ 文件系统
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    项目目录 (ProjectA, ProjectB...)              │
│                                                                 │
│  每个项目：                                                      │
│  - 源码、配置                                                  │
│  - .claude/skills/ (共享给 ZeroClaw)                          │
│  - .zeroclaw-workspace/ (ZeroClaw workspace)                  │
└─────────────────────────────────────────────────────────────────┘
```

## 技术选型

| 层 | 技术 | 说明 |
|---|------|------|
| GUI | Tauri (Rust + WebView) | 跨平台桌面应用，Windows 支持 |
| 前端 | Svelte / TypeScript | Tauri 原生支持 |
| 后端 | ZeroClaw Library | 通过 FFI 直接调用 |
| 通信 | FFI (Rust 函数) | 进程内通信，| 存储 | SQLite + 文件系统 | 历史对话、任务状态 |

## 关键模块

### 1. 主控 GUI (SmanClaw Desktop App)

```
smanclaw-desktop/
├── src-tauri/          # Rust 后端
│   ├── src/
│   │   ├── main.rs
│   │   ├── zeroclaw_bridge.rs  # ZeroClaw FFI 接口
│   │   ├── task_manager.rs    # 任务编排
│   │   └── history_store.rs  # 历史对话存储
│   └── Cargo.toml
├── src/                # 前端
│   ├── lib/
│   │   └── stores/       # Svelte stores
│   ├── routes/
│   │   └── +layout.svelte
│   ├── components/
│   │   ├── Chat.svelte      # 聊天窗口
│   │   ├── History.svelte   # 历史记录
│   │   ├── ProjectCard.svelte  # 项目卡片
│   │   ├── TaskProgress.svelte  # 任务进度
│   │   └── Sidebar.svelte    # 侧边栏
│   └── app.html
├── tauri.conf.json
├── package.json
└── README.md
```

### 2. ZeroClaw FFI 接口

ZeroClaw 需要暴露为 library 以便 FFI 调用：

```rust
// zeroclaw/src/lib.rs - 添加 public API
pub mod ffi {
    pub fn execute_task(
        task: &str,
        project_path: Option<&str>,
    ) -> anyhow::Result<TaskResult>;

    pub fn load_history(project_path: &str) -> anyhow::Result<Vec<HistoryEntry>>;

    pub fn get_task_status(task_id: &str) -> anyhow::Result<TaskStatus>;
}

pub struct TaskResult {
    pub success: bool,
    pub output: String,
    pub error: Option<String>,
}

pub struct HistoryEntry {
    pub role: String,
    pub content: String,
    pub timestamp: i64,
}

pub struct TaskStatus {
    pub id: String,
    pub project: String,
    pub status: String, // pending, running, completed, error
    pub progress: f32,
}
```

### 3. 前端组件

#### Chat.svelte
```svelte
<script lang="ts">
  import { invoke } from '@tauri-apps/api/tauri';

  export let task = '';
  export let projectPath = '';
  export let history = [];
  export let status = 'idle';
  export let output = '';

  async function executeTask() {
    status = 'running';
    output = '';

    try {
      const result = await invoke('execute_task', {
        task,
        projectPath: projectPath || null
      });

      if (result.success) {
        output = result.output;
        status = 'completed';

        // 添加到历史
        history = [...history, {
          role: 'user',
          content: task,
          timestamp: Date.now()
        }, {
          role: 'assistant',
          content: result.output,
          timestamp: Date.now()
        }];
      } else {
        status = 'error';
        output = result.error || 'Unknown error';
      }
    } catch (e) {
      status = 'error';
      output = e.message;
    }
  }
</script>

<div class="chat-container">
  <div class="history">
    {#each history as entry}
      <div class="message {entry.role}">
        {entry.content}
      </div>
    {/each}
  </div>

  <div class="input-area">
    <input bind:value={task} placeholder="输入你的需求..." />
    <input bind:value={projectPath} placeholder="项目路径 (可选)" />
    <button on:click={executeTask} disabled={status === 'running'}>
      {status === 'running' ? '执行中...' : '执行'}
    </button>
  </div>

  {#if status === 'running'}
    <div class="output">
      <pre>{output}</pre>
    </div>
  {/if}
</div>
```

## 项目目录结构

```
projects/
├── project-a/
│   ├── src/
│   ├── .claude/
│   │   └── skills/          # Claude Code skills
│   ├── .zeroclaw-workspace/  # ZeroClaw workspace (软链接到 .claude/skills)
│   │   ├── memory/
│   │   ├── config.toml
│   │   └── skills -> ../../.claude/skills
│   └── package.json
│
├── project-b/
│   └── ... (类似结构)
│
└── smanclaw-desktop/
    └── ... (桌面应用)
```

## 启动方式

### 1. 双击桌面图标
- Windows: 注册文件关联 `.smclaw` 扩展名
- 用户双击图标，- 应用启动，- 显示主窗口

### 2. 选择项目
- 显示最近使用的项目列表
- 或点击 "打开项目" 选择目录

### 3. 输入需求
- 在 Chat 窗口输入自然语言需求
- 例如: "实现用户登录功能"

### 4. 查看进度
- 实时显示 ZeroClaw 执行进度
- 显示正在读取/修改的文件

- 显示执行的命令

### 5. 查看结果
- 执行完成后显示结果
- 支持复制/导出

## 开发阶段

### Phase 1: 基础框架 (3-5 天)
- [x] Tauri 项目初始化
- [x] ZeroClaw FFI 接口
- [x] 基础 UI 组件
- [x] 项目选择功能

### Phase 2: 核心功能 (5-7 天)
- [x] 任务执行接口
- [x] 历史对话显示
- [x] 实时进度更新
- [x] 错误处理

### Phase 3: 功能完善 (2-3 天)
- [x] 历史对话存储 (SQLite)
- [x] 任务状态持久化
- [x] 报告生成
- [x] 快捷键支持

## 配置文件示例

### tauri.conf.json
```json
{
  "build": {
    "beforeBuildCommand": ["cargo", "build"],
    "beforeDevCommand": ["cargo", "dev"],
    "devPath": "../zeroclaw",
    "distDir": "../dist",
    "withGlobalTauri": true
  }
}
```

### Cargo.toml (smanclaw-desktop)
```toml
[package]
name = "smanclaw-desktop"
version = "0.1.0"
edition = "2021"

[build-dependencies]
zeroclaw = { path = "../zeroclaw" }
tauri = { version = "1.5" }
serde = { version = "1.0", features = ["derive"] }
tokio = { version = "1", features = ["full"] }
anyhow = "1.0"

[features]
default = ["std"]
```

## 未来扩展

1. **多项目管理** - 同时管理多个项目
2. **模板系统** - 预定义任务模板
3. **团队协作** - 多用户协作
4. **CI/CD 集成** - 持续集成
5. **插件系统** - 可扩展插件架构
