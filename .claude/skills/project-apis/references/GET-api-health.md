# GET /api/health

## Signature
```
GET /api/health
```

## Business Flow
Returns `{ status: "ok", timestamp: "..." }`. No authentication required. Used for health checks and load balancers.

## Source
`server/index.ts` — HTTP handler, no auth gate.
