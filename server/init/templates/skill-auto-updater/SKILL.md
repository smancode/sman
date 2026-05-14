---
name: skill-auto-updater
description: |
  每2小时自动检查并更新项目 skills。包括首次全量扫描、增量更新和团队知识辩证聚合。
  默认关闭，需要在 Sman 设置中启用。
---

# Skill Auto-Updater

仅在用户手动触发或 cron 启用时运行。默认不执行。

## 目标

保持项目的 `.claude/skills/` 与项目当前状态同步：
1. **首次全量扫描** — 新项目首次运行时，生成完整知识体系
2. **增量更新** — 后续运行只更新变更部分
3. **辩证聚合** — 团队知识去伪存真

## 核心原则

- **可靠优先**：knowledge skill 是开发者直接依赖的知识库，每条知识必须经得起验证
- **宁缺毋滥**：只有真正对项目长期有价值的知识才值得变成 skill
- **已有优先**：已有 skill 能覆盖的内容，只更新不新增
- **绝不删除**：不删除用户手动创建的 skill，不删除核心 skill
- **上下文隔离**：每个 phase/扫描任务独立上下文，通过文件系统传递结果，避免上下文爆炸
- **最多 2 个子 agent 并行**：LLM 并发能力有限，超过 2 个并行容易报错

## 模式判断

```
读取 .sman/INIT.md
  → project-structure SKILL.md 的 _scanned.commitHash 为 null？
    → 是：首次全量扫描模式
    → 否：增量更新模式（Step 一-四）
```

---

## 首次全量扫描模式

用于新项目/新人入职，生成完整知识体系。

**关键约束**：Cron 任务有 10 分钟工具超时和 30 分钟僵尸检测。必须正确评估项目规模，大项目分批执行。

### Phase 0: 项目识别与规模评估

**本阶段必须快速完成，不使用子 agent。**

1. 读取 `CLAUDE.md`（如存在）提取项目基本信息
2. 扫描项目根目录结构（构建文件、配置文件、源码目录）
3. 识别技术栈（同下方特征文件表）
4. 识别架构特征（同下方架构特征表）
5. **评估项目规模**：

```bash
# 统计源码文件数（排除 node_modules/.git/target/dist/build）
find . -type f \( -name "*.ts" -o -name "*.tsx" -o -name "*.js" -o -name "*.jsx" -o -name "*.java" -o -name "*.go" -o -name "*.py" -o -name "*.rs" \) \
  -not -path "*/node_modules/*" -not -path "*/.git/*" -not -path "*/target/*" -not -path "*/dist/*" -not -path "*/build/*" | wc -l
```

6. **根据规模选择执行策略**：

| 规模 | 源码文件数 | 执行策略 |
|------|-----------|---------|
| 小型 | < 200 | 单次执行，Phase 1 用 2 个子 agent 并行 |
| 中型 | 200 ~ 1000 | 分 2-3 轮，每轮独立上下文，中间结果写文件 |
| 大型 | > 1000 | 分 3-5 轮，每轮独立上下文，大项目降级策略 |

7. **将评估结果写入** `.sman/scan-plan.json`（供后续 phase 读取）：

```json
{
  "scale": "small|medium|large",
  "sourceFileCount": 1234,
  "techStack": ["TypeScript", "React"],
  "architecture": "三层/MVC",
  "hasDatabase": true,
  "phases": ["knowledge", "database", "project", "rules"],
  "startedAt": "2026-01-01T00:00:00Z",
  "completedPhases": []
}
```

#### 特征文件 → 技术栈

| 特征文件 | 推断技术栈 |
|----------|-----------|
| `pom.xml` / `build.gradle` / `build.gradle.kts` | Java + Maven/Gradle |
| `package.json` | Node.js / 前端 |
| `go.mod` | Go |
| `requirements.txt` / `pyproject.toml` | Python |
| `Cargo.toml` | Rust |
| `*.sln` / `*.csproj` | .NET |

#### 架构特征

| 特征 | 推断架构 |
|------|----------|
| Controller/Service/DAO 分层 | 经典三层/MVC |
| Handler + Proto + Caller | 消息驱动/RPC |
| graphql/ | GraphQL API |
| serverless.yml / template.yaml | Serverless |
| Dockerfile / docker-compose | 容器化部署 |

---

### Phase 0.5: INIT.md 深度总结

**目标**：将 INIT.md 中的粗糙信息（如 "java 项目"、"GRADLE, SH, MD"）升级为有意义的 Summary、Description 和 TechStack。

**执行步骤**：

1. **读取 CLAUDE.md**（如存在）— 提取项目的核心定位、功能描述、技术栈信息
2. **读取当前 INIT.md** — 获取现有字段
3. **基于 CLAUDE.md 内容总结**（由主 agent 直接执行，不用子 agent）：

| 字段 | 当前问题 | 升级要求 |
|------|---------|---------|
| Summary | "java 项目" 等泛泛描述 | 一句具体的话说明项目是什么、做什么 |
| Description | 跟 Summary 重复 | 2-3 句话描述项目目的、核心功能、关键特性 |
| TechStack | 文件扩展名（GRADLE, SH, MD） | 框架/库级别（Spring Boot, React, Express） |

4. **无 CLAUDE.md 时的降级策略**：
   - 从项目目录结构、入口文件、配置文件中推断
   - 读取 `README.md`（如存在）作为补充来源
   - 读取 `package.json` 的 description 字段 / `pom.xml` 的 name+description
5. **回写 INIT.md** — 只更新 Summary、Description、TechStack 三个字段，保留其他字段不变

**示例**：

升级前：
```
**Summary:** java 项目
**Description:** java 项目
**Tech Stack:** GRADLE, SH, MD, JAR, PROPERTIES, BAT, SQL
```

升级后：
```
**Summary:** 基于 Spring Boot 的交易风控系统，支持实时反欺诈检测和交易限额管理
**Description:** 核心交易风控平台，提供规则引擎驱动的实时反欺诈检测、多维度交易限额管理、风险事件告警和审计追踪。支持与核心银行系统的实时对接，日均处理百万级交易风控决策。
**Tech Stack:** Spring Boot, MyBatis, Redis, Kafka, MySQL, Gradle
```

**注意事项**：
- Summary 不超过 50 字
- Description 不超过 150 字
- TechStack 用框架/库名，不超过 8 个
- 不确定的技术栈不要猜，宁可少写

---

### Phase 1-3: 扫描执行（根据规模分批）

**核心规则：每个扫描任务是一个独立 Task，分配给子 agent 执行。最多 2 个子 agent 并行。**

#### 小型项目（< 200 文件）

单次执行，Phase 1 用最多 2 个子 agent 并行：

| Task | 目标 Skill | 子 agent 说明 |
|------|-----------|-------------|
| Task 1 | knowledge-conventions | 编码规范扫描 |
| Task 2 | knowledge-technical | 技术横切面扫描 |
| （Task 1/2 完成后） | knowledge-business | 业务链路扫描（单 agent） |
| 数据库扫描 | database-schema | 如有数据库 |
| project 扫描 | project-structure + project-apis + project-external-calls | 单 agent 串行 |

#### 中型项目（200 ~ 1000 文件）

分 2-3 轮执行，每轮完成后结果写文件，下一轮从文件读取上下文：

**第 1 轮**（最多 2 个子 agent 并行）：

| 子 agent | 目标 Skill | 说明 |
|----------|-----------|------|
| Agent A | knowledge-conventions + knowledge-technical | 规范 + 技术横切面（轻量，合并到一个 agent） |
| Agent B | project-structure | 项目结构（为后续提供模块索引） |

**第 2 轮**（读取第 1 轮产出的 project-structure references）：

| 子 agent | 目标 Skill | 说明 |
|----------|-----------|------|
| Agent A | knowledge-business | 业务链路（基于 project-structure 定位入口） |
| Agent B | project-apis + project-external-calls | API 端点 + 外部依赖 |

**第 3 轮**（如有数据库）：

| 子 agent | 目标 Skill | 说明 |
|----------|-----------|------|
| 单 agent | database-schema | 数据库扫描 |

#### 大型项目（> 1000 文件）

分 3-5 轮，**启用降级策略**：

**降级规则**：
- 业务链路：只追踪 Top 3 核心链路，不深挖每个 Handler
- API 扫描：只记录端点清单（Method + Path + 简述），不追踪每个端点的完整调用链
- 数据库：只记录核心表（Top 15），不追踪索引/分区细节
- 规范扫描：只提取 Top 5 最显著的编码规范

**第 1 轮**：

| 子 agent | 目标 Skill | 说明 |
|----------|-----------|------|
| Agent A | project-structure | 项目结构全景（为后续所有扫描提供索引） |
| Agent B | knowledge-conventions | 编码规范（降级：Top 5 规范） |

**第 2 轮**（基于 project-structure 的模块索引）：

| 子 agent | 目标 Skill | 说明 |
|----------|-----------|------|
| Agent A | project-apis | API 端点清单（降级：不追踪完整调用链） |
| Agent B | project-external-calls | 外部依赖清单 |

**第 3 轮**：

| 子 agent | 目标 Skill | 说明 |
|----------|-----------|------|
| Agent A | knowledge-technical | 技术横切面 |
| Agent B | knowledge-business | 业务链路（降级：Top 3 核心链路） |

**第 4 轮**（如有数据库）：

| 子 agent | 目标 Skill | 说明 |
|----------|-----------|------|
| 单 agent | database-schema | 数据库全景（降级：Top 15 表） |

#### 子 agent 执行规范

每个子 agent 必须遵循以下规范：

1. **Task 描述**：明确目标 skill、扫描范围、降级规则（如适用）、输出文件路径
2. **独立上下文**：子 agent 不继承主 agent 的对话历史，通过文件传递信息
   - 输入：读取 `.sman/scan-plan.json`、`project-structure/references/`（如已有）
   - 输出：写入对应的 `.claude/skills/{skill-name}/SKILL.md` 和 `references/`
3. **完成后标记**：每个 skill 扫描完成后，更新 `.sman/scan-plan.json` 的 `completedPhases` 数组
4. **错误隔离**：某个子 agent 失败不影响其他，记录失败并继续

---

### Phase 4: 规则提取

从已完成的扫描结果中提取关键规则，写入 `.claude/rules/`（无目录先创建）：

- `coding-standards.md` — 编码规范（5-15 条，每条 1-2 行）
- `architecture-rules.md` — 架构决策（如有）

提取原则：
- 只提取项目特定的、非通用的规范
- 每条规则 1-2 行，不啰嗦
- 用户明确说的 > 代码隐含的

### Phase 5: 验证与报告

1. 逐个 Skill 检查：SKILL.md 行数 ≤ 80，references/ 文件内容完整
2. 更新 `.sman/INIT.md` 时间戳，记录首次扫描完成
3. 清理 `.sman/scan-plan.json`（扫描完成后删除）
4. 输出扫描报告：

```markdown
## 项目初始化报告

### 项目基本信息
- 技术栈：xxx
- 架构模式：xxx
- 规模：small/medium/large（源码文件数）
- 入口点数量：xxx
- 外部依赖数量：xxx

### Skill 生成情况
| Skill | 状态 | 摘要行数 | references 文件数 |
|-------|------|----------|------------------|

### 关键发现
| 发现 | 影响 |
|------|------|

### 未覆盖（后续补充）
| 内容 | 建议 |
|------|------|
```

---

## 增量更新模式

后续运行时只更新变更部分。

### 一、Capability Skills 更新

1. **读取基线** — 读取 `.sman/INIT.md` 获取上次扫描结果
2. **检测变更** — 对比项目当前状态与基线，判断是否有显著变化
3. **同步 skills** — 如有显著变化，重新匹配能力，更新或补充 `.claude/skills/` 中的通用能力 skills（不删除用户手动添加的）

### 二、Project Knowledge Skills 更新

检查以下 4 个 skill 的 `_scanned.commitHash` 是否与当前 `git rev-parse HEAD` 一致。不一致或尚未扫描时，执行对应扫描并覆写 SKILL.md 和 references/。

**增量更新也遵循最多 2 个子 agent 并行的限制。** 每个扫描任务作为独立 Task 分配给子 agent。

#### project-structure

扫描项目结构，生成 `.claude/skills/project-structure/` 下的文件：

- **SKILL.md**（含 frontmatter，总计 < 80 行）：
  - Tech stack（语言、框架、构建工具）
  - Directory tree（top 2-3 levels，排除 node_modules/.git/target/dist）
  - Module list（表格：name | path | purpose）
  - How to build and run

- **references/{name}.md**（每个 < 100 行）— 按模块：Purpose、Key files、Dependencies

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

#### database-schema

扫描数据库结构，生成 `.claude/skills/database-schema/` 下的文件：

- **SKILL.md**：Database overview（Engine | Table count | Key relationships），核心表清单（Table | Columns | Purpose | Reference File）

- **references/{table-name}.md**（每个 < 100 行）— 每张核心表：
  - CREATE TABLE DDL、Column detail（Name | Type | Nullable | Description）、Indexes、Foreign keys、Source file location

扫描方法：根据项目技术栈自适应（同首次全量扫描 Phase 0 的识别结果）。必须先 grep `CREATE TABLE`、`@Table`、`@Entity` 等关键词确认项目有数据库。

无数据库的项目跳过此 skill，不生成空文件。

### 三、Team Knowledge Skills 辩证聚合

扫描 `{workspace}/.sman/knowledge/` 目录，对团队知识进行**辩证聚合**：验证真实性、标记冲突、记录变迁。

#### 目标

生成的 knowledge skill 是开发者可以直接依赖的可靠知识库。

#### 3.1 扫描来源

列出 `.sman/knowledge/` 下所有文件，按类别分组：
- `business-*.md` → knowledge-business skill
- `conventions-*.md` → knowledge-conventions skill
- `technical-*.md` → knowledge-technical skill

#### 3.2 辩证聚合

对每个类别的所有知识条目，执行以下处理：

**验证**：用 Read/Grep 工具查代码，确认每条知识是否仍然成立。

**冲突处理**：多个用户对同一问题有不同说法时，以代码实际情况为准。

**变迁处理**：如果源文件中可观察到方案变迁（A→B），记录变迁过程。

**存疑处理**：无法通过代码验证的知识，保留但标记待验证。

**淘汰**：已验证为过时或错误的条目，不写入 skill。

**上限**：每次验证最多处理 30 条知识条目。超出部分留到下一轮。

#### 3.3 验证标记

每条知识必须带一个验证标记：

| 标记 | 含义 | 用法 |
|------|------|------|
| `✅ [已验证]` | 代码确认成立 | 后附代码位置 |
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
```

#### 3.5 源文件清理

**安全约束**：只在 skill 文件写入成功后才清理源文件。

对每个 `.sman/knowledge/{category}-{user}.md`，删除已被处理的 hash 条目。只保留尚未处理的新条目。

如果清理后文件为空或只剩标题，保留文件但清空内容。

#### 3.6 边界条件

- `.sman/knowledge/` 不存在或为空 → 跳过本阶段
- 某类别无 md 文件 → 跳过该类别
- 过滤后某类别无有价值内容 → 不生成空 skill
- 所有条目验证失败 → 不生成 skill，源文件照常清理
- 验证超过 30 条 → 只处理最新 30 条，剩余留到下一轮
- 不删除用户手动创建的 skill

### 四、记录结果

更新 `.sman/INIT.md` 时间戳和匹配结果，记录本次变更内容。

---

## Skill 文件结构规范

```
.claude/skills/{skill-name}/
├── SKILL.md              # 摘要（≤80行）+ references 索引
└── references/           # 详细内容
    ├── xxx.md
    └── yyy.md
```

### SKILL.md 格式

```markdown
---
name: {skill-name}
description: "一句话描述。TRIGGER: 关键词1/关键词2"
_scanned:
  commitHash: {git hash}
  scannedAt: "{ISO date}"
  branch: "{branch name}"
---

# 标题

> 一句话概要

## 概要

| 章节 | 要点 | 详见 |
|------|------|------|
| xxx | 一句话摘要 | references/xxx.md |

## 速查（最常用的 5-10 行关键信息）
```

### Skill 名称清单

| Skill 名称 | 内容 | 首次必选 | 增量必选 |
|------------|------|----------|----------|
| project-structure | 目录结构、技术栈、构建命令 | ✅ | ✅ |
| project-apis | 入口点清单 | ✅ | ✅ |
| project-external-calls | 外部依赖清单 | ✅ | ✅ |
| knowledge-conventions | 编码规范 | ✅ | ✅ |
| knowledge-technical | 技术横切面 | ✅ | ✅ |
| knowledge-business | 业务链路 | ✅ | ✅ |
| database-schema | 数据库全景 | 有数据库时 ✅ | ✅ |

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
10. **最多 2 个子 agent 并行**，超过容易导致 LLM 报错
11. 每个子 agent 是独立上下文，不继承主 agent 对话历史
12. 通过文件系统（`.sman/scan-plan.json`、references/）在 phase 间传递状态

## 边界条件

- 如果 `.sman/INIT.md` 不存在，跳过 capability 更新，但仍然执行 project knowledge 扫描
- 如果没有显著变化且 project skills 的 commit hash 未变，跳过本次执行
- 不要删除用户手动添加的 skills，只更新和补充
- 首次扫描优先级：规范 > 技术横切面 > 业务链路 > 数据库
- 项目规模评估在 Phase 0 完成，后续 phase 严格按评估结果执行
