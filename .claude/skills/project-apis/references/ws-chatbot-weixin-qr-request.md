# WS chatbot.weixin.qr.request

## Signature
```
WS message: { type: "chatbot.weixin.qr.request" }
```

## Business Flow
Requests a QR code for WeChat personal bot login. Returns `{ type: "chatbot.weixin.qr.response", qrcodeUrl, sessionKey }`. Requires WeChat bot to be enabled in settings.

## Called Services
`weixinConnection.startQRLogin()`

## Source
`server/index.ts` — requires `weixinConnection` initialized (settings: chatbot.weixin.enabled)
