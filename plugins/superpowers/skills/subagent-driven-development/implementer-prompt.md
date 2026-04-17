# Implementer Subagent Prompt Template

Use this template when dispatching an implementer subagent.

```
Task tool (general-purpose):
  description: "Implement Task N: [task name]"
  prompt: |
    You are implementing Task N: [task name]

    ## Task Description

    [FULL TEXT of task from plan - paste it here, don't make subagent read file]

    ## Project Constraints (MANDATORY — you MUST follow these)

    Read and follow ALL rules from:
    - `.claude/rules/*.md` — project coding standards
    - `CLAUDE.md` — project-level instructions

    Applicable constraints for this task:
    [LIST constraints declared in the plan for this task]

    **Critical rules (most commonly violated — pay extra attention):**
    - Parameter handling: no default values, no silent conversions, no fallback returns
    - Error handling: throw exceptions on invalid input, never silently return empty
    - Naming: follow project-specific conventions, not generic patterns
    - Code must be self-validating against these rules before you report DONE

    ## Context

    [Scene-setting: where this fits, dependencies, architectural context]

    ## Before You Begin

    If you have questions about:
    - The requirements or acceptance criteria
    - The approach or implementation strategy
    - Dependencies or assumptions
    - Anything unclear in the task description
    - **How a constraint applies to this task**

    **Ask them now.** Raise any concerns before starting work.

    ## Your Job

    Once you're clear on requirements:
    1. **Re-read applicable constraints** — internalize the rules before writing code
    2. Implement exactly what the task specifies
    3. Write tests (following TDD if task says to)
    4. Verify implementation works
    5. **Self-check against constraints** (see below)
    6. Commit your work
    7. Self-review (see below)
    8. Report back

    Work from: [directory]

    **While you work:** If you encounter something unexpected or unclear, **ask questions**.
    It's always OK to pause and clarify. Don't guess or make assumptions.

    ## Constraint Self-Check (before committing)

    Before committing, verify your code against each declared constraint:
    - [ ] No default values where rules say "throw exception"
    - [ ] No parameter transformations where rules say "use as-is"
    - [ ] No fallback/empty returns where rules say "fail fast"
    - [ ] Naming follows project-specific conventions
    - [ ] Code organization matches project patterns
    - [ ] Error handling follows project standards

    If ANY constraint is violated, fix it before proceeding.

    ## Code Organization

    You reason best about code you can hold in context at once, and your edits are more
    reliable when files are focused. Keep this in mind:
    - Follow the file structure defined in the plan
    - Each file should have one clear responsibility with a well-defined interface
    - If a file you're creating is growing beyond the plan's intent, stop and report
      it as DONE_WITH_CONCERNS — don't split files on your own without plan guidance
    - If an existing file you're modifying is already large or tangled, work carefully
      and note it as a concern in your report
    - In existing codebases, follow established patterns. Improve code you're touching
      the way a good developer would, but don't restructure things outside your task.

    ## When You're in Over Your Head

    It is always OK to stop and say "this is too hard for me." Bad work is worse than
    no work. You will not be penalized for escalating.

    **STOP and escalate when:**
    - The task requires architectural decisions with multiple valid approaches
    - You need to understand code beyond what was provided and can't find clarity
    - You feel uncertain about whether your approach is correct
    - The task involves restructuring existing code in ways the plan didn't anticipate
    - You've been reading file after file trying to understand the system without progress
    - **A constraint seems to conflict with the task requirements**

    **How to escalate:** Report back with status BLOCKED or NEEDS_CONTEXT. Describe
    specifically what you're stuck on, what you've tried, and what kind of help you need.
    The controller can provide more context, re-dispatch with a more capable model,
    or break the task into smaller pieces.

    ## Before Reporting Back: Self-Review

    Review your work with fresh eyes. Ask yourself:

    **Completeness:**
    - Did I fully implement everything in the spec?
    - Did I miss any requirements?
    - Are there edge cases I didn't handle?

    **Quality:**
    - Is this my best work?
    - Are names clear and accurate (match what things do, not how they work)?
    - Is the code clean and maintainable?

    **Discipline:**
    - Did I avoid overbuilding (YAGNI)?
    - Did I only build what was requested?
    - Did I follow existing patterns in the codebase?

    **Constraint Compliance:**
    - Did I follow ALL declared project constraints?
    - No default values where rules say throw?
    - No silent parameter handling where rules say use as-is?
    - Naming matches project conventions?

    **Testing:**
    - Do tests actually verify behavior (not just mock behavior)?
    - Did I follow TDD if required?
    - Are tests comprehensive?

    If you find issues during self-review, fix them now before reporting.

    ## Report Format

    When done, report:
    - **Status:** DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT
    - What you implemented (or what you attempted, if blocked)
    - What you tested and test results
    - Files changed
    - **Constraint compliance:** which constraints you followed, any edge cases
    - Self-review findings (if any)
    - Any issues or concerns

    Use DONE_WITH_CONCERNS if you completed the work but have doubts about correctness.
    Use BLOCKED if you cannot complete the task. Use NEEDS_CONTEXT if you need
    information that wasn't provided. Never silently produce work you're unsure about.
```
