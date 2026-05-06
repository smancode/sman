# `git.commit` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'git.commit', workspace: string, message: string, files?: string[] }
Server → Client: { type: 'git.commit', success: boolean, commitHash?: string, error?: string }
```

## Request Parameters
- `workspace` (string, required): Project directory path
- `message` (string, required): Commit message
- `files` (string[], optional): Specific files to commit (default: all staged)

## Business Flow
1. Validate workspace is git repository
2. If files specified: stage only those files
3. If no files: use already staged files
4. Execute git commit with message
5. Return commit hash on success
6. Handle errors (no changes, merge conflicts, etc.)

## Called Services
- `handleGitCommit()`: Core git commit logic
- Uses simple-git or child_process for git operations

## Source File
`server/index.ts:1666-1675`
- Handler: `server/git-handler.ts`
