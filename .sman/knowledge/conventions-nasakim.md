# Conventions — nasakim

> Last extracted: 2026-05-06T06:09:37.196Z

## 消息推送必须基于会话订阅精确路由
<!-- hash: d4e5f6 -->
- 禁止遍历所有已认证客户端广播消息，必须通过 client↔session 双向映射精确定位接收者
- 每条 WebSocket 消息只能发给订阅了对应会话的客户端，防止消息错发到其他标签页
<!-- end: d4e5f6 -->