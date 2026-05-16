# Chatbot 多 Bot 会话隔离与并发控制流

> 验证时间: 2026-05-17 | 相关文件: server/chatbot/chatbot-session-manager.ts, server/chatbot/types.ts

## 用户工作流（Step-by-Step）

1. **用户发消息**：
   - 企微/飞书/微信用户发送消息到 Bot
   - Bot 连接层接收消息（wecom-bot-connection.ts / feishu-bot-connection.ts / weixin-bot-connection.ts）
2. **解析命令**：
   - 调用 `parseChatCommand(msg.content)` 解析命令
   - 支持的命令：`//cd`、`//pwd`、`//workspaces`、`//status`、`//help`
3. **确保会话隔离**：
   - 构建 userKey：`{platform}:{botProfileId}:{userId}`
   - 例如：`wecom:bot-001:user-123`
   - 根据 userKey + workspacePath 查找或创建 session
   - 会话 ID 前缀：`chatbot-{botProfileId}-`（例如 `chatbot-bot-001-1234567890-abc123`）
4. **队列排队**：
   - 检查当前活跃请求数（activeBotCount）
   - 如果 < maxBotConcurrency（默认 2），立即执行
   - 否则加入 botQueue 等待
5. **执行并返回**：
   - 用独立 session 处理消息
   - 流式返回结果（sender.delta()）
   - 完成后发送结束标记（sender.finish()）
   - 从队列取下一个请求

## 业务规则

### 会话隔离机制
- **userKey 构造**：`{platform}:{botProfileId}:{userId}`
  - 不同 Bot / 不同用户天然隔离
  - 本地 session 不允许 `chatbot-` 前缀
- **Session ID 前缀**：`chatbot-{botProfileId}-{timestamp}-{uuid8}`
  - 确保不同 Bot 的 session 不会串
- **数据表隔离**：`chatbot_sessions` 表独立于本地 session 表
- **V2 进程隔离**：每个会话独立的 V2 SDK 进程

### 并发控制
- **全局并发限制**：同一时间最多 2 个 Bot 会话在处理请求（maxBotConcurrency = 2）
- **单用户限制**：同一用户同时只能有 1 个活跃请求
- **队列机制**：
  - 超出排队的请求进入 botQueue
  - 前序任务完成后自动从队列取下一个
  - 不会丢弃请求

### Workspace 解析规则
- **优先级**：
  1. 精确路径匹配（从桌面端 sessions）
  2. 名称精确匹配
  3. 名称模糊匹配（contains）
  4. 路径存在性检查（last resort）
- **支持路径**：
  - 绝对路径：`/Users/user/projects/sman`
  - `~` 路径：`~/projects/sman`
  - 项目名称：`sman`
  - 模糊名称：`sm`

### 会话恢复机制
- **Soft-delete 恢复**：
  - session 被软删除后可恢复
  - 恢复后通知前端刷新侧边栏
- **Hard-delete 重创建**：
  - session 被硬删除后清理 stale chatbot_sessions 记录
  - 重新创建 session

## 领域术语

| 术语 | 定义 |
|------|------|
| **userKey** | 用户标识，格式 `{platform}:{botProfileId}:{userId}`，确保不同 Bot / 不同用户隔离 |
| **botProfileId** | Bot 配置 ID，例如 `bot-001`、`bot-002` |
| **workspace** | 项目目录路径，例如 `/Users/user/projects/sman` |
| **soft-delete** | 软删除，session 标记为已删除但保留数据，可恢复 |
| **hard-delete** | 硬删除，session 彻底删除，数据清空 |
| **ephemeral session** | 临时会话，执行完立即销毁（主要用于 SmartPath） |
| **botQueue** | Bot 请求队列，超出并发限制时排队等待 |

## 解决的痛点

1. **多 Bot 会话串扰**：通过 userKey + session ID 前缀确保完全隔离
2. **高并发下资源耗尽**：全局并发限制 + 单用户限制
3. **会话状态丢失**：chatbot_sessions 表独立存储，支持恢复
4. **Workspace 切换麻烦**：支持路径/名称/模糊匹配，优先使用桌面端已打开的项目
