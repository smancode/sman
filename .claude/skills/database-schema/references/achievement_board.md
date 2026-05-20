# achievement_board Table

**Purpose**: Leaderboard entries synced from remote hub service (multi-agent ranking).

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS achievement_board (
  agent_id TEXT PRIMARY KEY,
  agent_name TEXT,
  total_unlocked INTEGER DEFAULT 0,
  total_points INTEGER DEFAULT 0,
  tier_counts TEXT,
  dimension_scores TEXT,
  last_synced TEXT
);
```

**Columns**:
- `agent_id` (TEXT, PK): Unique agent identifier
- `agent_name` (TEXT): Display name for the agent
- `total_unlocked` (INTEGER): Total achievements unlocked
- `total_points` (INTEGER): Weighted score across all dimensions (see achievement-definitions.ts)
- `tier_counts` (TEXT): JSON string mapping tiers to counts (e.g., '{"bronze":5,"silver":3}')
- `dimension_scores` (TEXT): JSON string with raw metric values for each dimension
- `last_synced` (TEXT): ISO timestamp of last hub sync

**Indexes**: None (ordered query on total_points in getBoard)

**Foreign Keys**: None

**Notes**:
- Local cache of hub leaderboard data (source of truth: hub SQLite)
- Fetched via WebSocket from hub service (`/api/achievement-leaderboard`)
- UPSERT pattern: `INSERT ... ON CONFLICT(agent_id) DO UPDATE SET ...`
- Ordered by `total_points DESC` for leaderboard display
- `dimension_scores` enables multi-dimensional ranking (e.g., "Most Sessions" vs "Highest Score")
- Tier system: bronze(0) → silver(100) → gold(300) → platinum(600) → diamond(1200) → star(2000) → king(3200) → legend(4800) → epic(7000) → eternal(10000)
