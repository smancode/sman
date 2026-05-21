# Achievement System (SQLite)

## Call Method
better-sqlite3 — synchronous SQLite driver, embedded achievement tracking

## Config Source
- Database path: ~/.sman/sman.db (shared with other tables)
- Tables: 4 (achievement_progress, achievement_stats, achievement_streaks, achievement_board)
- WAL mode + foreign keys enabled

## Call Locations
| File | Purpose |
|------|---------|
| server/achievement-store.ts | Achievement progress, stats, streaks, leaderboard storage |
| server/achievement-engine.ts | Event-driven achievement tracking, leaderboard upload/fetch |

## Schema

### achievement_progress
```sql
CREATE TABLE achievement_progress (
  achievement_id TEXT PRIMARY KEY,
  current_value INTEGER DEFAULT 0,
  unlocked_at TEXT,
  notified_at TEXT
);
```

### achievement_stats
```sql
CREATE TABLE achievement_stats (
  key TEXT PRIMARY KEY,
  value TEXT,
  updated_at TEXT
);
```

### achievement_streaks
```sql
CREATE TABLE achievement_streaks (
  id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  current_streak INTEGER DEFAULT 0,
  longest_streak INTEGER DEFAULT 0,
  last_active_date TEXT
);
```

### achievement_board
```sql
CREATE TABLE achievement_board (
  agent_id TEXT PRIMARY KEY,
  agent_name TEXT,
  total_unlocked INTEGER DEFAULT 0,
  total_points INTEGER DEFAULT 0,
  tier_counts TEXT,
  dimension_scores TEXT,
  last_synced TEXT
);
```

## Purpose
Local SQLite DB for achievement system: progress tracking, stats aggregation, streak calculation, leaderboard caching, and Smart Path scoring.

## Smart Path Integration
- **Event Tracking**: `smartpath_run` event (triggered on path completion)
- **Score Calculation**: completed=2 points, failed=0.5 points (other statuses ignored)
- **Data Source**: `smartpath_run_log` table (DB-based, removed filesystem scanning)
- **Reconciliation**: Query DB directly on startup (no backfill needed)

## External Integration
- **Hub Upload**: `POST /api/achievement-report` (hourly, PSK encrypted)
- **Leaderboard Query**: `GET /api/achievement-leaderboard?dimension=xxx` (on-demand)

See: [references/hub.md](hub.md) for Hub API details.
