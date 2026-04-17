---
name: writing-plans
description: Use when you have a spec or requirements for a multi-step task, before touching code
---

# Writing Plans

## Overview

Write comprehensive implementation plans assuming the engineer has zero context for our codebase and questionable taste. Document everything they need to know: which files to touch for each task, code, testing, docs they might need to check, how to test it. Give them the whole plan as bite-sized tasks. DRY. YAGNI. TDD. Frequent commits.

**Constraint-first: Before writing the plan, load project constraints from `{workspace}/.claude/rules/*.md` and `{workspace}/CLAUDE.md`. Every task MUST declare which constraints apply and how to verify compliance.**

Assume they are a skilled developer, but know almost nothing about our toolset or problem domain. Assume they don't know good test design very well.

**Announce at start:** "I'm using the writing-plans skill to create the implementation plan."

**Context:** This should be run in a dedicated worktree (created by brainstorming skill).

**Save plans to:** `docs/superpowers/plans/YYYY-MM-DD-<feature-name>.md`
- (User preferences for plan location override this default)

## Scope Check

If the spec covers multiple independent subsystems, it should have been broken into sub-project specs during brainstorming. If it wasn't, suggest breaking this into separate plans — one per subsystem. Each plan should produce working, testable software on its own.

## File Structure

Before defining tasks, map out which files will be created or modified and what each one is responsible for. This is where decomposition decisions get locked in.

- Design units with clear boundaries and well-defined interfaces. Each file should have one clear responsibility.
- You reason best about code you can hold in context at once, and your edits are more reliable when files are focused. Prefer smaller, focused files over large ones that do too much.
- Files that change together should live together. Split by responsibility, not by technical layer.
- In existing codebases, follow established patterns. If the codebase uses large files, don't unilaterally restructure - but if a file you're modifying has grown unwieldy, including a split in the plan is reasonable.

This structure informs the task decomposition. Each task should produce self-contained changes that make sense independently.

## Bite-Sized Task Granularity

**Each step is one action (2-5 minutes):**
- "Write the failing test" - step
- "Run it to make sure it fails" - step
- "Implement the minimal code to make the test pass" - step
- "Run the tests and make sure they pass" - step
- "Commit" - step

## Task Size Control — 分而治之

<HARD-LIMIT>
A single task MUST NOT exceed these limits. If it does, split into smaller tasks.
</HARD-LIMIT>

| 指标 | 硬性上限 | 超了怎么办 |
|------|---------|-----------|
| 涉及文件数 | ≤ 3 个文件 | 按文件/职责拆成多个 task |
| 步骤数 | ≤ 8 步 | 拆成多个顺序 task |
| 代码量估算 | ≤ 150 行新代码 | 按功能点拆 |
| 预计 LLM 上下文占用 | ≤ 单个文件全量 | 拆到每个 task 只需读 1-2 个文件 |

**为什么必须有这个限制：**
- LLM 上下文窗口有限，task 太大 → 看不全代码 → 遗漏细节 → 交付质量下降
- reviewer Agent 需要读代码做对比，task 太大 → reviewer 也看不过来 → review 形同虚设
- 小 task = 快速反馈 = 早发现问题 = 低修复成本

**拆分策略（按优先级）：**
1. **按文件拆** — 一个 task 只创建/修改 1-3 个文件（优先选这个）
2. **按职责拆** — 一个 task 只做一件事（数据模型 / 业务逻辑 / 接口层 / UI）
3. **按层次拆** — 一个 task 只涉及一层（底层 → 中间层 → 顶层，逐层推进）
4. **按场景拆** — 一个 task 只覆盖一个场景（正常流程 / 异常处理 / 边界情况）

**Task 依赖声明：**
每个 task 必须声明依赖关系：
- `Depends on: Task N` — 必须等 Task N 完成才能开始
- `No dependencies` — 可以并行（但 subagent-driven 不支持并行，顺序执行即可）

## Plan Document Header

**Every plan MUST start with this header:**

```markdown
# [Feature Name] Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** [One sentence describing what this builds]

**Architecture:** [2-3 sentences about approach]

**Tech Stack:** [Key technologies/libraries]

**Project Constraints (from .claude/rules/*.md and CLAUDE.md):**
- [List each applicable constraint here — these are non-negotiable rules the implementer MUST follow]
- [Reference the specific rule file for traceability]

---
```

## Task Structure

````markdown
### Task N: [Component Name]

**Files:**
- Create: `exact/path/to/file.py`
- Modify: `exact/path/to/existing.py:123-145`
- Test: `tests/exact/path/to/test.py`

**Applicable Constraints:**
- [List specific rules from .claude/rules that apply to this task]
- [e.g. "参数缺失必须抛异常，不返回默认值 (CODING_RULES.md §2.2)"]

- [ ] **Step 1: Write the failing test**

```python
def test_specific_behavior():
    result = function(input)
    assert result == expected
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/path/test.py::test_name -v`
Expected: FAIL with "function not defined"

- [ ] **Step 3: Write minimal implementation**

```python
def function(input):
    return expected
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/path/test.py::test_name -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add tests/path/test.py src/path/file.py
git commit -m "feat: add specific feature"
```

- [ ] **Step 6: Verify constraint compliance**

Check the implementation against applicable constraints listed above. For each constraint:
- Confirm the code follows the rule
- If violated, fix before proceeding
````

## Remember
- Exact file paths always
- Complete code in plan (not "add validation")
- Exact commands with expected output
- Reference relevant skills with @ syntax
- DRY, YAGNI, TDD, frequent commits

## Plan Review Loop

After completing each chunk of the plan:

1. Dispatch plan-document-reviewer subagent (see plan-document-reviewer-prompt.md) for the current chunk
   - Provide: chunk content, path to spec document
2. If ❌ Issues Found:
   - Fix the issues in the chunk
   - Re-dispatch reviewer for that chunk
   - Repeat until ✅ Approved
3. If ✅ Approved: proceed to next chunk (or execution handoff if last chunk)

**Chunk boundaries:** Use `## Chunk N: <name>` headings to delimit chunks. Each chunk should be ≤1000 lines and logically self-contained.

**Review loop guidance:**
- Same agent that wrote the plan fixes it (preserves context)
- If loop exceeds 5 iterations, surface to human for guidance
- Reviewers are advisory - explain disagreements if you believe feedback is incorrect

## Execution Handoff

After saving the plan:

**"Plan complete and saved to `docs/superpowers/plans/<filename>.md`. Ready to execute?"**

**Execution path depends on harness capabilities:**

**If harness has subagents (Claude Code, etc.):**
- **REQUIRED:** Use superpowers:subagent-driven-development
- Do NOT offer a choice - subagent-driven is the standard approach
- Fresh subagent per task + two-stage review

**If harness does NOT have subagents:**
- Execute plan in current session using superpowers:executing-plans
- Batch execution with checkpoints for review
