# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SmanAgent is a bank core system code analysis assistant driven by LLM. It consists of:
- **agent/** - Spring Boot backend service (Java 21) - Lightweight ReAct Agent
- **ide-plugin/** - IntelliJ IDEA plugin (Kotlin)
- **knowledge/** - Knowledge service for semantic search and business graph (future)

## Core Architecture

### Design Philosophy
- **LLM 是引擎，架构是底盘** - LLM drives the process, architecture provides tools
- **Agent 轻量化，Knowledge 重心** - Agent focuses on tool orchestration, complex features moved to Knowledge service
- **后端提供纯查询服务** - Backend provides tool orchestration and business graph queries
- **前端插件负责 LLM 推理和交互** - Frontend plugin handles LLM inference and UI interaction
- **流式输出 + 可点击链接** - Streaming output with clickable links
- **云原生扩展** - Architecture supports cloud-native deployment (client-server → cloud-server)

### Key Components

**Backend (agent/)**
- `SmanAgentLoop` - Core ReAct loop for LLM-driven task execution
- `SubTaskExecutor` - Executes tool calls in isolated child sessions (prevents token explosion)
- `ToolRegistry` - Registers all available tools for LLM to use
- `LlmService` - LLM client with retry policy and endpoint pooling
- `SessionManager` - Manages conversation sessions with context isolation
- `ExpertConsultTool` - Expert consultation tool that delegates to Knowledge service via HTTP

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
- `knowledge.service.*` - Knowledge service configuration (HTTP endpoint)
- `websocket.path` - WebSocket path
- `project.path` - Project path to analyze

**Environment variables:**
- `GLM_API_KEY` - GLM API key for LLM
- `PROJECT_PATH` - Path to project being analyzed
- `PROJECT_KEY` - Project key for cache isolation
- `KNOWLEDGE_SERVICE_BASE_URL` - Knowledge service base URL (default: http://localhost:8081)

## LLM Request/Response Logging

**CRITICAL**: LLM request logs and response content must NOT be truncated during use or in logging output.

## Key Design Principles

1. **Agent Lightweight** - Agent focuses on ReAct loop and tool orchestration only
2. **Knowledge as Service** - Vector search, reranking, and complex features delegated to Knowledge service
3. **Context Isolation** - Tool calls execute in isolated child sessions to prevent token explosion
4. **Streaming First** - All Parts support streaming updates pushed to frontend in real-time
5. **LLM Autonomy** - No hardcoded intent recognition, LLM decides behavior entirely
6. **State Management** - Minimal state (IDLE/BUSY/RETRY), managed through Parts

## Tool System

Tools implement `Tool` interface:
- `getName()` - Tool name
- `getDescription()` - Description for LLM
- `getParameters()` - Parameter definitions
- `execute()` - Execute tool and return result

**Available tools:**
- `expert_consult` - Expert consultation: query business knowledge and locate code entries (calls Knowledge service via HTTP)
- `read_file` - Read file content (executed in IDE)
- `grep_file` - Regex search in files
- `find_file` - Find files by pattern
- `call_chain` - Analyze method call chains
- `extract_xml` - Extract XML tag content

## Architecture Evolution

**Before (Heavy Agent):**
- Agent contained BGE-M3 embedding service
- Agent contained VectorSearchService
- Agent contained SearchSubAgent with complex search logic
- Agent managed vector indices and reranking

**After (Light Agent + Knowledge Service):**
- Agent only has ExpertConsultTool that makes HTTP calls to Knowledge service
- Knowledge service handles vector search, embedding, reranking
- Supports cloud-native deployment (Agent and Knowledge can scale independently)
- Agent becomes a pure ReAct orchestrator

## Tool Naming Evolution

**Important**: The search functionality has been renamed from `search` to `expert_consult` to better reflect its purpose:

| Old Name | New Name | Reason |
|----------|----------|--------|
| `search` | `expert_consult` | More accurately describes the function - it's an expert consultation that queries business knowledge and locates code entries |
| `SearchTool` | `ExpertConsultTool` | Aligns with the new naming convention |
| `needSearch` | `needConsult` | Clarifies that this is about consultation, not just searching |
| `SearchSubAgent` | (removed) | Functionality moved to Knowledge service |

**Prompt Updates**: All prompts in `agent/src/main/resources/prompts/` have been updated to use `expert_consult` instead of `search`.

## Testing

Tests located in:
- `agent/src/test/java/` - JUnit tests
- `ide-plugin/src/test/kotlin/` - Kotlin tests

Run all tests: `./gradlew test` (from respective module directory)
