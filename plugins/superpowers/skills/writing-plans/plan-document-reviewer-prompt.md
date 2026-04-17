# Plan Document Reviewer Prompt Template

Use this template when dispatching a plan document reviewer subagent.

**Purpose:** Verify the plan chunk is complete, matches the spec, has proper task decomposition, and enforces project constraints.

**Dispatch after:** Each plan chunk is written

```
Task tool (general-purpose):
  description: "Review plan chunk N"
  prompt: |
    You are a plan document reviewer. Verify this plan chunk is complete and ready for implementation.

    **Plan chunk to review:** [PLAN_FILE_PATH] - Chunk N only
    **Spec for reference:** [SPEC_FILE_PATH]
    **Project constraints:** [CONTENT of .claude/rules/*.md and CLAUDE.md, or path to read]

    ## What to Check

    | Category | What to Look For |
    |----------|------------------|
    | Completeness | TODOs, placeholders, incomplete tasks, missing steps |
    | Spec Alignment | Chunk covers relevant spec requirements, no scope creep |
    | Task Decomposition | Tasks atomic, clear boundaries, steps actionable |
    | File Structure | Files have clear single responsibilities, split by responsibility not layer |
    | File Size | Would any new or modified file likely grow large enough to be hard to reason about as a whole? |
    | Task Syntax | Checkbox syntax (`- [ ]`) on steps for tracking |
    | Chunk Size | Each chunk under 1000 lines |
    | **Constraint Declaration** | **Does each task list its applicable constraints from .claude/rules? Is there a "Project Constraints" section in the plan header?** |
    | **Constraint Verification Steps** | **Does each task include a step to verify compliance with declared constraints?** |

    ## CRITICAL

    Look especially hard for:
    - Any TODO markers or placeholder text
    - Steps that say "similar to X" without actual content
    - Incomplete task definitions
    - Missing verification steps or expected outputs
    - Files planned to hold multiple responsibilities or likely to grow unwieldy
    - **Tasks without "Applicable Constraints" declarations**
    - **Plan header missing "Project Constraints" section**
    - **Constraint references that don't match actual .claude/rules content**

    ## Output Format

    ## Plan Review - Chunk N

    **Status:** ✅ Approved | ❌ Issues Found

    **Issues (if any):**
    - [Task X, Step Y]: [specific issue] - [why it matters]

    **Constraint Compliance:**
    - ✅/❌ Plan header includes Project Constraints section
    - ✅/❌ Each task declares applicable constraints
    - ✅/❌ Each task includes constraint verification step
    - [Any specific constraint gaps or mismatches]

    **Recommendations (advisory):**
    - [suggestions that don't block approval]
```

**Reviewer returns:** Status, Issues, Constraint Compliance, Recommendations
