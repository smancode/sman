# GET /api/auth/token

## Signature
```
GET /api/auth/token
```

## Business Flow
Returns `{ token: "..." }` containing the server's auth Bearer token. **Loopback connections only** (127.0.0.1 / ::1). External requests get 403. Frontend calls this on startup to obtain the token for WebSocket auth.

## Source
`server/index.ts` — `isLoopback()` guard, no Bearer auth required.
