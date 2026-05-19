# SmartPath Guide WebSocket APIs

> Interactive AI-powered guidance for SmartPath step execution.

## API Endpoints

### `smartpath.guideChat` / `smartpath.guideChat.delta` / `smartpath.guideChat.completed`

Initiates or continues a guided conversation for a specific step.

```json
// Request
{
  "type": "smartpath.guideChat",
  "pathId": "string",
  "workspace": "string",
  "stepIndex": 0,
  "stepResult": "string",      // Result from previous step execution
  "sessionId?: "string",       // Optional: reuse existing guide session
  "message?: "string"          // Optional: user's follow-up question
}

// Streaming Response
{ "type": "smartpath.guideChat.delta", "pathId": "string", "stepIndex": 0, "delta": "string" }

// Final Response
{ "type": "smartpath.guideChat.completed", "pathId": "string", "stepIndex": 0, "response": "string", "sessionId": "string" }
```

### `smartpath.guideSave` / `smartpath.guideSaved`

Saves the guide conversation to a reference file for future reuse.

```json
// Request
{
  "type": "smartpath.guideSave",
  "pathId": "string",
  "workspace": "string",
  "stepIndex": 0,
  "content": "string",         // Full guide content to save
  "sessionId?: "string"        // Optional: ephemeral session to close
}

// Response
{ "type": "smartpath.guideSaved", "pathId": "string", "stepIndex": 0, "fileName": "guide0.md", "references": ["string"] }
```

## Implementation

### Ephemeral Session Management
- Session ID format: `smartpath-guide-{pathId}-step-{stepIndex}-{timestamp}`
- Session NOT persisted to SQLite (purely in-memory)
- Closed on `guideSave` or auto-expires after timeout

### Prompt Construction
```
步骤名称: {stepName}
步骤描述: {stepInput}
步骤序号: {stepIndex}

[前序步骤]
{previousSteps}

[已有指南]
{existingGuideContent}

[当前步骤执行结果]
{stepResult}

[用户问题]
{userMessage}
```

### Reference File Storage
- Location: `{workspace}/.sman/paths/{pathId}/references/guide{n}.md`
- Naming: `guide0.md` for step 0, `guide1.md` for step 1, etc.

## Risk Analysis

### ✅ SAFE: Ephemeral Sessions
- Not persisted to DB, no cleanup needed on restart
- Memory bounded by session timeout

### ⚠️ POTENTIAL: Session Leak
If user closes dialog without saving, ephemeral session remains in memory. Mitigation: Sessions auto-expire after V2 SDK timeout.

### ⚠️ POTENTIAL: Concurrent Guide Updates
Two users could create `guide0.md` simultaneously (last write wins). Acceptable for single-user desktop app.

## Integration
- **Frontend**: Guide dialog with streaming chat interface
- **State**: Reuse `sessionId` for follow-up questions
- **Auto-subscribe**: Client auto-subscribes to guide session on `chat.send`
