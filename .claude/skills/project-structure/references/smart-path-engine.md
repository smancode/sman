# Smart Path Engine Reference

> Earth Path step-by-step execution engine with orchestration

## Purpose
Execute multi-step automated workflows with step-by-step UI, result editing, and progress tracking.

## Key Changes (v26.517.1)
**MAJOR REFACTOR**: Split monolithic execution into three methods:
1. `orchestrate()`: Analyze path, prepare steps, generate blueprint
2. `runStep(stepId)`: Execute single step with LLM
3. `finalize()`: Generate final report and cleanup

## Key Files
- **server/smart-path-engine.ts**: Core execution engine (orchestrate/runStep/finalize)
- **server/smart-path-store.ts**: SQLite storage for paths/runs/references
- **src/stores/smart-path.ts**: Frontend state management
- **src/features/smart-paths/index.tsx**: Step-by-step execution UI
