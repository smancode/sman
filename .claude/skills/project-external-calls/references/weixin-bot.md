# WeChat Personal Bot (iLink)

## Call Method
Native fetch() HTTP calls — pure functional API client in server/chatbot/weixin-api.ts

## Config Source
- ~/.sman/config.json — no static credentials; auth token acquired via QR login
- Per-account token stored in ~/.sman/weixin/accounts/{accountId}.json

## Call Locations
| File | Purpose |
|------|---------|
| server/chatbot/weixin-api.ts | All iLink API calls: QR login, long-poll monitor, send |
| server/chatbot/weixin-bot-connection.ts | QR code generation, connection lifecycle, dispatch |
| server/chatbot/weixin-store.ts | Account data + session token storage |
| server/chatbot/chatbot-session-manager.ts | Message routing to Claude session |

## Purpose
Personal WeChat bot via iLink Bot API — user scans QR code to authenticate,
then bot long-polls for incoming messages and sends AI responses.
