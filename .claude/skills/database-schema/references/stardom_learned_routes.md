# stardom_learned_routes Table

## Purpose
Capability-to-agent routing cache for intelligent task dispatching.

## DDL
```sql
CREATE TABLE IF NOT EXISTS learned_routes (
  capability TEXT NOT NULL,
  agent_id TEXT NOT NULL,
  agent_name TEXT NOT NULL,
  experience TEXT DEFAULT '',
  updated_at TEXT NOT NULL,
  PRIMARY KEY (capability, agent_id)
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| capability | TEXT | NO | Agent capability keyword |
| agent_id | TEXT | NO | Agent identifier |
| agent_name | TEXT | NO | Agent display name |
| experience | TEXT | YES | Additional experience notes |
| updated_at | TEXT | NO | Last update timestamp |

## Indexes
- `idx_learned_capability` on capability
- PRIMARY KEY (capability, agent_id)

## Foreign Keys
None

## Notes
- Routing cache: matches tasks to capable agents
- UPSERT pattern: `INSERT OR REPLACE`
- Fuzzy search: `capability LIKE ? OR experience LIKE ?`
- One agent per capability, updated on re-routing

## Source File
`/Users/nasakim/projects/sman/server/stardom/stardom-store.ts`
