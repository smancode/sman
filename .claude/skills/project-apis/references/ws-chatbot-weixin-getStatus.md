# WS chatbot.weixin.getStatus

## Signature
```
WS message: { type: "chatbot.weixin.getStatus" }
```

## Business Flow
Returns current connection status of WeChat bot: `idle`, `connecting`, `connected`, `error`.

## Called Services
`weixinConnection.getConnectionStatus()`

## Source
`server/index.ts`
