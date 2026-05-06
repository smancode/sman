# Web Access MCP (server/web-access/mcp-server.ts)

Provides 9 MCP tools for browser automation via Chrome DevTools Protocol.

## Tools

1. `web_access_navigate`: Navigate to URL
2. `web_access_snapshot`: Get accessibility tree
3. `web_access_screenshot`: Capture screenshot
4. `web_access_click`: Click element by selector
5. `web_access_fill`: Fill form field
6. `web_access_press_key`: Press keyboard key
7. `web_access_evaluate`: Execute JavaScript
8. `web_access_list_tabs`: List open tabs
9. `web_access_close_tab`: Close tab
10. `web_access_find_url`: Smart URL matching from history
11. `web_access_remember_url`: Save URL for future matching

## CDP Engine

Uses `chrome-remote-interface` to connect to Chrome/Chromium:
- Launches Chrome in remote debugging mode
- Maintains persistent browser instance
- Manages tab lifecycle (create, navigate, close)

## URL Experience Learning

Learns from user navigation:
- "ITSM" → `https://itsm.company.com`
- "Jira" → `https://jira.company.com`
- Stores in `~/.sman/url-experience.json`

## Smart Matching

`web_access_find_url` uses semantic search:
1. Query saved experiences (fuzzy match)
2. Fallback to Chrome history
3. Return best match or ask user

## Important

Browser instance is shared across all sessions in a workspace. Isolated per-workspace.
