# achievement_streaks Table

**Purpose**: Track user activity streaks (current and longest consecutive days).

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS achievement_streaks (
  id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  current_streak INTEGER DEFAULT 0,
  longest_streak INTEGER DEFAULT 0,
  last_active_date TEXT
);
```

**Columns**:
- `id` (INTEGER, PK): Fixed to 1 via CHECK constraint (singleton table)
- `current_streak` (INTEGER): Current consecutive days of activity
- `longest_streak` (INTEGER): Maximum streak achieved (all-time)
- `last_active_date` (TEXT, nullable): ISO date of last activity (YYYY-MM-DD)

**Indexes**: None (single-row table)

**Foreign Keys**: None

**Notes**:
- Singleton pattern enforced by `CHECK (id = 1)` constraint
- Auto-initialized via `INSERT OR IGNORE INTO achievement_streaks (id, current_streak, longest_streak) VALUES (1, 0, 0)`
- Streak logic in `updateStreak(today)`:
  - Same day: no change
  - Consecutive day (diff = 1): increment current_streak
  - Gap detected: reset to 1
  - Update longest_streak if current exceeds it
- Date comparison uses day difference calculation (86400000 ms)
