# WeCom Bot

## Call Method
- WebSocket: wss://openws.work.weixin.qq.com via ws package (long-polling for inbound)
- Outbound media: separate HTTP fetch calls for image/audio/video upload

## Config Source
- ~/.sman/config.json — chatbot.wecom.botId, chatbot.wecom.secret

## Call Locations
| File | Purpose |
|------|---------|
| server/chatbot/wecom-bot-connection.ts | WebSocket connect, heartbeat, dispatch, reconnect |
| server/chatbot/wecom-media.ts | Media type mapping |
| server/chatbot/chatbot-session-manager.ts | Message routing to Claude session |

## Purpose
WeCom enterprise chat bot — connects via WeCom WebSocket to receive user messages
and relay Claude responses. Uses stream mode for streaming text replies.
