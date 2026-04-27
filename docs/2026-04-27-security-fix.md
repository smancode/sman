# Sman 安全扫描报告

**扫描日期**: 2026-04-27
**扫描目标**: ~/projects/sman (Node.js AI 工作站)
**运行实例**: http://localhost:5880
**扫描方式**: 代码审计 + npm audit + 在线 HTTP 扫描

---

## 漏洞统计

| 严重级别 | 数量 |
|---------|------|
| **Critical** | 5 |
| **High** | 10 |
| **Medium** | 11 |
| **Low** | 8 |

---

## Critical（严重）

### C-1. 命令注入 — `/api/open-external` 端点

**文件**: `server/index.ts`, 第 359 行

```typescript
const cmd = process.platform === 'darwin'
    ? `open "${targetUrl}"`
    : ...;
exec(cmd, (err) => { ... });
```

用 `exec()` 执行 shell 命令且**无需认证**（位于 auth 检查第 407 行之前）。构造恶意 URL 可执行任意 shell 命令。

**修复**: 使用 `execFile()` 替代 `exec()`，并将该端点移至认证检查之后。

### C-2. AI 生成代码直接执行

**文件**: `server/batch-engine.ts`, 第 174 行

```typescript
fs.writeFileSync(scriptPath, task.generatedCode);
const { stdout } = await execFileAsync(interpreter, [scriptPath], { ... });
```

LLM 生成的脚本代码写入临时文件后直接执行，无人工审核，且使用 `bypassPermissions` + `allowDangerouslySkipPermissions`。

**修复**: 增加执行前用户确认环节，考虑在沙箱/容器环境中执行，限制可用的解释器。

### C-3. shell.openExternal 无 URL 校验

**文件**: `electron/main.ts`, 第 111 行

```typescript
ipcMain.handle('shell:openExternal', (_event, url: string) => {
    shell.openExternal(url);
});
```

IPC handler 对传入 URL 无任何校验，可打开 `file:///`、`javascript:` 等危险协议 URI。

**修复**: 校验 URL scheme，仅允许 `http://` 和 `https://`:

```typescript
ipcMain.handle('shell:openExternal', (_event, url: string) => {
  if (!url.startsWith('http://') && !url.startsWith('https://')) return;
  shell.openExternal(url);
});
```

### C-4. 目录遍历 — `/api/directory/read` 端点

**文件**: `server/index.ts`, 第 414 行

```typescript
const dirPath = urlObj.searchParams.get('path');
const normalizedPath = path.normalize(dirPath);
const entries = fs.readdirSync(normalizedPath, { withFileTypes: true });
```

`path.normalize()` 不限制范围，可读取服务器任意目录内容（如 `/etc`、`/root`）。

**修复**: 使用 `path.resolve()` 后与允许的根目录做前缀比较:

```typescript
const resolved = path.resolve(dirPath);
if (!resolved.startsWith(allowedRoot)) {
  return res.end(JSON.stringify({ error: 'Path not allowed' }));
}
```

### C-5. 敏感凭据明文存储

**文件**: `server/settings-manager.ts`, 第 39 行

`~/.sman/config.json` 包含所有 API Key、Token、密钥，无加密，无文件权限限制（无 `chmod 0o600`）。

包含的敏感信息:
- `llm.apiKey` — LLM API 密钥
- `webSearch.braveApiKey` / `tavilyApiKey` / `bingApiKey` — 搜索 API 密钥
- `auth.token` — 认证令牌
- `chatbot.wecom.botId` / `secret` — 企业微信密钥
- `chatbot.feishu.appId` / `appSecret` — 飞书应用密钥
- `savedLlms[].apiKey` — 多个 LLM 配置中的 API 密钥

**修复**: 对敏感字段加密存储，设置文件权限为 `0o600`。

---

## High（高危）

### H-1. Token 永不过期/不轮换

**文件**: `server/settings-manager.ts`, 第 135 行

Token 一旦生成永不过期、永不轮换，没有 session timeout、token refresh 或 rotation 机制。

### H-2. `/api/auth/token` 信息泄露

**文件**: `server/index.ts`, 第 395 行

该端点将完整 auth token 以 JSON 明文返回，仅依赖 loopback IP 检查。多用户系统上其他本地用户可通过 `curl` 获取 token。

### H-3. WebSocket 可修改认证 Token

**文件**: `server/index.ts`, 第 687 行

已认证的 WebSocket 客户端可通过 `settings.update` 消息任意修改 auth token，导致其他客户端被踢出。

### H-4. WebSocket 认证无暴力破解防护

**文件**: `server/index.ts`, 第 509 行

WebSocket `auth.verify` 无连接频率限制、无失败尝试计数或锁定。

### H-5. SSRF — `web_fetch` 无 URL 限制

**文件**: `server/web-search/mcp-server.ts`, 第 91 行

`web_fetch` 工具接受任意 URL，无内网 IP 过滤。可请求 `http://169.254.169.254/`（AWS 元数据）或 `http://127.0.0.1:5880/api/auth/token`（获取 token）。

### H-6. SSRF — 用户配置的 `baseUrl` 直接用于 fetch

**文件**: 多处（knowledge-extractor.ts、user-profile.ts、stardom-bridge.ts 等 6 个文件）

用户通过 `settings.update` 可设置任意 `baseUrl`，服务端会用此 URL 发起 HTTP 请求并附带 API Key。

### H-7. 静态文件路径遍历

**文件**: `server/index.ts`, 第 464 行

```typescript
let filePath = path.join(distDir, urlPath === '/' ? 'index.html' : urlPath);
```

URL 路径直接拼接到文件系统路径，可能读取 dist 目录之外的文件。

### H-8. Stardom WebSocket 完全无认证

**文件**: `stardom/src/index.ts`, 第 99 行

Stardom 服务器（端口 5890）的 WebSocket 连接完全没有认证，任何人都可以冒充 Agent 执行任务。

### H-9. Token 明文存储于 localStorage

**文件**: `src/stores/ws-connection.ts`, 第 90 行

```typescript
localStorage.setItem('sman-backend-token', data.token);
```

一旦存在 XSS 漏洞，Token 即被窃取。

### H-10. 服务器列表（含 Token）明文存储于 localStorage

**文件**: `src/features/settings/BackendSettings.tsx`, 第 19 行

所有远程服务器的认证 Token 作为 JSON 数组存储在 `sman-servers` 键中。

---

## Medium（中危）

### M-1. 缺少 Content Security Policy (CSP)

`index.html` 和 Electron 主进程中均未配置 CSP。XSS 漏洞可被利用加载任意外部资源。

### M-2. CORS 配置可通过环境变量扩展为不安全值

**文件**: `server/index.ts`, 第 310 行

`CORS_ORIGINS` 环境变量允许添加任意 Origin，无验证。

### M-3. WebSocket Origin 验证允许无 Origin 的连接

**文件**: `server/index.ts`, 第 490 行

非浏览器客户端不发送 `origin` 头，可绕过 Origin 检查。

### M-4. `env_vars` 明文存储在 SQLite

**文件**: `server/batch-store.ts`, 第 62 行

批量任务的环境变量以明文 JSON 存储。代码中有 AES-256-GCM 加密的 TODO，但尚未实现。

### M-5. `config.json` 无文件权限限制

**文件**: `server/settings-manager.ts`, 第 48 行

`fs.writeFileSync()` 写入时未调用 `fs.chmodSync(path, 0o600)`。微信的存储文件做了权限限制，但配置文件反而没有。

### M-6. WebSocket 消息缺乏类型验证

**文件**: `server/index.ts`, 第 520-1301 行

`workspace` 字段作为文件系统路径在多处使用，但未校验路径合法性。

### M-7. Brainstorm 服务器 WebSocket 无认证

**文件**: `plugins/superpowers/lib/brainstorm-server/index.js`, 第 69 行

接受任意 WebSocket 连接，可注入大量数据造成 DoS。

### M-8. HTTP 明文加载前端

**文件**: `electron/main.ts`, 第 18 行

生产模式下 Electron 从 `http://localhost:5880` 加载前端，WebSocket 也使用明文 `ws://`。

### M-9. `innerHTML` 使用

**文件**: `src/features/chat/streamdown-components.tsx`, 第 97 行

通过 `innerHTML` 设置 SVG 内容。当前为硬编码静态字符串，但模式本身危险。

### M-10. `shell.openExternal` 的 URL 校验仅检查前缀

**文件**: `electron/main.ts`, 第 44 行

`setWindowOpenHandler` 只检查 `http://` 前缀，无进一步校验。

### M-11. `JSON.parse` 解析 localStorage 数据缺少错误边界

**文件**: `src/components/SessionTree.tsx`, 第 51 行

`localStorage` 数据被篡改可导致应用崩溃（DoS）。

---

## Low（低危）

### L-1. 所有 API 端点均无速率限制

未发现任何 HTTP/WebSocket 端点的速率限制实现。

### L-2. 错误信息泄露内部细节

**文件**: `server/index.ts`, 第 441 行

多处 catch 块将原始错误信息返回给客户端，可能包含文件路径、数据库错误等。

### L-3. Stardom HTTP API 缺乏认证

**文件**: `stardom/src/index.ts`, 第 61 行

`/api/leaderboard`、`/api/capabilities/search` 等端点均无认证。

### L-4. Brainstorm 服务器 `execSync` 使用

**文件**: `plugins/superpowers/lib/skills-core.js`, 第 151 行

使用 `execSync` 和 `&&` 连接多个命令的模式。

### L-5. `window.__sman_gitBranchRefresh` 暴露于全局作用域

**文件**: `src/components/layout/Titlebar.tsx`, 第 52 行

暴露了内部实现细节。

### L-6. `Math.random()` 用于 ID 生成

**文件**: `src/stores/stardom.ts`, 第 139 行

非密码学安全的随机数生成器。

### L-7. `/api/health` 端点无认证

**文件**: `server/index.ts`, 第 352 行

暴露服务器时间戳，风险较低。

### L-8. CORS 仅允许 GET 方法

**文件**: `server/index.ts`, 第 324 行

限制跨域攻击面，这是正向发现。

---

## 依赖漏洞

| 级别 | 包 | 问题 | 修复版本 |
|------|---|------|---------|
| Critical | `protobufjs` < 7.5.5 | 任意代码执行 | >= 7.5.5 |
| High | `tar` < 7.5.8 | 路径遍历（3 个 CVE） | >= 7.5.8 |
| High | `electron` < 39.8.1 | Use-after-free（4 个 CVE） | >= 39.8.1 |
| High | `lodash` < 4.17.21 | 原型污染 | >= 4.17.21 |

---

## 在线扫描发现

| 检查项 | 结果 |
|-------|------|
| `/ws` 端点 | 暴露（返回 426 Upgrade Required） |
| X-Content-Type-Options | **缺失** |
| X-Frame-Options | **缺失** |
| Content-Security-Policy | **缺失** |
| Strict-Transport-Security | **缺失** |
| CORS 限制 | 无跨域限制（未返回 Access-Control-Allow-Origin） |
| `/.env` | 404（安全） |
| `/.git/config` | 404（安全） |
| `/admin` | 404（安全） |

---

## 安全正向做法（已做好的）

- Token 使用 `crypto.randomBytes(32)` 生成（256 位熵）
- 默认绑定 `127.0.0.1`
- Electron `contextIsolation: true` + `nodeIntegration: false`
- Preload 使用 `contextBridge.exposeInMainWorld` 暴露有限 API
- WebSocket 未认证连接 5 秒超时断开
- 外部链接使用 `rel="noopener noreferrer"`
- SQLite 启用 WAL 模式和外键约束
- 未发现 `dangerouslySetInnerHTML` 使用
- 未发现硬编码的 API 密钥/密码

---

## 最紧急修复建议（Top 5）

1. **`/api/open-external` 命令注入** → 用 `execFile()` 替换 `exec()`，增加认证
2. **BatchEngine AI 代码执行** → 增加执行前用户确认，沙箱环境执行
3. **`shell.openExternal` URL 校验** → 白名单仅允许 `http://` 和 `https://`
4. **`/api/directory/read` 路径遍历** → 限制在工作区目录范围内
5. **凭据加密存储** → 对 `config.json` 敏感字段加密，设置 `0o600` 权限
