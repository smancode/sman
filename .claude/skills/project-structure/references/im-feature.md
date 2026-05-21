# IM Feature — Frontend UI

**Purpose**: React UI for instant messaging (rooms, agents, messaging).

## Components

| Component | Purpose |
|-----------|---------|
| **IMEntry.tsx** | Main layout (SessionList + ChatWindow) |
| **SessionList.tsx** | Room list sidebar |
| **ChatWindow.tsx** | Main chat area (MessageList + ChatInput) |
| **MessageList.tsx** | Message display (virtualized scroll) |
| **MessageBubble.tsx** | Individual message (text, agent output, system) |
| **ChatInput.tsx** | Message composer with @mention popup |
| **GroupChatList.tsx** | Group chat management UI |
| **IMListPanel.tsx** | List of all IM sessions |
| **AgentCard.tsx** | Agent selector card with collapse/expand |
| **MemberPanel.tsx** | Room member display |

## State Management (src/stores/im.ts)

Zustand store with:
- Rooms and messages arrays
- Current room selection
- Typing indicators
- Agent lists for @mention

## React Query Hooks (src/queries/use-im.ts)

- `useRooms()`: Fetch room list
- `useMessages(roomId)`: Fetch message history
- `useAgents()`: Fetch available agents

## Routing

Route: `/im` → IMEntry component

## Key Features

- **Real-time messaging**: WebSocket sync via Hub
- **@mention activation**: Type @ to see agent list, triggers Claude session
- **Quote/reply**: Reply to specific messages with context
- **Agent streaming**: Real-time delta updates for agent responses
- **Typing indicators**: Show when users are typing
- **Multi-room support**: Switch between different chat rooms
