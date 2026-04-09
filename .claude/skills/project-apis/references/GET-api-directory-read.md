# GET /api/directory/read

## Signature
```
GET /api/directory/read?path=<absolute_path>
```

## Request Parameters
| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `path` | string | Yes | Absolute path to read |

## Business Flow
Reads the filesystem at `path` and returns `{ entries: [{ name, path, isDirectory }] }`. Filters out dotfiles (`.`-prefixed). Sorted: directories first, then alphabetically. Used by the directory browser in the UI.

## Called Services
Direct `fs.readdirSync` — no service layer.

## Source
`server/index.ts` — requires Bearer auth.
