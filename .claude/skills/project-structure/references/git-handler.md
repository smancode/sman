# Git Handler Reference

> Async Git operations with performance optimizations

## Purpose
Non-blocking Git operations for status, diff, log, branch management with parallel execution and smart directory expansion.

## Key Changes (v26.520.0)
**ASYNC MIGRATION COMPLETE**: All Git operations migrated from sync `execSync` to async `execFile`:
1. All functions now return `Promise<T>` (e.g., `handleGitStatus()` → `Promise<GitStatusResult>`)
2. Parallel execution: `Promise.all([git(...), git(...)])` for branch + status
3. **NEW: AI-powered conflict resolution** during git push (ephemeral sessions)
4. Performance optimizations: Depth limits, directory skipping, max file limits

## Architecture
```
GitHandler (async)
├── git(): Async execFile wrapper with timeout
├── handleGitStatus(): Parallel branch + status fetch
├── handleGitDiff(): Diff with path validation
├── expandDirFiles(): Recursive untracked expansion (with guards)
└── Constants
    ├── SKIP_DIRS: node_modules, .git, dist, build, etc.
    ├── MAX_EXPAND_DEPTH: 3 levels
    └── MAX_EXPAND_FILES: 500 files
```

## Performance Optimizations
- **Parallel Git Commands**: `rev-parse` + `status --porcelain` run concurrently
- **Directory Skipping**: Large dirs (node_modules, .git) excluded from expansion
- **Depth Limits**: Prevent deep recursion (MAX_EXPAND_DEPTH=3)
- **File Limits**: Cap expansion at 500 files to prevent UI freeze
- **Async Non-blocking**: UI remains responsive during Git operations

## Key Files
- **server/git-handler.ts**: Async Git operations with execFile
- **server/index.ts**: WebSocket handlers (git.status, git.diff, git.log, etc.)
- **src/stores/git.ts**: Frontend Git state management
- **src/features/git/**: Git panel UI (status, diff, log, branch)

## API Methods
All methods are async and return promises:
- `handleGitStatus(workspace)`: Branch + file status
- `handleGitDiff(workspace, filePath?, staged?)`: Diff output
- `handleGitLog(workspace, maxCount?)`: Commit history
- `handleGitBranchList(workspace)`: Branch listing
- `handleGitCheckout(workspace, branch)`: Switch branch
- `handleGitCommit(workspace, message, files?)`: Create commit
