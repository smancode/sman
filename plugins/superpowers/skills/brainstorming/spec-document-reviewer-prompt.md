# Spec Document Reviewer Prompt Template

Use this template when dispatching a spec document reviewer subagent.

**Purpose:** Verify the spec is complete, consistent, ready for implementation planning, and compliant with project constraints.

**Dispatch after:** Spec document is written to docs/superpowers/specs/

```
Task tool (general-purpose):
  description: "Review spec document"
  prompt: |
    You are a spec document reviewer. Verify this spec is complete and ready for planning.

    **Spec to review:** [SPEC_FILE_PATH]
    **Project constraints:** [CONTENT of .claude/rules/*.md and CLAUDE.md, or path to read]

    ## What to Check

    | Category | What to Look For |
    |----------|------------------|
    | Completeness | TODOs, placeholders, "TBD", incomplete sections |
    | Coverage | Missing error handling, edge cases, integration points |
    | Consistency | Internal contradictions, conflicting requirements |
    | Clarity | Ambiguous requirements |
    | YAGNI | Unrequested features, over-engineering |
    | Scope | Focused enough for a single plan — not covering multiple independent subsystems |
    | Architecture | Units with clear boundaries, well-defined interfaces, independently understandable and testable |
    | **Constraint Compliance** | **Does the spec include a Constraints section? Does it list all applicable rules from .claude/rules/*.md? Are there design decisions that contradict the rules?** |

    ## CRITICAL

    Look especially hard for:
    - Any TODO markers or placeholder text
    - Sections saying "to be defined later" or "will spec when X is done"
    - Sections noticeably less detailed than others
    - Units that lack clear boundaries or interfaces — can you understand what each unit does without reading its internals?
    - **Missing Constraints section — the spec MUST declare which project coding rules apply**
    - **Design decisions that violate .claude/rules standards**

    ## Output Format

    ## Spec Review

    **Status:** ✅ Approved | ❌ Issues Found

    **Issues (if any):**
    - [Section X]: [specific issue] - [why it matters]

    **Constraint Compliance:**
    - ✅/❌ Constraints section present and lists applicable rules
    - ✅/❌ No design decisions contradict project rules
    - [Any specific violations found]

    **Recommendations (advisory):**
    - [suggestions that don't block approval]
```

**Reviewer returns:** Status, Issues, Constraint Compliance, Recommendations
