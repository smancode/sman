# server/web-access/ — Chrome CDP Browser Automation

## Purpose
Chrome DevTools Protocol (CDP) engine for browser automation. Exposed as an MCP server with 9 tools: navigate, snapshot, screenshot, click, fill, press_key, evaluate, list_tabs, close_tab.

## Key Files

| File | Purpose |
|---|---|
| `web-access-service.ts` | High-level service layer (coordinates engines) |
| `cdp-engine.ts` | CDP connection management, DOM stability detection, page snapshots |
| `browser-engine.ts` | Abstract browser engine interface |
| `mcp-server.ts` | MCP server exposing 9 tools to Claude Agent SDK |
| `chrome-sites.ts` | Auto-discover enterprise sites from Chrome bookmarks/history |
| `url-experience-store.ts` | SQLite: learned URL interaction patterns |
| `index.ts` | Module exports |

## CDP Flow
1. Chrome launched with `--remote-debugging-port=9222`
2. `cdp-engine.ts` connects via WebSocket to Chrome's CDP
3. Commands translated to CDP `Target.sendMessageToTarget`
4. `web-access-service.ts` wraps low-level CDP with stability detection
5. `mcp-server.ts` exposes tools as MCP JSON-RPC endpoints

## Dependencies
- `server/mcp-config.ts` wires this as an MCP server when enabled
- Used by Claude Agent SDK tools during autonomous browsing tasks
