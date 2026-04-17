# Code Quality Reviewer Prompt Template

Use this template when dispatching a code quality reviewer subagent.

**Purpose:** Verify implementation is well-built (clean, tested, maintainable) AND consistent with project coding standards.

**Only dispatch after spec compliance review passes.**

```
Task tool (superpowers:code-reviewer):
  Use template at requesting-code-review/code-reviewer.md

  WHAT_WAS_IMPLEMENTED: [from implementer's report]
  PLAN_OR_REQUIREMENTS: Task N from [plan-file]
  BASE_SHA: [commit before task]
  HEAD_SHA: [current commit]
  DESCRIPTION: [task summary]
  PROJECT_CONSTRAINTS: [CONTENT of applicable .claude/rules/*.md rules]
```

**In addition to standard code quality concerns, the reviewer should check:**
- Does each file have one clear responsibility with a well-defined interface?
- Are units decomposed so they can be understood and tested independently?
- Is the implementation following the file structure from the plan?
- Did this implementation create new files that are already large, or significantly grow existing files? (Don't flag pre-existing file sizes — focus on what this change contributed.)
- **Does the code follow ALL declared project constraints from .claude/rules/*.md?**
- **Are there coding patterns that contradict project standards (e.g. parameter handling, error handling, naming conventions)?**
- **Has the implementer added "smart" defaults or fallbacks that project rules explicitly forbid?**

**Constraint-specific checks:**
- Parameter handling: no default values, no silent conversions, no fallback returns where rules say fail-fast
- Naming: follows project-specific conventions, not generic best practices
- Error handling: throws exceptions as required by rules, no swallowed errors
- Code organization: matches project structure patterns declared in constraints

**Code reviewer returns:** Strengths, Issues (Critical/Important/Minor), Constraint Violations, Assessment
