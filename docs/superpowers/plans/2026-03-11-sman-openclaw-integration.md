# SMAN + OpenClaw 集成实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 SMAN 改造为"上下文管理者"，OpenClaw 作为"执行者"，实现零门槛桌面应用 + 自学习 AI 助手

**Architecture:** Tauri 最小壳 + SvelteKit 前端 + OpenClaw Sidecar（Bun 编译）+ SMAN Core（上下文管理）

**Tech Stack:** Tauri 2.x, SvelteKit, Bun, OpenClaw, better-sqlite3

---

## File Structure

```
sman/
├── src-tauri/
│   ├── src/
│   │   ├── lib.rs              # 精简后的入口（只保留壳功能）
│   │   ├── commands/
│   │   │   ├── mod.rs          # 只保留 5 个基础命令
│   │   │   ├── shell.rs        # 窗口/托盘
│   │   │   ├── fs.rs           # 文件系统
│   │   │   └── sidecar.rs      # Sidecar 管理
│   │   └── setup.rs            # 启动 Sidecar
│   ├── Cargo.toml              # 精简依赖
│   └── tauri.conf.json         # Sidecar 配置
├── binaries/
│   └── openclaw-server         # OpenClaw 编译产物
├── src/
│   ├── core/                   # SMAN Core（新增）
│   │   ├── context/
│   │   │   ├── loader.ts       # 上下文加载器
│   │   │   ├── builder.ts      # System Prompt 构建器
│   │   │   └── types.ts        # 类型定义
│   │   ├── skills/
│   │   │   ├── selector.ts     # Skill 渐进式选择
│   │   │   └── scanner.ts      # Skill 扫描
│   │   ├── learning/
│   │   │   ├── analyzer.ts     # 学习分析器
│   │   │   └── prompts.ts      # 学习提示词模板
│   │   └── openclaw/
│   │       ├── client.ts       # OpenClaw HTTP 客户端
│   │       └── types.ts        # OpenClaw 类型
│   ├── lib/
│   │   ├── stores/
│   │   │   └── conversation.ts # 对话状态管理（修改）
│   │   └── api/
│   │       └── openclaw.ts     # OpenClaw API（新增）
│   └── routes/
│       └── +page.svelte        # 主页面（修改）
└── docs/
    └── superpowers/
        └── specs/
            └── 2026-03-11-sman-architecture-design.md
```

---

## Chunk 1: 基础设施 - OpenClaw Sidecar 集成

### Task 1.1: 创建 OpenClaw Sidecar 配置

**Files:**
- Modify: `src-tauri/tauri.conf.json`
- Create: `src-tauri/src/commands/sidecar.rs`
- Modify: `src-tauri/src/commands/mod.rs`

- [ ] **Step 1: 更新 tauri.conf.json 添加 sidecar 配置**

```json
{
  "build": {
    "beforeBuildCommand": "npm run build",
    "beforeDevCommand": "npm run dev",
    "frontendDist": "../build",
    "devUrl": "http://localhost:5173"
  },
  "bundle": {
    "active": true,
    "externalBin": ["binaries/openclaw-server"],
    "targets": "all"
  }
}
```

- [ ] **Step 2: 创建 sidecar.rs 管理命令**

```rust
// src-tauri/src/commands/sidecar.rs
use tauri::Manager;
use tauri_plugin_shell::ShellExt;

#[tauri::command]
pub async fn start_openclaw_server(app: tauri::AppHandle) -> Result<String, String> {
    let sidecar = app
        .shell()
        .sidecar("openclaw-server")
        .map_err(|e| e.to_string())?;

    let (mut _rx, _child) = sidecar
        .spawn()
        .map_err(|e| format!("Failed to spawn sidecar: {}", e))?;

    Ok("OpenClaw server started".to_string())
}

#[tauri::command]
pub async fn stop_openclaw_server() -> Result<String, String> {
    // TODO: Implement graceful shutdown
    Ok("OpenClaw server stopped".to_string())
}
```

- [ ] **Step 3: 更新 commands/mod.rs**

```rust
// src-tauri/src/commands/mod.rs
pub mod sidecar;
pub mod shell;
pub mod fs;

pub use sidecar::*;
pub use shell::*;
pub use fs::*;
```

- [ ] **Step 4: 验证编译**

Run: `cd /Users/nasakim/projects/sman && cargo check --manifest-path src-tauri/Cargo.toml`

Expected: 编译通过（可能有警告）

- [ ] **Step 5: Commit**

```bash
git add src-tauri/
git commit -m "feat: add OpenClaw sidecar configuration"
```

---

### Task 1.2: 精简 Tauri 命令到最小集

**Files:**
- Create: `src-tauri/src/commands/shell.rs`
- Create: `src-tauri/src/commands/fs.rs`
- Modify: `src-tauri/src/lib.rs`

- [ ] **Step 1: 创建 shell.rs（窗口/托盘命令）**

```rust
// src-tauri/src/commands/shell.rs
use tauri::Manager;

#[tauri::command]
pub fn minimize_window(window: tauri::Window) -> Result<(), String> {
    window.minimize().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn maximize_window(window: tauri::Window) -> Result<(), String> {
    window.maximize().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn close_window(window: tauri::Window) -> Result<(), String> {
    window.close().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn show_in_finder(path: String) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg("-R")
            .arg(&path)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .args(["/select,", &path])
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}
```

- [ ] **Step 2: 创建 fs.rs（文件系统命令）**

```rust
// src-tauri/src/commands/fs.rs
use std::fs;
use std::path::Path;

#[tauri::command]
pub fn read_text_file(path: String) -> Result<String, String> {
    fs::read_to_string(&path).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn write_text_file(path: String, content: String) -> Result<(), String> {
    fs::write(&path, content).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn list_directory(path: String) -> Result<Vec<String>, String> {
    let entries = fs::read_dir(&path).map_err(|e| e.to_string())?;
    let mut names = Vec::new();
    for entry in entries {
        if let Ok(entry) = entry {
            names.push(entry.file_name().to_string_lossy().to_string());
        }
    }
    Ok(names)
}

#[tauri::command]
pub fn file_exists(path: String) -> bool {
    Path::new(&path).exists()
}
```

- [ ] **Step 3: 精简 lib.rs**

```rust
// src-tauri/src/lib.rs
pub mod commands;

pub use commands::*;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            // Sidecar
            commands::sidecar::start_openclaw_server,
            commands::sidecar::stop_openclaw_server,
            // Shell
            commands::shell::minimize_window,
            commands::shell::maximize_window,
            commands::shell::close_window,
            commands::shell::show_in_finder,
            // FS
            commands::fs::read_text_file,
            commands::fs::write_text_file,
            commands::fs::list_directory,
            commands::fs::file_exists,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

- [ ] **Step 4: 验证编译**

Run: `cd /Users/nasakim/projects/sman && cargo check --manifest-path src-tauri/Cargo.toml`

Expected: 编译通过

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/
git commit -m "refactor: slim down Tauri to minimal shell commands"
```

---

### Task 1.3: 精简 Cargo.toml 依赖

**Files:**
- Modify: `src-tauri/Cargo.toml`

- [ ] **Step 1: 移除不需要的依赖**

```toml
[package]
name = "sman"
version = "0.1.0"
edition = "2021"
rust-version = "1.77"

[build-dependencies]
tauri-build = { version = "2", features = [] }

[dependencies]
tauri = { version = "2", features = ["macos-private-api"] }
tauri-plugin-shell = "2"
tauri-plugin-dialog = "2"
serde = { version = "1", features = ["derive"] }
serde_json = "1"

[lib]
name = "sman"
crate-type = ["lib", "cdylib", "staticlib"]
```

- [ ] **Step 2: 验证编译**

Run: `cd /Users/nasakim/projects/sman && cargo check --manifest-path src-tauri/Cargo.toml`

Expected: 编译通过

- [ ] **Step 3: Commit**

```bash
git add src-tauri/Cargo.toml
git commit -m "refactor: remove unused Rust dependencies"
```

---

## Chunk 2: SMAN Core - OpenClaw 客户端

### Task 2.1: 创建 OpenClaw 类型定义

**Files:**
- Create: `src/core/openclaw/types.ts`

- [ ] **Step 1: 创建 OpenClaw 类型**

```typescript
// src/core/openclaw/types.ts

export interface OpenClawConfig {
  skills?: {
    load?: {
      extraDirs?: string[];
    };
  };
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
}

export interface ChatRequest {
  messages: ChatMessage[];
  systemPrompt?: string;
  skillFilter?: string[];
}

export interface ChatResponse {
  message: ChatMessage;
  done: boolean;
}

export interface SkillMeta {
  name: string;
  description: string;
  filePath: string;
}

export interface LearnRequest {
  conversation: ChatMessage[];
  projectContext: string;
}

export interface LearnResponse {
  updates: Array<{
    type: 'habit' | 'memory' | 'skill';
    path: string;
    content: string;
    reason: string;
  }> | null;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/core/openclaw/types.ts
git commit -m "feat: add OpenClaw type definitions"
```

---

### Task 2.2: 创建 OpenClaw HTTP 客户端

**Files:**
- Create: `src/core/openclaw/client.ts`
- Create: `src/core/openclaw/index.ts`

- [ ] **Step 1: 创建 OpenClaw 客户端**

```typescript
// src/core/openclaw/client.ts
import type {
  ChatRequest,
  ChatResponse,
  SkillMeta,
  LearnRequest,
  LearnResponse,
} from './types';

const OPENCLAW_BASE_URL = 'http://127.0.0.1:3000';

export class OpenClawClient {
  private baseUrl: string;

  constructor(baseUrl: string = OPENCLAW_BASE_URL) {
    this.baseUrl = baseUrl;
  }

  async chat(request: ChatRequest): Promise<ChatResponse> {
    const response = await fetch(`${this.baseUrl}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(`OpenClaw chat failed: ${response.statusText}`);
    }

    return response.json();
  }

  async *chatStream(request: ChatRequest): AsyncGenerator<string> {
    const response = await fetch(`${this.baseUrl}/api/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(`OpenClaw stream failed: ${response.statusText}`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error('No response body');

    const decoder = new TextDecoder();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      yield decoder.decode(value);
    }
  }

  async listSkills(projectPath: string): Promise<SkillMeta[]> {
    const response = await fetch(
      `${this.baseUrl}/api/skills?project=${encodeURIComponent(projectPath)}`
    );

    if (!response.ok) {
      throw new Error(`OpenClaw list skills failed: ${response.statusText}`);
    }

    return response.json();
  }

  async analyzeLearning(request: LearnRequest): Promise<LearnResponse> {
    const response = await fetch(`${this.baseUrl}/api/learn`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(`OpenClaw learn failed: ${response.statusText}`);
    }

    return response.json();
  }

  async healthCheck(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/health`, {
        method: 'GET',
      });
      return response.ok;
    } catch {
      return false;
    }
  }
}
```

- [ ] **Step 2: 创建 index.ts 导出**

```typescript
// src/core/openclaw/index.ts
export * from './types';
export * from './client';
```

- [ ] **Step 3: Commit**

```bash
git add src/core/openclaw/
git commit -m "feat: add OpenClaw HTTP client"
```

---

## Chunk 3: SMAN Core - 上下文管理

### Task 3.1: 创建上下文类型定义

**Files:**
- Create: `src/core/context/types.ts`

- [ ] **Step 1: 创建上下文类型**

```typescript
// src/core/context/types.ts

export interface ProjectContext {
  path: string;
  name: string;
  soul?: string;           // .sman/SOUL.md
  agents?: string;         // .sman/AGENTS.md
  skills: SkillFile[];     // .sman/skills/*.md
  memory: MemoryFile[];    // .sman/memory/*.md
}

export interface SkillFile {
  name: string;
  path: string;
  content: string;
}

export interface MemoryFile {
  name: string;
  path: string;
  content: string;
}

export interface UserHabits {
  preferences: string;     // ~/.smanlocal/habits.md
  globalSkills: SkillFile[]; // ~/.smanlocal/skills/*.md
}

export interface ContextConfig {
  projectPath: string;
  homePath: string;
}

export interface SelectedSkill {
  name: string;
  content: string;
  relevance: number;  // 0-1
}
```

- [ ] **Step 2: Commit**

```bash
git add src/core/context/types.ts
git commit -m "feat: add context type definitions"
```

---

### Task 3.2: 创建上下文加载器

**Files:**
- Create: `src/core/context/loader.ts`

- [ ] **Step 1: 创建上下文加载器**

```typescript
// src/core/context/loader.ts
import type { ProjectContext, UserHabits, ContextConfig, SkillFile, MemoryFile } from './types';

const SMAN_DIR = '.sman';
const SMAN_LOCAL_DIR = '.smanlocal';

export class ContextLoader {
  private readFile: (path: string) => Promise<string>;
  private listFiles: (dir: string) => Promise<string[]>;
  private homePath: string;

  constructor(
    homePath: string,
    readFile: (path: string) => Promise<string>,
    listFiles: (dir: string) => Promise<string[]>
  ) {
    this.homePath = homePath;
    this.readFile = readFile;
    this.listFiles = listFiles;
  }

  async loadProjectContext(projectPath: string): Promise<ProjectContext> {
    const smanPath = `${projectPath}/${SMAN_DIR}`;

    const [soul, agents, skills, memory] = await Promise.all([
      this.tryReadFile(`${smanPath}/SOUL.md`),
      this.tryReadFile(`${smanPath}/AGENTS.md`),
      this.loadSkillFiles(`${smanPath}/skills`),
      this.loadMemoryFiles(`${smanPath}/memory`),
    ]);

    return {
      path: projectPath,
      name: projectPath.split('/').pop() || projectPath,
      soul,
      agents,
      skills,
      memory,
    };
  }

  async loadUserHabits(): Promise<UserHabits> {
    const localPath = `${this.homePath}/${SMAN_LOCAL_DIR}`;

    const [preferences, globalSkills] = await Promise.all([
      this.tryReadFile(`${localPath}/habits.md`),
      this.loadSkillFiles(`${localPath}/skills`),
    ]);

    return {
      preferences: preferences || '',
      globalSkills,
    };
  }

  private async loadSkillFiles(dir: string): Promise<SkillFile[]> {
    return this.loadMarkdownFiles(dir, '.md');
  }

  private async loadMemoryFiles(dir: string): Promise<MemoryFile[]> {
    return this.loadMarkdownFiles(dir, '.md');
  }

  private async loadMarkdownFiles(dir: string, ext: string): Promise<Array<{ name: string; path: string; content: string }>> {
    try {
      const files = await this.listFiles(dir);
      const mdFiles = files.filter(f => f.endsWith(ext));

      const results = await Promise.all(
        mdFiles.map(async (fileName) => {
          const filePath = `${dir}/${fileName}`;
          const content = await this.tryReadFile(filePath);
          if (content) {
            return { name: fileName, path: filePath, content };
          }
          return null;
        })
      );

      return results.filter((r): r is NonNullable<typeof r> => r !== null);
    } catch {
      return [];
    }
  }

  private async tryReadFile(path: string): Promise<string | undefined> {
    try {
      return await this.readFile(path);
    } catch {
      return undefined;
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/core/context/loader.ts
git commit -m "feat: add context loader"
```

---

### Task 3.3: 创建 System Prompt 构建器

**Files:**
- Create: `src/core/context/builder.ts`

- [ ] **Step 1: 创建 Prompt 构建器**

```typescript
// src/core/context/builder.ts
import type { ProjectContext, UserHabits, SelectedSkill } from './types';

export class SystemPromptBuilder {
  build(
    projectContext: ProjectContext,
    userHabits: UserHabits,
    selectedSkills: SelectedSkill[]
  ): string {
    const parts: string[] = [];

    // 1. 项目使命
    if (projectContext.soul) {
      parts.push(`## 项目使命\n\n${projectContext.soul}`);
    }

    // 2. 角色定义
    if (projectContext.agents) {
      parts.push(`## 角色定义\n\n${projectContext.agents}`);
    }

    // 3. 选中技能
    if (selectedSkills.length > 0) {
      const skillsContent = selectedSkills
        .sort((a, b) => b.relevance - a.relevance)
        .map(s => `### ${s.name}\n\n${s.content}`)
        .join('\n\n');
      parts.push(`## 可用技能\n\n${skillsContent}`);
    }

    // 4. 项目知识
    if (projectContext.memory.length > 0) {
      const memoryContent = projectContext.memory
        .map(m => `### ${m.name}\n\n${m.content}`)
        .join('\n\n');
      parts.push(`## 项目知识\n\n${memoryContent}`);
    }

    // 5. 用户习惯（最高优先级）
    if (userHabits.preferences) {
      parts.push(`## 用户习惯\n\n${userHabits.preferences}`);
    }

    // 6. 用户个人技能
    if (userHabits.globalSkills.length > 0) {
      const personalSkills = userHabits.globalSkills
        .map(s => `### ${s.name}\n\n${s.content}`)
        .join('\n\n');
      parts.push(`## 个人技能\n\n${personalSkills}`);
    }

    return parts.join('\n\n---\n\n');
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/core/context/builder.ts
git commit -m "feat: add system prompt builder"
```

---

### Task 3.4: 创建 context 模块导出

**Files:**
- Create: `src/core/context/index.ts`

- [ ] **Step 1: 创建导出文件**

```typescript
// src/core/context/index.ts
export * from './types';
export * from './loader';
export * from './builder';
```

- [ ] **Step 2: Commit**

```bash
git add src/core/context/index.ts
git commit -m "feat: add context module exports"
```

---

## Chunk 4: SMAN Core - Skill 渐进式选择

### Task 4.1: 创建 Skill 选择器

**Files:**
- Create: `src/core/skills/selector.ts`
- Create: `src/core/skills/scanner.ts`
- Create: `src/core/skills/index.ts`

- [ ] **Step 1: 创建 Skill 扫描器**

```typescript
// src/core/skills/scanner.ts
import type { SkillFile } from '../context/types';

export class SkillScanner {
  private readFile: (path: string) => Promise<string>;
  private listFiles: (dir: string) => Promise<string[]>;

  constructor(
    readFile: (path: string) => Promise<string>,
    listFiles: (dir: string) => Promise<string[]>
  ) {
    this.readFile = readFile;
    this.listFiles = listFiles;
  }

  async scanDirectory(dir: string): Promise<SkillFile[]> {
    try {
      const files = await this.listFiles(dir);
      const skillFiles = files.filter(f => f.endsWith('.md'));

      const results = await Promise.all(
        skillFiles.map(async (fileName) => {
          const filePath = `${dir}/${fileName}`;
          try {
            const content = await this.readFile(filePath);
            return { name: fileName.replace('.md', ''), path: filePath, content };
          } catch {
            return null;
          }
        })
      );

      return results.filter((r): r is NonNullable<typeof r> => r !== null);
    } catch {
      return [];
    }
  }
}
```

- [ ] **Step 2: 创建 Skill 选择器**

```typescript
// src/core/skills/selector.ts
import type { SkillFile, SelectedSkill } from '../context/types';

export class SkillSelector {
  private maxSkills: number;
  private maxTokens: number;

  constructor(maxSkills: number = 10, maxTokens: number = 8000) {
    this.maxSkills = maxSkills;
    this.maxTokens = maxTokens;
  }

  select(userInput: string, skills: SkillFile[]): SelectedSkill[] {
    // 1. 计算每个 skill 的相关性分数
    const scored = skills.map(skill => ({
      ...skill,
      relevance: this.calculateRelevance(userInput, skill),
    }));

    // 2. 按相关性排序
    scored.sort((a, b) => b.relevance - a.relevance);

    // 3. 选择 top N，考虑 token 限制
    const selected: SelectedSkill[] = [];
    let totalTokens = 0;

    for (const skill of scored) {
      if (selected.length >= this.maxSkills) break;

      const skillTokens = this.estimateTokens(skill.content);
      if (totalTokens + skillTokens > this.maxTokens) break;

      selected.push({
        name: skill.name,
        content: skill.content,
        relevance: skill.relevance,
      });
      totalTokens += skillTokens;
    }

    return selected;
  }

  private calculateRelevance(userInput: string, skill: SkillFile): number {
    const inputLower = userInput.toLowerCase();
    const skillLower = skill.content.toLowerCase();
    const skillNameLower = skill.name.toLowerCase();

    let score = 0;

    // 检查 skill 名称是否在用户输入中出现
    if (inputLower.includes(skillNameLower)) {
      score += 0.5;
    }

    // 检查关键词匹配
    const keywords = this.extractKeywords(skill.content);
    for (const keyword of keywords) {
      if (inputLower.includes(keyword.toLowerCase())) {
        score += 0.1;
      }
    }

    // 检查 When to Use 部分
    const whenToUseMatch = skill.content.match(/when to use/i);
    if (whenToUseMatch) {
      const whenSection = skill.content.slice(whenToUseMatch.index!);
      if (this.hasOverlap(inputLower, whenSection.toLowerCase())) {
        score += 0.3;
      }
    }

    return Math.min(score, 1);
  }

  private extractKeywords(content: string): string[] {
    // 简单的关键词提取：从标题和加粗文本中提取
    const headings = content.match(/^#+\s+(.+)$/gm) || [];
    const bold = content.match(/\*\*(.+?)\*\*/g) || [];

    const allText = [...headings, ...bold]
      .map(s => s.replace(/[#*]/g, '').trim())
      .join(' ');

    const words = allText.split(/\s+/).filter(w => w.length > 3);
    return [...new Set(words)].slice(0, 20);
  }

  private hasOverlap(text1: string, text2: string): boolean {
    const words1 = new Set(text1.split(/\s+/).filter(w => w.length > 3));
    const words2 = text2.split(/\s+/).filter(w => w.length > 3);
    return words2.some(w => words1.has(w));
  }

  private estimateTokens(text: string): number {
    // 粗略估计：1 token ≈ 4 字符
    return Math.ceil(text.length / 4);
  }
}
```

- [ ] **Step 3: 创建导出文件**

```typescript
// src/core/skills/index.ts
export * from './scanner';
export * from './selector';
```

- [ ] **Step 4: Commit**

```bash
git add src/core/skills/
git commit -m "feat: add skill scanner and selector"
```

---

## Chunk 5: SMAN Core - 自学习机制

### Task 5.1: 创建学习提示词模板

**Files:**
- Create: `src/core/learning/prompts.ts`

- [ ] **Step 1: 创建学习提示词模板**

```typescript
// src/core/learning/prompts.ts

export const LEARNING_PROMPT = `你刚才和用户完成了一次对话。请分析这次对话，找出值得记录的内容。

## 分析维度

1. **用户习惯**
   - 用户偏好的代码风格（如缩进、引号风格）
   - 用户常用的工具或框架
   - 用户偏好的回复语言和详细程度
   - 如果发现，写入: ~/.smanlocal/habits.md

2. **项目知识**
   - 项目特有的业务规则
   - API 端点或数据结构
   - 常见问题和解决方案
   - 如果发现，写入: .sman/memory/<topic>.md

3. **可复用技能**
   - 解决某类问题的通用方法
   - 特定领域的专业知识
   - 如果发现，写入: .sman/skills/<skill-name>.md

## 输出格式

如果没有值得记录的内容，只输出:
NO_UPDATE

如果有，输出 JSON 数组:
\`\`\`json
[
  {
    "type": "habit" | "memory" | "skill",
    "path": "要写入的文件路径（相对于项目根目录或用户主目录）",
    "content": "要写入的内容",
    "reason": "为什么要记录这个"
  }
]
\`\`\`

## 注意事项

- 只记录真正有价值、可复用的内容
- 不要重复已有知识
- 保持简洁，避免冗余
- 路径示例：
  - 个人习惯: ~/.smanlocal/habits.md
  - 项目知识: .sman/memory/api-conventions.md
  - 项目技能: .sman/skills/code-review.md

## 对话记录

{{CONVERSATION}}

## 项目上下文

{{PROJECT_CONTEXT}}
`;

export function buildLearningPrompt(
  conversation: Array<{ role: string; content: string }>,
  projectContext: string
): string {
  const conversationText = conversation
    .map(m => `**${m.role === 'user' ? '用户' : '助手'}**: ${m.content}`)
    .join('\n\n');

  return LEARNING_PROMPT
    .replace('{{CONVERSATION}}', conversationText)
    .replace('{{PROJECT_CONTEXT}}', projectContext);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/core/learning/prompts.ts
git commit -m "feat: add learning prompt templates"
```

---

### Task 5.2: 创建学习分析器

**Files:**
- Create: `src/core/learning/analyzer.ts`
- Create: `src/core/learning/index.ts`

- [ ] **Step 1: 创建学习分析器**

```typescript
// src/core/learning/analyzer.ts
import { OpenClawClient } from '../openclaw';
import { buildLearningPrompt } from './prompts';
import type { ProjectContext, UserHabits } from '../context/types';

export interface LearningUpdate {
  type: 'habit' | 'memory' | 'skill';
  path: string;
  content: string;
  reason: string;
}

export class LearningAnalyzer {
  private client: OpenClawClient;
  private writeFile: (path: string, content: string) => Promise<void>;
  private readFile: (path: string) => Promise<string>;
  private homePath: string;

  constructor(
    client: OpenClawClient,
    homePath: string,
    writeFile: (path: string, content: string) => Promise<void>,
    readFile: (path: string) => Promise<string>
  ) {
    this.client = client;
    this.homePath = homePath;
    this.writeFile = writeFile;
    this.readFile = readFile;
  }

  async analyze(
    conversation: Array<{ role: string; content: string }>,
    projectContext: ProjectContext,
    userHabits: UserHabits
  ): Promise<LearningUpdate[]> {
    // 1. 构建学习提示词
    const contextSummary = this.summarizeContext(projectContext, userHabits);
    const prompt = buildLearningPrompt(conversation, contextSummary);

    // 2. 调用 OpenClaw 分析
    const response = await this.client.chat({
      messages: [{ role: 'user', content: prompt }],
    });

    // 3. 解析结果
    const updates = this.parseResponse(response.message.content);
    return updates;
  }

  async applyUpdates(
    updates: LearningUpdate[],
    projectPath: string
  ): Promise<void> {
    for (const update of updates) {
      const resolvedPath = this.resolvePath(update.path, projectPath);

      // 如果是追加模式，先读取现有内容
      if (update.type === 'habit' || update.type === 'memory') {
        try {
          const existing = await this.readFile(resolvedPath);
          await this.writeFile(resolvedPath, existing + '\n\n' + update.content);
        } catch {
          // 文件不存在，直接创建
          await this.writeFile(resolvedPath, update.content);
        }
      } else {
        // skill 文件直接覆盖或创建
        await this.writeFile(resolvedPath, update.content);
      }
    }
  }

  private summarizeContext(
    projectContext: ProjectContext,
    userHabits: UserHabits
  ): string {
    const parts: string[] = [];

    if (projectContext.soul) {
      parts.push(`项目使命: ${projectContext.soul.slice(0, 500)}`);
    }

    if (projectContext.memory.length > 0) {
      parts.push(`已有知识: ${projectContext.memory.map(m => m.name).join(', ')}`);
    }

    if (userHabits.preferences) {
      parts.push(`用户习惯: ${userHabits.preferences.slice(0, 300)}`);
    }

    return parts.join('\n');
  }

  private parseResponse(response: string): LearningUpdate[] {
    if (response.includes('NO_UPDATE')) {
      return [];
    }

    // 提取 JSON 块
    const jsonMatch = response.match(/```json\s*([\s\S]*?)\s*```/);
    if (!jsonMatch) {
      return [];
    }

    try {
      const parsed = JSON.parse(jsonMatch[1]);
      if (Array.isArray(parsed)) {
        return parsed.filter(u =>
          u.type && u.path && u.content && u.reason
        );
      }
    } catch {
      // JSON 解析失败，忽略
    }

    return [];
  }

  private resolvePath(path: string, projectPath: string): string {
    if (path.startsWith('~/')) {
      return path.replace('~', this.homePath);
    }
    if (path.startsWith('./') || path.startsWith('.sman/)) {
      return `${projectPath}/${path.replace(/^\.\//, '')}`;
    }
    return path;
  }
}
```

- [ ] **Step 2: 创建导出文件**

```typescript
// src/core/learning/index.ts
export * from './prompts';
export * from './analyzer';
```

- [ ] **Step 3: Commit**

```bash
git add src/core/learning/
git commit -m "feat: add learning analyzer"
```

---

## Chunk 6: SMAN Core - 整合与导出

### Task 6.1: 创建 SMAN Core 主入口

**Files:**
- Create: `src/core/index.ts`

- [ ] **Step 1: 创建主入口文件**

```typescript
// src/core/index.ts
export * from './context';
export * from './skills';
export * from './learning';
export * from './openclaw';
```

- [ ] **Step 2: Commit**

```bash
git add src/core/index.ts
git commit -m "feat: add SMAN core module entry"
```

---

### Task 6.2: 创建 .smanlocal 初始化模板

**Files:**
- Create: `templates/.smanlocal/habits.md`
- Create: `templates/.sman/openclaw.yaml`

- [ ] **Step 1: 创建 habits.md 模板**

```markdown
# 用户习惯

此文件记录你的个人偏好，SMAN 会自动学习和更新。

## 偏好设置

<!-- SMAN 会自动添加发现的习惯 -->
```

- [ ] **Step 2: 创建 openclaw.yaml 模板**

```yaml
# SMAN OpenClaw 配置
skills:
  load:
    extraDirs:
      - ~/.smanlocal/skills
      - ./skills
      - ./.sman/skills
      - ./.claude/skills
```

- [ ] **Step 3: Commit**

```bash
git add templates/
git commit -m "feat: add .sman and .smanlocal templates"
```

---

## Chunk 7: 验证与测试

### Task 7.1: 运行 TypeScript 类型检查

- [ ] **Step 1: 运行 typecheck**

Run: `cd /Users/nasakim/projects/sman && npm run typecheck`

Expected: 通过，无错误

- [ ] **Step 2: 修复类型错误（如有）**

根据实际错误修复。

- [ ] **Step 3: Commit**

```bash
git add .
git commit -m "fix: resolve TypeScript type errors"
```

---

### Task 7.2: 运行现有测试

- [ ] **Step 1: 运行测试**

Run: `cd /Users/nasakim/projects/sman && npm run test`

Expected: 所有测试通过

- [ ] **Step 2: 修复测试失败（如有）**

根据实际失败修复。

---

### Task 7.3: 最终提交

- [ ] **Step 1: 确认所有更改已提交**

Run: `git status`

Expected: 工作区干净

- [ ] **Step 2: 推送到远程**

```bash
git push origin agent
```

---

## 后续工作（不在本计划范围）

1. **OpenClaw Sidecar 编译** - 需要在 OpenClaw 项目中添加 HTTP server 入口，然后用 Bun compile
2. **前端 UI 对接** - 修改 SvelteKit 组件使用新的 SMAN Core API
3. **打包测试** - 验证 Tauri + Sidecar 打包是否正常工作
4. **集成测试** - 端到端测试对话流程和学习机制

---

**Plan complete and saved to `docs/superpowers/plans/2026-03-11-sman-openclaw-integration.md`. Ready to execute?**
