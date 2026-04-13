# Feishu Bot

## Call Method
@larkSuiteoapi/node-sdk — official Feishu/Lark Node.js SDK (HTTP under the hood)

## Config Source
- ~/.sman/config.json — chatbot.feishu.appId, chatbot.feishu.appSecret

## Call Locations
| File | Purpose |
|------|---------|
| server/chatbot/feishu-bot-connection.ts | SDK event listener for messages, send replies |
| server/chatbot/chatbot-session-manager.ts | Message routing to Claude session |

## Purpose
Feishu (Lark) enterprise messaging bot — receives events via Feishu SDK and relays
messages to Claude for AI responses.
