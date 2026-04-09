# plugins/ — Claude Code Plugins (symlinked)

## Purpose
Claude Code plugin modules that provide extended capabilities. Loaded by the skills-registry and available globally.

## Plugins

| Plugin | Path | Purpose |
|---|---|---|
| web-access | `plugins/web-access/` | Browser automation Skill ( CDP-based ) |
| superpowers | `plugins/superpowers/` | TDD, planning, review, subagent-driven development chains |
| gstack | `plugins/gstack/` (symlink) | Gstack plugin at `/Users/nasakim/projects/gstack` |

## Plugin Structure (superpowers example)
```
plugins/superpowers/
├── agents/       # Specialized agent definitions
├── commands/     # CLI command handlers
├── hooks/        # Claude Code lifecycle hooks
├── skills/       # Skill definitions (per-plugin skills)
├── lib/          # Shared utilities
├── tests/        # Plugin-specific tests
├── docs/         # Plugin documentation
├── LICENSE
└── README.md
```

## Loading
Plugins are loaded via `server/skills-registry.ts` alongside global (`~/.sman/skills/`) and project-specific (`{workspace}/.claude/skills/`) skills.

## Key Files
- `plugins/web-access/` — Skill for web browsing automation
- `plugins/superpowers/skills/` — TDD, debugging, planning skills
- `plugins/gstack/` — Symbolically linked to external project
