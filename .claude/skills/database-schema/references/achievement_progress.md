# achievement_progress Table

**Purpose**: Track progress and unlock status for each achievement definition.

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS achievement_progress (
  achievement_id TEXT PRIMARY KEY,
  current_value INTEGER DEFAULT 0,
  unlocked_at TEXT,
  notified_at TEXT
);
```

**Columns**:
- `achievement_id` (TEXT, PK): Achievement identifier from achievement definitions
- `current_value` (INTEGER): Current progress value towards unlock threshold
- `unlocked_at` (TEXT, nullable): ISO timestamp when achievement was unlocked (NULL if locked)
- `notified_at` (TEXT, nullable): ISO timestamp when user was notified of unlock (NULL if not notified)

**Indexes**: None (single-row lookups by PK)

**Foreign Keys**: None

**Notes**:
- Uses UPSERT pattern: `INSERT ... ON CONFLICT(achievement_id) DO UPDATE`
- `unlocked_at` set via `unlock()` method when threshold reached
- `notified_at` set via `markNotified()` after toast notification shown
- Accessed via AchievementStore class in `server/achievement-store.ts`
