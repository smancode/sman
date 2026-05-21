# Hub 协作服务器

## 概述

Hub 是企业级 Sman 协作服务器，提供多 Agent 协作、任务分发、技能自动更新触发、广播消息等功能。

## 连接方式

### HTTP 心跳连接

- **端点**: `POST {serverBaseUrl}/api/report`
- **用途**: 心跳上报、活跃会话统计、工作区列表上报
- **频率**: 每 15 分钟
- **超时**: 5 秒
- **认证**: PSK 加密 payload (`buildEncryptedRequest`)

### WebSocket 长连接

- **端点**: `WS {serverBaseUrl}/ws`
- **用途**: 实时任务分发、Agent 注册、房间管理、心跳检测
- **重连**: 指数退避 (1s → 30s 最大)
- **心跳**: 每 30 秒发送 `agent.heartbeat`
- **消息类型**: `auth.psk`, `agent.register`, `task.*`, `room.list`

### 广播查询
- **端点**: `POST {serverBaseUrl}/api/broadcasts`
- **用途**: 拉取服务器广播消息（系统公告、更新通知等）
- **频率**: 每 15 分钟，**超时**: 5 秒，**认证**: PSK 加密

### 成就排行榜
- **上传端点**: `POST {serverBaseUrl}/api/achievement-report`
- **查询端点**: `GET {serverBaseUrl}/api/achievement-leaderboard?dimension={dimension}`
- **用途**: 上传本地成就数据 + 查询全局排行榜（支持按维度排序）
- **频率**: 每小时自动上传，用户手动查询，**超时**: 10 秒，**认证**: PSK 加密

### IM 消息同步
- **消息类型**: `im.message`, `im.agent_delta`
- **用途**: 跨设备同步 IM 消息（群聊、DM、Agent 输出）
- **频率**: 实时转发，**超时**: 无（fire-and-forget）
- **认证**: Hub WS 隧道已认证

## 配置来源
| 配置项 | 环境变量 | Settings 路径 | 说明 |
|--------|----------|---------------|------|
| `serverBaseUrl` | `SMAN_HUB_URL` | `settings.hub.serverBaseUrl` | Hub 服务器地址 |
| `fallbackUrl` | - | `settings.hub.fallbackUrl` | 内网备用地址 |
| `enabled` | - | `settings.hub.enabled` | 是否启用 Hub |
| `psk` | 文件: `~/.sman/hub.key` | - | 预共享密钥（加密通信） |

## 调用位置

### HTTP 心跳
- `server/hub/client.ts` → `HubClient.reportHeartbeat()`
- 上报内容: clientId, version, hostname, ip, port, activeSessions, workspaces

### WebSocket 连接
- `server/hub/hub-ws-client.ts` → `HubWsClient`
- 调用时机: `initHub()` 时建立长连接
- 支持消息类型: 认证、Agent 注册、心跳、任务分发

### 广播查询
- `server/hub/client.ts` → `HubClient.fetchBroadcasts()`
- 存储位置: `BroadcastStore` (内存 + SQLite 持久化)

### 成就排行榜
- `server/achievement-engine.ts` → `uploadToLeaderboard()` + `fetchLeaderboard()`
- 上传内容: agentId, agentName, totalPoints, totalUnlocked, level, tierCounts, dimensionScores
- 查询参数: dimension (可选，支持按维度排序：total_sessions, total_messages, total_tokens 等)

### IM 消息转发
- `server/im/im-ws-handler.ts` → `sendToHub()`
- 转发消息类型: `im.message` (文本消息), `im.agent_delta` (Agent 流式输出)
- 调用时机: 用户发送消息、Agent 输出增量
- Hub 广播: 所有连接的客户端收到 `im.*` 消息，实现跨设备同步

## 技能自动更新触发

Hub 服务器可下发 `commands` 数组，触发本地 MCP `skill-auto-updater` 技能:

- 触发方式: `POST http://127.0.0.1:{port}/api/mcp/tools/trigger`
- 参数: `{ workspace, toolId: "skill-auto-updater" }`
- 认证: Bearer token (本地 `GET /api/auth/token` 获取)

## 加密通信

使用 PSK (Pre-Shared Key) 加密所有 HTTP 请求:

```typescript
// server/hub/crypto.ts
buildEncryptedRequest(payload) → { payload, timestamp, pskVersion }
decrypt(encryptedPayload) → original data
```

PSK 存储位置: `~/.sman/hub.key` (格式: `{psk: string, version: number}`)

## 错误处理

- **超时**: HTTP 请求 5 秒超时，失败不阻塞主流程
- **重连**: WebSocket 断开后自动重连（指数退避）
- **降级**: Hub 连接失败不影响本地功能
