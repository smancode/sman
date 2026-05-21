# IMAgentBridge — @mention Activation

**Purpose**: Activate Claude Agent sessions when @mentioned in IM group chat.

**Architecture**: Fully decoupled from ClaudeSessionManager via injected callbacks.

## Lifecycle (per @mention)

1. **handleMention()** filters mentioned agents to those owned by this client
2. For each owned agent, **activateAgent()** runs:
   - Insert agent_output message (status=running) → broadcast
   - Create/reuse Claude session via injected callback
   - Stream response via injected callback, broadcasting deltas
   - On completion: update message (status=completed, final content) → broadcast
   - On error: update message (status=failed, error message) → broadcast

## Constructor Dependencies

```typescript
constructor(
  imStore: IMStore,
  broadcastToRoom: (roomId: string, msg: any) => void,
  clientId: string,
  getWorkspaceForAgent: (agentDisplayId: string) => string | undefined,
  createOrGetSession: (workspace: string) => Promise<{ sessionId: string; isNew: boolean }>,
  streamSessionMessage: (sessionId: string, content: string, onDelta: (delta: string) => void) => Promise<string>,
)
```

**Key Design**:
- `getWorkspaceForAgent`: Resolves agent display ID to local workspace path
- `createOrGetSession`: Returns existing session or creates new one
- `streamSessionMessage`: Streams Claude response with delta callbacks

## Parallel Execution

Multiple agents can be activated in parallel (Promise.allSettled). Errors don't block other agents.

## Quote Context

If message has `quoteId`, the quoted message content is prepended to the prompt:
```
[引用 {sender} 的消息]
{quoted content}

---
{prompt}
```

## Broadcast Events

- `im.message`: New message or status update
- `im.agent_delta`: Streaming content chunk (data: { messageId, agentId, content })
