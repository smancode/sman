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
1. **首次全量扫描** — 新项目首次运行时，并行扫描生成完整知识体系
2. **增量更新** — 后续运行只更新变更部分
3. **辩证聚合** — 团队知识去伪存真

## 核心原则

- **可靠优先**：knowledge skill 是开发者直接依赖的知识库，每条知识必须经得起验证
- **宁缺毋滥**：只有真正对项目长期有价值的知识才值得变成 skill
- **已有优先**：已有 skill 能覆盖的内容，只更新不新增
- **绝不删除**：不删除用户手动创建的 skill，不删除核心 skill（project-structure、project-apis、project-external-calls、database-schema、knowledge-business、knowledge-conventions、knowledge-technical）
- **已有优先**：已有 skill 能覆盖的内容，只更新不新增

## 模式判断

```
读取 .sman/INIT.md
  → project-structure SKILL.md 的 _scanned.commitHash 为 null？
    → 是：首次全量扫描模式（Phase 0-5）
    → 否：增量更新模式（Step 一-四）
```

---

## 首次全量扫描模式

用于新项目/新人入职，一次性生成完整知识体系。

### Phase 0: 项目识别

1. 读取 `CLAUDE.md`（如存在）提取项目基本信息
2. 扫描项目根目录结构（构建文件、配置文件、源码目录）
3. 识别技术栈特征：

| 特征文件 | 推断技术栈 |
|----------|-----------|
| `pom.xml` / `build.gradle` / `build.gradle.kts` | Java + Maven/Gradle |
| `package.json` | Node.js / 前端 |
| `go.mod` | Go |
| `requirements.txt` / `pyproject.toml` | Python |
| `Cargo.toml` | Rust |
| `*.sln` / `*.csproj` | .NET |

4. 识别架构特征：

| 特征 | 推断架构 |
|------|----------|
| Controller/Service/DAO 分层 | 经典三层/MVC |
| Handler + Proto + Caller | 消息驱动/RPC |
| graphql/ | GraphQL API |
| serverless.yml / template.yaml | Serverless |
| Dockerfile / docker-compose | 容器化部署 |

### Phase 1: 并行扫描（3 个 Agent）

**Agent A: 编码规范扫描** → `knowledge-conventions`

| 扫描项 | Java | Node.js | Go | Python |
|--------|------|---------|-----|--------|
| 枚举/常量 | enums/ + 常量类 | constants/ + enums/ | const 定义 | constants/ |
| 命名规范 | 包/类/方法命名 | 文件/函数命名 | 包/函数命名 | 模块/函数命名 |
| 配置管理 | application.yml + Spring 配置 | .env + config/ | config.yaml | settings.py |
| SQL 规范 | MyBatis XML / JPA | ORM / raw SQL | sqlc / GORM | SQLAlchemy / raw |
| 反模式 | magic number、硬编码 | magic string、any 类型 | 硬编码 error | 魔法值 |

**Agent B: 技术横切面扫描** → `knowledge-technical`

| 扫描项 | 通用关注点 |
|--------|-----------|
| 事务/一致性 | 事务管理器、传播行为、隔离级别 |
| 缓存 | 缓存框架、Key 规范、TTL 策略 |
| 通信 | HTTP/RPC/MQ 通信框架、超时配置、重试策略 |
| 并发控制 | 锁机制（Redis/DB/分布式）、并发安全 |
| 线程/协程 | 线程池配置、异步框架、调度机制 |
| 安全 | 认证鉴权、加密、审计日志 |

**Agent C: 业务链路扫描** → `knowledge-business`

1. 找到所有入口点（Controller/Handler/RPC Service/消息消费者）
2. 按业务域分组，统计 Handler 数量
3. 选取 Top 3-5 高频业务域，追踪完整链路：入口 → Service → 外部调用 → 数据访问
4. 提取跨域公共机制（如记账、冻结、状态机）

### Phase 2: 数据库扫描 → `database-schema`

按数据访问层自适应扫描：

| 技术栈 | 扫描方式 |
|--------|---------|
| MyBatis | Entity 类 + Mapper XML |
| JPA/Hibernate | Entity 类 + Repository |
| Prisma | schema.prisma |
| SQLAlchemy | models.py |
| GORM | model 结构体 |
| better-sqlite3 / sqlite3 | TypeScript/JavaScript 中的 `CREATE TABLE` 语句（`*-store.ts`、`*-repository.ts` 等文件） |
| 原生 SQL | migration 文件 / SQL 脚本 / 代码内嵌 SQL |

> **注意**：很多项目不使用 ORM，而是在代码中直接写 SQL（如 better-sqlite3 的 `db.exec(CREATE TABLE ...)`）。必须 grep `CREATE TABLE` 关键词定位所有建表语句，不能只找 migration 文件。找不到 migration 文件不代表没有数据库。

提取内容：
- 核心表结构（Top 15-20 表）
- 表间关联关系（通过字段名推测）
- 索引策略
- 分区/分表策略

无数据库的项目（纯前端、CLI 工具等）跳过本阶段，不生成 database-schema skill。

### Phase 3: Project Knowledge 扫描

执行下方「二、Project Knowledge Skills 更新」的全部内容，生成：
- `project-structure`
- `project-apis`
- `project-external-calls`

### Phase 4: 规则提取

从扫描结果中提取关键规则，写入 `.claude/rules/`（无目录先创建）：

- `coding-standards.md` — 编码规范（5-15 条，每条 1-2 行）
- `architecture-rules.md` — 架构决策（如有）

提取原则：
- 只提取项目特定的、非通用的规范
- 每条规则 1-2 行，不啰嗦
- 用户明确说的 > 代码隐含的

### Phase 5: 验证与报告

1. 逐个 Skill 检查：SKILL.md 行数 ≤ 80，references/ 文件内容完整
2. 更新 `.sman/INIT.md` 时间戳，记录首次扫描完成
3. 输出扫描报告：

```markdown
## 项目初始化报告

### 项目基本信息
- 技术栈：xxx
- 架构模式：xxx
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

扫描方法：根据项目技术栈自适应（同首次全量扫描 Phase 2 的扫描方式表）。必须先 grep `CREATE TABLE`、`@Table`、`@Entity` 等关键词确认项目有数据库，找不到不代表没有数据库——也可能是代码内嵌 SQL。

无数据库的项目跳过此 skill，不生成空文件。

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

## 边界条件

- 如果 `.sman/INIT.md` 不存在，跳过 capability 更新，但仍然执行 project knowledge 扫描
- 如果没有显著变化且 project skills 的 commit hash 未变，跳过本次执行
- 不要删除用户手动添加的 skills，只更新和补充
- 首次全量扫描的 Phase 1 应并行执行 3 个 Agent，提升效率
- 首次扫描优先级：规范 > 技术横切面 > 业务链路 > 数据库
