# WeCom Bot

## Call Method
Raw `WebSocket` (npm `ws`) — outgoing connection to WeCom's official WebSocket gateway. HTTP requests use `fetch()` for media downloads.

## Config Source
- `chatbot.wecom.botId` and `chatbot.wecom.secret` in `~/.sman/config.json`

## Call Locations
- `server/chatbot/wecom-bot-connection.ts` — WS connection (line 48), media download
- `server/chatbot/wecom-media.ts` — HTTP/HTTPS media fetch (lines 23-24)
- `server/index.ts` — initialization (lines 132-137)

## Endpoints
- WS: `wss://openws.work.weixin.qq.com` (line 13 of wecom-bot-connection.ts)
- Media: `https://qyapi.weixin.qq.com` (called via fetch in wecom-media.ts)

## Purpose
Enterprise WeChat (WeCom) bot — receives messages, sends replies, streams responses.
