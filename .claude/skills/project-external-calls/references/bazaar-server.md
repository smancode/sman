# Bazaar Server

## Overview
Multi-agent collaboration hub. smanbase connects as an agent to a user-configured Bazaar server, receives task offers, and collaborates via Claude sessions.

## Call Method
WebSocket client (ws npm package) to user-configured Bazaar server address. Protocol is JSON messages over WebSocket.

## Config Source
- bazaar.server - Bazaar server WebSocket URL
- bazaar.agentName - Agent display name
- bazaar.mode - manual | notify (30s auto-accept) | auto
- bazaar.maxConcurrentTasks - Max parallel task limit

Source: ~/.sman/config.json

## Additional External Call
- Leaderboard: fetch HTTP GET to http://${identity.server}/api/leaderboard?limit=50

## Call Locations
- server/bazaar/bazaar-client.ts - WebSocket client (heartbeat, reconnect, agent.register/heartbeat/offline messages)
- server/bazaar/bazaar-bridge.ts - Bridge between frontend, Claude sessions, and Bazaar server
- server/bazaar/bazaar-mcp.ts - MCP server exposing bazaar_search + bazaar_collaborate tools
- bazaar/src/index.ts - Self-contained Bazaar server (separate bazaar/ subpackage)

## Purpose
Agent swarming/collaboration. Modes: manual (user approves tasks), notify (auto-accept after 30s), auto (immediate accept). Leaderboard fetched for UI display.
