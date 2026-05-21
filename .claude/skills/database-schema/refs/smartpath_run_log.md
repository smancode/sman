# `smartpath_run_log` Table

**Purpose**: Execution history for SmartPath (multi-step workflow automation)

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS smartpath_run_log (
  id TEXT PRIMARY KEY,
  path_id TEXT NOT NULL,            -- Links to path file
  path_name TEXT NOT NULL,
  workspace TEXT NOT NULL,
  mode TEXT NOT NULL,               -- 'full' | 'stepping'
  step_count INTEGER NOT NULL DEFAULT 0,
  args TEXT,                        -- JSON: input args for the run
  status TEXT NOT NULL DEFAULT 'running', -- 'running' | 'completed' | 'failed'
  error_message TEXT,
  started_at TEXT NOT NULL,
  finished_at TEXT
);
```

**Relationships**:
- No direct FK (links to file-based paths in `{workspace}/.sman/paths/{pathId}/`)

**Use Cases**:
- Audit trail for path executions
- Debug failed workflow runs
- Performance analytics (duration tracking)
- Re-run with same args

**Query Patterns**:
- Latest 50 runs per path/workspace (ORDER BY started_at DESC LIMIT 50)
- Status-based filtering (running/completed/failed)
- Error analysis (WHERE status = 'failed')

**Store**: `server/smart-path-store.ts` (SmartPathStore class)
