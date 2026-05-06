# Technical — nasakim

> Last extracted: 2026-05-06T03:50:39.404Z

## WebSocket 客户端↔会话双向映射机制
<!-- hash: b7c8d9 -->
- `server/index.ts` 维护两个 Map：`clientToSessions`(WebSocket→Set<sessionId>) 和 `sessionToClients`(sessionId→Set<WebSocket>)
- 核心函数：subscribeClientToSession / unsubscribeClientFromSession / getSessionClients / sendToSessionClients
- 客户端断开时必须双向清理映射，防止内存泄漏和幽灵订阅
<!-- end: b7c8d9 -->