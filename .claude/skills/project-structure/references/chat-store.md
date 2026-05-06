# Chat Store (src/stores/chat.ts)

Zustand store for chat state management.

## State

```typescript
{
  sessions: Session[],           // All sessions
  currentSessionId: string,      // Active session
  messages: Record<string, Message[]>,  // Messages by session
  isStreaming: boolean,          // Currently streaming
  pendingMessages: string[],     // Queue
  streamingContent: string       // Current stream buffer
}
```

## Actions

- `createSession()`: Create new session
- `switchSession()`: Change active session
- `sendMessage()`: Add message to queue
- `appendDelta()`: Add streaming content
- `finishStreaming()`: Mark stream complete
- `abortTurn()`: Cancel in-progress turn

## Message Queue

SDK doesn't support concurrent turns. Store enforces:
1. Send message
2. Wait for `chat.done`
3. Then send next

## Reactivity

Components subscribe to specific slices:
- `ChatInput`: Only `isStreaming` (avoid re-render on messages)
- `ChatMessage`: Only `messages[currentSessionId]`
- `SessionTree`: Only `sessions`

## Important

Keep store updates minimal. Avoid triggering re-renders unnecessarily.
