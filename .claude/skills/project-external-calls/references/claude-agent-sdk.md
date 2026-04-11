# Claude Agent SDK

## Overview
Powers all AI chat, cron tasks, and chatbot responses via the Anthropic API. Uses @anthropic-ai/claude-agent-sdk v0.1 (core session) + @anthropic-ai/claude-code v2.1 (tool execution).

## Call Method
SDK - npm packages imported in TypeScript. Sessions created via unstable_v2_createSession, streamed via SDKSession.stream().

## Config Source
- llm.apiKey - API key (Bearer token)
- llm.baseUrl - Base URL for Anthropic-compatible endpoints
- llm.model - Model ID (e.g., claude-opus-4-5)
- Env vars ANTHROPIC_API_KEY, ANTHROPIC_BASE_URL set per session from config

Source: ~/.sman/config.json

## Call Locations
- server/claude-session.ts - Core V2 session management (create, stream, resume, idle cleanup)
- server/mcp-config.ts - Injects search provider configs into MCP server env
- server/index.ts - Orchestrates session manager lifecycle
- server/cron-executor.ts - Executes cron tasks via Claude session
- server/chatbot/chatbot-session-manager.ts - Routes chatbot messages to Claude

## Purpose
Handles all LLM inference. Supports session persistence (session_id in SQLite), idle timeout (30 min), crash recovery via resume. MCP search tools injected per-provider at session creation.
