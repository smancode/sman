# Git CLI Integration

Sman 通过 Node.js `child_process.execFile` 异步调用系统 Git 命令。

## 调用方式

```typescript
async function git(workspace: string, args: string[], timeout = 10000): Promise<string> {
  const { stdout } = await execFileAsync('git', ['--no-pager', ...args], {
    cwd: workspace, encoding: 'utf-8', timeout,
    maxBuffer: 10 * 1024 * 1024, // 10MB
  });
  return stdout.trim();
}
```

## 核心操作

| 操作 | 命令 | 特性 |
|------|------|------|
| **Status** | `rev-parse --abbrev-ref HEAD` + `status --porcelain=v2 --branch` | 并发查询，自动展开未跟踪目录（深度 3，最多 500 文件） |
| **Diff** | `diff -U3`, `diff --cached -U3`, `diff HEAD...{upstream}` | 超时 30s，路径验证，新文件全绿/删除文件全红 |
| **Commit** | `add -A` / `add -- "{file}"` + `commit -m "{msg}"` | 非空校验，支持指定文件或全量，返回短哈希 |
| **Log** | `log --pretty=format:"%H%n%h..."` + `log --graph` | 自定义格式（hash/message/author/date），NUL 分离图形 |
| **Push** | `push -u origin {branch}` / `pull --rebase` + `push` | 自动检测 upstream，冲突时调用 Claude Agent SDK 解决 |
| **Remote** | `fetch --all --prune`, `checkout {branch}` | 更新引用，切换分支，从远程创建本地分支 |

## 安全措施

- **路径验证**: `validatePath(workspace, filePath)` 防止 `../../../etc/passwd`
- **分支名白名单**: `/^[a-zA-Z0-9\/_.@-]+$/`
- **命令注入防护**: 使用 `execFile`（参数数组）而非 `exec`（shell 字符串）
- **超时保护**: 10-60s（diff/push 30-60s，其他 10s）

## 性能优化

- **并发执行**: `Promise.all([git(...), git(...)])` 比 串行快 2x
- **智能展开**: 跳过 `node_modules`/`.git`/`dist`，限制深度 3 层、500 文件
- **缓冲区保护**: `maxBuffer: 10MB` 防止大文件 diff 内存溢出

## WebSocket API

```typescript
// 请求
{ type: 'git.status', workspace: '/path/to/project' }

// 响应
{ type: 'git.status', result: { branch: 'main', files: [...], ahead: 0, behind: 0 } }
```

**异步转换**: `handleGitStatus(workspace).then(result => ws.send(...)).catch(err => ws.send(...))`
