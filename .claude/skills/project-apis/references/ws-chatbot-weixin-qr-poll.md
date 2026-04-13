# WS chatbot.weixin.qr.poll

Poll the QR code scan status.

**Signature:** `chatbot.weixin.qr.poll` → `{ sessionKey: string }` → `chatbot.weixin.qr.status`

## Response

```json
{
  "status": "wait" | "confirmed" | "error",
  "connected": boolean,
  "message": "optional error message"
}
```

## Business Flow

Frontend polls this every few seconds after displaying the QR code. When `connected: true`, the WeChat bot is logged in and can receive messages.

## Source

`server/index.ts` — `case 'chatbot.weixin.qr.poll'`
Calls: `weixinConnection.waitForLogin()` in `server/chatbot/weixin-bot-connection.ts`
