# GET /api/directory/read

Read directory entries for the frontend directory browser.

**Signature:** `GET /api/directory/read?path=<string>`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `path` | string | Yes | Absolute path to read |

## Response

```json
{
  "entries": [
    { "name": "src", "path": "/abs/src", "isDirectory": true },
    { "name": "README.md", "path": "/abs/README.md", "isDirectory": false }
  ]
}
```

Entries starting with `.` are filtered out. Directories sorted first, then alphabetically.

## Business Flow

Used by the directory browser dialog to display folder contents. Path is normalized and read with `fs.readdirSync`.

## Source

`server/index.ts` — HTTP handler at `req.url?.startsWith('/api/directory/read')`
