# Technical — nasakim

> Last extracted: 2026-05-07T03:14:03.172Z

## Smart Path path.md 文件格式
<!-- hash: 7a8b9c -->
- YAML front matter 字段：name、description、workspace、created_at、updated_at、status、cron_expression、steps
- body 区域为 markdown 内容（标题 + 描述文本）
- 后端存储：server/smart-path-store.ts；类型定义：src/types/settings.ts（SmartPath 含 description?）
- 前端组件 src/features/smart-paths/index.tsx 负责新建/编辑/详情页的 description 展示与编辑
<!-- end: 7a8b9c -->

## MCP HTTP API 端点与实现
<!-- hash: f6a7b8 -->
- `POST /api/mcp/tools/list`：从数据库获取有会话的活跃 workspaces，返回 skills + paths 列表
- `POST /api/mcp/tools/invoke`：参数含 workspace、toolType(skill/path)、toolId，path 类型走 smartPathEngine.run()，skill 类型创建临时会话执行
- 路由定义在 `server/index.ts`，活跃 workspace 查询方法 `getActiveWorkspaces()` 在 `server/session-store.ts`
<!-- end: f6a7b8 -->

## skill-auto-updater 迁移位置
<!-- hash: 2d6f8b -->
- 原 skill-auto-updater 在 `.claude` 目录下工作（往 `.claude/skills/` 写入）
- 需迁移到 `.sman` 体系，使 skill 自动更新写入 `.sman/skills/`
- 这是自我进化能力的核心组件，迁移时不能影响现有 skill-injector 等模块的正常运行
<!-- end: 2d6f8b -->

## skill-auto-updater 迁移涉及的关键文件
<!-- hash: 4e9d2a -->
- `skill-injector.ts:18` — 注入目标改为 `.sman/skills/`
- `cron-scheduler.ts:189` — crontab.md 扫描改为 `.sman/skills/`
- `index.ts:1223` — skills.listProject 扫描改为 `.sman/skills/`
- `capability-matcher.ts:327` — 能力匹配扫描改为 `.sman/skills/`
- 新项目初始化时 skills 放 `.sman/skills/`，旧项目 `.claude/skills/` 保留不动（向后兼容）
<!-- end: 4e9d2a -->

## Claude SDK 深度绑定清单
<!-- hash: 6b1f8c -->
- Session 管理：`claude-session.ts` 依赖 `unstable_v2_createSession`、`SDKSession`、`SDKMessage` 类型
- MCP 框架：6 个 MCP Server 依赖 `McpSdkServerConfigWithInstance`、`createSdkMcpServer`、`tool`
- 流式输出与工具调用：`await streamDone` 消息队列、SDK tool system
- 多模型支持已可通过环境变量 `ANTHROPIC_BASE_URL` 实现，无需改动代码
<!-- end: 6b1f8c -->