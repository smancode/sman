# IM 消息可靠性实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development to implement this plan.

**Goal:** 补齐 IM 消息可靠性的 4 个核心缺口：心跳检测、断线同步、发送队列、消息合并。

**Architecture:** 在现有 ws 原生 API 上实现 TailChat 的可靠性模式。不引入新依赖。

**Tech Stack:** Node.js ws 库 ping/pong, Zustand store, React Query

---

## Task 1: 服务端心跳检测 + 死连接清理

**Files:**
- Modify: `server/index.ts` (WS connection section)

**Why:** 半开 TCP 连接永远不清理，广播时给死连接发消息浪费资源且不报错。

- [ ] **Step 1: 在 wss.on('connection') 里启用 ws 心跳**

```ts
// 在 ws.on('close', ...) 之前添加
ws.isAlive = true;
ws.on('pong', () => { ws.isAlive = true; });
```

- [ ] **Step 2: 添加全局心跳定时器，在 imModule.setBroadcastFn 之前**

```ts
// Heartbeat: every 30s ping all clients, terminate non-responsive
const HEARTBEAT_INTERVAL = 30_000;
setInterval(() => {
  for (const client of authenticatedClients) {
    const ws = client as any;
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_INTERVAL);
```

- [ ] **Step 3: auth.verify 时初始化 isAlive**

在 `authenticatedClients.add(ws)` 之后添加 `ws.isAlive = true;`

- [ ] **Step 4: 验证**

编译检查: `npx tsc --noEmit`

- [ ] **Step 5: Commit**

```bash
git add server/index.ts
git commit -m "feat(im): add WebSocket heartbeat to detect dead connections"
```

---

## Task 2: 客户端断线重连后消息同步

**Files:**
- Modify: `src/stores/im.ts` (registerIMListeners + reconnect sync)
- Modify: `src/stores/ws-connection.ts` (emit reconnect event)

**Why:** 重连后错过的消息永久丢失，用户无感知。

- [ ] **Step 1: 在 IM store 添加 per-room 最后消息时间戳追踪**

在 IMStore interface 中添加:
```ts
roomLastMsgTimestamp: Map<string, number>;
```

在 `addMessage` 中更新: 每次收到消息时记录 `max(existing, msg.timestamp)` 到 `roomLastMsgTimestamp`。

- [ ] **Step 2: 添加 syncAfterReconnect 方法到 IMStore**

```ts
syncAfterReconnect: () => {
  const client = getWsClient();
  if (!client?.connected) return;
  const selectedRoomId = get().selectedRoomId;
  if (!selectedRoomId) return;
  const lastTs = get().roomLastMsgTimestamp.get(selectedRoomId) || 0;
  client.send({ type: 'im.sync', roomId: selectedRoomId, afterTimestamp: lastTs });
}
```

- [ ] **Step 3: 在 registerIMListeners 里监听 im.sync 响应**

```ts
unsubs.push(wrapHandler(client, 'im.sync', (msg) => {
  const raw = msg as Record<string, unknown>;
  const payload = (raw.data ?? raw) as Record<string, unknown>;
  const messages = Array.isArray(payload.messages) ? payload.messages : [];
  for (const m of messages) {
    const imMsg = parseIMMessage(m);
    if (imMsg.id) get().addMessage(imMsg);
  }
}));
```

- [ ] **Step 4: 在 ws-connection.ts 的 registerListeners 里监听 connected 事件，触发 IM 同步**

在 `client.on('connected', ...)` 回调中调用 `useIMStore.getState().syncAfterReconnect()`

- [ ] **Step 5: 验证** — 编译检查

- [ ] **Step 6: Commit**

---

## Task 3: 客户端发送队列（断线缓存）

**Files:**
- Modify: `src/lib/ws-client.ts`

**Why:** 断线期间用户发的消息直接丢失（send 抛异常）。

- [ ] **Step 1: 在 WsClient 类中添加 outbox**

```ts
private outbox: string[] = [];
```

- [ ] **Step 2: 修改 send 方法 — 未连接时缓存**

```ts
send(data: object): void {
  const json = JSON.stringify(data);
  if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
    this.outbox.push(json);
    return;
  }
  this.ws.send(json);
}
```

- [ ] **Step 3: 在 onopen 或 auth.verified 后 flush outbox**

```ts
// 在 onopen 的 auth.verified 处理后
while (this.outbox.length > 0) {
  const msg = this.outbox.shift()!;
  this.ws!.send(msg);
}
```

- [ ] **Step 4: 验证** — 编译检查

- [ ] **Step 5: Commit**

---

## Task 4: 历史消息与实时消息统一合并

**Files:**
- Modify: `src/features/im/IMEntry.tsx`
- Modify: `src/features/im/ChatWindow.tsx` (if used)

**Why:** 当前 fetchedMessages（React Query）和 realtimeMessages（Zustand store）两套缓存，合并逻辑有丢失可能。

- [ ] **Step 1: 简化 IMEntry 的 messages 合并**

将现有的 `if (realtime.length === 0) return fetchedMessages;` 逻辑改为：
- 始终合并 fetched + realtime 到一个 Map<id, msg>
- 按 timestamp 排序
- 去掉 `fetchedMessages.length === 0` 的 early return

- [ ] **Step 2: 确保服务端消息的 timestamp 可信**

服务端 `handleSend` 用 `Date.now()` 作为 timestamp，客户端排序信任服务端时间戳。乐观消息用客户端时间，被服务端消息替换后自动用服务端时间。

- [ ] **Step 3: 验证** — 编译检查

- [ ] **Step 4: Commit**
