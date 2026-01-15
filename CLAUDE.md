# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SmanAgent is a bank core system code analysis assistant driven by LLM. It consists of:
- **agent/** - Spring Boot backend service (Java 21)
- **ide-plugin/** - IntelliJ IDEA plugin (Kotlin)

## Core Architecture

### Design Philosophy
- **LLM 是引擎，架构是底盘** - LLM drives the process, architecture provides tools
- **后端提供纯查询服务** - Backend provides semantic search and business graph queries
- **前端插件负责 LLM 推理和交互** - Frontend plugin handles LLM inference and UI interaction
- **流式输出 + 可点击链接** - Streaming output with clickable links

### Key Components

**Backend (agent/)**
- `SmanAgentLoop` - Core ReAct loop for LLM-driven task execution
- `SubTaskExecutor` - Executes tool calls in isolated child sessions (prevents token explosion)
- `ToolRegistry` - Registers all available tools for LLM to use
- `LlmService` - LLM client with retry policy and endpoint pooling
- `SessionManager` - Manages conversation sessions with context isolation

**Frontend (ide-plugin/)**
- `SmanAgentChatPanel` - Main UI panel
- `AgentWebSocketClient` - WebSocket client for real-time communication
- `StyledMessageRenderer` - Renders messages with CLI-style formatting

### Part System (Unified Content Abstraction)

All message content is expressed through `Part` subclasses:
- `TextPart` - Plain text content
- `ReasoningPart` - Thinking/reasoning process
- `ToolPart` - Tool invocation with parameters and result
- `GoalPart` - Goal display with status
- `ProgressPart` - Progress updates
- `TodoPart` - Task list items

## Common Development Commands

### Backend (agent/)
```bash
cd agent
./gradlew bootRun          # Run backend server
./gradlew test             # Run tests
./gradlew build            # Build JAR
```

### Frontend (ide-plugin/)
```bash
cd ide-plugin
./gradlew runIde           # Run IDE with plugin
./gradlew buildPlugin      # Build plugin
```

## Configuration

**Backend configuration** in `agent/src/main/resources/application.yml`:
- `smancode.react.max-steps` - ReAct loop maximum steps (default: 25)
- `llm.pool.endpoints` - LLM endpoint configuration
- `bank.analysis.vector.*` - Vector search configuration
- `websocket.path` - WebSocket path
- `project.path` - Project path to analyze

**Environment variables:**
- `GLM_API_KEY` - GLM API key for LLM
- `PROJECT_PATH` - Path to project being analyzed
- `PROJECT_KEY` - Project key for cache isolation

## LLM Request/Response Logging

**CRITICAL**: LLM request logs and response content must NOT be truncated during use or in logging output.

## Key Design Principles

1. **Context Isolation** - Tool calls execute in isolated child sessions to prevent token explosion
2. **Streaming First** - All Parts support streaming updates pushed to frontend in real-time
3. **LLM Autonomy** - No hardcoded intent recognition, LLM decides behavior entirely
4. **State Management** - Minimal state (IDLE/BUSY/RETRY), managed through Parts

## Tool System

Tools implement `Tool` interface:
- `getName()` - Tool name
- `getDescription()` - Description for LLM
- `getParameters()` - Parameter definitions
- `execute()` - Execute tool and return result

**Available tools:**
- `semantic_search` - Semantic code search
- `read_file` - Read file content (executed in IDE)
- `grep_file` - Regex search in files
- `find_file` - Find files by pattern
- `call_chain` - Analyze method call chains
- `extract_xml` - Extract XML tag content

## Testing

Tests located in:
- `agent/src/test/java/` - JUnit tests
- `ide-plugin/src/test/kotlin/` - Kotlin tests

Run all tests: `./gradlew test` (from respective module directory)
