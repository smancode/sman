# GET /api/directory/home

Return the user's home directory path.

**Signature:** `GET /api/directory/home`

## Request

No parameters.

## Response

```json
{ "home": "/Users/username" }
```

## Business Flow

Used by the frontend to initialize the directory browser at the user's home directory.

## Source

`server/index.ts` — HTTP handler at `req.url === '/api/directory/home'`
