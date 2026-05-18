# Git CLI Integration

Sman 通过 Node.js `child_process.execFile` 异步调用系统 Git 命令，实现版本控制功能。

## 调用方式

```typescript
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

async function git(workspace: string, args: string, timeout = 10000): Promise<string> {
  const { stdout } = await execFileAsync('git', ['--no-pager', ...args.split(' ')], {
    cwd: workspace,
    encoding: 'utf-8',
    timeout,
    maxBuffer: 10 * 1024 * 1024, // 10MB
  });
  return stdout.trim();
}
```

## 核心操作

### 1. Status (并行查询)

```bash
git rev-parse --abbrev-ref HEAD          # 当前分支
git status --porcelain=v2 --branch       # 文件状态 + ahead/behind
```

**特性**:
- 两个命令并发执行（`Promise.all`）
- 自动展开未跟踪目录（跳过 `node_modules`, `.git`, `dist` 等）
- 最大展开深度 3 层，最多 500 个文件

### 2. Diff

```bash
git diff --no-color -U3                  # 工作区 vs 暂存区
git diff --cached --no-color -U3         # 暂存区 vs HEAD
git diff HEAD...{upstream} --no-color -U3 # 与远程分支对比
```

**特性**:
- 超时 30s（大文件 diff）
- 路径验证（防止目录遍历）
- 新建文件显示全文为绿色，删除文件显示 HEAD 内容为红色

### 3. Commit

```bash
git add -- "{filePath}"                   # 添加指定文件
git add -A                                # 添加所有变更
git commit -m "{message}"                 # 提交
```

**特性**:
- 提交信息非空校验
- 支持指定文件列表或全量提交
- 提取 commit hash（短哈希）返回前端

### 4. Log

```bash
git log --max-count=20 --pretty=format:"%H%n%h%n%s%n%an%n%aI%n%D"
git log --graph --all --decorate --max-count=200
```

**特性**:
- 自定义格式（hash, shortHash, message, author, date, refs）
- Graph 模式用 NUL 字符分隔图形前缀和数据
- 搜索支持 hash 前缀匹配 / message 内容模糊查询

### 5. Push (带冲突解决)

```bash
git rev-parse --abbrev-ref HEAD                  # 当前分支
git rev-parse --abbrev-ref {branch}@{upstream}   # 检查 upstream
git push -u origin {branch}                       # 首次推送（设置 upstream）
git pull --rebase                                 # 拉取远程变更
git push                                          # 推送本地提交
```

**特性**:
- 自动检测并设置 upstream
- 冲突时调用 Claude Agent SDK 自动解决
- AI 冲突解决后验证 `ahead` 数量

### 6. Remote Operations

```bash
git fetch --all --prune                          # 更新远程引用
git checkout {branch}                            # 切换分支
git checkout -b {local} {remote}                 # 从远程创建本地分支
```

## 安全措施

### 路径验证

```typescript
import { validatePath } from './code-viewer-handler.js';

validatePath(workspace, filePath);  // 防止 ../../../etc/passwd
```

### 分支名白名单

```typescript
if (!/^[a-zA-Z0-9\/_.@-]+$/.test(branch)) {
  throw new Error('Invalid branch name');
}
```

### 命令注入防护

- 使用 `execFile`（参数数组）而非 `exec`（shell 字符串）
- 分支名、文件名强校验
- 超时保护（10-60s）

## 性能优化

### 并发执行

```typescript
// ✅ 并发（快）
const [branch, porcelain] = await Promise.all([
  git(workspace, 'rev-parse --abbrev-ref HEAD'),
  git(workspace, 'status --porcelain=v2 --branch'),
]);

// ❌ 串行（慢）
const branch = await git(workspace, 'rev-parse --abbrev-ref HEAD');
const porcelain = await git(workspace, 'status --porcelain=v2 --branch');
```

### 智能目录展开

- 跳过大目录（`SKIP_DIRS` Set）
- 限制深度（`MAX_EXPAND_DEPTH = 3`）
- 限制文件数（`MAX_EXPAND_FILES = 500`）

### 缓冲区保护

- `maxBuffer: 10 * 1024 * 1024`（10MB）
- 防止大文件 diff 内存溢出

## 错误处理

```typescript
try {
  const result = await git(workspace, args);
} catch (err: unknown) {
  const e = err as { stderr?: string; message?: string };
  const msg = e.stderr?.trim() || e.message || String(err);
  throw new Error(msg);
}
```

## WebSocket API

所有 Git 操作通过 WebSocket 暴露：

```typescript
// 请求
{ type: 'git.status', workspace: '/path/to/project' }

// 响应
{ type: 'git.status', result: { branch: 'main', files: [...], ahead: 0, behind: 0 } }
```

**异步转换**:
```typescript
// server/index.ts
handleGitStatus(workspace)
  .then(result => ws.send(JSON.stringify({ type: 'git.status', result })))
  .catch(err => ws.send(JSON.stringify({ type: 'git.status', result: { error: err.message } })));
```
