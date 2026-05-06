# Smart Path Engine (server/smart-path-engine.ts)

Executes "Earth Path" workflows step-by-step with result passing.

## Path Format

Markdown file with frontmatter + steps:
```yaml
name: "Daily Deploy"
steps:
  - name: "Run tests"
    prompt: "Run all tests and report failures"
  - name: "Build"
    prompt: "Build the project"
```

## Execution Model

**Step-by-step**: Each step completes before next starts
**Result passing**: Previous step's output becomes context for next
**Manual approval**: User can approve/reject each step
**Rerun from step**: Can jump to any step and resume

## Key Methods

- `executePath()`: Run path from beginning
- `executeStep()`: Run single step
- `rerunFromStep()`: Jump to step and continue

## Persistence

Paths stored as files in `{workspace}/.sman/paths/{pathId}/`
Run history in `runs/` directory

## Report Generation

After execution, generates markdown report with:
- Step results
- Output summaries
- Artifacts generated
- Total execution time

## Important

Paths are version-controlled (git). Team can share and collaborate on workflows.
