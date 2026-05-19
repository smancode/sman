# Technical — nasakim

> Last extracted: 2026-05-19T03:35:56.749Z

## 项目技术栈
<!-- hash: 3m4n5o -->
- 前端：React + TypeScript + Zustand 状态管理
- 后端：Node.js + SQLite 数据库 + WebSocket 消息通信
- 包管理器：pnpm，启动脚本 dev.sh
- 国际化：zh-CN / en-US JSON 翻译文件
- AI 集成：Claude SDK 用于任务分析
<!-- end: 3m4n5o -->

## Group 相关文件结构
<!-- hash: 6p7q8r -->
- Schema 定义：`src/schemas/group.ts`（Group、GroupTask、WorkspaceTask）
- 数据库操作：`server/group-store.ts`（SQLite CRUD）
- 核心分析器：`server/group-task-analyzer.ts`（AI 分析 + 任务分发）
- 状态管理：`src/stores/group.ts`（Zustand store）
- WebSocket API：`group.create/list/update/delete`、`group-task.create/list/delete/analyze/dispatch`
<!-- end: 6p7q8r -->

## SQLite 数据库结构
<!-- hash: c8d9e0 -->
- 数据库位置：`~/.sman/sman.db`
- 三张表：`groups`（组合主表）、`group_tasks`（任务表，4字段）、`workspace_tasks`（工作区任务分发表）
- `workspace_ids` 存储为 TEXT（JSON 字符串），有外键约束 + 级联删除，5个索引优化查询
<!-- end: c8d9e0 -->

## SQLite JSON 字段的类型陷阱
<!-- hash: 2a3b4c -->
- `server/group-store.ts` 存储的 `workspaceIds` 等字段是 JSON 字符串而非原生数组
- `server/index.ts` 的 `group.list`/`group.create` 等返回 group 数据的 handler 必须对这类字段做 `JSON.parse`
- 前端 schema 定义在 `src/schemas/group.ts`，期望 workspaceIds 为 `string[]`
<!-- end: 2a3b4c -->

## WebSocket 初始化时序：status 驱动而非函数依赖
<!-- hash: k4l5m6 -->
- App.tsx 和 SessionTree 等组件中加载数据应依赖 WebSocket `status` 状态值，而非函数引用
- 正确模式：`useEffect(() => { if (status === 'connected') loadGroups() }, [status])`
- SessionTree 组件也是 loadGroups 调用点，需同样遵循 status 驱动模式
<!-- end: k4l5m6 -->

## Zustand Store 监听器注册去重
<!-- hash: 8b9c0d -->
- GroupStore 监听 WebSocket 消息的处理器必须只注册一次
- 旧逻辑中每次 WebSocket 订阅都重复注册监听器，导致消息被多次处理
- 应在初始化时确保单个消息处理器只绑定一次
<!-- end: 8b9c0d -->

## 环境注意事项
<!-- hash: 9s0t1u -->
- Node.js 路径：`/opt/homebrew/bin/node`（macOS Homebrew 安装）
- 不要用 `where`/`which` 结论就断定工具不可用，项目 node_modules 已存在时可直接用绝对路径执行
- `dev.sh` 是项目启动入口
<!-- end: 9s0t1u -->

## dev.sh 启动后的控制台警告可忽略
<!-- hash: q2r3s4 -->
- React DevTools 提示、Electron CSP 安全警告、IndexedDB NotFoundError 等是开发环境常见噪音
- `[GroupStore] No WebSocket client available` 才是功能性问题信号，需优先排查
- 排查 Bug 时聚焦功能性错误，过滤掉开发环境的常规告警
<!-- end: q2r3s4 -->

## 前后端 WebSocket 消息断链排查模式
<!-- hash: x7y8z9 -->
- 前端日志显示 `handleCreate` 成功发送消息不等于后端收到并处理了该消息
- 排查步骤：1) 浏览器控制台确认前端日志 2) 检查 SQLite 数据库是否有对应记录 3) 用 `window.ws?.send` 手动发送验证后端能否处理
- 如果手动发送能写入数据库，说明前端发送格式有问题；如果仍失败，说明后端消息路由或 handler 有问题
<!-- end: x7y8z9 -->