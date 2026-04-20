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

### 三、记录结果

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
