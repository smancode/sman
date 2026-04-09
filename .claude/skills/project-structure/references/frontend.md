# src/ — Frontend (React 19, TypeScript, TailwindCSS)

## Purpose
Single-page React app (Vite). Provides chat UI, settings panels, session management, and WebSocket client. All state managed via Zustand stores.

## Key Files

| File | Purpose |
|---|---|
| `src/app/App.tsx` | Root component; layout + routing |
| `src/app/routes.tsx` | Route definitions (/chat, /settings, /cron-tasks, /batch-tasks) |
| `src/main.tsx` | Vite entry |
| `src/index.css` | TailwindCSS base |

### Features (`src/features/`)

| Directory | Purpose |
|---|---|
| `features/chat/` | Chat page: ChatInput, ChatMessage, ChatToolbar, message-utils, highlighter (Shiki) |
| `features/settings/` | Settings tabs: LLMSettings, WebSearchSettings, ChatbotSettings, CronTaskSettings, BatchTaskSettings, BackendSettings, UserProfileSettings |
| `features/cron-tasks/` | Cron task page (placeholder) |
| `features/batch-tasks/` | Batch task page (placeholder) |

### State Stores (`src/stores/`)
| File | State |
|---|---|
| `stores/chat.ts` | Messages, sessions, streaming state |
| `stores/settings.ts` | Settings (mirrors backend config) |
| `stores/cron.ts` | Cron task list |
| `stores/batch.ts` | Batch task list |
| `stores/ws-connection.ts` | WebSocket connection status |

### Components (`src/components/`)
| File | Purpose |
|---|---|
| `SessionTree.tsx` | Session tree grouped by workspace directory |
| `DirectorySelectorDialog.tsx` | Native directory picker dialog |
| `SkillPicker.tsx` | Skill selection dropdown |
| `layout/` | Layout components |
| `common/` | Shared generic components |
| `ui/` | Radix UI base components (primitives) |

### Library (`src/lib/`)
| File | Purpose |
|---|---|
| `ws-client.ts` | WebSocket client (auto-reconnect, auth header) |
| `auth.ts` | Auth token utilities |
| `utils.ts` | General utilities |
| `streamdown-plugins.ts` | Streamdown code block plugins |

### Types (`src/types/`)
| File | Purpose |
|---|---|
| `types/chat.ts` | ContentBlock, Message, etc. (mirrors server types) |
| `types/settings.ts` | Settings types (mirrors server types) |

## Dependencies
- React Router v7 for routing
- Zustand for state management
- TailwindCSS + Radix UI for styling
- Shiki for code highlighting in chat messages
- react-markdown + remark-gfm for markdown rendering
- `src/lib/ws-client.ts` connects to `server/` WebSocket at port 5880
