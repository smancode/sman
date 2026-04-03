# Sman 多模态支持方案 —— 全面分析

> 分析日期：2026-04-03

## 一、现状

所有渠道目前只支持**纯文本**：

| 渠道 | 代码位置 | 限制 |
|------|---------|------|
| 桌面端 | `ChatInput.tsx:80` — `onSend(textToSend, undefined, null)` | 附件永远为 `undefined` |
| Claude SDK | `claude-session.ts:443` — `v2Session.send(contentWithProfile)` | 发送纯 string |
| WeCom | `wecom-bot-connection.ts:136` — `msg.body?.text?.content` | 只提取 text 字段 |
| 飞书 | `feishu-bot-connection.ts:60` — `if (messageType !== 'text') return;` | **直接丢弃**非文本消息 |
| WeChat | `weixin-bot-connection.ts:346-350` | 只处理 `MessageItemType.TEXT` |

前端类型定义已有 `image`、`tool_use` 等 ContentBlock 类型，也有 `AttachedFileMeta`、`FileAttachment` 接口和图片渲染组件（`ImageThumbnail`、`ImagePreviewCard`、`FileCard`、`ImageLightbox`），但**全未接线**。

---

## 二、Claude API/SDK 多模态能力

### 2.1 Claude Messages API 支持的内容类型

| 内容块类型 | 方向 | 说明 |
|-----------|------|------|
| `text` | 双向 | 文本 |
| `image` | 用户→Claude | 图片（base64/URL/file_id） |
| `document` | 用户→Claude | PDF 文档（base64/URL/file_id） |
| `tool_use` | Claude→用户 | 工具调用请求 |
| `tool_result` | 用户→Claude | 工具结果（可含 image 块） |
| `thinking` | Claude→用户 | 扩展思考内容 |

### 2.2 支持的图片格式

- **格式**：JPEG, PNG, GIF, WebP
- **最大分辨率**：8000×8000px（超过 20 张时降至 2000×2000px）
- **单张大小**：≤5MB（API）/ ≤10MB（claude.ai）
- **最优尺寸**：长边 1568px（1.15 megapixels）
- **每请求上限**：最多 600 张（100 for 200k-token context）
- **Token 估算**：`tokens = (width_px × height_px) / 750`

### 2.3 关键：Claude 不支持音视频原生输入

音频和视频**没有**原生 content block 类型。社区强烈需求但尚未实现。

- Audio：[anthropic-sdk-python#1198](https://github.com/anthropics/anthropic-sdk-python/issues/1198), [claude-code#14444](https://github.com/anthropics/claude-code/issues/14444)
- Video：无原生支持，需通过第三方（如 TwelveLabs 插件）或 Gemini 管道中转

### 2.4 Claude Agent SDK `send()` 方法

当前代码：`v2Session.send(string)` —— 纯字符串。

SDK 理论上支持传入 content blocks 数组（如 `[{type:'text', text:'...'}, {type:'image', source:{...}}]`）。Rust 版 SDK 明确文档了此能力。TypeScript 版需验证。

**验证方法**：构造 `[{type:'text', text:'描述这张图'}, {type:'image', source:{type:'base64', media_type:'image/png', data:'...'}}]` 传入 `send()`，观察是否正常。

### 2.5 模态检测

所有当前 Claude 模型（Opus 4.6, Sonnet 4.6, Haiku 4.5）都支持 text + image + PDF。

可通过 Models API (`GET /v1/models`) 查询 `capabilities` 字段来程序化检测。但实际只需区分：

| 模态 | Claude 支持 | 处理方式 |
|------|------------|---------|
| text | ✅ | 直接发送 |
| image (JPEG/PNG/GIF/WebP) | ✅ | content block `type: 'image'` |
| PDF | ✅ | content block `type: 'document'` |
| audio | ❌ | 转文字后发送，或友好提示 |
| video | ❌ | 友好提示 |
| 其他文件格式 | ❌ | 友好提示 |

---

## 三、各渠道多模态接收能力

### 3.1 企业微信（智能机器人）

根据[官方文档](https://developer.work.weixin.qq.com/document/path/100719)，WeCom 智能机器人已支持：

| 消息类型 | msgtype | 接收 | 回复 | 限制 |
|---------|---------|------|------|------|
| 文本 | `text` | ✅ 单聊+群聊 | ✅ 流式/markdown | - |
| 图片 | `image` | ✅ **仅单聊** | ✅ media_id | URL 加密，需 AES 解密 |
| 图文混排 | `mixed` | ✅ 单聊+群聊 | ✅ 流式+msgItem | text+image 组合 |
| 语音 | `voice` | ✅ **仅单聊** | ✅ media_id | **已自动转为文字**！`voice.content` 就是文字 |
| 文件 | `file` | ✅ **仅单聊** | ✅ media_id | ≤100MB，URL 加密 |
| 视频 | `video` | ✅ **仅单聊** | ✅ media_id | ≤100MB，URL 加密 |

关键细节：
- 图片/文件/视频的 URL **已加密**（AES-256-CBC），需用消息体的 `aeskey` 解密
- 语音消息 **已经自动转为文字**，`voice.content` 就是文字内容
- 官方提供了 [`@wecom/aibot-node-sdk`](https://github.com/WecomTeam/aibot-node-sdk)，内置以下能力：
  - `downloadFile(url, aesKey)` — 自动 AES 解密下载
  - `uploadMedia(buffer, options)` — 分片上传获取 media_id
  - `replyMedia(frame, mediaType, mediaId)` — 回复媒体消息
  - `sendMediaMessage(chatid, mediaType, mediaId)` — 主动发送媒体消息
  - `replyStream(frame, streamId, content, finish, msgItem)` — 流式回复（支持图文混排）

**WeCom 图片消息回调格式**（`aibot_msg_callback`）：
```json
{
  "msgid": "...",
  "aibotid": "AIBOTID",
  "chattype": "single",
  "from": { "userid": "USERID" },
  "response_url": "...",
  "msgtype": "image",
  "image": {
    "url": "https://...(加密URL)",
    "aeskey": "AES_KEY"
  }
}
```

**WeCom 语音消息回调格式**：
```json
{
  "msgtype": "voice",
  "voice": {
    "content": "这是语音转成文本的内容"  // ← 已经是文字！
  }
}
```

**WeCom 文件消息回调格式**：
```json
{
  "msgtype": "file",
  "file": {
    "url": "https://...(加密URL)",
    "aeskey": "AES_KEY"
  }
}
```

**WeCom 图文混排消息回调格式**：
```json
{
  "msgtype": "mixed",
  "mixed": {
    "msg_item": [
      { "msgtype": "text", "text": { "content": "描述文字" } },
      { "msgtype": "image", "image": { "url": "...", "aeskey": "..." } }
    ]
  }
}
```

**WeCom 视频消息回调格式**：
```json
{
  "msgtype": "video",
  "video": {
    "url": "https://...(加密URL)",
    "aeskey": "AES_KEY"
  }
}
```

### 3.2 飞书

飞书 `im.message.receive_v1` 事件支持的消息类型：

| 消息类型 | msg_type | 接收 | 回复方式 |
|---------|----------|------|---------|
| 文本 | `text` | ✅ | `im.message.create` |
| 图片 | `image` | ✅ | 需 `im:resource` 权限下载 |
| 文件 | `file` | ✅ | 需 `im:resource` 权限下载 |
| 语音 | `audio` | ✅ | 飞书自动转文字（`recognition` 字段） |
| 视频 | `video` | ✅ | 需下载 |
| 富文本 | `post` | ✅ | 结构化内容 |

关键：
- 图片消息事件体包含 `image_key`，需调用飞书 API `im.resource.get` 下载
- 文件消息包含 `file_key`，同样需下载
- 需要权限 `im:resource`
- 回复图片：先用 `im.resource.create` 上传图片获取 `image_key`，再发送 `msg_type: 'image'` 消息

**飞书图片消息事件格式**：
```json
{
  "message": {
    "message_id": "om_xxx",
    "chat_id": "oc_xxx",
    "message_type": "image",
    "content": "{\"image_key\": \"img_xxx\"}",
    "chat_type": "p2p"
  }
}
```

**飞书文件消息事件格式**：
```json
{
  "message": {
    "message_type": "file",
    "content": "{\"file_key\": \"file_xxx\", \"file_name\": \"doc.pdf\"}"
  }
}
```

**飞书语音消息**：
- `message_type: "audio"`
- `content` 包含 `file_key`
- 如果用户开启了语音转文字，`content` 中会包含 `recognition` 字段（已转写的文字）

### 3.3 微信个人号（WeChat iLink API）

> **iLink API (`ilinkai.weixin.qq.com`) 是腾讯微信官方提供的 Bot 接口**，
> 域名 `weixin.qq.com` 归属腾讯。Sman 的 WeChat 代码独立于 openclaw 开发。

#### 已定义的消息类型常量

`weixin-types.ts:8-15` 中 `MessageItemType` 已定义完整类型：

```typescript
export const MessageItemType = {
  NONE:   0,
  TEXT:   1,   // ✅ 已处理
  IMAGE:  2,   // ❌ 未处理
  VOICE:  3,   // ❌ 未处理
  FILE:   4,   // ❌ 未处理
  VIDEO:  5,   // ❌ 未处理
};
```

#### 当前处理逻辑

`weixin-bot-connection.ts:340-353`:
```typescript
private async handleInboundMessage(msg: WeixinMessage): Promise<void> {
  for (const item of msg.item_list) {
    if (item.type === MessageItemType.TEXT && item.text_item?.text) {
      textParts.push(item.text_item.text);  // 只提取 TEXT
    }
    // IMAGE/VOICE/FILE/VIDEO 类型被静默忽略
  }
}
```

#### 关键数据结构分析

**接收消息**：iLink `getUpdates` 长轮询返回 `WeixinMessage.item_list: MessageItem[]`。
当前 `MessageItem` 接口（`weixin-types.ts:37-44`）**只定义了 `text_item`**：

```typescript
interface MessageItem {
  type?: number;           // MessageItemType: 0-5
  create_time_ms?: number;
  update_time_ms?: number;
  is_completed?: boolean;
  msg_id?: string;
  text_item?: TextItem;    // 只有这个！没有 image_item / voice_item / file_item
}
```

**缺失的字段定义**：需要抓包确认实际数据结构。推测应有：
- `image_item?: { url?: string; aes_key?: string; ... }` — 图片下载地址（可能加密）
- `voice_item?: { url?: string; text?: string; ... }` — 语音（可能有自动转文字）
- `file_item?: { url?: string; file_name?: string; file_size?: number; ... }`
- `video_item?: { url?: string; ... }`

**发送消息**：`weixin-api.ts:166-199` — `sendMessage` 只构造 TEXT item：
```typescript
item_list: [{ type: 1, text_item: { text: opts.text } }]
```
iLink API 是否支持发送 IMAGE/FILE item **未知**。

#### 可行性评估

| 模态 | 接收 | 发送 | 需确认 |
|------|------|------|--------|
| 图片 | ⚠️ type=2 存在，但 item 数据结构未定义 | ❓ 需确认 iLink 是否支持 | 抓包看 `item_list[0].image_item` 格式 |
| 语音 | ⚠️ type=3 存在，是否有自动转文字未知 | ❓ | 抓包看是否有 `voice_item.text` |
| 文件 | ⚠️ type=4 存在 | ❓ | 抓包看 `file_item` 格式 |
| 视频 | ⚠️ type=5 存在 | ❓ | 抓包看 `video_item` 格式 |

**建议处理策略**：
1. **P0**：打印完整 `item_list` 日志 —— 在 `handleInboundMessage` 中对非 TEXT item 输出 `JSON.stringify(item)`
2. **P1**：收到实际数据后，补充 `MessageItem` 类型定义
3. **P2**：测试 iLink `sendmessage` 接口是否支持发送非 TEXT item
4. **P3**：确认 iLink 是否有媒体文件下载 API（类似 WeCom 的 AES 解密下载）

### 3.4 桌面端（Electron/Web）

完全自主可控，限制最少：
- Electron 可用 `dialog.showOpenDialog()` 选择文件
- 支持拖拽上传
- 文件先存到临时目录（`~/.sman/tmp/`），再传给后端
- 可支持图片、PDF、任意文件

---

## 四、全链路架构方案

### 4.1 整体数据流

```
用户发图片/文件
    ↓
渠道层：接收 + 下载媒体 + 提取元信息
    ↓
适配层：统一转为 IncomingMediaMessage
    ↓
Claude 适配层：判断模态 → 可处理则构造 content blocks → send()
    ↓
Claude 处理 → 返回文本响应
    ↓
响应层：文本回复（或特殊情况下回复媒体）
```

### 4.2 核心类型扩展

```typescript
// 媒体类型枚举
type MediaType = 'image' | 'audio' | 'video' | 'document' | 'file';

// 单个媒体附件
interface MediaAttachment {
  type: MediaType;
  fileName?: string;
  mimeType?: string;
  fileSize?: number;
  // 下载后的本地路径
  localPath?: string;
  // base64 数据（用于构造 Claude content block）
  base64Data?: string;
  // 语音转文字结果（WeCom/飞书自动提供）
  transcription?: string;
}

// 扩展 IncomingMessage
interface IncomingMediaMessage extends IncomingMessage {
  content: string;  // 可能有文字描述，可能为空
  media?: MediaAttachment[];
}
```

### 4.3 各渠道处理流程

#### 桌面端改造

```
ChatInput 组件
  ├─ 添加附件按钮 / 拖拽区
  ├─ Electron: dialog.showOpenDialog({ multiSelection: true })
  ├─ Web: <input type="file"> 或拖拽
  ↓
上传到后端 (新增 WebSocket: file.upload)
  ↓
后端保存到 ~/.sman/tmp/ → 返回 stagedPath
  ↓
chat.send 时携带 attachments: [{type, stagedPath, mimeType, ...}]
  ↓
构造 content blocks 数组 → v2Session.send()
```

#### WeCom 渠道改造

建议迁移到官方 `@wecom/aibot-node-sdk`，获得完整多模态支持。

```
aibot_msg_callback (或 SDK message.* 事件)
  ├─ msgtype=text    → 现有逻辑（不变）
  ├─ msgtype=image   → downloadFile(url, aesKey) → base64 → Claude image block
  ├─ msgtype=voice   → voice.content 已是文字 → 直接当文本处理（零成本）
  ├─ msgtype=file    → downloadFile(url, aesKey) → 判断 mimeType:
  │    ├─ PDF        → Claude document block
  │    ├─ 图片       → Claude image block
  │    └─ 其他       → 不支持提示
  ├─ msgtype=video   → 不支持 → 友好提示
  └─ msgtype=mixed   → 拆分 text + image 分别处理
```

#### 飞书渠道改造

```
im.message.receive_v1
  ├─ msg_type=text   → 现有逻辑（不变）
  ├─ msg_type=image  → image_key → im.resource.get 下载 → base64 → Claude image block
  ├─ msg_type=audio  → 用 recognition 字段（自动转文字）→ 当文本处理
  ├─ msg_type=file   → file_key → 下载 → 判断 mimeType
  ├─ msg_type=video  → 不支持 → 友好提示
  └─ msg_type=post   → 提取文本内容
```

#### WeChat 个人号渠道改造

```
handleInboundMessage
  ├─ item.type=TEXT(1)   → 现有逻辑
  ├─ item.type=IMAGE(2)  → 取决于 iLink API 是否提供图片 URL → 下载 → Claude image block
  ├─ item.type=VOICE(3)  → 取决于 iLink API 是否提供转文字能力 → 当文本或友好提示
  ├─ item.type=FILE(4)   → 取决于 iLink API → 判断类型
  └─ item.type=VIDEO(5)  → 不支持 → 友好提示
```

> **注意**：WeChat iLink 渠道的多模态能力高度依赖 iLink API 的实际支持程度。`MessageItem` 接口目前只定义了 `text_item`，需要确认 iLink 返回的图片/语音/文件消息的实际数据结构。**建议先做消息抓包确认再实现**。

### 4.4 Claude SDK 调用改造

**当前**：`v2Session.send(string)`
**改为**：根据内容类型构造 content blocks

```typescript
function buildContentBlocks(
  text: string,
  media?: MediaAttachment[]
): string | ContentBlock[] {
  if (!media || media.length === 0) return text;

  const blocks: ContentBlock[] = [];

  if (text) {
    blocks.push({ type: 'text', text });
  }

  for (const m of media) {
    if (m.type === 'image' && m.base64Data) {
      blocks.push({
        type: 'image',
        source: {
          type: 'base64',
          media_type: m.mimeType || 'image/jpeg',
          data: m.base64Data,
        },
      });
    } else if (m.type === 'document' && m.mimeType === 'application/pdf' && m.base64Data) {
      blocks.push({
        type: 'document',
        source: {
          type: 'base64',
          media_type: 'application/pdf',
          data: m.base64Data,
        },
      });
    }
    // audio/video 不构造 block，走降级逻辑
  }

  return blocks.length === 1 && blocks[0].type === 'text'
    ? text  // 纯文本保持兼容
    : blocks;
}
```

三个 send 方法都需要改造：
- `sendMessage()` — 桌面端 WebSocket
- `sendMessageForChatbot()` — Chatbot 渠道
- `sendMessageForCron()` — Cron 任务（暂不需要多模态）

### 4.5 不支持模态的识别和应答

```typescript
function handleUnsupportedMedia(media: MediaAttachment): string {
  switch (media.type) {
    case 'video':
      return '暂不支持视频消息处理。目前支持：文本、图片（JPEG/PNG/GIF/WebP）、PDF 文件。' +
             '您可以截图或提取关键信息后以文字或图片形式发送。';
    case 'audio':
      // WeCom/飞书会自动转文字，走到这里说明平台没提供转写
      return '暂不支持语音消息处理。请以文字形式发送您的需求。';
    case 'file':
      if (!isSupportedFile(media.mimeType)) {
        return `暂不支持 ${media.mimeType || '该'} 类型的文件。` +
               '目前支持：图片（JPEG/PNG/GIF/WebP）和 PDF 文件。';
      }
      break;
  }
  return '';
}

function isSupportedFile(mimeType?: string): boolean {
  if (!mimeType) return false;
  const supported = [
    'image/jpeg', 'image/png', 'image/gif', 'image/webp',
    'application/pdf',
  ];
  return supported.includes(mimeType);
}
```

**当整条消息只有不支持的媒体时**（没有文字），直接回复友好提示，不调用 Claude。
**当消息同时有文字和不支持的媒体时**，处理文字部分，附加说明忽略了的媒体。

---

## 五、实现优先级建议

| 优先级 | 内容 | 渠道 | 原因 |
|--------|------|------|------|
| **P0** | 图片支持 | 桌面端 + WeCom + 飞书 | 最常用的多模态，Claude 原生支持 |
| **P0** | 不支持类型识别 + 友好提示 | 全部渠道 | 防止用户发视频等无法处理的消息后无响应 |
| **P1** | 语音消息 → 文字 | WeCom + 飞书 | 几乎零成本（平台已自动转文字） |
| **P1** | WeCom 迁移到官方 SDK | WeCom | 一劳永逸获得完整多模态支持 |
| **P2** | PDF 文件支持 | 桌面端 | Claude 支持 document block |
| **P2** | WeCom 图文混排消息 | WeCom | msgtype=mixed 拆分处理 |
| **P3** | 回复图片/文件给用户 | WeCom + 飞书 | 需要 uploadMedia + replyMedia |
| **P3** | WeChat iLink 多模态 | WeChat | 需先确认 API 能力 |
| **P4** | 回复中嵌入图片 | 桌面端 | Claude 生成的图片（如截图工具）在聊天中显示 |

---

## 六、模型能力检测 —— 用户发了图片，但 LLM 不支持图片怎么办

### 6.1 现状：Sman 完全不知道模型支持什么

**当前 Sman 的模型配置**（`SmanConfig.llm`）只有三个字段：

```typescript
// types.ts
llm: {
  apiKey: string;
  model: string;      // 用户手动输入的模型名，如 "GLM-5"
  baseUrl?: string;   // 可选，内网部署的 API 地址
}
```

`LLMSettings.tsx` 中 Model 是一个**纯文本输入框**，用户想填什么就填什么：
- 可能填 `claude-sonnet-4-6`（支持图片）
- 可能填 `deepseek-chat`（不支持图片）
- 可能填 `GLM-5`（可能支持图片）
- 可能填 `qwen-plus`（支持图片）
- 可能填某个自部署的内网模型（完全未知）

**Sman 没有任何机制来判断当前配置的模型是否支持图片/多模态。**

### 6.2 Claude Agent SDK 的盲区

Sman 通过 `@anthropic-ai/claude-agent-sdk` 的 `unstable_v2_createSession` 创建会话，传入 `model` 参数。SDK 内部会将 `model` 透传给 Claude API 的 `/v1/messages` 请求。

问题在于：
1. **SDK 不会告诉 Sman 模型支持什么** —— `createSession` 和 `send()` 没有返回模型能力信息
2. **如果用户配置了 `baseUrl` 指向非 Claude API**（如 DeepSeek、OpenAI 兼容接口），SDK 会向该 URL 发送包含 `image` content block 的请求，对端可能直接报 400 错误
3. **即使对端是 Claude API**，也不是所有模型都支持 vision —— 但实际上所有当前 Claude 模型（Opus/Sonnet/Haiku）都支持

### 6.3 各大 LLM API 的能力查询支持

#### Anthropic Claude API — 原生支持能力查询 ✅

`GET /v1/models/{model_id}` 返回 `ModelCapabilities` 对象：

```json
{
  "id": "claude-sonnet-4-6",
  "display_name": "Claude Sonnet 4.6",
  "max_input_tokens": 200000,
  "max_tokens": 8192,
  "capabilities": {
    "image_input": { "supported": true },
    "pdf_input": { "supported": true },
    "thinking": { "supported": true, "types": { "adaptive": { "supported": true }, "enabled": { "supported": true } } },
    "batch": { "supported": true },
    "citations": { "supported": false },
    "code_execution": { "supported": false },
    "context_management": { "supported": true },
    "effort": { "supported": true },
    "structured_outputs": { "supported": true }
  }
}
```

关键能力字段：`capabilities.image_input.supported`、`capabilities.pdf_input.supported`。
注意：**没有 `audio_input` 和 `video_input` 字段**（因为 Claude 当前不支持音视频输入）。

#### OpenAI API — 不支持能力查询 ❌

`GET /v1/models` 只返回 `{ id, object, created, owned_by }`，没有任何能力字段。
社区有 [feature request](https://github.com/openai/openai-python/issues/1826) 但未实现。

#### 其他 API（DeepSeek、千问、GLM 等）— 基本不支持 ❌

多数 LLM API 的 models endpoint 只返回模型列表，不返回能力信息。

#### 总结

| API 提供商 | 能力查询 | 返回内容 |
|-----------|---------|---------|
| Anthropic | **✅ 支持** | `image_input`、`pdf_input`、`thinking`、`batch` 等 |
| OpenAI | ❌ 不支持 | 只有 id/owner/created |
| DeepSeek | ❌ 不支持 | 只有模型列表 |
| 通义千问 | ❌ 不支持 | 只有模型列表 |
| 智谱 GLM | ❌ 不支持 | 只有模型列表 |

### 6.4 推荐方案：三层策略（API 查询 → 映射表 → 探测）

```
用户点击"保存配置"
    ↓
┌─ 第 1 层：API 查询（Anthropic 原生）
│   GET {baseUrl}/v1/models/{model}
│   Headers: x-api-key, anthropic-version: 2023-06-01
│   ↓ 成功 → 直接用返回的 capabilities
│   ↓ 失败（非 Anthropic API 或 endpoint 不存在）
│
├─ 第 2 层：内置映射表（覆盖 OpenAI/DeepSeek/千问等已知模型）
│   getModelCapabilities(model) 精确匹配 + 模糊匹配
│   ↓ 命中 → 用映射表能力
│   ↓ 未命中
│
└─ 第 3 层：最小探测请求（发送 1x1 PNG + "ok" 文字）
    POST {baseUrl}/v1/messages with image content block
    ↓ 200 OK → 支持 vision
    ↓ 400/错误 → 不支持 vision
    ↓ 连接失败 → 不可用，全部标 ✗
```

#### 第 1 层实现：Anthropic API 查询

```typescript
interface DetectedCapabilities {
  text: boolean;     // 所有模型都支持文本
  image: boolean;
  pdf: boolean;
  audio: boolean;    // 当前均为 false（Claude 不支持原生音频输入）
  video: boolean;    // 当前均为 false
  // 额外信息
  maxInputTokens?: number;
  displayName?: string;
  source: 'api' | 'mapping' | 'probe' | 'unknown';
}

async function queryModelCapabilities(
  apiKey: string,
  model: string,
  baseUrl?: string
): Promise<DetectedCapabilities | null> {
  const url = baseUrl
    ? `${baseUrl}/v1/models/${model}`
    : `https://api.anthropic.com/v1/models/${model}`;

  try {
    const resp = await fetch(url, {
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      },
    });
    if (!resp.ok) return null;

    const data = await resp.json();
    const caps = data.capabilities;
    return {
      text: true,
      image: caps?.image_input?.supported ?? false,
      pdf: caps?.pdf_input?.supported ?? false,
      audio: false,
      video: false,
      maxInputTokens: data.max_input_tokens,
      displayName: data.display_name,
      source: 'api',
    };
  } catch {
    return null;
  }
}
```

#### 第 2 层实现：内置映射表

```typescript
const MODEL_CAPABILITIES: Record<string, Partial<DetectedCapabilities>> = {
  // Anthropic Claude 系列
  'claude-opus-4-6':     { image: true,  pdf: true },
  'claude-sonnet-4-6':   { image: true,  pdf: true },
  'claude-haiku-4-5':    { image: true,  pdf: true },
  'claude-3-5-sonnet':   { image: true,  pdf: true },
  'claude-3-opus':       { image: true,  pdf: true },
  'claude-3-haiku':      { image: true,  pdf: true },

  // OpenAI 系列
  'gpt-4o':              { image: true,  pdf: false },
  'gpt-4o-mini':         { image: true,  pdf: false },
  'gpt-4-turbo':         { image: true,  pdf: false },
  'gpt-4':               { image: true,  pdf: false },
  'gpt-3.5-turbo':       { image: false, pdf: false },

  // DeepSeek
  'deepseek-chat':       { image: false, pdf: false },
  'deepseek-reasoner':   { image: false, pdf: false },

  // 通义千问
  'qwen-vl-plus':        { image: true,  pdf: false },
  'qwen-plus':           { image: false, pdf: false },

  // 智谱 GLM
  'glm-4v':              { image: true,  pdf: false },
  'glm-4':               { image: false, pdf: false },
};

function lookupMappingTable(model: string): DetectedCapabilities | null {
  // 精确匹配
  if (MODEL_CAPABILITIES[model]) {
    return { text: true, audio: false, video: false, source: 'mapping', ...MODEL_CAPABILITIES[model] };
  }
  // 模糊匹配
  const lower = model.toLowerCase();
  if (lower.includes('claude'))              return { text: true, image: true, pdf: true, audio: false, video: false, source: 'mapping' };
  if (lower.includes('gpt-4'))              return { text: true, image: true, pdf: false, audio: false, video: false, source: 'mapping' };
  if (lower.includes('deepseek'))           return { text: true, image: false, pdf: false, audio: false, video: false, source: 'mapping' };
  if (lower.includes('qwen-vl'))            return { text: true, image: true, pdf: false, audio: false, video: false, source: 'mapping' };
  if (lower.includes('glm-4v'))             return { text: true, image: true, pdf: false, audio: false, video: false, source: 'mapping' };

  return null;
}
```

#### 第 3 层实现：最小探测请求

```typescript
async function probeVisionCapability(
  apiKey: string,
  model: string,
  baseUrl?: string
): Promise<DetectedCapabilities> {
  const url = baseUrl
    ? `${baseUrl}/v1/messages`
    : 'https://api.anthropic.com/v1/messages';

  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'content-type': 'application/json',
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model,
        max_tokens: 1,
        messages: [{
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'iVBORw0KGgo=' } },
            { type: 'text', text: 'ok' },
          ],
        }],
      }),
    });

    const supported = resp.ok;
    return { text: true, image: supported, pdf: false, audio: false, video: false, source: 'probe' };
  } catch {
    return { text: true, image: false, pdf: false, audio: false, video: false, source: 'probe' };
  }
}
```

#### 组合入口函数

```typescript
async function detectCapabilities(
  apiKey: string,
  model: string,
  baseUrl?: string
): Promise<DetectedCapabilities> {
  // 第 1 层：Anthropic API 原生查询
  const apiResult = await queryModelCapabilities(apiKey, model, baseUrl);
  if (apiResult) return apiResult;

  // 第 2 层：内置映射表
  const mappingResult = lookupMappingTable(model);
  if (mappingResult) return mappingResult;

  // 第 3 层：发送最小探测请求
  return probeVisionCapability(apiKey, model, baseUrl);
}
```

### 6.5 设置页面集成：保存时验证兼容性 + 测试能力 + 展示

> **前提**：Sman 通过 `@anthropic-ai/claude-agent-sdk` 调用 LLM，底层必须是 **Anthropic Messages API 兼容格式**。
> 如果用户配的 `baseUrl` 指向非兼容 API（如 OpenAI、DeepSeek 原生接口），根本无法工作。
> 因此保存时**必须先验证 Anthropic 兼容性**，通过后才保存并检测能力。

**完整保存流程**：

```
用户点击"保存配置"
    ↓
前端发送 settings.testAndSave { apiKey, model, baseUrl }
    ↓
后端执行：
    ├─ Step 1: 测试 Anthropic 兼容性
    │   POST {baseUrl}/v1/messages
    │   { model, max_tokens: 1, messages: [{ role: "user", content: "hi" }] }
    │   Headers: x-api-key, anthropic-version: 2023-06-01
    │   ↓
    │   ├─ 200 OK          → API 可用，继续 Step 2
    │   ├─ 401/403         → 返回错误："API Key 无效或无权限"
    │   ├─ 404             → 返回错误："模型不存在: {model}"
    │   ├─ 连接失败        → 返回错误："无法连接到 {baseUrl}，请检查 URL 是否正确"
    │   ├─ 400 (格式错误)  → 返回错误："该 API 不兼容 Anthropic 格式，Sman 需要 Anthropic 兼容接口"
    │   └─ 其他错误        → 返回具体错误信息
    │
    ├─ Step 2: 保存配置到 config.json（只有 Step 1 通过才执行）
    │
    └─ Step 3: 检测模态能力（三层策略，见 6.4）
        GET {baseUrl}/v1/models/{model} → 映射表 → 探测
        ↓
    返回前端：{ success: true, capabilities: DetectedCapabilities }
```

**后端实现骨架**：

```typescript
// server/index.ts — 新增 handler
ws.on('settings.testAndSave', async (data, wsSend) => {
  const { apiKey, model, baseUrl } = data;
  const apiUrl = baseUrl
    ? `${baseUrl.replace(/\/+$/, '')}/v1/messages`
    : 'https://api.anthropic.com/v1/messages';

  // Step 1: 测试 Anthropic 兼容性
  let testResult: { ok: boolean; error?: string; response?: any };
  try {
    const resp = await fetch(apiUrl, {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'content-type': 'application/json',
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model,
        max_tokens: 1,
        messages: [{ role: 'user', content: 'hi' }],
      }),
    });

    if (resp.ok) {
      testResult = { ok: true, response: await resp.json() };
    } else {
      const errorBody = await resp.text();
      if (resp.status === 401 || resp.status === 403) {
        testResult = { ok: false, error: 'API Key 无效或无权限' };
      } else if (resp.status === 404) {
        testResult = { ok: false, error: `模型不存在: ${model}` };
      } else if (resp.status === 400 && errorBody.includes('invalid')) {
        testResult = { ok: false, error: '该 API 不兼容 Anthropic 格式，Sman 需要 Anthropic 兼容接口' };
      } else {
        testResult = { ok: false, error: `API 返回错误 (${resp.status}): ${errorBody.substring(0, 200)}` };
      }
    }
  } catch (err: any) {
    testResult = { ok: false, error: `无法连接到 API: ${err.message}` };
  }

  if (!testResult.ok) {
    wsSend(JSON.stringify({ type: 'settings.testResult', success: false, error: testResult.error }));
    return;
  }

  // Step 2: 保存配置
  updateLlm({ apiKey, model, baseUrl });

  // Step 3: 检测模态能力
  const capabilities = await detectCapabilities(apiKey, model, baseUrl);

  wsSend(JSON.stringify({ type: 'settings.testResult', success: true, capabilities }));
});
```

**UI 效果**：

验证通过 + 能力检测成功（Anthropic 原生 API）：
```
┌──────────────────────────────────────────────────┐
│  模型配置                                        │
│                                                  │
│  URL      [ https://api.anthropic.com          ] │
│  API Key  [ sk-ant-...                         ] │
│  Model    [ claude-sonnet-4-6                  ] │
│                                                  │
│  ✓ 连接成功                                     │
│  模态能力:  ✓文本  ✓图片  ✓PDF  ✗音频  ✗视频   │
│                                                  │
│  [ 保存配置 ]                                    │
└──────────────────────────────────────────────────┘
```

验证失败（API Key 错误）：
```
│  ✗ API Key 无效或无权限                          │
│  配置未保存，请检查后重试                         │
```

验证失败（非 Anthropic 兼容）：
```
│  ✗ 该 API 不兼容 Anthropic 格式                  │
│  Sman 需要 Anthropic 兼容接口                    │
│  提示: 如需使用非 Anthropic 模型，请在前端部署    │
│  兼容代理（如 one-api、litellm）                 │
```

验证通过但模型不支持图片（映射表命中）：
```
│  ✓ 连接成功                                     │
│  模态能力:  ✓文本  ✗图片  ✗PDF  ✗音频  ✗视频   │
│  ⚠ 当前模型不支持图片理解，发送图片将被忽略       │
```

未知模型（探测结果）：
```
│  ✓ 连接成功                                     │
│  模态能力:  ✓文本  ✓图片  ✗PDF  ✗音频  ✗视频   │
│  (来源: 探测测试)                                │
```

### 6.6 降级策略：用户发了图片但模型不支持

```
用户发图片
    ↓
检测 getModelCapabilities(config.llm.model)
    ↓
┌─ image=true  → 正常处理：构造 image content block → Claude
│
├─ image=false → 降级策略：
│    ├─ 有文字 → 只处理文字部分，附加提示：
│    │         "已收到您发送的图片，但当前模型 (deepseek-chat) 不支持图片理解。
│    │          图片已忽略，仅处理您的文字内容。如需图片分析，请切换到支持图片的模型。"
│    │
│    └─ 纯图片（无文字）→ 回复友好提示：
│              "当前模型 (deepseek-chat) 不支持图片理解。
│               请切换到支持图片的模型（如 claude-sonnet-4-6），或以文字描述您的需求。"
│
└─ 未知模型 → 保守按 image=false 处理
```

### 6.7 需要新增/修改的代码位置

| 文件 | 改动 |
|------|------|
| `server/types.ts` | 新增 `DetectedCapabilities` 接口 |
| `server/model-capabilities.ts`（**新文件**） | Anthropic 兼容性测试 `testAnthropicCompat()` + 三层能力检测 `detectCapabilities()` |
| `server/index.ts` | 新增 WebSocket handler `settings.testAndSave`（验证 → 保存 → 检测能力 → 返回） |
| `server/claude-session.ts` | `sendMessage` / `sendMessageForChatbot` 中，发送前检查模型能力 |
| `server/chatbot/chatbot-session-manager.ts` | 处理带媒体消息时，先查模型能力再决定是传 image block 还是降级 |
| `src/features/settings/LLMSettings.tsx` | 保存按钮触发 `settings.testAndSave`，展示连接结果 + 模态能力行（✓/✗ 图标） |
| `src/stores/settings.ts` | 新增 `testAndSaveLlm()` action，处理测试+保存+能力展示的完整流程 |
| `src/stores/chat.ts` | 前端发送附件前，检查模型能力，不支持时 UI 提示 |

### 6.8 常见内网模型能力表（供参考）

| 模型/部署 | vision | pdf | 说明 |
|-----------|--------|-----|------|
| Ollama `llava` | ✅ | ❌ | 开源视觉模型 |
| Ollama `bakllava` | ✅ | ❌ | 轻量视觉模型 |
| Ollama `minicpm-v` | ✅ | ❌ | MiniCPM-V |
| Ollama `qwen2-vl` | ✅ | ❌ | 通义千问 VL |
| Ollama `llama3.2-vision` | ✅ | ❌ | Meta Llama 3.2 Vision |
| vLLM 部署的模型 | 取决于模型 | 取决于模型 | 需看具体模型 |
| LM Studio 模型 | 取决于模型 | 取决于模型 | 需看具体模型 |
| FastChat 部署 | ❌ | ❌ | 通常纯文本 |

---

## 七、关键风险和待验证项

### 7.1 Claude Agent SDK `send()` 是否支持 content blocks 数组

**当前**：`v2Session.send(contentWithProfile)` 只传 string。
**需要验证**：传入 `[{type:'text', text:'...'}, {type:'image', source:{...}}]` 是否正常工作。

如果不支持，备选方案：
1. 将图片 base64 嵌入文本（通过 system prompt 指令）
2. 先将图片保存到本地文件，让 Claude 通过 Read 工具读取
3. 等待 SDK 更新

### 7.2 WeCom 加密文件下载

WeCom 图片/文件/视频的 URL 是 AES-256-CBC 加密的。建议直接使用 `@wecom/aibot-node-sdk` 的 `downloadFile(url, aesKey)` 方法，避免自己实现解密。

### 7.3 大文件处理

- WeCom 文件 ≤100MB，视频 ≤100MB
- Claude API 单张图片 ≤5MB
- 需要对大图片进行压缩/缩放后再传给 Claude
- PDF 大小也需要控制

### 6.4 WeChat iLink API 多模态能力不确定

`MessageItem` 接口目前只定义了 `text_item` 字段。需要：
1. 抓包确认 IMAGE/VOICE/FILE/VIDEO 类型 item 的实际数据结构
2. 确认 iLink 是否提供媒体下载 API
3. 确认 iLink 是否支持发送非文本消息

---

## 八、参考资料

- [Claude Vision 文档](https://platform.claude.com/docs/en/build-with-claude/vision)
- [Claude PDF 支持](https://platform.claude.com/docs/en/build-with-claude/pdf-support)
- [Claude Messages API](https://platform.claude.com/docs/en/build-with-claude/working-with-messages)
- [企业微信智能机器人 - 接收消息](https://developer.work.weixin.qq.com/document/path/100719)
- [@wecom/aibot-node-sdk (GitHub)](https://github.com/WecomTeam/aibot-node-sdk)
- [飞书 - 接收消息事件](https://open.feishu.cn/document/ukTMukTMukTM/uYDNxYjL2QTM24iN0EjN/event-im-message-receive_v1)
- [Audio 输入 Feature Request](https://github.com/anthropics/anthropic-sdk-python/issues/1198)
- [Native Audio Modality Feature Request](https://github.com/anthropics/claude-code/issues/14444)
