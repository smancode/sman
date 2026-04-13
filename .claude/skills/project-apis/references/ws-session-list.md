# WS session.list

List all persisted sessions with metadata.

**Signature:** `session.list` → `{ sessions: ActiveSession[] }`

## Request Parameters

None.

## Response

```json
{
  "sessions": [
    {
      "id": "uuid",
      "workspace": "/path/to/project",
      "label": "optional-label",
      "createdAt": "ISO8601",
      "lastActiveAt": "ISO8601"
    }
  ]
}
```

## Business Flow

Returns all sessions from SQLite merged with in-memory labels. Used to populate the session tree sidebar.

## Source

`server/index.ts` — `case 'session.list'`
Calls: `sessionManager.listSessions()` in `server/claude-session.ts`
