# WeCom Bot

## Overview
Enterprise WeChat (WeCom/WeChat Work) AI bot. Receives user messages via WebSocket long-connection and streams Claude responses back.

## Call Method
Native WebSocket (ws npm package) to wss://openws.work.weixin.qq.com. JSON RPC-like protocol over WebSocket.

## Config Source
- chatbot.wecom.botId - WeCom bot ID
- chatbot.wecom.secret - WeCom bot secret

Source: ~/.sman/config.json

## Call Locations
- server/chatbot/wecom-bot-connection.ts - Full WebSocket lifecycle (connect, heartbeat, subscribe, message routing, reconnect)
- server/chatbot/wecom-media.ts - Media download via Node http/https + AES-256-CBC decryption (per-message aeskey)
- server/chatbot/chatbot-session-manager.ts - Routes messages to Claude, sends aibot_respond_msg with msgtype: stream

## Endpoints
- WebSocket: wss://openws.work.weixin.qq.com (long-connection WebSocket)
- Media download: https://qyapi.weixin.qq.com (REST, access token)

## Purpose
WeCom enterprise bot. Supports text, image, voice, file, video, mixed messages. Streams Claude replies with 2-second throttle.
