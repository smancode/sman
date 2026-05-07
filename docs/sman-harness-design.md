# Sman Harness Engineering 设计方案

> 基于 [Harness Engineering](https://mp.weixin.qq.com/s/rlIyIIZOXFObNIXbPI7gDg) 理念的 Sman 本土化改造
>
> 创建时间：2025-05-07
> 状态：草稿

## 目录

- [一、核心洞察](#一核心洞察)
- [二、当前架构问题](#二当前架构问题)
- [三、.sman Harness 四要素架构](#三sman-harness-四要素架构)
- [四、增强点：Sman 特有能力](#四增强点sman-特有能力)
- [五、迁移方案](#五迁移方案)
- [六、实施路径](#六实施路径)

---

## 一、核心洞察

### 1.1 .harness 的本质

**Harness Engineering 不是一次性的 Prompt 优化，而是一个持续演进的系统工程闭环：**

> "Every time you discover an agent has made a mistake, you take the time to engineer a solution so that it can never make that mistake again." — Mitchell Hashimoto

**核心目标：** 弥合从"模型原始能力"到"可信赖的工程产出"之间的系统性鸿沟。

**四根支柱：**
1. **上下文架构**：分层加载，按需获取
2. **Agent 专业化**：受限工具集的专业 Agent 优于通用 Agent
3. **持久化记忆**：进度持久化在文件系统，而非上下文窗口
4. **结构化执行**：永远不让 Agent 在未经审查和批准书面计划之前写代码

### 1.2 Sman 的独特优势

相比于文章中的 Java 单体应用，Sman 已经具备了大量 Harness 能力的基础设施：

| 能力 | .harness 文章 | Sman 现有 | 本方案增强 |
|------|--------------|-----------|-----------|
| Agent 协作 | 通过 MCP Server 调用 | ✅ Stardom（协作星图） | 增强 Agent 定义 + 编排 |
| 工作流编排 | 10 阶段流程 | ✅ SmartPath（地球路径） | 标准化 Flow Template |
| 能力匹配 | 手动配置 | ✅ Capability Matcher（自动） | 增强语义匹配 |
| 知识管理 | Wiki 知识库 | ✅ .sman/knowledge（自动提取） | 结构化索引 |
| 技能注入 | 手动复制 | ✅ Skill Injector（自动） | 迁移到 .sman/skills |
| 变更追踪 | .harness/changes/ | ❌ 缺失 | 新增 |
| MCP 集成 | .harness/mcp/ | ✅ 已支持（配置在 ~/.sman） | 项目级 MCP 配置 |

---

## 二、当前架构问题

### 2.1 职责混淆

**现状：**
```
{workspace}/.claude/
├── skills/              # ❌ 业务能力绑定在 Claude 工具目录下
│   ├── project-apis/
│   ├── project-structure/
│   ├── database-schema/
│   └── ...
├── settings.json         # ✅ Claude Code 特定配置
└── knowledge-manifest.json

{workspace}/.sman/
├── knowledge/            # ✅ 团队知识（已有）
└── paths/                # ✅ 地球路径（已有）
```

**问题：**
1. **可移植性差**：切换到 Cursor/Copilot 时，`.claude/skills` 无法复用
2. **职责不清**：`.sman` 作为 Sman 的工作区目录，角色太弱
3. **违背设计理念**：Sman 应该是能力中心，但实际能力都耦合在 `.claude` 下

### 2.2 所有代码硬编码 `.claude/skills`

**受影响的文件：**
- `server/init/skill-injector.ts:18`
- `server/cron-scheduler.ts:189`
- `server/index.ts:1223`
- `server/init/capability-matcher.ts:327`

### 2.3 缺失的关键能力

| 缺失能力 | 影响 |
|---------|------|
| **变更管理**（.sman/changes/） | 无法追溯每个需求的完整过程 |
| **Agent 定义**（.sman/agents/） | 无法标准化 Agent 的角色和职责 |
| **规则体系**（.sman/rules/） | 项目特定的约束散落在各处 |
| **MCP 项目配置**（.sman/mcp/） | 无法为项目配置专用的外部工具 |

---

## 三、.sman Harness 四要素架构

### 3.1 完整目录结构

```
.sman/                        # 项目能力中心（工具无关）
├── INIT.md                   # 初始化标记（已有）
├── MANIFEST.md               # 能力清单（新增）- 四要素索引
│
├── agents/                   # Agent 定义（新增）
│   ├── application-owner.md  # 应用 Owner Agent
│   ├── planner.md            # 规划 Agent
│   ├── generator.md          # 实现 Agent
│   └── evaluator.md          # 评审 Agent
│
├── skills/                   # 技能体系（从 .claude 迁移）
│   ├── 📁 project-apis/      # 项目 API 知识
│   ├── 📁 project-structure/ # 项目结构知识
│   ├── 📁 database-schema/   # 数据库 Schema
│   ├── 📁 project-external-calls/ # 外部依赖
│   ├── 📁 knowledge-business/    # 业务知识技能
│   ├── 📁 knowledge-conventions/  # 开发规范技能
│   ├── 📁 knowledge-technical/    # 技术知识技能
│   └── 📁 superpowers/            # superpowers 技能（从全局注入）
│
├── knowledge/                # 团队知识（已有）
│   ├── business-{username}.md
│   ├── conventions-{username}.md
│   └── technical-{username}.md
│
├── rules/                    # 规则体系（新增）
│   ├── project-structure.md  # 项目结构约束
│   ├── coding-standards.md   # 编码规范
│   ├── api-design.md         # API 设计规范
│   └── testing-requirements.md # 测试要求
│
├── changes/                  # 变更管理（新增）
│   └── {changeId}/
│       ├── MANIFEST.md       # 变更元数据
│       ├── spec/             # 需求分析阶段
│       ├── plan/             # 计划阶段
│       ├── implementation/   # 实现阶段
│       ├── review/           # 评审阶段
│       └── deployment/       # 部署阶段
│
├── paths/                    # 地球路径（已有）
│   └── {pathId}/
│       ├── path.md
│       ├── runs/
│       ├── reports/
│       └── references/
│
└── mcp/                      # MCP 项目配置（新增）
    ├── web-access.json       # Web 访问配置
    ├── database.json         # 数据库连接配置
    └── custom-tools.json     # 自定义工具配置
```

### 3.2 MANIFEST.md - 四要素索引

**作用：** 类似于 .harness 的 Agent 定义文件，作为"地图"索引，告诉 Agent 在什么阶段该去哪里找什么知识。

```markdown
# {项目名称} 能力清单

> Last updated: 2025-05-07

## 项目概览

- **项目类型**: {类型}
- **技术栈**: {技术栈}
- **核心约束**: {关键约束}

---

## 四要素索引

### 1. Agents（编排中枢）

| Agent | 路径 | 触发场景 |
|-------|------|---------|
| Application Owner | `.sman/agents/application-owner.md` | 需求接收、全流程编排 |
| Planner | `.sman/agents/planner.md` | 需求拆解、任务规划 |
| Generator | `.sman/agents/generator.md` | 编码实现 |
| Evaluator | `.sman/agents/evaluator.md` | 代码评审、质量检查 |

### 2. Skills（可复用流程）

| 技能 | 路径 | 触发场景 |
|------|------|---------|
| 项目 APIs | `.sman/skills/project-apis/` | 需要了解项目 API 时 |
| 数据库 Schema | `.sman/skills/database-schema/` | 需要了解数据模型时 |
| 开发规范 | `.sman/skills/knowledge-conventions/` | 编码前查看规范 |
| TDD | `.sman/skills/superpowers/test-driven-development/` | 实现功能前先写测试 |
| Debugging | `.sman/skills/superpowers/systematic-debugging/` | 排查问题时 |

### 3. Knowledge（业务上下文）

| 知识类型 | 路径 | 加载时机 |
|---------|------|---------|
| 业务知识 | `.sman/knowledge/business-*.md` | L1 - 会话常驻 |
| 开发规范 | `.sman/knowledge/conventions-*.md` | L1 - 会话常驻 |
| 技术知识 | `.sman/knowledge/technical-*.md` | L2 - 按需加载 |

### 4. Rules（稳定约束）

| 规则 | 路径 | 作用范围 |
|------|------|---------|
| 项目结构 | `.sman/rules/project-structure.md` | 所有阶段 |
| 编码规范 | `.sman/rules/coding-standards.md` | 编码阶段 |
| API 设计 | `.sman/rules/api-design.md` | API 设计阶段 |

---

## 工作流程映射

- **SmartPath 流程** → `.sman/paths/{pathId}/`
- **Stardom 协作** → `.sman/agents/{agent}.md` 定义
- **变更追踪** → `.sman/changes/{changeId}/`
```

### 3.3 Element 1: Agents（Agent 定义）

**借鉴文章：** Application Owner Agent 作为编排中枢

**Sman 增强：** 结合 Stardom（协作星图）的多 Agent 协作能力

#### 3.3.1 Application Owner Agent 定义模板

```markdown
# Application Owner - {项目名称}

## 角色定位

你是「{项目名称}」的应用 Owner，是整个项目的第一负责人。

## 项目背景

### 核心信息
- **项目类型**: {类型}
- **主要技术栈**: {技术栈}
- **目录结构**: {关键目录说明}

### 关键约束
- **核心约束 1**: {例如：价格字段必须用 long 类型，单位为分}
- **核心约束 2**: {例如：所有外部调用必须设置超时和降级}
- **高频变更区**: {例如：配置中心、流程编排文件}

---

## 配置中枢索引

### Skills（按阶段加载）
| 阶段 | 加载技能 | 路径 |
|------|---------|------|
| 需求分析 | request-analysis | `.sman/skills/superpowers/request-analysis/` |
| 规划 | writing-plans | `.sman/skills/superpowers/writing-plans/` |
| 实现 | test-driven-development | `.sman/skills/superpowers/test-driven-development/` |
| 评审 | code-reviewer | `.sman/skills/superpowers/requesting-code-review/` |

### Knowledge（推荐阅读路径）
- 快速上手: `.sman/knowledge/business-{username}.md` → `conventions-{username}.md`
- 业务开发: `business-{username}.md` → `.sman/skills/project-apis/`
- 数据对接: `.sman/skills/database-schema/` → `technical-{username}.md`

### Rules（硬性约束）
- 所有阶段必须遵守: `.sman/rules/project-structure.md`
- 编码阶段必须遵守: `.sman/rules/coding-standards.md`

---

## 核心职责

### 1. 需求理解与澄清
- 主动用简短问句快速验证假设
- 不清晰的描述立即追问，不要基于模糊假设做决定

### 2. 任务拆解
- 使用 SmartPath 创建标准流程
- 每个子任务明确：目标、范围、输入输出、验收标准、依赖关系

### 3. 协作调度
- 需要专业 Agent 时，通过 Stardom 创建协作任务
- 明确指定 Agent 的职责和产出要求

### 4. 质量把关
- 关键路径必须有可验证的证据
- 必要时主动要求补充单元测试或集成验证

### 5. 变更记录
- 每个需求创建独立的 Change
- 每个阶段完成后立即更新 MANIFEST.md

---

## 工作流程调度

### 标准开发流程

```
需求分析 → 计划 → 实现 → 评审 → 验证 → 部署
```

每个阶段结束后：
1. 更新 `.sman/changes/{changeId}/MANIFEST.md`
2. 触发下一阶段或回退
3. 5 个 Human-in-the-Loop 确认点

---

## 沟通原则

### 必须做到
- 任何工作开始前必须优先读取规则文件
- 每次变更前先理解现有代码逻辑
- 任务验收必须有可验证的证据
- 代码变更必须同步文档

### 禁止做的
- 不在未理解需求的情况下直接动手
- 不跳过验收直接交付
- 不隐瞒执行过程中发现的问题
- 不做超出需求范围的过度重构
```

#### 3.3.2 Stardom 协作增强

**现状：** Stardom 支持多 Agent 协作，但 Agent 定义不够标准化

**增强方案：**
1. **标准化 Agent 注册表**
   ```typescript
   // .sman/agents/registry.json
   {
     "agents": [
       {
         "id": "planner",
         "name": "规划 Agent",
         "description": "负责需求拆解和任务规划",
         "definition": ".sman/agents/planner.md",
         "capabilities": ["task-breakdown", "dependency-analysis"],
         "tools": ["read", "grep", "glob"]
       },
       {
         "id": "evaluator",
         "name": "评审 Agent",
         "description": "负责代码评审和质量检查",
         "definition": ".sman/agents/evaluator.md",
         "capabilities": ["code-review", "quality-check"],
         "tools": ["read", "grep", "bash"]
       }
     ]
   }
   ```

2. **协作协议标准化**
   - 明确 Agent 之间的消息格式
   - 定义状态转换图
   - 支持回退和重试

### 3.4 Element 2: Skills（技能体系）

**现状：** Skills 在 `.claude/skills/`，需要迁移到 `.sman/skills/`

**迁移规则：**
```
.claude/skills/
├── project-apis/           → .sman/skills/project-apis/
├── project-structure/      → .sman/skills/project-structure/
├── database-schema/        → .sman/skills/database-schema/
├── project-external-calls/ → .sman/skills/project-external-calls/
├── knowledge-business/     → .sman/skills/knowledge-business/
├── knowledge-conventions/  → .sman/skills/knowledge-conventions/
└── knowledge-technical/    → .sman/skills/knowledge-technical/
```

**Superpowers Skills（全局注入）：**
- `~/.sman/skills/superpowers/*` → 每个项目初始化时自动注入到 `.sman/skills/superpowers/`
- 不包含用户自定义的 skills

### 3.5 Element 3: Knowledge（知识库）

**现状：** 已有 `.sman/knowledge/`，由 skill-auto-updater 自动提取

**增强：**
1. **结构化索引**
   ```markdown
   # .sman/knowledge/INDEX.md
   ## 快速查找
   - 核心业务概念: `business-{username}.md#核心概念`
   - API 调用规范: `conventions-{username}.md#API`
   - 数据库连接: `technical-{username}.md#数据库`
   ```

2. **分层加载策略**
   - **L1（常驻）**: `business-{username}.md` + `conventions-{username}.md`
   - **L2（按需）**: `technical-{username}.md` + 具体的技能文档

### 3.6 Element 4: Changes（变更管理）

**新增能力：** 借鉴 `.harness/changes/` 的设计

#### 3.6.1 变更目录结构

```
.sman/changes/{changeId}/
├── MANIFEST.md             # Single Source of Truth
├── spec/                   # 需求分析阶段
│   ├── request.md          # 原始需求
│   ├── analysis.md         # 需求分析
│   └── clarification.md    # 澄清记录
├── plan/                   # 计划阶段
│   ├── tasks.md            # 任务拆解
│   └── dependencies.md     # 依赖分析
├── implementation/         # 实现阶段
│   ├── changes.md          # 代码变更清单
│   └── progress.md         # 进度记录
├── review/                 # 评审阶段
│   ├── review-v1.md        # 评审报告（版本递增）
│   └── review-v2.md
└── deployment/             # 部署阶段
    ├── verification.md     # 部署验证
    └── summary.md          # 完整总结
```

#### 3.6.2 MANIFEST.md 模板

```markdown
# {变更名称} - 变更清单

> Change ID: {changeId}
> Created: 2025-05-07
> Status: In Progress

## 元数据

- **类型**: {feature/bugfix/refactor}
- **优先级**: {high/medium/low}
- **负责人**: {username}
- **预计工时**: {Xh}

---

## 执行状态

| 阶段 | 状态 | 开始时间 | 完成时间 | 备注 |
|------|------|---------|---------|------|
| 需求分析 | ✅ | 05-07 10:00 | 05-07 10:30 | 1 轮澄清 |
| 计划 | ⏳ | - | - | - |
| 实现 | ⏸️ | - | - | 依赖计划完成 |
| 评审 | ⏸️ | - | - | - |
| 部署 | ⏸️ | - | - | - |

---

## 评审记录

| 轮次 | 类型 | 状态 | 意见数 | 评审人 |
|------|------|------|--------|--------|
| v1 | Plan Review | APPROVED | 3 | - |
| v2 | Code Review | PENDING | - | - |

---

## 变更统计

- **新增文件**: 0
- **修改文件**: 0
- **删除文件**: 0
- **新增行数**: 0
- **删除行数**: 0

---

## 链接

- **需求文档**: `spec/request.md`
- **计划文档**: `plan/tasks.md`
- **实现记录**: `implementation/changes.md`
- **最新评审**: `review/review-v1.md`
```

---

## 四、增强点：Sman 特有能力

### 4.1 SmartPath 标准化 Flow Templates

**现状：** SmartPath 支持自定义工作流，但缺少标准化模板

**增强：** 预定义 Harness 标准流程

```typescript
// .sman/paths/templates/harness-10-stage.json
{
  "name": "Harness 10 阶段开发流程",
  "description": "基于 Harness Engineering 的标准开发流程",
  "steps": [
    {
      "id": "requirement-analysis",
      "name": "需求分析",
      "skill": "request-analysis",
      "output": "spec/request.md",
      "qualityGate": "用户确认需求理解正确"
    },
    {
      "id": "requirement-review",
      "name": "需求评审",
      "skill": "expert-reviewer",
      "output": "review/requirement-review-v1.md",
      "qualityGate": "评审通过或需要修改"
    },
    {
      "id": "planning",
      "name": "计划",
      "skill": "writing-plans",
      "output": "plan/tasks.md",
      "qualityGate": "计划获得批准"
    },
    {
      "id": "implementation",
      "name": "实现",
      "skill": "test-driven-development",
      "output": "implementation/changes.md",
      "qualityGate": "所有测试通过"
    },
    {
      "id": "code-review",
      "name": "代码评审",
      "skill": "code-reviewer",
      "output": "review/code-review-v1.md",
      "qualityGate": "评审通过或需要修改"
    },
    {
      "id": "verification",
      "name": "验证",
      "skill": "verification-before-completion",
      "output": "deployment/verification.md",
      "qualityGate": "所有验证通过"
    },
    {
      "id": "deployment",
      "name": "部署",
      "skill": "custom",
      "output": "deployment/summary.md",
      "qualityGate": "部署成功且无回滚"
    }
  ],
  "rollback": {
    "verification-failed": "implementation",
    "review-rejected": "implementation",
    "requirement-rejected": "requirement-analysis"
  }
}
```

**用户使用：**
```javascript
// 创建新 Path 时选择模板
await smartPathEngine.create({
  template: 'harness-10-stage',
  workspace: '/path/to/project',
  name: '用户登录功能开发'
});
```

### 4.2 Capability Matcher 增强

**现状：** 基于项目元数据匹配能力

**增强点 1：** 语义化匹配
```typescript
// 当前：基于关键词匹配
if (text.includes('database')) { matchedIds.push('database-schema'); }

// 增强：基于语义理解
const semanticMatch = await llm.semanticMatch(
  projectDescription,
  capabilityDescription
);
```

**增强点 2：** 依赖分析
```typescript
// 自动检测能力依赖
if (matchedIds.includes('project-apis')) {
  // API 项目通常需要数据库知识
  matchedIds.push('database-schema');
  // 需要 external-calls 知识
  matchedIds.push('project-external-calls');
}
```

### 4.3 Stardom + SmartPath 深度集成

**场景：** 开发"用户登录功能"

**流程：**
1. **Application Owner Agent** 接收需求
2. 创建 SmartPath：`harness-10-stage` 模板
3. 需求分析阶段：
   - 加载 `.sman/skills/superpowers/request-analysis/`
   - 产出 `spec/request.md`
4. 计划阶段：
   - 通过 Stardom 调用 **Planner Agent**
   - Planner Agent 只能使用：`read`, `grep`, `glob` 工具
   - 产出 `plan/tasks.md`
5. 实现阶段：
   - 通过 Stardom 调用 **Generator Agent**
   - Generator Agent 可使用：`read`, `write`, `edit`, `bash` 工具
   - 每次提交前更新 `implementation/changes.md`
6. 评审阶段：
   - 通过 Stardom 调用 **Evaluator Agent**
   - Evaluator Agent 只能使用：`read`, `grep` 工具
   - 产出 `review/code-review-v1.md`
7. 验证阶段：
   - 运行测试、部署验证
   - 更新 `deployment/verification.md`

**关键：** 每个阶段的 Agent 只能访问该阶段需要的工具，避免"一步到位"的失败模式

---

## 五、迁移方案

### 5.1 一次性迁移（方案 A）

#### 5.1.1 迁移脚本

```typescript
// server/migrations/migrate-to-sman-harness.ts
import fs from 'fs';
import path from 'path';

export function migrateToSmanHarness(workspace: string): void {
  const claudeSkillsDir = path.join(workspace, '.claude', 'skills');
  const smanSkillsDir = path.join(workspace, '.sman', 'skills');

  // 1. 创建 .sman 目录结构
  const dirs = [
    path.join(workspace, '.sman', 'agents'),
    path.join(workspace, '.sman', 'skills'),
    path.join(workspace, '.sman', 'rules'),
    path.join(workspace, '.sman', 'changes'),
    path.join(workspace, '.sman', 'mcp'),
  ];
  dirs.forEach(d => fs.mkdirSync(d, { recursive: true }));

  // 2. 迁移 skills
  if (fs.existsSync(claudeSkillsDir)) {
    fs.renameSync(claudeSkillsDir, smanSkillsDir);
  }

  // 3. 创建 MANIFEST.md
  const manifestPath = path.join(workspace, '.sman', 'MANIFEST.md');
  fs.writeFileSync(manifestPath, generateManifest(workspace));

  // 4. 创建默认 Agent 定义
  const agentDir = path.join(workspace, '.sman', 'agents');
  createDefaultAgents(agentDir);

  // 5. 更新 INIT.md
  const initPath = path.join(workspace, '.sman', 'INIT.md');
  updateInitVersion(initPath, '2.0.0');

  console.log(`✅ Migration complete for ${workspace}`);
}
```

#### 5.1.2 代码修改

**修改 1：skill-injector.ts**
```typescript
// 修改前
const skillsDir = path.join(workspace, '.claude', 'skills');

// 修改后
const skillsDir = path.join(workspace, '.sman', 'skills');
```

**修改 2：cron-scheduler.ts**
```typescript
// 修改前
const skillsDir = path.join(workspace, '.claude', 'skills');

// 修改后
const skillsDir = path.join(workspace, '.sman', 'skills');
```

**修改 3：index.ts（skills.listProject API）**
```typescript
// 修改前
const skillsDir = path.join(msg.workspace, '.claude', 'skills');

// 修改后
const skillsDir = path.join(msg.workspace, '.sman', 'skills');
```

**修改 4：project-scanner.ts**
```typescript
// 修改前
const skillsDir = path.join(entry.path, '.claude', 'skills');

// 修改后
const skillsDir = path.join(entry.path, '.sman', 'skills');
```

#### 5.1.3 兼容性处理

**软链接支持（向后兼容）：**
```typescript
// 迁移后创建软链接
const claudeSkillsLink = path.join(workspace, '.claude', 'skills');
if (!fs.existsSync(claudeSkillsLink)) {
  fs.symlinkSync(
    path.join(workspace, '.sman', 'skills'),
    claudeSkillsLink,
    'dir'
  );
}
```

### 5.2 初始化流程更新

**server/init/init-manager.ts**
```typescript
export async function initializeWorkspace(
  workspace: string,
  force: boolean = false
): Promise<InitResult> {
  // ... 现有逻辑 ...

  // 创建 .sman 目录结构（新增）
  const dirs = [
    path.join(workspace, '.sman', 'agents'),
    path.join(workspace, '.sman', 'skills'),
    path.join(workspace, '.sman', 'rules'),
    path.join(workspace, '.sman', 'changes'),
    path.join(workspace, '.sman', 'mcp'),
  ];
  dirs.forEach(d => fs.mkdirSync(d, { recursive: true }));

  // 创建 MANIFEST.md
  const manifestPath = path.join(workspace, '.sman', 'MANIFEST.md');
  if (!fs.existsSync(manifestPath)) {
    fs.writeFileSync(manifestPath, generateManifest(workspace));
  }

  // 注入 skills 到 .sman/skills（而不是 .claude/skills）
  const injected = injectSkills(
    capabilityMatches,
    this.pluginsDir,
    workspace
  );

  // ... 其余逻辑 ...
}
```

### 5.3 迁移检查清单

```markdown
## 迁移前检查

- [ ] 备份现有 `.claude/skills/` 目录
- [ ] 确认没有其他工具依赖 `.claude/skills/`
- [ ] 检查是否有自定义脚本硬编码了 `.claude/skills/`

## 迁移步骤

1. [ ] 停止 Sman 服务
2. [ ] 运行迁移脚本
3. [ ] 验证目录结构正确
4. [ ] 重启 Sman 服务
5. [ ] 测试创建新会话
6. [ ] 验证 skills 正常加载

## 迁移后验证

- [ ] `.sman/skills/` 存在且包含原有 skills
- [ ] `.sman/MANIFEST.md` 存在
- [ ] `.sman/agents/` 目录存在
- [ ] 新建会话时 skills 正常加载
- [ ] SmartPath 可正常创建
```

---

## 六、实施路径

### 6.1 分阶段实施

#### Phase 1：基础迁移（1-2 天）
**目标：** 将 skills 从 `.claude` 迁移到 `.sman`

**任务：**
1. ✅ 创建 `.sman/skills/` 目录结构
2. ✅ 修改所有硬编码 `.claude/skills` 的代码
3. ✅ 更新 skill-injector 逻辑
4. ✅ 创建迁移脚本
5. ✅ 测试现有项目的 skills 加载

**交付物：**
- 迁移脚本 `server/migrations/migrate-to-sman-harness.ts`
- 更新的 4 个核心文件
- 迁移检查清单

#### Phase 2：四要素基础设施（2-3 天）
**目标：** 实现 Agents、Rules、Changes、MCP 目录结构

**任务：**
1. ✅ 设计 Agent 定义模板
2. ✅ 创建 MANIFEST.md 生成逻辑
3. ✅ 实现 Changes 目录结构
4. ✅ 添加 MCP 项目配置支持
5. ✅ 更新初始化流程

**交付物：**
- Agent 定义模板
- MANIFEST.md 生成器
- Changes 管理模块
- MCP 配置加载器

#### Phase 3：SmartPath 模板化（1-2 天）
**目标：** 预定义 Harness 标准流程

**任务：**
1. ✅ 设计 Flow Template 格式
2. ✅ 实现 `harness-10-stage` 模板
3. ✅ 支持 Flow Template 选择和实例化
4. ✅ 集成到 SmartPath 创建流程

**交付物：**
- Flow Template 定义
- 模板实例化逻辑
- UI 支持模板选择

#### Phase 4：Stardom 深度集成（3-4 天）
**目标：** Agent 协作与 SmartPath 深度集成

**任务：**
1. ✅ 标准化 Agent 注册表
2. ✅ 实现协作协议
3. ✅ SmartPath 步骤与 Agent 绑定
4. ✅ 支持工具权限控制
5. ✅ 实现状态转换和回退

**交付物：**
- Agent 注册表格式
- 协作协议定义
- SmartPath-Agent 集成逻辑

#### Phase 5：知识管理增强（1-2 天）
**目标：** 结构化知识索引和分层加载

**任务：**
1. ✅ 实现 Knowledge INDEX.md
2. ✅ 支持分层加载策略
3. ✅ 优化 skill-auto-updater
4. ✅ 支持知识版本化

**交付物：**
- Knowledge INDEX.md 生成器
- 分层加载逻辑
- 更新的 skill-auto-updater

#### Phase 6：质量门禁（2-3 天）
**目标：** 实现可程序化验证的质量门禁

**任务：**
1. ✅ 定义质量门禁 DSL
2. ✅ 实现门禁验证器
3. ✅ 集成到 SmartPath 步骤
4. ✅ 支持回退路径

**交付物：**
- 质量门禁 DSL
- 门禁验证器
- 回退逻辑

### 6.2 优先级排序

| 优先级 | 阶段 | 价值 | 复杂度 | 建议 |
|--------|------|------|--------|------|
| P0 | Phase 1 | 解耦 Claude 工具 | 低 | **立即实施** |
| P1 | Phase 2 | 完整 Harness 能力 | 中 | **第二优先** |
| P2 | Phase 3 | 标准化流程 | 中 | 第三优先 |
| P3 | Phase 4 | Agent 协作增强 | 高 | 可分阶段 |
| P4 | Phase 5 | 知识管理优化 | 低 | 最后实施 |
| P5 | Phase 6 | 质量门禁 | 中 | 可选 |

### 6.3 风险与缓解

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| 迁移导致现有项目不可用 | 高 | 中 | 充分测试 + 回滚方案 |
| Agent 协作过于复杂 | 中 | 高 | 渐进式实施，保留手动模式 |
| 性能下降（分层加载） | 中 | 低 | 性能测试 + 缓存优化 |
| 用户学习曲线陡峭 | 低 | 中 | 文档 + 示例 + 引导 |

---

## 七、总结

### 7.1 核心价值

1. **工具无关性**：`.sman` 成为真正的能力中心，支持切换不同的 Coding 工具
2. **标准化流程**：Harness 10 阶段流程内嵌到 SmartPath 模板
3. **Agent 协作**：Stardom + Agent 定义实现专业化协作
4. **完整追溯**：Changes 目录记录每个需求的完整过程
5. **持续演进**：每次发现错误都能工程化地消除

### 7.2 与 .harness 的差异

| 维度 | .harness 文章 | Sman 本土化 |
|------|--------------|-------------|
| **Agent 协作** | 通过 MCP Server 调用 | ✅ Stardom（内置） |
| **工作流编排** | 10 阶段硬编码 | ✅ SmartPath（可定制） |
| **能力匹配** | 手动配置 | ✅ 自动匹配（Capability Matcher） |
| **知识管理** | 手动编写 Wiki | ✅ 自动提取（skill-auto-updater） |
| **技能注入** | 手动复制 | ✅ 自动注入（Skill Injector） |
| **变更追踪** | ✅ .harness/changes/ | ✅ .sman/changes/（新增） |
| **MCP 集成** | ✅ .harness/mcp/ | ✅ .sman/mcp/（新增） |

### 7.3 下一步

**建议立即开始：**
1. **Phase 1（基础迁移）**：将 skills 迁移到 `.sman`
2. **Phase 2（四要素）**：实现基础目录结构
3. **验证核心价值**：确保工具无关性

**后续迭代：**
4. Phase 3-6 可以根据实际需求分阶段实施
5. 每个阶段独立交付，避免"大爆炸"式上线

---

## 附录

### A. 参考文章

- [Harness Engineering：耗时一周，我是如何将应用的AI Coding率提升至90%的](https://mp.weixin.qq.com/s/rlIyIIZOXFObNIXbPI7gDg)
- Anthropic. [Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- OpenAI. [Harness engineering: leveraging Codex in an agent-first world](https://openai.com/index/harness-engineering/)

### B. 相关代码

- `server/init/skill-injector.ts` - Skill 注入逻辑
- `server/init/capability-matcher.ts` - 能力匹配逻辑
- `server/stardom/` - 多 Agent 协作
- `server/smart-path-*.ts` - 工作流引擎
- `server/capabilities/` - 能力网关

### C. 文件清单

**新增文件：**
- `server/migrations/migrate-to-sman-harness.ts`
- `server/harness/manifest-generator.ts`
- `server/harness/agent-registry.ts`
- `server/harness/changes-manager.ts`
- `server/harness/quality-gates.ts`

**修改文件：**
- `server/init/skill-injector.ts`
- `server/cron-scheduler.ts`
- `server/index.ts`
- `server/init/capability-matcher.ts`
- `server/init/init-manager.ts`
- `server/smart-path-engine.ts`

**新增模板：**
- `.sman/agents/application-owner.md.template`
- `.sman/agents/planner.md.template`
- `.sman/agents/generator.md.template`
- `.sman/agents/evaluator.md.template`
- `.sman/paths/templates/harness-10-stage.json`
- `.sman/MANIFEST.md.template`

---

**文档状态：** 🟢 草稿完成，待评审
**下一步：** 等待用户确认后，进入 writing-plans 阶段
