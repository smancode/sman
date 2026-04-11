# Chrome CDP

## Overview
Browser automation via Chrome DevTools Protocol (CDP). Connects to user running Chrome (reusing cookies/sessions) or launches headless Chrome with copied profile.

## Call Method
Native WebSocket (ws npm package) to Chrome CDP debugging port. No external packages required for CDP itself.

## Config Source
- Auto-discovers Chrome debugging port from platform-specific paths:
  - macOS: ~/Library/Application Support/Google/Chrome/DevToolsActivePort
  - Linux: ~/.config/google-chrome/
  - Windows: via registry
- Can auto-launch Chrome on port 9333
- Profile copy sources: Chrome Cookies, Login Data, Bookmarks, History, Preferences (SQLite files)

## Call Locations
- server/web-access/cdp-engine.ts - Full CDP engine (navigate, snapshot, screenshot, click, fill, pressKey, evaluate)
- server/web-access/chrome-sites.ts - Reads Chrome Bookmarks + History (SQLite) for site discovery
- server/web-access/mcp-server.ts - Exposes web_access_* tools as MCP server
- server/web-access/browser-engine.ts - Abstract browser interface
- server/web-access/web-access-service.ts - High-level service

## Purpose
Browser automation for web access. 9 tools: navigate, snapshot, screenshot, click, fill, press_key, evaluate, list_tabs, close_tab. Profile copy includes cookies for SSO.
