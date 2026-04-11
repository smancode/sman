# Feishu Bot

## Overview
Feishu (Lark) AI bot. Receives Feishu messages via WebSocket long connection and streams Claude responses back via im.message.create.

## Call Method
SDK - @larksuiteoapi/node-sdk npm package. Uses WSClient for long connection + REST HTTP for file download.

## Config Source
- chatbot.feishu.appId - Feishu app ID
- chatbot.feishu.appSecret - Feishu app secret

Source: ~/.sman/config.json

## Call Locations
- server/chatbot/feishu-bot-connection.ts - Lark.Client + Lark.WSClient for event dispatch
- server/chatbot/chatbot-session-manager.ts - Routes messages to Claude, sends responses via im.message.create

## Endpoints
- WebSocket: WSClient (Feishu SDK handles transport)
- REST: https://open.feishu.cn/open-apis/ (file downloads, message sending)

## Purpose
Feishu/Lark bot. Supports text, image, audio, file, video messages. Streams responses with 3-second throttle.
