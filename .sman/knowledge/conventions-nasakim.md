# Conventions — nasakim

> Last extracted: 2026-05-07T10:00:14.933Z

## Markdown 文件元数据统一使用 YAML front matter
<!-- hash: d4e5f6 -->
- SKILL.md 和 path.md 等核心 markdown 文件均使用 YAML front matter 存储结构化元数据
- 保持格式统一，方便解析和后续扩展字段
<!-- end: d4e5f6 -->

## 临时会话机制用于 Skill/Path 的 MCP 调用
<!-- hash: e5f6a7 -->
- Skill 和 Path 的 MCP invoke 执行均通过临时会话完成，等价于用户新建会话发送命令
- 使用 `createEphemeralSession()` 创建，不写入完整会话管理，执行完立即清理
- Path 每步独立临时会话（`smartpath-ephemeral-{runId}-step-{i}`），上下文通过 prompt 传递
- Skill 单次临时会话，发送 `/{skillId}` 命令，取最后一条 assistant 消息作为结果
<!-- end: e5f6a7 -->

## 配置文件不加多余新增
<!-- hash: 7e3b9a -->
- 当前配置结构够用就不新增（如 `settings.json` 只保留现有的），避免过度设计
- 改造优先走"修正已有"而非"重建新建"的路线
<!-- end: 7e3b9a -->

## 分析先行原则
<!-- hash: 2p9n4x -->
- 复杂功能改造前先给出详细分析（问题定位、方案验证、影响范围），不动代码
- 分析需包含当前代码的硬编码问题定位、改造方案对比、以及关键验证点
<!-- end: 2p9n4x -->

## 国际化改造需全面覆盖常量与所有页面
<!-- hash: f1a2b3 -->
- 硬编码中文不仅存在于 JSX 文本，还藏在常量定义（如 `WEB_SEARCH_PROVIDER_OPTIONS`）中，搜索替换时需覆盖常量文件
- Git 页面、CodeViewer（代码查看器）等非设置页面也需纳入国际化范围，不能遗漏
- 批量修改 JSON 翻译文件时极易损坏格式（多余逗号、引号缺失），应使用程序化方式操作或修改后立即验证
<!-- end: f1a2b3 -->

## 多语言硬编码禁止规则（CLAUDE.md 强化版）
<!-- hash: 3t5v8y -->
- 所有用户界面文本禁止硬编码，必须通过 `t()` 函数；日志/调试信息可硬编码
- 常量数组方案：用 `labelKey` 存 i18n key，组件内 `t(labelKey)` 渲染
- 动态拼接方案：翻译文件写完整句子 + 参数插值，禁止拼接片段
- 模块顶层禁止调用 `t()`（初始化时 locale 未确定）
<!-- end: 3t5v8y -->