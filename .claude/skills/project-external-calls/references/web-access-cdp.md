# Web Access (Chrome DevTools Protocol)

## Call Method
CDP WebSocket — connects to user's local Chrome browser via Chrome DevTools Protocol. Uses `ws` npm package for the CDP WebSocket connection.

## Config Source
- No external credentials needed. Reads Chrome's `DevToolsActivePort` file for debug port discovery.

## Call Locations
- `server/web-access/cdp-engine.ts` — CDP connection (line 424), port discovery (lines 72-88)
- `server/web-access/mcp-server.ts` — MCP tools registration (line 235)
- `server/index.ts` — engine initialization

## Purpose
Browser automation: navigate, snapshot, screenshot, click, fill, evaluate, tab management. Copies Chrome profile (cookies, login data) to `~/.sman/chrome-profile/` for session preservation.
