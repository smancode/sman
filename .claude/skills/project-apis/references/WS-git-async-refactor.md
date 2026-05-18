# Git Operations - Async Refactoring

## Change Summary
All Git WebSocket handlers converted from synchronous to async (Promise-based) to prevent event loop blocking during large repository operations.

## Impact
- **API Compatibility**: ✅ No breaking changes - message signatures unchanged
- **Performance**: ✅ Parallel operations where possible
- **Error Handling**: ✅ Maintains same error response format

## Modified Handlers

### Before (Sync)
```typescript
case 'git.status': {
  try {
    const result = handleGitStatus(String(msg.workspace));
    ws.send(JSON.stringify({ type: 'git.status', result }));
  } catch (err) {
    ws.send(JSON.stringify({ type: 'git.status', result: { error: err.message } }));
  }
}
```

### After (Async)
```typescript
case 'git.status': {
  handleGitStatus(String(msg.workspace))
    .then(result => ws.send(JSON.stringify({ type: 'git.status', result })))
    .catch(err => ws.send(JSON.stringify({ type: 'git.status', result: { error: err.message } })));
}
```

## Affected Endpoints
All Git handlers now async:
- `git.status` - with parallel `git rev-parse` + `git status` 🚀
- `git.diff` - large file diffs no longer block
- `git.diffFile` - safe for large files
- `git.commit` - non-blocking commit
- `git.log` - long history queries
- `git.logGraph` - graph rendering
- `git.logSearch` - search across commits
- `git.aheadCommits` - ahead/behind calculation
- `git.branchList` - branch listing
- `git.checkout` - branch switching
- `git.fetch` - remote fetch
- `git.remoteDiff` - remote comparison

## Internal Changes (git-handler.ts)

### Core Function
```typescript
// Before: sync
function git(workspace: string, args: string, timeout = 10000): string {
  return execSync(`git --no-pager ${args}`, { ... }).trim();
}

// After: async
async function git(workspace: string, args: string, timeout = 10000): Promise<string> {
  const { stdout } = await execFileAsync('git', ['--no-pager', ...args.split(' ')], { ... });
  return stdout.trim();
}
```

### Optimization Example (handleGitStatus)
```typescript
// Parallel execution
const [branch, porcelain] = await Promise.all([
  git(workspace, 'rev-parse --abbrev-ref HEAD'),
  git(workspace, 'status --porcelain=v2 --branch'),
]);
```

### Directory Expansion Safeguards
```typescript
const SKIP_DIRS = new Set(['node_modules', '.git', 'dist', 'build', ...]);
const MAX_EXPAND_DEPTH = 3;
const MAX_EXPAND_FILES = 500;

function expandDirFiles(workspace: string, dirPath: string, out: GitFileStatus[], depth = 0): void {
  if (depth > MAX_EXPAND_DEPTH || out.length >= MAX_EXPAND_FILES) return;
  // ... expansion logic
}
```

## Client Impact
- **No changes required** - WebSocket message format identical
- **Better responsiveness** - UI won't freeze during large git operations
- **Same error handling** - `{ error: string }` response maintained

## Source Files
- `server/index.ts:2412-2523` (WebSocket handlers)
- `server/git-handler.ts:1-300` (Core git operations)
