---
name: skill-auto-updater
description: |
  每30分钟自动检查并更新项目 skills。包括能力匹配、项目知识扫描和团队知识辩证聚合。
  默认关闭，需要在 Sman 设置中启用。
---

# Skill Auto-Updater

仅在用户手动触发或 cron 启用时运行。默认不执行。

## 目标

保持项目的 `.claude/skills/` 与项目当前状态同步：
1. 更新/补充通用能力 skills（capability skills）
2. 重新生成项目知识 skills（project-structure、project-apis、project-external-calls）
3. 辩证聚合团队知识 skills（knowledge-business、knowledge-conventions、knowledge-technical）

## 核心原则

- **可靠优先**：knowledge skill 是开发者直接依赖的知识库，每条知识必须经得起验证
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

### 三、Team Knowledge Skills 辩证聚合

扫描 `{workspace}/.sman/knowledge/` 目录，对团队知识进行**辩证聚合**：验证真实性、标记冲突、记录变迁。

#### 目标

生成的 knowledge skill 是开发者可以直接依赖的可靠知识库。源文件（`.sman/knowledge/*.md`）是快速变动的原始素材，可能包含错误、过时信息、个人误解。你的职责是去伪存真，生成经得起验证的知识。

#### 3.1 扫描来源

列出 `.sman/knowledge/` 下所有文件，按类别分组：
- `business-*.md` → knowledge-business skill
- `conventions-*.md` → knowledge-conventions skill
- `technical-*.md` → knowledge-technical skill

每个文件名中的 `*` 部分即用户名。

#### 3.2 辩证聚合

对每个类别的所有知识条目，执行以下处理：

**验证**：用 Read/Grep 工具查代码，确认每条知识是否仍然成立。

**冲突处理**：多个用户对同一问题有不同说法时，以代码实际情况为准。保留所有方的说法，标注代码实际的实现。

**变迁处理**：如果源文件中可观察到方案变迁（A→B），记录变迁过程。

**存疑处理**：无法通过代码验证的知识，保留但标记待验证。

**淘汰**：已验证为过时或错误的条目（代码中已不存在对应实现），不写入 skill。

**上限**：每次验证最多处理 30 条知识条目。超出部分留到下一轮。

#### 3.3 验证标记

每条知识必须带一个验证标记：

| 标记 | 含义 | 用法 |
|------|------|------|
| `✅ [已验证]` | 代码确认成立 | 后附代码位置，如 `src/auth.ts:L42` |
| `⚠️ [冲突]` | 多用户说法不同 | 列出各方说法，以代码实际为准 |
| `🔄 [变迁]` | 方案发生过变化 | 记录旧方案→新方案 |
| `❓ [待验证]` | 无法通过代码验证 | 保留但提醒使用者 |

#### 3.4 输出格式

为每个类别生成 `.claude/skills/knowledge-{category}/SKILL.md`：

```markdown
---
name: knowledge-{category}
description: "{描述}。经代码验证，由 skill-auto-updater 聚合。"
---

# {Category Label}

> 贡献者: {用户A}, {用户B} | 验证时间: {YYYY-MM-DD}

## 知识点标题
> by {贡献者} | 验证: {YYYY-MM}
✅ [已验证] src/path/file.ts:L42
- 具体内容

## 有冲突的知识点
> by {用户A}, {用户B} | 验证: {YYYY-MM}
⚠️ [冲突] {用户A}: 方案A; {用户B}: 方案B → 代码实际: 方案A
- 具体内容

## 方案已变更的知识点
> by {贡献者} | 验证: {YYYY-MM}
🔄 [变迁] 旧: 方案A → 新: 方案B
- 具体内容

## 存疑的知识点
> by {贡献者} | 验证: {YYYY-MM}
❓ [待验证] 未找到对应代码实现
- 具体内容
```

#### 3.5 源文件清理

**安全约束**：只在 skill 文件写入成功后才清理源文件。如果写入失败，不清理。

对每个 `.sman/knowledge/{category}-{user}.md`，删除已被处理的 hash 条目（`<!-- hash: xxx --> ... <!-- end: xxx -->` 整段移除）。只保留尚未处理的新条目，作为下一轮的素材。

如果清理后文件为空或只剩标题，保留文件但清空内容（下次 KnowledgeExtractor 会重新填充）。

#### 3.6 边界条件

- `.sman/knowledge/` 不存在或为空 → 跳过本阶段
- 某类别无 md 文件 → 跳过该类别
- 过滤后某类别无有价值内容 → 不生成空 skill
- 所有条目验证失败 → 不生成 skill，源文件照常清理
- 验证超过 30 条 → 只处理最新 30 条，剩余留到下一轮
- 不删除用户手动创建的 skill

#### 3.7 Skill 数量控制

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
