---
name: dev-workflow
description: "Use when user says '完整流程', /dev-workflow, or complex development tasks. Structured development: brainstorm → plan → implement → review → verify → simplify → summarize."
---

# 实战开发流程

复杂开发任务的标准化流程。每步派独立 Agent 执行，主进程只做编排和用户确认。

## 核心原则

### 原则一：约束先行

```
约束加载 → 理解约束 → 遵循约束 → 验证约束
```

每个阶段开始前必须：
1. **加载约束** — 读取项目 `{workspace}/.claude/rules/*.md` 和 `{workspace}/CLAUDE.md` 中定义的所有编码规范
2. **理解约束** — 摘要出与当前任务相关的规范要点
3. **遵循约束** — 执行时严格按规范实现，不允许"差不多就行"
4. **验证约束** — review 阶段逐条检查是否违背了规范

**为什么：** 规范写在那里但不遵循 = 没写。约束先行确保每一步交付都符合项目标准，用户拿到就能用，不需要大改。

### 原则二：分而治之

```
大任务 → 拆成小任务 → 每个任务独立可验证 → 逐步推进
```

**核心判断标准：子任务必须能在单个 Agent 上下文内完整解决。**

不是机械地按文件数硬拆，而是确保每个子任务满足：
1. **上下文自足** — Agent 只需读计划中指定的文件，就能理解任务并完成实现
2. **目标明确** — 一句话能说清楚这个任务要交付什么
3. **可独立验证** — 完成后能用明确的命令或检查点验证结果
4. **依赖清晰** — 明确声明依赖哪些前置任务的产出

<HARD-LIMIT>
任务超出以下任一上限，必须拆分后再执行。不允许以"拆分太麻烦"为借口跳过拆分。
</HARD-LIMIT>

| 维度 | 上限 | 拆分标准 |
|------|------|---------|
| 单个 spec | ≤ 8 个新文件，≤ 20 步 | 子项目独立 spec，独立 spec → plan → execute 循环 |
| 单个 task | ≤ 3 个文件，≤ 8 步，≤ 150 行 | 按职责/文件/层次拆成多个 task |
| 单个 Agent 上下文 | 只需读 ≤ 3-5 个文件就能完成 | 超了就拆，确保上下文自足 |

**拆分不是硬切，必须保持上下文完整性：**
- ❌ 错误拆法：把一个函数的实现拆成两个 task，第一个 task 看不懂全局
- ✅ 正确拆法：按职责拆，每个 task 是一个完整的职责单元（如"实现数据模型层"）

**拆分是递归的：**
- Step 1 brainstorming 发现 spec 太大 → 拆成多个子 spec
- Step 2 writing-plans 发现 task 太大 → 拆成多个小 task
- Step 3 implementer 发现单个 task 还是太大 → 报告 BLOCKED，建议拆分方案
- **任何阶段发现"太大"，立即拆分，不硬撑**

**依赖和顺序：**
- 每个子任务必须声明 `Depends on: Task N`
- 严格按依赖顺序执行，不跳步
- 不建议 3 个以上并行 Agent（LLM 资源紧张，并行反而不卡死反而拖慢）
- 最优节奏：1-2 个 Agent 串行执行，每完成一个立即 review

## 流水线

```
Step 0: 约束加载（每步之前自动执行）
Step 1: 需求分析 (brainstorming)
Step 2: 写实施计划 (writing-plans)
Step 3: 逐任务执行 (subagent-driven-development)
Step 4: 集成验证 (verification)
Step 5: 代码优化
Step 6: 总结沉淀
```

---

## Step 0: 约束加载

**每进入下一步之前，执行约束检查：**

```
1. 列出 {workspace}/.claude/rules/*.md 中所有规则文件
2. 列出 {workspace}/CLAUDE.md 中定义的规范
3. 摘要与当前步骤相关的约束要点
4. 将约束要点作为上下文注入到下一步的 Agent prompt 中
```

**约束传递链：**
```
Step 0 加载约束 → 注入 Step 1 Agent
Step 1 结束 → 约束 + spec → 注入 Step 2 Agent
Step 2 结束 → 约束 + spec + plan → 注入 Step 3 Agent
Step 3 每个 task → 约束 + spec + plan + task → 注入 implementer/reviewer
Step 4 → 约束 + 全部交付物 → 注入验证 Agent
```

---

## Step 1: 需求分析

使用 superpowers:brainstorming skill 的流程:
- **首先加载项目约束** — 读取 `.claude/rules/*.md` 和 `CLAUDE.md`
- 探索项目上下文（读文件、看结构）
- 向用户逐个提问澄清需求
- 提出 2-3 个方案并推荐
- 呈报用户确认方案
- **Spec 中声明约束要求** — 在设计文档中明确列出必须遵循的编码规范

**用户确认后才能进入 Step 2。**

---

## Step 2: 写实施计划

使用 superpowers:writing-plans skill 的流程:
- **注入约束上下文** — 将 Step 0 加载的约束传递给计划 Agent
- 把确认的方案拆成任务列表
- 每个任务含精确文件路径 + TDD 步骤 + 验证命令
- **每个任务声明适用约束** — 列出该任务必须遵循的 `.claude/rules` 中的具体规则
- 保存计划到 `docs/superpowers/plans/YYYY-MM-DD-<name>.md`

**用户确认计划后才能进入 Step 3。**

TDD 分级由计划 Agent 根据项目环境自动判断（尊重实际情况，不强求）:

| 级别 | 条件 | 行为 |
|------|------|------|
| 严格 TDD | 测试命令能跑，依赖齐全 | 写测试 → 验证 RED → 写代码 → 验证 GREEN |
| 编译验证 | 能编译但测试跑不起来 | 写实现 → 编译验证 → 逻辑审查 |
| 逻辑审查 | 编译也跑不起来（缺二方库/三方库/环境依赖） | 写代码 → 逐文件逻辑审查 → 告知用户无法自动验证的原因 |

**原则：尝试编译和测试，跑不起来就如实说明，不要卡死。**

---

## Step 3: 逐任务执行

使用 superpowers:subagent-driven-development skill 的流程。
逐个任务派 Agent，每个任务走三轮（约束贯穿每轮）:

```
for each task:
  0. 约束注入 — 将 .claude/rules 中的相关规范注入 Agent prompt
  1. 派 Agent (general-purpose) → TDD + 编码（严格按约束实现）+ 自检（对照约束检查）+ 提交
  2. 派 Agent (general-purpose) → spec review + 约束合规检查：读实际代码验证是否匹配需求 + 是否违背编码规范
  3. 派 Agent (general-purpose) → code quality review：单一职责、简洁、模式一致 + 约束一致性
  → review 不通过则派修复 Agent → 重新 review，循环直到通过
```

---

## Step 4: 集成验证

使用 superpowers:verification-before-completion skill 的流程:
- 编译构建
- 运行全部测试
- **约束合规性验证** — 逐条检查 `.claude/rules` 中的规范，确保所有变更都符合
- 逐条检查验收标准
- 验证失败回到 Step 3 修复

---

## Step 5: 代码优化

派 Agent (general-purpose)，指令:
- 消除重复代码
- 简化逻辑
- 改善命名
- 保持功能不变
- **优化后重新检查约束合规性** — 优化不能引入违背规范的代码

---

## Step 6: 总结沉淀

### A. Memory 记录

- 开发中遇到的问题和解法
- 用户反馈和修正
- 架构变化（如有）
- **约束遵循情况** — 记录哪些约束被严格遵循、哪些有偏差

### B. 规则提取 → `.claude/rules/*.md`

扫描本次所有变更（`git diff`），提取可复用的规范决策：

1. `git diff HEAD~N --stat` 查看变更范围
2. `git diff HEAD~N` 查看具体变更内容
3. 识别以下类型的决策：

| 类别 | 值得记录 | 不值得记录 |
|------|---------|-----------|
| 编码规范 | 项目特定的模式、反直觉的约束 | 通用常识（camelCase、异常处理） |
| 业务约束 | 用户或 reviewer 明确强调的边界 | 显而易见的 CRUD |
| 命名约定 | 项目统一的特殊格式 | 标准命名 |
| 技术决策 | 选型理由、架构取舍 | 通用最佳实践 |

4. 读取 `{workspace}/.claude/rules/*.md`，检查已有内容
5. 写入策略：
   - 有同类文件（如 `coding-standards.md`）→ 追加到对应分类下
   - 无同类文件 → 创建新文件，文件名用英文短横线
   - **去重**：相同语义的规则不重复添加

**写入格式**：纯 Markdown，无 frontmatter。
```markdown
# 编码规范

- 金额字段必须用 BigDecimal，禁止 float/double
- Redis key 格式: {project}:{module}:{id}
```

**原则**：
- 每条规则 1-2 行，不啰嗦
- 只提取非通用、反直觉的规则
- 用户明确说的 > 代码隐含的
- 不确定是否值得记录 → 不记录

### C. Skills 审计 → `.claude/skills/*/SKILL.md`

检查已有 skills 是否因本次变更而过时：

1. 列出 `{workspace}/.claude/skills/*/SKILL.md` 中所有 skill
2. 对比 skill 中引用的文件路径、类名、模块与本次 git diff 变更
3. 处理策略：

| 情况 | 动作 |
|------|------|
| 引用的路径/类名已重命名 | 更新 skill 中的引用 |
| 功能逻辑发生了变化 | 更新 skill 的描述和步骤 |
| 引用的模块已删除 | 提示用户是否删除该 skill |
| 新增重要模块无对应 skill | 仅在总结中建议，不自动创建 |

**注意**：只维护已有 skill，不自动创建新 skill。
