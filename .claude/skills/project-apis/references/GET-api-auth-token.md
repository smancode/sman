# GET /api/auth/token

Retrieve the server auth token — **loopback connections only**.

**Signature:** `GET /api/auth/token`

## Request

No parameters. Must originate from `127.0.0.1` or `::1`.

## Response

```json
{ "token": "<auth-token>" }
```

Returns `403 Forbidden` for non-loopback requests.

## Business Flow

Called by Electron frontend on first launch to obtain the auth token without manual entry. Electron connects via loopback so this is safe.

## Source

`server/index.ts` — HTTP handler at `req.url === '/api/auth/token'`
