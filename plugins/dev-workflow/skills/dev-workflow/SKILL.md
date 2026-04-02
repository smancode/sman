---
name: dev-workflow
description: "Use when user says '完整流程', /dev-workflow, or complex development tasks. Structured development: brainstorm → plan → implement → review → verify → simplify → summarize."
---

# 实战开发流程

复杂开发任务的标准化流程。每步派独立 Agent 执行，主进程只做编排和用户确认。

## 流水线

```
Step 1: 需求分析 (brainstorming)
Step 2: 写实施计划 (writing-plans)
Step 3: 逐任务执行 (subagent-driven-development)
Step 4: 集成验证 (verification)
Step 5: 代码优化
Step 6: 总结沉淀
```

---

## Step 1: 需求分析

使用 superpowers:brainstorming skill 的流程:
- 探索项目上下文（读文件、看结构）
- 向用户逐个提问澄清需求
- 提出 2-3 个方案并推荐
- 呈报用户确认方案

**用户确认后才能进入 Step 2。**

---

## Step 2: 写实施计划

使用 superpowers:writing-plans skill 的流程:
- 把确认的方案拆成任务列表
- 每个任务含精确文件路径 + TDD 步骤 + 验证命令
- 保存计划到 `docs/superpowers/plans/YYYY-MM-DD-<name>.md`

**用户确认计划后才能进入 Step 3。**

TDD 分级由计划 Agent 根据项目环境自动判断:

| 级别 | 条件 | 行为 |
|------|------|------|
| 严格 TDD | `pnpm test` / `mvn test` / `pytest` 能跑 | 写测试 → 验证 RED → 写代码 → 验证 GREEN |
| 逻辑验证 | 跑不起来但能编译 | 写测试代码(不运行) → 写实现 → 编译验证 |
| 纯实现 | 编译环境都没有 | 写代码 + review 逻辑 |

---

## Step 3: 逐任务执行

使用 superpowers:subagent-driven-development skill 的流程。
逐个任务派 Agent，每个任务走三轮:

```
for each task:
  1. 派 Agent (general-purpose) → TDD + 编码 + 自检 + 提交
  2. 派 Agent (general-purpose) → spec review: 读实际代码验证是否匹配需求
  3. 派 Agent (general-purpose) → code quality review: 单一职责、简洁、模式一致
  → review 不通过则派修复 Agent → 重新 review，循环直到通过
```

---

## Step 4: 集成验证

使用 superpowers:verification-before-completion skill 的流程:
- 编译构建
- 运行全部测试
- 逐条检查验收标准
- 验证失败回到 Step 3 修复

---

## Step 5: 代码优化

派 Agent (general-purpose)，指令:
- 消除重复代码
- 简化逻辑
- 改善命名
- 保持功能不变

---

## Step 6: 总结沉淀

写 memory 记录:
- 开发中遇到的问题和解法
- 用户反馈和修正
- 架构变化（如有）
