# Chatbot Bidirectional Sync Design

> 桌面端 → Chatbot 平台双向消息同步

## 1. 目标

在 Electron 桌面端的 WeCom/Feishu 会话里发消息时，用户消息和 Claude 应答同步推送到对应的 Chatbot 平台，使手机端（WeCom/Feishu）能看到完整的聊天记录。

## 2. 核心方案

在 `server/index.ts` 的 `chat.send` handler 中，检测该 session 是否属于 chatbot 会话，如果是，在 Claude 流式应答的同时，同步推送到对应平台。

### 数据流

```
桌面端 chat.send { sessionId, content }
  → ChatbotStore.findBySessionId(sessionId)
  → 得到 { userKey, platform, chatId? }
  → platform === 'wecom' → WeComBotConnection 发送
  → platform === 'feishu' → FeishuBotConnection 发送
```

### 同步流程

1. 桌面端发 `chat.send`
2. 查 `findBySessionId(sessionId)` 判断是否为 chatbot 会话
3. 如果不是 → 正常流程，不同步
4. 如果是：
   a. 创建 `ChatResponseSender`（流式推送）
   b. 先推用户消息到平台（`finish` 方式，一次性发）
   c. 调用 `ClaudeSessionManager.sendMessage` 正常获取 Claude 应答
   d. 在流式回调中同步推送到平台
   e. Claude 应答完成后，平台也推送完成

### 防回声机制

两条消息路径天然不重叠：
- WeCom → Sman：`ChatbotSessionManager.handleMessage` → `sendMessageForChatbot`
- 桌面端 → Sman：`chat.send` handler → `sendMessage`

无回声循环风险。

## 3. 改动文件与接口

### 3.1 `server/chatbot/chatbot-store.ts`

新增方法：

```typescript
findBySessionId(sessionId: string): { userKey: string; workspace: string } | null
```

SQL: `SELECT user_key, workspace FROM chatbot_sessions WHERE session_id = ?`

### 3.2 `server/chatbot/wecom-bot-connection.ts`

暴露发送能力：

```typescript
sendTextMessage(content: string): void
createExternalSender(): ChatResponseSender
```

### 3.3 `server/chatbot/feishu-bot-connection.ts`

同理：

```typescript
sendTextMessage(chatId: string, content: string): Promise<void>
createExternalSender(chatId: string): ChatResponseSender
```

### 3.4 `server/chatbot/types.ts`

`IncomingMessage` 已有 `chatId` 字段。需要确保收到消息时持久化 `chatId`。

### 3.5 `server/chatbot/chatbot-store.ts` - 表结构变更

`chatbot_sessions` 表新增 `chat_id` 列：

```sql
ALTER TABLE chatbot_sessions ADD COLUMN chat_id TEXT;
```

WeCom 不需要 chatId（用 requestId 推送），但 Feishu 需要 chatId 才能发消息。

### 3.6 `server/chatbot/chatbot-session-manager.ts`

在 `handleMessage` 中收到消息时，将 `chatId` 存入 `chatbot_sessions`：

```typescript
this.store.updateChatId(userKey, workspace, msg.chatId);
```

`ChatbotStore` 新增：

```typescript
updateChatId(userKey: string, workspace: string, chatId: string): void
```

### 3.7 `server/claude-session.ts`

`sendMessage` 方法签名变更：

```typescript
async sendMessage(
  sessionId: string,
  content: string,
  wsSend: WsSend,
  onChunk?: (chunk: string) => void,  // 新增：流式内容回调
): Promise<void>
```

在现有 `chat.delta` 发送处，同时调用 `onChunk(delta.content)`。

### 3.8 `server/index.ts`

在 `chat.send` handler 中增加同步逻辑：

```typescript
case 'chat.send': {
  if (!msg.sessionId || !msg.content) throw new Error('Missing sessionId or content');

  // 检查是否需要同步到 chatbot 平台
  const chatbotInfo = chatbotStore.findBySessionId(msg.sessionId);
  let chatbotSender: ChatResponseSender | null = null;

  if (chatbotInfo) {
    const platform = chatbotInfo.userKey.split(':')[0];
    if (platform === 'wecom' && wecomConnection) {
      chatbotSender = wecomConnection.createExternalSender();
      wecomConnection.sendTextMessage(msg.content);
    } else if (platform === 'feishu' && feishuConnection) {
      // 需要 chatId
      const chatId = chatbotStore.findChatIdBySessionId(msg.sessionId);
      if (chatId) {
        chatbotSender = feishuConnection.createExternalSender(chatId);
        await feishuConnection.sendTextMessage(chatId, msg.content);
      }
    }
  }

  const wsSend = (d: string) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(d);
  };

  await sessionManager.sendMessage(
    msg.sessionId,
    msg.content,
    wsSend,
    chatbotSender ? (chunk: string) => chatbotSender!.sendChunk(chunk) : undefined,
  );

  // 完成
  if (chatbotSender) {
    // 需要拿到 fullContent 来 finish
    // ...
  }
  break;
}
```

## 4. 测试策略

### 4.1 单元测试

- `ChatbotStore.findBySessionId` — 正常查询、无结果返回 null
- `ChatbotStore.updateChatId` — 更新 chat_id
- `WeComBotConnection.sendTextMessage` — 正确构造发送命令
- `WeComBotConnection.createExternalSender` — 返回有效的 ChatResponseSender
- `FeishuBotConnection.sendTextMessage` — 调用 API 发送
- `FeishuBotConnection.createExternalSender` — 返回有效的 ChatResponseSender

### 4.2 集成测试

- 桌面端发消息 → WeCom 收到用户消息 + Claude 应答
- 桌面端发消息 → Feishu 收到用户消息 + Claude 应答
- 非 chatbot 会话 → 不触发同步
- WeCom 发消息 → 不触发同步到 WeCom（防回声）

## 5. 影响范围

- 不影响现有的 WeCom → Sman 单向流程
- 不影响非 chatbot 会话的桌面端聊天
- `sendMessage` 的 `onChunk` 参数是可选的，不影响其他调用方（cron、chatbot）

## 6. Review 发现与修正（未实现，待后续落地）

### 6.1 WeCom 主动推送限制（关键）

**问题**：当前 WeCom AI Bot 使用 WebSocket 长连接（`wss://openws.work.weixin.qq.com`），`aibot_respond_msg` 命令是**被动回复**，必须有 requestId 才能响应。无法从桌面端主动推送消息到 WeCom。

**解决方案**：WeCom AI Bot 支持配置**消息推送 Webhook URL**，通过 HTTP POST 实现主动消息。这是一个独立于 WebSocket 的机制。

**需要的改动**：
1. 设置页面新增 WeCom Webhook URL 配置项
2. 新增 HTTP endpoint 接收 Webhook 回调验证
3. `WeComBotConnection` 新增 `sendProactiveMessage()` 方法，通过 HTTP POST 发送

**参考**：AstrBot 文档明确说明"默认情况下只能被动回复，主动推送需配置 Webhook URL"。

### 6.2 Feishu 无此限制

Feishu 通过 `client.im.message.create` + `chatId` 原生支持主动消息，无需额外配置。

### 6.3 `sendMessage` 签名优化

**问题**：直接给 `sendMessage` 加 `onChunk` 参数会让 `ClaudeSessionManager` 感知 chatbot 逻辑，违反单一职责。

**替代方案**：使用 composite `wsSend` wrapper，在外层拦截 `chat.delta` 类型消息并转发给 chatbot sender：

```typescript
const chatbotWsSend = chatbotSender
  ? (d: string) => {
      wsSend(d); // 正常发给前端
      const parsed = JSON.parse(d);
      if (parsed.type === 'chat.delta' && parsed.deltaType === 'text') {
        chatbotSender!.sendChunk(parsed.content);
      }
    }
  : wsSend;
```

这样 `sendMessage` 签名不变，chatbot 同步逻辑完全在 `index.ts` 层处理。

### 6.4 `findBySessionId` 返回类型扩展

应一次查询返回所有需要的信息，避免多次 DB 查询：

```typescript
findBySessionId(sessionId: string): {
  userKey: string;
  workspace: string;
  chatId: string | null;
} | null
```

SQL: `SELECT user_key, workspace, chat_id FROM chatbot_sessions WHERE session_id = ?`

### 6.5 Migration 安全策略

`ALTER TABLE` 需要幂等处理：

```typescript
const columns = this.db.prepare('PRAGMA table_info(chatbot_sessions)').all() as Array<{ name: string }>;
if (!columns.some(c => c.name === 'chat_id')) {
  this.db.prepare('ALTER TABLE chatbot_sessions ADD COLUMN chat_id TEXT').run();
}
```

## 7. 状态

**方案设计完成，未实现。** 核心阻碍是 WeCom 需要额外配置 Webhook URL 才能实现主动推送。Feishu 可直接实现。
