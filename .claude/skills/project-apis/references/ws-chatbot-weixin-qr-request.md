# WS chatbot.weixin.qr.request

Request a QR code URL for WeChat personal account login.

**Signature:** `chatbot.weixin.qr.request` → `chatbot.weixin.qr.response` with QR URL

## Response

```json
{
  "qrcodeUrl": "data:image/png;base64,...",
  "sessionKey": "uuid-for-poll"
}
```

## Business Flow

Called by frontend to initiate WeChat QR login flow. The QR code is displayed as a data URI image. Client must then poll `chatbot.weixin.qr.poll` with the `sessionKey`.

## Source

`server/index.ts` — `case 'chatbot.weixin.qr.request'`
Calls: `weixinConnection.startQRLogin()` in `server/chatbot/weixin-bot-connection.ts`
