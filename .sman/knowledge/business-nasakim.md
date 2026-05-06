# Business — nasakim

> Last extracted: 2026-05-06T06:09:37.196Z

## 会话串扰是零容忍的核心体验问题
<!-- hash: a1b2c3 -->
- 用户反馈会话卡顿和串会话（不同用户的会话内容互相渗透）属于最高优先级问题
- 多标签页同时打开同一会话是必须支持的合法使用场景，不能简单禁用
<!-- end: a1b2c3 -->

## 项目功能模块全景
<!-- hash: f3a7b1 -->
- Web 搜索：支持 baidu / brave / tavily 三个 MCP 提供商（`server/web-search/`）
- 代码查看器：基于 CodeMirror 6 的内嵌编辑器（`src/features/code-viewer/`，WebSocket API `code.*` 含 5 个方法）
- Git 面板：完整的 Git 操作 UI（`src/features/git/`，WebSocket API `git.*` 含 13 个方法覆盖 status/diff/commit/push/log/checkout/fetch 等）
<!-- end: f3a7b1 -->