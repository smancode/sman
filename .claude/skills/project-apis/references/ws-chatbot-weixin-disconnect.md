# WS chatbot.weixin.disconnect

Disconnect the WeChat personal bot.

**Signature:** `chatbot.weixin.disconnect` → `chatbot.weixin.status` with `status: 'idle'`

## Business Flow

Calls `weixinConnection.disconnect()`. Stops the WeChat protocol listener.

## Source

`server/index.ts` — `case 'chatbot.weixin.disconnect'`
