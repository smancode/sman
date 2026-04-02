# 用户自动画像系统设计

> 日期: 2026-04-02
> 状态: 已确认

## 目标

每次对话结束后自动分析用户行为，持续积累用户画像，让 Sman "越用越懂用户"，而非纯粹的工具化体验。

## 核心理念

- 画像精简（< 800字），宁少勿多
- 推测标注 `[推测]`，确认的直接记录
- 动态重写，不追加，保持画像始终最新不冗余
- 用户可手动编辑画像文件
- 画像可启用/禁用

## 画像文件

**路径**: `~/.sman/user-profile.md`

当前为单用户系统（桌面端/WeCom/飞书共享一个画像）。未来多用户时改为 `~/.sman/profiles/{userKey}.md`。

```markdown
# 用户画像

## 助手身份
- 名字: Sman（默认，用户可自定义）
- 角色设定: Sman 数字助手

## 用户身份
- 用户名称: （用户告诉了就记录）
- 角色: [推测/确认] ...
- 团队/部门: ...
- 业务领域: ...

## 技术偏好
- 主力语言/工具: ...
- 使用偏好: ...

## 常用任务
- 高频操作: ...（一句话归纳）
- 最近任务: ...

## 失败任务
- 2026-04-01: 尝试XXX → 原因: ...（最近5条，去重）

## 沟通风格
- 偏好语言: 中文
- 回复详细度: 简洁 / 详细
- 关注点: ...

## 回复策略
- 根据用户习惯调整回复风格和技术深度
- 注意事项: ...
```

## 数据流

```
用户发消息 → Claude 回复 → result case 完成
                              ↓
                    读取 user-profile.md（已有画像）
                    + 本轮对话内容（截断后的 user msg + assistant msg）
                              ↓
                    调用 Haiku/主模型（低成本）
                    → 重写 user-profile.md
                              ↓
下次发消息 → v2Session.send() 的 content 前拼接画像摘要
```

## 模块设计

### `server/user-profile.ts` — 画像管理器

职责：
- `loadProfile()`: 读取画像文件，不存在则创建空模板
- `updateProfile(userMsg, assistantMsg)`: 调用 LLM 分析并重写画像
- `getProfileForPrompt()`: 返回画像内容（用于注入 prompt）
- `isEnabled()`: 检查画像功能是否启用

并发控制：
- 使用内存 Promise 队列串行化 `updateProfile` 调用
- 同一时间只允许一个画像更新进行，后续调用排队等待
- 避免桌面端和 WeCom 同时发消息时的文件写入冲突

LLM 调用：
- 模型: 优先使用 `SmanConfig.llm.profileModel`，未配置则用主模型
- API Key / BaseUrl: 复用 `SmanConfig.llm` 配置
- 方式: 独立 API 调用（直接 HTTP，不走 Claude Session）
- 时机: fire-and-forget，不阻塞主流程

### `server/claude-session.ts` — 注入点

两处修改，覆盖所有消息入口：

1. **画像注入**（所有 `sendMessage*` 方法中）：
   - 在 `this.store.addMessage(sessionId, { role: 'user', content })` 存储原始消息
   - 然后在 `v2Session.send(content)` 前拼接画像到 content 前面
   - 这样数据库存的是干净的原始消息，Claude 看到的是带画像前缀的消息

2. **画像更新**（所有 `sendMessage*` 方法的 `result` case 中）：
   - 在 `wsSend(chat.done)` 之后、`break` 之前
   - fire-and-forget: `this.userProfile.updateProfile(userContent, fullContent).catch(...)`
   - `sendMessage` 和 `sendMessageForChatbot` 都触发画像更新
   - `sendMessageForCron` 不触发（headless 执行）

### 注入格式

在 `v2Session.send()` 时拼接：

```
[用户画像参考 - 仅供参考，不要在回复中提及此段]
{画像内容}

{用户实际消息}
```

注意：`addMessage` 存储的是不含画像前缀的原始 `content`。

### 输入截断策略

`updateProfile` 的输入控制：
- `user_message`: 截断到 2000 字符
- `assistant_message`: 截断到 3000 字符
- 总输入控制在 ~5000 token 以内，适配 Haiku 的上下文窗口

## 配置

在 `SmanConfig.llm` 中新增可选字段：

```typescript
interface LlmConfig {
  // ... 现有字段
  profileModel?: string;   // 画像分析用的模型，不填则使用主模型
  userProfile?: boolean;   // 是否启用用户画像，默认 true
}
```

前端设置页新增"用户画像"开关（默认开启），高级设置可选画像模型。

## 画像更新提示词

```
你是用户画像分析引擎。根据已有画像和本轮对话，输出更新后的完整画像。

## 规则
1. 保持精简，总字数 < 800字，宁可少写不要多写
2. 确认的信息直接写，推测的加 [推测] 前缀
3. 失败任务只保留最近5条，相同原因不重复记录
4. 常用任务归纳为一句话，不罗列具体操作
5. 回复策略要具体可执行，不要空话
6. 不要编造信息，只在有明确证据时更新
7. 助手身份如果用户要求了就更新，否则保持默认

## 输入
已有画像：
{existing_profile}

本轮对话：
用户: {user_message}
助手: {assistant_message}

## 输出
直接输出更新后的完整画像 Markdown，不要输出其他内容。
```

## 成本控制

- 优先使用 Haiku 模型（用户可配置），单次分析约 500 input + 500 output tokens
- 画像文件 < 2KB，限制 prompt 长度
- fire-and-forget 异步执行，零延迟感知
- 输入截断控制 token 消耗

## 错误处理

- 画像文件损坏 → 重建空模板
- LLM 调用失败 → 保留现有画像不变，warn 日志
- 画像文件不存在 → 自动创建空模板
- 串行队列中的某个更新失败 → 不影响后续排队任务

## 画像维度说明

| 维度 | 目的 | 更新频率 |
|------|------|---------|
| 助手身份 | 用户自定义助手名字/角色 | 用户要求时 |
| 用户身份 | 推断用户角色、团队、领域 | 发现新证据时 |
| 技术偏好 | 适配技术栈和工具链 | 首次发现后稳定 |
| 常用任务 | 了解用户高频需求 | 每次有新任务时 |
| 失败任务 | 避免重复犯错 | 失败时记录 |
| 沟通风格 | 调整回复语言和详细度 | 观察到偏好时 |
| 回复策略 | 具体的回复适配指令 | 积累中动态调整 |

## 测试策略

- **单元测试**: `loadProfile`、`updateProfile`、`getProfileForPrompt`、串行队列、输入截断
- **集成测试**: 画像注入到 prompt 的效果、画像更新后下次消息能看到变化
- **边界测试**: 并发更新、损坏文件恢复、空画像模板、超大输入截断
