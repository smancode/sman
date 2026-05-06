# Conventions — nasakim

> Last extracted: 2026-05-06T09:38:56.860Z

## 消息推送必须基于会话订阅精确路由
<!-- hash: d4e5f6 -->
- 禁止遍历所有已认证客户端广播消息，必须通过 client↔session 双向映射精确定位接收者
- 每条 WebSocket 消息只能发给订阅了对应会话的客户端，防止消息错发到其他标签页
<!-- end: d4e5f6 -->

## CLAUDE.md 精简原则：只保留框架，详情指向 skill
<!-- hash: 3p8n1w -->
- CLAUDE.md 定位：基础信息、构建/测试方法、不知道就无法继续的关键信息、特别声明
- 具体项目信息（详细目录结构、API 列表、注意事项详解等）不内联，通过 skill 按需加载，CLAUDE.md 中仅指向对应 skill
- 目标行数控制在 200 行左右，避免膨胀（曾有 622 行的教训）
<!-- end: 3p8n1w -->

## 设计哲学：大模型越强，产品自动变强
<!-- hash: 5v2k9b -->
- 不搞强制的计数、阈值、降级机制等硬编码兜底，只通过提示词（user prompt）说清楚期望的执行逻辑
- 信任 LLM 的判断能力，AUTO 模式下如果用户搞不定会自己关掉
- 用户 prompt 修改行为指令放 user prompt（`[Sman 行为要求]` 区块），不动 system prompt
<!-- end: 5v2k9b -->