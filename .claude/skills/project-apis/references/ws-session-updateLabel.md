# WS session.updateLabel

## Signature
```
WS message: { type: "session.updateLabel", sessionId: string, label: string }
```

## Request Parameters
| Param | Type | Required |
|--------|------|----------|
| `sessionId` | string | Yes |
| `label` | string | Yes |

## Business Flow
Updates session display label in SQLite. Broadcasts `session.labelUpdated` to all clients so the UI tree updates immediately.

## Called Services
`store.updateLabel()` → SQLite

## Source
`server/index.ts`
