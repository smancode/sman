# WS chatbot.weixin.qr.poll

## Signature
```
WS message: { type: "chatbot.weixin.qr.poll", sessionKey: string }
```

## Business Flow
Polls for QR scan confirmation. Returns `{ type: "chatbot.weixin.qr.status", status, connected, message }`. Status values: wait, confirmed, error.

## Called Services
`weixinConnection.waitForLogin()`

## Source
`server/index.ts`
