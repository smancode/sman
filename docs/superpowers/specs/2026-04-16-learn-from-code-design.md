# 从代码中学习 — 自动提取规则 & 维护 Skills

> 日期: 2026-04-16

## 背景

dev-workflow 的 Step 6（总结沉淀）目前只写 memory 记录。开发过程中产生的编码规范、业务约束、技术决策等知识没有沉淀到项目配置中。同时，大量代码变更后，`.claude/skills/` 中引用的文件路径、类名、模块可能已过时。

## 方案

**零代码改动**。只修改 `plugins/dev-workflow/skills/dev-workflow/SKILL.md` 的 Step 6，追加规则提取和 Skills 审计指令。

### 为什么不需要代码

- `settingSources: ['project']` 已让 SDK 自动加载 `.claude/rules/*.md`
- Claude Agent 有 `Read`、`Write`、`Bash`（git diff）工具，天然能完成提取和写入
- 去重和归类由 Claude 语义理解完成

## Step 6 完整流程

```
A. Memory 记录（原有）
   - 问题解法、用户反馈、架构变化

B. 规则提取 → .claude/rules/*.md
   - 扫描 git diff
   - 提取编码规范/业务约束/命名约定/技术决策
   - 追加到同类文件，去重

C. Skills 审计 → .claude/skills/*/SKILL.md
   - 对比变更文件 vs 已有 skills 中的引用
   - 更新过时引用
   - 提示删除/新增
```

## 规则提取设计

### 触发时机

仅 dev-workflow Step 6，不走 dev-workflow 的对话不触发。

### 提取内容

| 类别 | 示例 | 不提取 |
|------|------|--------|
| 编码规范 | "金额字段必须用 BigDecimal" | "变量用 camelCase"（通用常识） |
| 业务约束 | "订单状态只允许正向流转" | 显而易见的 CRUD 逻辑 |
| 命名约定 | "Redis key 格式: {project}:{module}:{id}" | 标准的 Java 命名 |
| 技术决策 | "选 Caffeine 而非 Guava Cache，因为支持异步刷新" | 通用最佳实践 |

### 文件组织

```
{workspace}/.claude/rules/
├── coding-standards.md      # 编码规范
├── business-constraints.md  # 业务边界
├── naming-conventions.md    # 命名约定
└── ...                      # 按需自动归类
```

格式：纯 Markdown，无 yaml frontmatter。每条规则 1-2 行。

### 提取原则

- 只提取非通用、反直觉的规则
- 每条 1-2 行，不啰嗦
- 用户明确说的 > 代码隐含的
- 不确定是否值得记录 → 不记录

## Skills 审计设计

### 审计逻辑

1. `git diff --stat` 拿到变更文件列表
2. 读取 `{workspace}/.claude/skills/*/SKILL.md`
3. 对比 skill 中引用的文件路径/类名/模块与变更
4. 处理策略：

| 情况 | 动作 |
|------|------|
| 路径/名称变了 | 更新 skill 中的引用 |
| 功能逻辑变了 | 更新 skill 的描述和步骤 |
| 模块整个删除了 | 提示用户是否删除该 skill |
| 新增重要模块无对应 skill | 建议不自动创建，仅提示 |

### 不自动创建 skill 的原因

Skill 的编写需要理解业务意图，自动生成容易产生低质量内容。只做维护，不做创建。

## SKILL.md 改动

修改 `plugins/dev-workflow/skills/dev-workflow/SKILL.md` 的 Step 6 部分。
