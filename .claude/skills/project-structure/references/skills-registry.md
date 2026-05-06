# Skills Registry (server/skills-registry.ts)

Manages global and project-local skills loading and discovery.

## Skill Sources

1. **Global Skills**: `~/.sman/skills/` (user-installed, available everywhere)
2. **Project Skills**: `{workspace}/.sman/skills/` (project-specific)

## Skill Structure

Each skill is a directory with:
- `SKILL.md`: Frontmatter (id, name, description, category, triggers)
- `skill.ts` or `skill.mjs`: Implementation (optional, for advanced skills)

## Key Methods

- `listSkills()`: List all available skills (merged global + project)
- `loadSkill()`: Load skill by ID
- `reloadSkills()`: Refresh skill cache

## Frontmatter Fields

- `id`: Unique identifier
- `name`: Display name
- `description`: What the skill does
- `category`: Skill category (dev, testing, ops, etc.)
- `triggers`: When skill should auto-activate

## Important

Skills are simple markdown files with frontmatter. No compilation required.
