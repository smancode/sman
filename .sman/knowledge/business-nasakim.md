# Business — nasakim

> Last extracted: 2026-05-06T09:38:56.860Z

## 会话串扰是零容忍的核心体验问题
<!-- hash: a1b2c3 -->
- 用户反馈会话卡顿和串会话（不同用户的会话内容互相渗透）属于最高优先级问题
- 多标签页同时打开同一会话是必须支持的合法使用场景，不能简单禁用
<!-- end: a1b2c3 -->

## 对话自省与需求澄清机制
<!-- hash: 8m4n7q -->
- 核心痛点：AI 缺乏"自省"，用户反复校正说明理解偏差累积，需在中途发现走偏并纠正
- 触发条件：同一需求用户纠正 ≥ 2 次（含明确否定、纠正性指令、重复表达需求变体等）
- 落地形式：`clarify-requirements` 轻量 skill，触发后停止当前任务 → 3-5 个问题快速对齐 → 需求确认表 → 等待确认
- AUTO 模式下：理解清晰则自问自答，完全无法确认才停下来问用户
- 新建会话时通过 `META_SKILLS` 数组自动注入到 `{workspace}/.claude/skills/`
<!-- end: 8m4n7q -->

## 项目功能模块全景
<!-- hash: f3a7b1 -->
- Web 搜索：支持 baidu / brave / tavily 三个 MCP 提供商（`server/web-search/`）
- 代码查看器：基于 CodeMirror 6 的内嵌编辑器（`src/features/code-viewer/`，WebSocket API `code.*` 含 5 个方法）
- Git 面板：完整的 Git 操作 UI（`src/features/git/`，WebSocket API `git.*` 含 13 个方法覆盖 status/diff/commit/push/log/checkout/fetch 等）
<!-- end: f3a7b1 -->