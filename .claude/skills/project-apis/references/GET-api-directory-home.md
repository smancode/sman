# GET /api/directory/home

## Signature
```
GET /api/directory/home
```

## Business Flow
Returns `{ home: "<os.homedir()>" }`. Provides the user's home directory path for the frontend directory browser.

## Source
`server/index.ts` — no path param, returns `os.homedir()`.
