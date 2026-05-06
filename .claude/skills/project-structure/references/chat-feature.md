# Chat Feature (src/features/chat/)

Main chat UI with message rendering, streaming, and tool use display.

## Components

- `index.tsx`: Main chat container
- `ChatInput.tsx`: Message input with file attachment
- `ChatMessage.tsx`: Message bubble with content blocks
- `ChatToolbar.tsx`: Action buttons (copy, regenerate, etc.)
- `streamdown-components.tsx`: Custom Streamdown components
- `InitBanner.tsx`: Session initialization status
- `AskUserCard.tsx`: Tool use approval UI

## Streaming Flow

1. User sends message via `ChatInput`
2. Backend creates SDK session, streams response
3. WebSocket sends `chat.delta` events
4. UI updates in real-time (typing effect)
5. Tool use: show `tool_start` → wait → `done`
6. Ask user: show approval card → user decides

## Content Blocks

Messages render different block types:
- `text`: Markdown with syntax highlighting
- `thinking`: Collapsible thinking block
- `tool_use`: Tool invocation with input/result
- `image`: Image preview
- `attached_file`: File attachment link

## Message Queue

Uses `chat` store to queue messages (SDK doesn't support concurrent turns):
- `pendingMessages`: Messages waiting to send
- `isStreaming`: Current turn in progress

## Important

All UI updates must be non-blocking. Use `startTransition` for expensive renders.
