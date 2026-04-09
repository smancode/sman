# WS chatbot.weixin.disconnect

## Signature
```
WS message: { type: "chatbot.weixin.disconnect" }
```

## Business Flow
Disconnects the WeChat personal bot. Sets status to `idle`. Returns `{ type: "chatbot.weixin.status", status: "idle" }`.

## Called Services
`weixinConnection.disconnect()`

## Source
`server/index.ts`
