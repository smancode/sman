# achievement_stats Table

**Purpose**: Store key-value metrics for achievement system (session counts, token totals, etc.).

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS achievement_stats (
  key TEXT PRIMARY KEY,
  value TEXT,
  updated_at TEXT
);
```

**Columns**:
- `key` (TEXT, PK): Metric identifier (e.g., 'total_sessions', 'total_tokens')
- `value` (TEXT): Metric value stored as string (converted to int for numeric ops)
- `updated_at` (TEXT): ISO timestamp of last update

**Indexes**: None (single-row lookups by PK)

**Foreign Keys**: None

**Notes**:
- Used for counters: total_sessions, total_messages, total_tokens, total_cron_runs, total_smartpath_runs, total_skills_used, etc.
- Numeric operations: `incrementStat(key, delta)` converts value to int, adds delta, stores back
- UPSERT pattern: `INSERT ... ON CONFLICT(key) DO UPDATE`
- All timestamp fields use ISO 8601 format (`toISOString()`)
