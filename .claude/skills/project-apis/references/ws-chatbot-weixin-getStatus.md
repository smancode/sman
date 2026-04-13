# WS chatbot.weixin.getStatus

Get current WeChat bot connection status.

**Signature:** `chatbot.weixin.getStatus` → `chatbot.weixin.status` with current status

## Response

```json
{ "status": "idle" | "connecting" | "connected" | "error" }
```

## Business Flow

Returns the in-memory connection state of `WeixinBotConnection`.

## Source

`server/index.ts` — `case 'chatbot.weixin.getStatus'`
