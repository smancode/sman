# scripts/ — Build & Utility Scripts

## Purpose
One-time setup and recurring utility scripts for smanbase initialization, SDK patching, and Office Skills environment setup.

## Key Files

| File | Purpose |
|---|---|
| `init-skills.ts` | Initialize global skills registry (`~/.sman/skills/`) |
| `init-system.ts` | Full system init: skills + skills registry file |
| `patch-sdk.mjs` | Claude Agent SDK postinstall patch (ESM compat) |
| `setup-office-skills.sh` | Set up Python venv for office-skills plugin |
| `setup-office-skills.bat` | Windows equivalent |

## init-skills.ts
- Copies bundled skills from `resources/skills/` → `~/.sman/skills/`
- Runs after `pnpm install` (via postinstall hook)

## init-system.ts
- Calls `init-skills.ts` internally
- Also creates the `~/.sman/` directory structure
- Used for fresh system setup

## patch-sdk.mjs
- Postinstall script (`node scripts/patch-sdk.mjs`)
- Patches Claude Agent SDK for ESM compatibility
- Reads and modifies `node_modules/@anthropic-ai/claude-code/dist/index.js`

## Dependencies
- Run via `pnpm init:skills`, `pnpm init:system`, `pnpm postinstall`
- `tsx` for running TypeScript scripts
- `node` for the ESM patch script
