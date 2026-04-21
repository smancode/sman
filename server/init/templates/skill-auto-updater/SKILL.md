---
name: skill-auto-updater
description: |
  每日自动检查并更新项目 skills。包括能力匹配和项目知识扫描。
  默认关闭，需要在 Sman 设置中启用。
---

# Skill Auto-Updater

仅在用户手动触发或 cron 启用时运行。默认不执行。

## 目标

保持项目的 `.claude/skills/` 与项目当前状态同步：
1. 更新/补充通用能力 skills（capability skills）
2. 重新生成项目知识 skills（project-structure、project-apis、project-external-calls）
3. 聚合团队知识 skills（knowledge-business、knowledge-conventions、knowledge-technical）

## 核心原则：质量 > 数量

- **宁缺毋滥**：只有真正对项目长期有价值的知识才值得变成 skill
- **总量控制**：`.claude/skills/` 下不超过 20 个 skill，超过时优先淘汰低价值
- **已有优先**：已有 skill 能覆盖的内容，只更新不新增

## 执行步骤

### 一、Capability Skills 更新

1. **读取基线** — 读取 `.sman/INIT.md` 获取上次扫描结果
2. **检测变更** — 对比项目当前状态与基线，判断是否有显著变化
3. **同步 skills** — 如有显著变化，重新匹配能力，更新或补充 `.claude/skills/` 中的通用能力 skills（不删除用户手动添加的）

### 二、Project Knowledge Skills 更新

检查以下 3 个 skill 的 `_scanned.commitHash` 是否与当前 `git rev-parse HEAD` 一致。不一致或尚未扫描时，执行对应扫描并覆写 SKILL.md 和 references/。

#### project-structure

扫描项目结构，生成 `.claude/skills/project-structure/` 下的文件：

- **SKILL.md**（含 frontmatter，总计 < 80 行）：
  - Tech stack（语言、框架、构建工具）
  - Directory tree（top 2-3 levels，排除 node_modules/.git/target/dist）
  - Module list（表格：name | path | purpose）
  - How to build and run

- **references/{name}.md**（每个 < 100 行）— 按模块：
  - Purpose、Key files、Dependencies

#### project-apis

扫描 API 端点，生成 `.claude/skills/project-apis/` 下的文件：

- **SKILL.md**：Endpoint table（Method | Path | Description | Reference File），按模块分组

- **references/{METHOD}-{slug}.md**（每个 < 100 行）— 每个端点：
  - Signature、Request parameters、Business flow、Called services、Source file
  - 文件命名：`/` 替换为 `-`，去掉前导 `-`，最多 80 字符

#### project-external-calls

扫描外部依赖，生成 `.claude/skills/project-external-calls/` 下的文件：

- **SKILL.md**：External service table（Service | Type | Purpose | Reference File）

- **references/{name}.md**（每个 < 100 行）— 每个外部服务：
  - Call method、Config source（env var 名，不写实际值）、Call locations、Purpose

### 三、Team Knowledge Skills 聚合

扫描 `{workspace}/.sman/knowledge/` 目录，将团队成员的知识文件聚合为 3 个 skill。

#### 3.0 质量过滤（执行前必读）

不是所有 .sman/knowledge/ 里的内容都值得聚合。在合并前，必须过滤掉：

**必须过滤的内容**：
- 通用技术知识（语言语法、框架用法、工具常识、LLM/SDK 工作机制）
- 临时性调试信息、排查过程
- 已过时的信息（对应的代码/配置已不存在）
- 过于琐碎的细节（看代码就能知道的）
- 重复或高度相似的条目

**值得保留的内容**：
- 项目特有的业务规则和决策（为什么要这样做，而不是怎么做）
- 踩过的坑和解决方案（不是 trivial 的）
- 团队达成共识的非显而易见的约束
- 对未来开发有实际指导意义的经验

#### 3.1 扫描来源

列出 `.sman/knowledge/` 下所有文件，按类别分组：
- `business-*.md` → knowledge-business skill
- `conventions-*.md` → knowledge-conventions skill
- `technical-*.md` → knowledge-technical skill

每个文件名中的 `*` 部分即用户名（如 `business-nasakim.md` 中 `nasakim` 为贡献者）。

#### 3.2 去重合并规则

每个知识条目由 `<!-- hash: xxx --> ... <!-- end: xxx -->` 包裹。合并策略：

1. **相同 hash**：多个文件中出现相同 hash 的条目，只保留一份（选内容最完整的）
2. **不同 hash**：全部保留，标注贡献者
3. **无 hash 标记的旧条目**：为它们生成 hash 并包裹标记
4. **排序**：按主题归类（`## 标题`），同类内按贡献者排列

#### 3.3 生成 skill 文件

为每个类别生成 `.claude/skills/knowledge-{category}/SKILL.md`：

```markdown
---
name: knowledge-{category}
description: "{描述}。从团队对话中提取，由 skill-auto-updater 聚合。"
---

# {Category Label}

> 贡献者: {用户A}, {用户B} | 更新时间: {当前日期}

## 知识点标题
> by {贡献者}
- 具体内容
- 具体内容

## 另一个知识点
> by {贡献者1}, {贡献者2}
- 具体内容
```

#### 3.4 边界条件

- 如果 `.sman/knowledge/` 目录不存在或为空，跳过本阶段（不创建空 skill）
- 如果某个类别没有任何 md 文件，跳过该类别的 skill 更新
- 不删除其他来源的知识 skill，只更新 knowledge-business/conventions/technical 这三个
- 如果过滤后某个类别没有任何有价值的内容，不生成/不更新该类别的 skill（保留空比塞垃圾好）

#### 3.5 Skill 数量控制

执行完所有阶段后，检查 `.claude/skills/` 下的 skill 总数：

1. 如果 **≤ 20 个**：正常结束
2. 如果 **> 20 个**：按以下优先级淘汰，直到 ≤ 20：
   - 优先淘汰内容为空或只有占位符的 skill
   - 然后淘汰 skill-auto-updater 自动生成的低价值 skill（如知识点只有 1-2 条且不重要的）
   - **绝不删除**用户手动创建的 skill（没有 `_scanned` 或 `skill-auto-updater` 标记的）

### 四、记录结果

更新 `.sman/INIT.md` 时间戳和匹配结果，记录本次变更内容。

## Safety Rules

1. 不读敏感文件：.env, .env.*, credentials.*, *.key, *.pem。只记录它们的存在
2. 不记录实际密码/密钥值，只记录配置来源
3. 文件路径必须精确
4. 不确定时标记 ⚠️
5. 用 Write 工具写文件，不要用 Bash/cat
6. 写之前先用 Bash `mkdir -p` 创建目录
7. Reference 文件 < 100 行，只写关键信息
8. 输出语言：English（节省 token）
9. SKILL.md frontmatter 必须设置 `_scanned` 字段（commitHash、scannedAt、branch）

## 边界条件

- 如果 `.sman/INIT.md` 不存在，跳过 capability 更新，但仍然执行 project knowledge 扫描
- 如果没有显著变化且 project skills 的 commit hash 未变，跳过本次执行
- 不要删除用户手动添加的 skills，只更新和补充
