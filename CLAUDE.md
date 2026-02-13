# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the plugin
./gradlew buildPlugin

# Run the IDE with plugin (for development)
./gradlew runIde

# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests "*LocalToolFactoryTest*"

# Compile only
./gradlew compileKotlin

# Plugin verification
./gradlew verifyPluginConfiguration

# Run verification web service
./gradlew runVerification -Pverification.port=8080
```

## Environment Setup

Set API key before development:
```bash
export LLM_API_KEY=your_api_key_here
```

## Architecture Overview

### ReAct Loop Pattern
The core `SmanLoop` implements a Reasoning + Acting loop:
1. Receive user message
2. Check context compaction needs
3. Call LLM streaming
4. Execute tools in isolated sub-sessions
5. Push Parts to frontend

Key implementation: `src/main/kotlin/com/smancode/sman/smancode/core/SmanLoop.kt`

### Three-Tier Cache Architecture
```
L1 (Hot): Memory LRU cache (~500 entries)
L2 (Warm): JVector vector index (disk-persisted)
L3 (Cold): H2 database (persistent storage)
```

### Tool System
- **Tool Interface**: Common interface for all tools
- **ToolRegistry**: Manual registration (no Spring DI)
- **Execution Modes**: LOCAL (backend) / INTELLIJ (IDE)

**Available Tools**:
- `read_file`: Read file content
- `grep_file`: Regex search
- `find_file`: File pattern search
- `call_chain`: Call chain analysis
- `extract_xml`: Extract XML tag content
- `apply_change`: Code modification
- `expert_consult`: Semantic search via BGE + Reranker

### Project Analysis Pipeline
12 analysis modules with Pipeline pattern:
1. Project structure scanning
2. Tech stack detection
3. AST scanning
4. DB entity scanning
5. API entry scanning
6. External API scanning
7. Enum scanning
8. Common class scanning
9. XML code scanning
10. Case SOP generation
11. Semantic vectorization
12. Code walkthrough

## Key Design Decisions

1. **No Spring Dependency Injection**: Manual registration pattern for tools
2. **Three-Tier Cache**: Prevents memory overflow with large projects
3. **Incremental Updates**: MD5-based file change detection
4. **Context Isolation**: Sub-sessions for tool execution prevent token explosion
5. **Streaming First**: Real-time output display
6. **Semantic Search-Only**: `expert_consult` returns BGE results directly without LLM processing

## Project Structure

```
src/main/kotlin/com/smancode/sman/
├── analysis/           # Project analysis modules (12 modules)
├── config/             # Configuration management (SmanConfig)
├── ide/                # IntelliJ IDEA integration
├── model/              # Data models (message, part, session)
├── smancode/           # Core ReAct Loop implementation
├── tools/              # Tool system (interface, registry, executor)
├── util/               # Utilities
└── verification/       # Verification web service

src/main/resources/
├── META-INF/plugin.xml    # Plugin descriptor
├── sman.properties        # Configuration file
└── prompts/               # Prompt templates
```

## Data Storage (Per-Project Isolation)

```
{projectPath}/.sman/
├── analysis.mv.db      # H2 database
├── md/                 # Markdown analysis results
├── md5/                # File MD5 cache
├── vector/             # Vector data
└── ast/                # AST cache
```

## Configuration

Configuration file: `src/main/resources/sman.properties`

Key configuration categories:
- LLM API (key, URL, model, retry)
- ReAct loop (max steps, streaming)
- Context compaction (threshold, max tokens)
- Vector database (JVector, H2)
- BGE-M3 embedding service
- BGE-Reranker service
- Project analysis settings

Configuration priority: User settings > Environment variables > Config file > Default

## Technology Stack

- **Language**: Kotlin 1.9.20 (JDK 17+)
- **Platform**: IntelliJ IDEA 2024.1+
- **HTTP Client**: OkHttp 4.12.0
- **JSON**: Jackson 2.16.0 + kotlinx.serialization 1.6.0
- **Database**: H2 2.2.224 + JVector 3.0.0 + HikariCP
- **Testing**: JUnit 5.10.1 + MockK 1.13.8

## Test Reports

After running tests, view reports at:
```
build/reports/tests/test/index.html
```
