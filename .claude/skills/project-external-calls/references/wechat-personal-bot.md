# WeChat Personal Bot (iLink)

## Call Method
Native `fetch()` — HTTP long-polling to iLink Bot API. No WebSocket used.

## Config Source
- `chatbot.weixin.enabled` in `~/.sman/config.json`

## Call Locations
- `server/chatbot/weixin-bot-connection.ts` — monitor loop + QR login
- `server/chatbot/weixin-api.ts` — API helper functions (lines 67, 94, 144)

## Endpoints
- Base: `https://ilinkai.weixin.qq.com` (ILINK_BASE_URL constant)
- `/ilink/bot/get_bot_qrcode` — QR code generation
- `/ilink/bot/get_qrcode_status` — QR scan status check
- `/ilink/bot/getupdates` — long-poll for new messages

## Purpose
Personal WeChat account bot via iLink service — QR login, message receive/send.
