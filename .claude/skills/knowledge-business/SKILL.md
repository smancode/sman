---
name: knowledge-business
description: "业务知识：产品需求、用户流程、业务规则、领域术语。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "57e98c308c1cd0fc5693b3ebab5282836e02a241"
  scannedAt: "2026-05-17T00:00:00.000Z"
  branch: "master"
---

# 业务知识

> 贡献者: nasakim | 验证时间: 2026-05-17

## 核心产品定位
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L1-10
- **Sman**：智能业务系统助手，核心交互方式为多端对话
- 四端支持：桌面端（Electron）、企业微信 Bot、飞书 Bot、微信 Bot
- 选择项目目录即可开始对话，零预配置

## Top 3 核心业务流（本次更新）

### 1. 协作星图多 Agent 任务编排流
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/hub/index.ts, server/hub/task-worker.ts, src/features/hub/TaskBoard.tsx
- **用户工作流**：创建任务 → 多 Agent 评估并认领子任务 → 用户确认并分派 → Agent 并发执行 → 实时进度同步 → 任务完成报告
- **业务规则**：每个 Agent 最多同时执行 2 个任务（MAX_CONCURRENT=2），认领后可停止，支持子任务级别的评估和分派，任务状态机：evaluating → confirmed → dispatched → running → completed/failed
- **解决痛点**：多人协作场景下的任务分配不透明、执行进度不可控、结果难以追溯

### 2. 地球路径逐步执行工作流
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/smart-path-engine.ts, docs/superpowers/specs/2026-05-16-step-by-step-execution-design.md
- **用户工作流**：创建路径（多步骤） → 点"逐步执行" → 主编分析产出蓝图 → 逐步骤执行 → 每步完成后暂停 → 可编辑结果/描述 → 重试或继续 → 自动交付检查 → 生成报告
- **业务规则**：tmp/ 目录存放临时输出（每次清空），references/ 目录存放可复用资源（用 [REFERENCE:filename] 标记），每步独立 ephemeral session，交付检查不通过自动重试 1 次
- **解决痛点**：长流程执行中间出错需全部重跑、无法人工干预和调整、资源复用混乱

### 3. Chatbot 多 Bot 会话隔离与并发控制流
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/chatbot/chatbot-session-manager.ts, server/chatbot/types.ts
- **用户工作流**：用户在企微/飞书/微信发消息 → Bot 解析命令（如 //cd） → 按 userKey+workspace 确保会话隔离 → 队列排队（最多 2 个并发）→ 执行并返回结果
- **业务规则**：会话 ID 前缀 `chatbot-{botProfileId}-`，同一用户同时只能有 1 个活跃请求，超出则排队，会话恢复机制（soft-delete 可恢复）， workspace 解析支持路径/名称/模糊匹配
- **解决痛点**：多 Bot 会话串扰、高并发下资源耗尽、会话状态丢失

## 核心功能模块
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L45-50
- **协作星图**：多 Agent 协作网络（仪表盘 + 像素世界），含声望系统、任务路由、远程协作
- **地球路径（工作流）**：多步骤自动化，逐步骤执行，上一步结果作为下一步上下文，支持定时调度与资源复用
- **定时任务（Cron）**：Cron 表达式驱动的自动化任务，支持手动触发与队列管理
- **知识管理**：自动提取业务/规范/技术知识，通过 git push 实现团队知识共享

## 侧边栏核心功能
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L30-36
1. **新建会话** → 选择项目目录 → 开始对话
2. **协作星图** → 多 Agent 协作网络
3. **组队** → 多人协作空间（项目组、任务看板、Agent 管理）
4. **定时任务** → Cron 表达式驱动的自动化任务
5. **地球路径** → 多步骤自动化工作流
6. **设置** → LLM、Web 搜索、Chatbot、用户画像等配置
7. **代码查看器** → 集成在聊天界面右侧
8. **Git 面板** → 集成在聊天界面右侧

## Chatbot 命令
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L180-195
| 命令 | 别名 | 说明 |
|------|------|------|
| `//cd <项目名或路径>` | - | 切换工作目录 |
| `//pwd` | - | 显示当前工作目录 |
| `//workspaces` | `//wss` | 列出桌面端已打开的项目 |
| `//status` | `//sts` | 显示连接状态 |
| `//help` | - | 显示帮助信息 |

## 会话串扰是零容忍的核心体验问题
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts:L146-214
- 用户反馈会话卡顿和串会话（不同用户的会话内容互相渗透）属于最高优先级问题
- 多标签页同时打开同一会话是必须支持的合法使用场景，不能简单禁用
- 实现机制：通过 `clientToSessions` 和 `sessionToClients` 双向 Map 精确路由消息

## 对话自省与需求澄清机制
> by nasakim | 验证: 2026-05
✅ [已验证] server/init/init-manager.ts:L239, server/claude-session.ts, server/init/templates/clarify-requirements/SKILL.md
- 核心痛点：AI 缺乏"自省"，用户反复校正说明理解偏差累积，需在中途发现走偏并纠正
- 触发条件：同一需求用户纠正 ≥ 2 次（含明确否定、纠正性指令、重复表达需求变体等）
- 落地形式：`clarify-requirements` 轻量 skill，触发后停止当前任务 → 3-5 个问题快速对齐 → 需求确认表 → 等待确认
- AUTO 模式下：理解清晰则自问自答，完全无法确认才停下来问用户

## 任务前提机制（事前预防优于事后纠正）
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts:L346
- 在 user prompt 中新增"任务前提"章节，位于"交付要求"和"需求澄清"之间
- 四个前提条件：深入理解需求（理解目标而非表面）、获取足够上下文（读文件/了解现有实现）、确认可执行性（评估依赖/权限/环境）、不确定就问
- 核心理念：一开始就把事情做对，避免因模糊导致的反复拉扯，不要基于假设做决定

## 项目功能模块全景
> by nasakim | 验证: 2026-05
✅ [已验证] server/web-search/, src/features/code-viewer/, src/features/git/
- Web 搜索：支持 baidu / brave / tavily 三个 MCP 提供商
- 代码查看器：基于 CodeMirror 6 的内嵌编辑器（WebSocket API `code.*` 含 5 个方法）
- Git 面板：完整的 Git 操作 UI（WebSocket API `git.*` 含 13 个方法覆盖 status/diff/commit/push/log/checkout/fetch 等）

---

## 业务能力架构设计

### Smart Path 的 path.md 元数据要求
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/smart-path-engine.ts
- path.md 采用 YAML front matter 格式存储元数据，与 SKILL.md 保持一致
- 必须包含 name 和 description（路径的功能描述），便于后续扩展
- 新建 path 时需填写：name、业务系统、desc（功能描述）

### .sman 作为项目能力中心（工具无关）
> by nasakim | 验证: 2026-05-17
✅ [已验证] docs/sman-design-highlights.md
- 业务能力（skills/paths/rules/agents）应统一放在 `.sman/` 下，而非绑定在 `.claude/` 等具体工具目录
- 目的：切换 coding 工具（Claude/Cursor/Copilot）或自研时，能力定义可无缝迁移
- 架构参考 Harness Engineering 模式，`.sman/` 下包含 agents、skills、knowledge、rules、changes 等目录

### Skill 文件存储位置与分层策略
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/skills-registry.ts
- 全局 skills：`~/.sman/skills/`（用户通用技能）
- 项目级 skills：`{workspace}/.sman/skills/`（项目特定技能）
- 已有的 `.claude/skills/` 中的 skill 保留不迁移，新的往 `.sman/skills/` 写
- `skill-auto-updater` 需从 `.claude` 迁移到 `.sman` 体系下，是自我进化的核心能力

### Harness 增强的渐进原则
> by nasakim | 验证: 2026-05-17
✅ [已验证] CLAUDE.md
- 不破坏现有能力，不写迁移脚本，原有 `.claude` 内容保留不动
- `.sman/` 目录新增 agents、rules、changes 等子目录作为新能力，非替换
- 相比文章中的 Harness，Sman 已具备自我进化能力（skill-auto-updater、knowledge extractor），这是核心差异化

### agents/ 与 user-profile.md 的关系
> by nasakim | 验证: 2026-05-17
✅ [已验证] docs/sman-harness-design.md
- `user-profile.md` 是全局用户画像（我是谁、偏好），位于 `~/.sman/`
- `.sman/agents/` 是项目级 Agent 角色定义（Application Owner、Planner 等），两者层级和职责不同

---

## 核心技术决策

### MCP 服务对外提供能力调用
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/index.ts, docs/mcp-tools-api.md
- Sman 部署在服务器上，通过 HTTP API 对外提供 MCP 服务，供前端/集中服务调用
- 场景：公网用户发起自然语言请求（如"查账户本月出金笔数和金额"），集中服务路由到 Sman MCP
- 功能1：`POST /api/mcp/tools/list` 实时扫描已加载 workspaces，返回所有 skills 和 paths（带 workspace 标识）
- 功能2：`POST /api/mcp/tools/invoke` 指定 workspace/toolType/toolId 执行并返回结果
- workspaces 不由前端传递，Sman 自行维护已加载的 workspace 列表

### MCP 能力网关的不可替代性
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/capabilities/gateway-mcp-server.ts
- MCP 能力赋予权限必须保留：通过 capability_list/load/run 在不增加上下文的情况下无限赋能
- 这是 Sman 相比普通 Harness 的关键优势，不能因为引入新架构而削弱

### Sman 是深度定制版 Claude Code
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/claude-session.ts, server/stardom/stardom-mcp.ts, server/web-search/
- 项目从设计之初就深度依赖 Claude SDK（session 管理、MCP 框架、工具调用、流式输出等 13 个文件 28+ 处 import）
- 切换到其他 coding 工具（Cursor/Copilot）成本极高（重写 50-80%），因后端协议不开放
- 结论：当前阶段继续基于 Claude SDK 是最务实选择，多模型支持可通过 `ANTHROPIC_BASE_URL` 实现

---

## 国际化（i18n）业务需求

### 国际化语言设定的业务需求
> by nasakim | 验证: 2026-05-17
✅ [已验证] src/locales/index.ts, src/hooks/useLanguage.ts
- 用户可在设置页面修改 UI 语言，切换后需实时生效
- UI 国际化方案：所有文本替换为占位符（如 `t('menu.settings')`），从对应语言的 JSON 文件映射，语言包预加载到内存
- LLM prompt 中的语言要求也需动态跟随用户选择，而非硬编码"始终中文回复"

### 多语言支持的用户可见说明
> by nasakim | 验证: 2026-05-17
✅ [已验证] README.md:L220
- README.md 已增加"多语言支持"章节和核心能力表格中的条目，说明 zh-CN/en-US 双语及自动切换机制
- 桌面端跟随 OS 语言（需重启），浏览器端跟随浏览器语言，聊天界面会话级自动检测
- 开发者注意事项：引用 CLAUDE.md 多语言规范，所有 UI 文本禁止硬编码
