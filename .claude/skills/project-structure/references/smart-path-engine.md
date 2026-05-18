# Smart Path Engine Reference

> Earth Path step-by-step execution engine with skills integration

## Purpose
Execute multi-step automated workflows with step-by-step UI, result editing, skill constraints, and progress tracking.

## Key Changes (v26.519.0)
**SKILLS INTEGRATION**: Steps can now specify workspace skills to use:
1. `step.skills[]`: Array of skill IDs (e.g., ['database-schema', 'project-apis'])
2. Skill content injection via `buildSkillsContext()` from `.claude/skills/{skillId}/SKILL.md`
3. Script file validation: Only allow .py, .sh, .js, .ts, .bat, .sql, .r, .rb, .go, .java, .ps1
4. `useRefs` parameter: Optional references context injection

## Architecture
```
SmartPathEngine
├── orchestrate(): Analyze path, generate blueprint
├── runStep(): Execute single step with skill constraints
└── finalize(): Generate report and cleanup

StepPlan (per step)
├── revisedInput: Modified instruction
├── dependenciesOnPrior: Previous step dependencies
├── modifications: Corrections made
└── skills: Skill IDs to use (NEW)
```

## Key Files
- **server/smart-path-engine.ts**: Core engine with skills integration (SCRIPT_EXTENSIONS whitelist)
- **server/smart-path-store.ts**: SQLite storage for paths/runs/references
- **src/stores/smart-path.ts**: Frontend state management
- **src/features/smart-paths/index.tsx**: Step UI with skill selector + "Use References" toggle
