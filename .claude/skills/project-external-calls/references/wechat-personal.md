# WeChat Personal (iLink API)

## Overview
WeChat personal account bot via iLink API. QR-code login, long-polling for messages, HTTP API for sending.

## Call Method
Native fetch API (HTTP GET/POST), long-polling loop for message retrieval. Token stored in ~/.sman/weixin/.

## Config Source
- No static config - token acquired via QR code login flow
- Token + cursor persisted to filesystem: ~/.sman/weixin/

Source: ~/.sman/config.json (contains chatbot.weixin.* settings)

## Endpoints
- GET https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3 - QR code generation
- GET https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status - QR scan status polling
- POST https://ilinkai.weixin.qq.com/ilink/bot/getupdates - Long-polling message retrieval
- POST https://ilinkai.weixin.qq.com/ilink/bot/sendmessage - Send messages

## Call Locations
- server/chatbot/weixin-api.ts - Pure functional API client for all iLink endpoints
- server/chatbot/weixin-bot-connection.ts - QR login flow + long-poll monitor loop
- server/chatbot/weixin-store.ts - Persists bot token and cursor to filesystem
- server/chatbot/chatbot-session-manager.ts - Routes messages to Claude

## Purpose
WeChat personal account bot. Allows users to chat with Claude from their personal WeChat. Token persists across restarts.
