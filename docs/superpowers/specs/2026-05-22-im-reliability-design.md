# IM 消息可靠性设计 Spec

## 目标

对齐 TailChat 的消息可靠性水平，在 Sman 本地 server + Hub 两层架构下实现完整的 IM 消息生命周期保障。

## 约束

- **不引入 Redis** — Sman 是单用户桌面端，Redis 过重。所有状态用内存 Map + SQLite。
- **架构不变** — 保持本地 server (Express + ws) + Hub (sman-server) 两层，不迁移到 Socket.IO。
- **参考 TailChat 模式** — 用 TailChat 的设计思路，但用 ws 原生 API 实现。

## 需求清单

### P0 — 必须修复（会丢消息/内存泄漏）

| ID | 需求 | TailChat 参考 | 实现方案 |
|----|------|--------------|---------|
| P0-1 | **心跳/死连接检测** | Socket.IO ping-pong 25s | 服务端每 30s `ws.ping()`，10s 无 pong 则 `ws.terminate()`；客户端监听 `ws.onclose` 触发重连 |
| P0-2 | **断线重连后消息同步** | 重连后清缓存+重新拉取 | 客户端重连后对当前 room 发 `im.sync(afterTimestamp: lastMessageTimestamp)`，合并去重 |
| P0-3 | **乐观消息超时清理** | 超时标记 sendFailed | 5 秒后 temp 消息自动移除，红色感叹号已实现 |

### P1 — 重要（体验问题）

| ID | 需求 | TailChat 参考 | 实现方案 |
|----|------|--------------|---------|
| P1-1 | **服务端消息排序** | MongoDB ObjectId 单调递增 | 服务端用 `crypto.randomUUID()` + `Date.now()` 已足够（单 server 不存在时钟偏移），但 broadcast 中消息自带服务端 timestamp，客户端排序信任服务端时间 |
| P1-2 | **发送队列（断线缓存）** | Socket.IO 内存队列 | 客户端 WsClient 增加 outbox: `string[]`，send 时如果未连接则 push 到 outbox，重连后 flush |
| P1-3 | **历史+实时消息统一合并** | 单一消息列表 + `_id` 去重 | IMEntry/ChatWindow 中 messages 合并逻辑改为：以 Map<id, msg> 去重，按 timestamp 排序，消除 fetched 和 realtime 两套缓存不一致问题 |
| P1-4 | **Room 订阅按 roomId 隔离（广播不串）** | Socket.IO room `io.to(roomId)` | 已实现：wsRoomSubs + imJoinRoom/imLeaveRoom + broadcastToRoom 只发给订阅者。需确保前端 selectRoom 时发 im.room.join |

### P2 — 可迭代

| ID | 需求 | 说明 |
|----|------|------|
| P2-1 | **分页加载** | useInfiniteQuery + 游标分页，加载更早的历史消息 |
| P2-2 | **序列号/缺口检测** | 服务端分配单调 seq，客户端检测缺口并补拉 |
| P2-3 | **已读回执** | per-room ack，显示未读数 |

## 不做

- 分布式架构（单用户桌面端）
- 高并发百万级（局域网场景）
- 加密传输（局域网 HTTPS 可延后）
- 引入 Redis
