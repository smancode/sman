# GET /api/health

Public health check endpoint — no authentication required.

**Signature:** `GET /api/health`

## Request

No parameters.

## Response

```json
{ "status": "ok", "timestamp": "2026-04-13 12:00:00" }
```

## Business Flow

Returns server liveness status. Frontend polls this on startup to verify backend is reachable.

## Source

`server/index.ts` — HTTP handler at `req.url === '/api/health'`
