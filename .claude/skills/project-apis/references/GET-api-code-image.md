# `GET /api/code/image` HTTP Endpoint

## Signature
```
GET /api/code/image?workspace=<path>&file=<path>
Response: Binary image data with MIME type
```

## Request Parameters (Query)
- `workspace` (string, required): Project directory path
- `file` (string, required): Relative file path within workspace

## Business Flow
1. Validate workspace and file parameters
2. Resolve and validate file path (prevent traversal)
3. Check file exists and is regular file
4. Determine MIME type from extension
5. Serve file content with appropriate headers
6. Cache-Control: no-cache (prevent browser caching)

## Called Services
- `validatePath()`: Path resolution and security check
- `fs.readFileSync()`: Read image file

## Source File
`server/index.ts:579-619`
