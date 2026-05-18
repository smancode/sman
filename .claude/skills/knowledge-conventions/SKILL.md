---
_scanned.commitHash: "1ddac60bf3f5dbec4ced87ea1a0b7b680267f41c"
_scanned.scannedAt: "2026-05-19T00:00:00.000Z"
_scanned.branch: "master"
---

# 开发规范

Top 6 conventions from incremental scan (commit 1ddac60bf3f5dbec4ced87ea1a0b7b680267f41c).

## 1. Git 操作必须异步化

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/git-handler.ts: 所有导出函数

所有 Git 操作必须使用异步 `execFile` 而非同步 `execSync`，避免阻塞事件循环：

**❌ 错误示例**（旧实现）：
```typescript
function git(workspace: string, args: string): string {
  return execSync(`git --no-pager ${args}`, { cwd: workspace, encoding: 'utf-8' }).trim();
}
```

**✅ 正确示例**（新实现）：
```typescript
const execFileAsync = promisify(execFile);

async function git(workspace: string, args: string, timeout = 10000): Promise<string> {
  const { stdout } = await execFileAsync('git', ['--no-pager', ...args.split(' ')], {
    cwd: workspace,
    encoding: 'utf-8',
    timeout,
    maxBuffer: 10 * 1024 * 1024,
  });
  return stdout.trim();
}
```

**关键点**：
- 使用 `promisify(execFile)` 包装成 Promise
- 参数拆分数组传入，避免 shell 注入
- 所有导出函数改为 `async`：`handleGitStatus`, `handleGitDiff`, `handleGitCommit` 等
- WebSocket 处理器用 `.then()/.catch()` 处理异步结果

## 2. 脚本文件扩展名白名单

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/index.ts: SCRIPT_EXTENSIONS, smart-path-engine.ts: SCRIPT_EXTENSIONS

Reference 文件只能保存脚本文件，禁止保存数据文件：

```typescript
const SCRIPT_EXTENSIONS = new Set([
  '.py', '.sh', '.bash', '.zsh', '.js', '.ts', '.mjs', '.cjs',
  '.bat', '.cmd', '.ps1', '.sql', '.r', '.rb', '.go', '.java',
  '.pl', '.lua', '.php', '.rs', '.dart', '.kt', '.scala', '.clj',
]);

function isScriptFile(fileName: string): boolean {
  const ext = path.extname(fileName).toLowerCase();
  return SCRIPT_EXTENSIONS.has(ext);
}
```

**应用场景**：
- Smart Path 提取 `[REFERENCE:filename.ext]` 时过滤：`if (isScriptFile(refFileName)) saveReference(...)`
- Earth Path 步骤规则明确：只能保存 `.py, .sh, .js, .ts, .bat, .sql, .r, .rb, .go, .java, .ps1` 等脚本
- 禁止保存 `.json, .csv, .txt, .xlsx, .xml, .yaml, .yml` 等数据文件

**设计原因**：脚本可复用，数据文件应放在 `tmp/` 中避免耦合

## 3. Smart Path 规则放在 User Prompt

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: buildStepPrompt

Smart Path 的步骤执行规则必须放在 **user prompt** 的 `[规则]` 段落，**绝对不能**修改 system prompt：

**✅ 正确做法**：
```typescript
function buildStepPrompt(..., skills?: string[]): string {
  const parts: string[] = [];
  // ... 构建指令内容 ...

  parts.push('');
  parts.push('[规则]');
  parts.push('1. 直接执行，给出简洁结果。不要询问用户。');
  parts.push('2. 专注于本步骤目标，不要越界。');
  parts.push('3. 能用 tool 完成就用，不能的直接实现。');

  if (skills && skills.length > 0) {
    parts.push(buildSkillsContext(workspace, skills)); // 注入 skill 内容
  } else {
    parts.push('6. 不使用 workspace/.claude/skills 中的 skill。');
  }

  return parts.join('\n');
}
```

**关键点**：
- `STEP_SYSTEM_PROMPT` 设为空字符串，规则全部移到 user prompt
- System prompt 是 session 级的，path 不应该污染它
- 步骤级别的限制（workspace skills 限制、脚本文件限制等）放在 user prompt 的 `[规则]` 段落

## 4. 步骤级 Skills 注入

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: buildSkillsContext

Smart Path 步骤可以指定需要使用的 skills，动态注入到 prompt 中：

```typescript
function buildSkillsContext(workspace: string, skills: string[] | undefined): string {
  if (!skills || skills.length === 0) return '';
  const parts: string[] = [];
  for (const skillId of skills) {
    const content = readSkillContent(workspace, skillId);
    if (content) {
      parts.push(`### Skill: ${skillId}\n${content}`);
    }
  }
  return parts.length > 0
    ? `[可使用的 Skills — 严格按以下 skill 的指令执行]\n${parts.join('\n\n')}`
    : '';
}
```

**使用方式**：
- 步骤定义中新增 `skills?: string[]` 字段
- 执行时读取 `workspace/.claude/skills/{skillId}/SKILL.md` 内容
- 直接注入到 user prompt 的 `[规则]` 段落之后
- 未指定 skills 时明确告知：`不使用 workspace/.claude/skills 中的 skill`

## 5. WebSocket 异步消息处理

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/index.ts: Git 消息处理

WebSocket 消息处理器必须使用 Promise 模式，避免阻塞事件循环：

**✅ 正确示例**：
```typescript
case 'git.status': {
  if (!msg.workspace) {
    ws.send(JSON.stringify({ type: 'git.status', result: { error: 'Missing workspace' } }));
    break;
  }
  handleGitStatus(String(msg.workspace))
    .then(result => ws.send(JSON.stringify({ type: 'git.status', result })))
    .catch(err => ws.send(JSON.stringify({ type: 'git.status', result: { error: err instanceof Error ? err.message : String(err) } })));
  break;
}
```

**关键点**：
- 所有 Git 处理函数改为 async（Convention #1）
- 不使用 try-catch 包裹，改用 `.then()/.catch()` 链式处理
- 错误信息统一格式：`{ error: string }`
- 注释标注：`// ── Git (all async — never blocks the event loop) ───────`

## 6. 目录展开安全限制

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/git-handler.ts: expandDirFiles

Git status 中展开未跟踪目录时，必须限制深度和文件数量：

```typescript
const SKIP_DIRS = new Set(['node_modules', '.git', 'dist', 'build', 'out', '.next', '.nuxt', 'target', 'vendor', '__pycache__', '.venv', 'venv', 'Pods', '.gradle', '.idea', '.cache', 'coverage']);
const MAX_EXPAND_DEPTH = 3;
const MAX_EXPAND_FILES = 500;

function expandDirFiles(workspace: string, dirPath: string, out: GitFileStatus[], depth = 0): void {
  if (depth > MAX_EXPAND_DEPTH || out.length >= MAX_EXPAND_FILES) return;

  // ... 遍历目录 ...

  for (const entry of entries) {
    if (out.length >= MAX_EXPAND_FILES) break;
    if (entry.name.startsWith('.')) continue;
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) {
        out.push({ path: `${rel}/`, status: 'untracked', staged: false });
        continue;
      }
      expandDirFiles(workspace, rel, out, depth + 1);
    }
  }
}
```

**关键点**：
- 跳过常见大型目录（`node_modules`, `dist`, `build` 等）
- 限制递归深度最多 3 层
- 限制展开文件总数最多 500 个
- 达到限制时直接返回，不抛异常
