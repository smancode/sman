# 后端安全加固设计

## 背景

Sman 后端当前零认证，任何人能网络到达 5880 端口就能使用全部功能，包括读取 API Key、查看会话历史、修改配置、执行定时/批量任务。

当前是本地桌面应用，后续会部署到服务器，支持远程浏览器/桌面客户端连接。

## 修复范围

| 修复项 | 说明 |
|-------|------|
| 网络绑定 | `server.listen(PORT, '127.0.0.1')`，仅本机访问 |
| Token 认证 | 固定 Token，存 config.json；WebSocket 和 HTTP API 均需认证 |
| CORS | 只允许 localhost 和 127.0.0.1 来源，防恶意网站偷连 |
| 前端设置页 | 新增后端 URL + Token 配置 |

目录浏览 API `/api/directory/read` 不加路径白名单，由 Token 认证保护。

---

## 1. 网络绑定

**文件**: `server/index.ts`

```typescript
// Before
server.listen(PORT, () => { ... });

// After
const HOST = process.env.HOST || '127.0.0.1';
server.listen(PORT, HOST, () => { ... });
```

默认绑定 127.0.0.1（仅本机）。部署到服务器时设置 `HOST=0.0.0.0` 开放远程访问。

---

## 2. Token 生成与存储

**文件**: `server/settings-manager.ts`, `server/types.ts`

### 2.1 Config 类型扩展

```typescript
// SmanConfig 新增
auth: {
  token: string;
}
```

### 2.2 生成策略

- 后端启动时检查 `config.json` 中 `auth.token`
- 不存在 → 生成 64 字符随机 hex（`crypto.randomBytes(32).toString('hex')`）
- 已存在 → 直接使用，不重新生成
- 用户可通过设置页手动更换 Token

### 2.3 获取 Token 的 API

```
GET /api/auth/token    （未认证，仅限 127.0.0.1 访问）
Response: { token: "xxx" }
```

本机前端首次启动时调用此 API 获取 Token。远程前端不支持未认证获取，必须在设置页手动输入。

---

## 3. Token 认证流程

### 3.1 WebSocket 认证

```
客户端连接
  ↓
5 秒内必须发送: { type: 'auth.verify', token: 'xxx' }
  ↓
验证通过 → 加入 clients 集合，回复 { type: 'auth.verified' }
验证失败 → 回复 { type: 'auth.failed' }，断开连接
超时未发送 → 断开连接
```

认证前的其他消息类型一律拒绝，返回 `{ type: 'error', error: 'Authentication required' }`。

### 3.2 HTTP API 认证

```
Authorization: Bearer xxx
```

- `/api/health` — 不需要认证（健康检查）
- `/api/auth/token` — 不需要认证（仅限 127.0.0.1 访问）
- 其他所有 HTTP API — 需要 Bearer Token

---

## 4. CORS

### 4.1 HTTP CORS

所有 HTTP 响应添加：

```
Access-Control-Allow-Origin: http://localhost:5880 (或 5881)
Access-Control-Allow-Headers: Authorization, Content-Type
```

生产模式允许 `http://localhost:5880`，开发模式允许 `http://localhost:5881`。

### 4.2 WebSocket Origin 校验

WebSocketServer 的 `verifyClient` 回调校验 Origin Header：

```typescript
const wss = new WebSocketServer({
  server,
  path: '/ws',
  verifyClient: (info, callback) => {
    const origin = info.origin || info.req.headers.origin;
    const allowed = ['http://localhost:5880', 'http://localhost:5881'];
    if (!origin || allowed.includes(origin)) {
      callback(true);
    } else {
      callback(false, 403, 'Forbidden');
    }
  }
});
```

---

## 5. 前端变更

### 5.1 设置页

设置页新增"后端配置"区域：

- **后端 URL** — 默认 `ws://localhost:5880/ws`，远程场景填服务器地址
- **Token** — 本机模式自动从 `/api/auth/token` 获取并填入；远程模式手动输入

### 5.2 WsClient 变更

**文件**: `src/lib/ws-client.ts`

- 连接成功后自动发送 `{ type: 'auth.verify', token: 'xxx' }`
- 收到 `auth.verified` 后才触发 `connected` 事件
- 收到 `auth.failed` 后触发 `authFailed` 事件

### 5.3 HTTP 请求变更

所有 HTTP 请求（如 `/api/directory/read`）自动携带 `Authorization: Bearer xxx` Header。

---

## 6. 受影响文件

| 文件 | 变更 |
|------|------|
| `server/index.ts` | 绑定 127.0.0.1、Token 认证中间件、CORS、WebSocket verifyClient |
| `server/settings-manager.ts` | config 类型增加 `auth.token`，启动时生成逻辑 |
| `server/types.ts` | SmanConfig 增加 auth 字段 |
| `src/lib/ws-client.ts` | 连接后发 auth.verify，认证后才算连接成功 |
| `src/stores/settings.ts` | 新增后端 URL + Token 状态管理 |
| `src/components/SettingsPage.tsx` | 新增后端配置 UI（URL + Token） |
| `src/lib/http-client.ts`（或相关） | HTTP 请求携带 Authorization Header |

---

## 7. 验收标准

1. 后端默认绑定 127.0.0.1，可通过 `HOST` 环境变量覆盖
2. 无 Token 的 WebSocket 连接 5 秒后被断开
3. 无 Token 的 HTTP API 请求返回 401
4. `/api/health` 不需要认证
5. `/api/auth/token` 仅限 127.0.0.1 访问
6. 恶意网站（非 localhost Origin）无法建立 WebSocket 连接
7. 本机前端首次启动能自动获取 Token
8. 远程前端通过设置页配置 Token 后能正常连接
9. Token 重启后不变，存储在 config.json
