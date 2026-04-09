# Feishu Bot (飞书)

## Call Method
`@larksuiteoapi/node-sdk` — `WSClient` for long-connection + `Client` for REST API calls.

## Config Source
- `chatbot.feishu.appId` and `chatbot.feishu.appSecret` in `~/.sman/config.json`

## Call Locations
- `server/chatbot/feishu-bot-connection.ts` — WSClient start (line 31), Client init (line 22)
- `server/index.ts` — initialization (lines 142-147)

## Purpose
飞书 Bot — receives messages via WebSocket long-connection, sends replies, downloads attachments via Feishu REST API.
