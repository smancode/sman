# Business — nasakim

> Last extracted: 2026-05-07T10:00:14.933Z

## Smart Path 的 path.md 元数据要求
<!-- hash: a1b2c3 -->
- path.md 采用 YAML front matter 格式存储元数据，与 SKILL.md 保持一致
- 必须包含 name 和 description（路径的功能描述），便于后续扩展
- 新建 path 时需填写：name、业务系统、desc（功能描述）
<!-- end: a1b2c3 -->

## .sman 作为项目能力中心（工具无关）
<!-- hash: b2c3d4 -->
- 业务能力（skills/paths/rules/agents）应统一放在 `.sman/` 下，而非绑定在 `.claude/` 等具体工具目录
- 目的：切换 coding 工具（Claude/Cursor/Copilot）或自研时，能力定义可无缝迁移
- 架构参考 Harness Engineering 模式，`.sman/` 下包含 agents、skills、knowledge、rules、changes 等目录
<!-- end: b2c3d4 -->

## MCP 服务对外提供能力调用
<!-- hash: c3d4e5 -->
- Sman 部署在服务器上，通过 HTTP API 对外提供 MCP 服务，供前端/集中服务调用
- 场景：公网用户发起自然语言请求（如"查账户本月出金笔数和金额"），集中服务路由到 Sman MCP
- 功能1：`POST /api/mcp/tools/list` 实时扫描已加载 workspaces，返回所有 skills 和 paths（带 workspace 标识）
- 功能2：`POST /api/mcp/tools/invoke` 指定 workspace/toolType/toolId 执行并返回结果
- workspaces 不由前端传递，Sman 自行维护已加载的 workspace 列表
<!-- end: c3d4e5 -->

## Skill 文件存储位置与分层策略
<!-- hash: 1f8e2d -->
- 全局 skills：`~/.sman/skills/`（用户通用技能）
- 项目级 skills：`{workspace}/.sman/skills/`（项目特定技能）
- 已有的 `.claude/skills/` 中的 skill 保留不迁移，新的往 `.sman/skills/` 写
- `skill-auto-updater` 需从 `.claude` 迁移到 `.sman` 体系下，是自我进化的核心能力
<!-- end: 1f8e2d -->

## Harness 增强的渐进原则
<!-- hash: 3a7f5c -->
- 不破坏现有能力，不写迁移脚本，原有 `.claude` 内容保留不动
- `.sman/` 目录新增 agents、rules、changes 等子目录作为新能力，非替换
- 相比文章中的 Harness，Sman 已具备自我进化能力（skill-auto-updater、knowledge extractor），这是核心差异化
<!-- end: 3a7f5c -->

## agents/ 与 user-profile.md 的关系
<!-- hash: 9c2d4e -->
- `user-profile.md` 是全局用户画像（我是谁、偏好），位于 `~/.sman/`
- `.sman/agents/` 是项目级 Agent 角色定义（Application Owner、Planner 等），两者层级和职责不同
<!-- end: 9c2d4e -->

## MCP 能力网关的不可替代性
<!-- hash: 5b8a1f -->
- MCP 能力赋予权限必须保留：通过 capability_list/load/run 在不增加上下文的情况下无限赋能
- 这是 Sman 相比普通 Harness 的关键优势，不能因为引入新架构而削弱
<!-- end: 5b8a1f -->

## Sman 是深度定制版 Claude Code
<!-- hash: 8f3c1a -->
- 项目从设计之初就深度依赖 Claude SDK（session 管理、MCP 框架、工具调用、流式输出等 13 个文件 28+ 处 import）
- 切换到其他 coding 工具（Cursor/Copilot）成本极高（重写 50-80%），因后端协议不开放
- 结论：当前阶段继续基于 Claude SDK 是最务实选择，多模型支持可通过 `ANTHROPIC_BASE_URL` 实现
<!-- end: 8f3c1a -->

## 国际化语言设定的业务需求
<!-- hash: 4k7m2p -->
- 用户可在设置页面修改 UI 语言，切换后需实时生效
- UI 国际化方案：所有文本替换为占位符（如 `t('menu.settings')`），从对应语言的 JSON 文件映射，语言包预加载到内存
- LLM prompt 中的语言要求也需动态跟随用户选择，而非硬编码"始终中文回复"
<!-- end: 4k7m2p -->

## 多语言支持的用户可见说明
<!-- hash: 6h3j9r -->
- README.md 已增加"多语言支持"章节和核心能力表格中的条目，说明 zh-CN/en-US 双语及自动切换机制
- 桌面端跟随 OS 语言（需重启），浏览器端跟随浏览器语言，聊天界面会话级自动检测
- 开发者注意事项：引用 CLAUDE.md 多语言规范，所有 UI 文本禁止硬编码
<!-- end: 6h3j9r -->