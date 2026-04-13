# Chrome CDP (Browser Automation)

## Call Method
WebSocket to local Chrome instance via ws package or native WebSocket

## Config Source
- Default: localhost:9222 (Chrome remote debugging port, auto-discovered)
- Chrome profile path: ~/.sman/chrome-profile/ (cookies, bookmarks)
- Windows: LOCALAPPDATA/Google/Chrome/User Data

## Call Locations
| File | Purpose |
|------|---------|
| server/web-access/cdp-engine.ts | Full CDP client: DOM snapshots, click, fill, evaluate, screenshot |
| server/web-access/chrome-sites.ts | Chrome bookmark/history DB read (better-sqlite3) |
| server/web-access/browser-engine.ts | Browser launch abstraction |
| server/web-access/mcp-server.ts | MCP Server exposing 9 web_access_* tools |

## Purpose
Controls a running Chrome browser via Chrome DevTools Protocol for web automation:
navigate, snapshot (accessibility tree), screenshot, click, fill, press key,
JS evaluate, list tabs, close tab. Also reads Chrome bookmarks/history to auto-discover enterprise sites.
