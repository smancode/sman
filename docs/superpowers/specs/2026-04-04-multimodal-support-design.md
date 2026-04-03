# Multimodal Support Design

> Date: 2026-04-04
> Status: Approved
> Batch delivery: A (settings) → F+B (SDK+desktop) → C (WeCom) → D (Feishu) → E (WeChat)

## 1. Scope

Five subsystems, delivered in dependency order:

| Batch | Subsystem | Description |
|-------|-----------|-------------|
| 1 | A. Settings validation + capability detection | Save-time Anthropic compat test + modality display |
| 2 | F. Claude SDK content blocks | `buildContentBlocks()` + degrade strategy |
| 2 | B. Desktop image upload | File picker → base64 → send with media |
| 3 | C. WeCom multimodal | Migrate to `@wecom/aibot-node-sdk` |
| 4 | D. Feishu multimodal | Extend feishu-bot-connection for image/audio/file |
| 5 | E. WeChat multimodal | Log non-TEXT items for data structure discovery |

## 2. Architecture: base64 passthrough (Plan A)

All media downloaded at channel layer, converted to base64, passed to Claude SDK as content blocks.

```
Channel layer: receive + download media + convert to base64
    ↓
Adapter: unified IncomingMessage with MediaAttachment[]
    ↓
Claude adapter: buildContentBlocks() → send()
    ↓
Response: text reply (or degrade notice)
```

No file server, no temp storage, no new WebSocket upload protocol.

### 2.1 Size limits and memory protection

| Source | Max size | Action |
|--------|----------|--------|
| Desktop upload | 10MB per file, 20MB total per message | Reject at client with toast |
| WeCom image | ~5MB (Claude limit) | If >5MB, reject with notice |
| WeCom file | ≤100MB, but reject >10MB | Reject oversized with notice |
| Feishu image/file | Similar limits | Same |
| PDF | ≤32MB (Claude limit) | If >10MB, reject with notice |

All channels validate size before base64 conversion. Rejection messages are friendly and platform-appropriate.

### 2.2 SDK send() fallback

**Critical uncertainty**: `v2Session.send()` may only accept `string`, not `ContentBlock[]`.

Resolution strategy:
1. **Batch 2 first task**: verify `send()` accepts arrays by testing with minimal image block
2. If YES: use content blocks directly (happy path)
3. If NO: save media to temp file in workspace, modify user message to "用户发送了图片，已保存到 {path}，请用 Read 工具查看" — Claude reads file via its own Read tool

## 3. Types

### 3.1 DetectedCapabilities

```typescript
// server/model-capabilities.ts

export interface DetectedCapabilities {
  text: boolean;      // always true
  image: boolean;
  pdf: boolean;
  audio: boolean;     // always false (Claude)
  video: boolean;     // always false (Claude)
  maxInputTokens?: number;
  displayName?: string;
  source: 'api' | 'mapping' | 'probe';
}

export interface ModelTestResult {
  success: boolean;
  error?: string;
  capabilities?: DetectedCapabilities;
}
```

### 3.2 MediaAttachment

```typescript
// server/chatbot/types.ts extension

export interface MediaAttachment {
  type: 'image' | 'audio' | 'video' | 'document';
  fileName?: string;
  mimeType: string;
  base64Data: string;
  transcription?: string;   // auto-provided by WeCom/Feishu voice
}
```

### 3.3 IncomingMessage extension

```typescript
export interface IncomingMessage {
  platform: 'wecom' | 'feishu' | 'weixin';
  userId: string;
  content: string;
  requestId: string;
  chatType: 'single' | 'group' | 'p2p';
  chatId: string;
  media?: MediaAttachment[];  // NEW
}
```

## 4. Settings Page Redesign (Batch 1)

### 4.1 Local draft pattern

Replace current "onChange immediate save" with local draft + explicit save:

```
Page load → fill local state from store.settings.llm
User edits → only update local state
Save button → testAndSave → show result
```

### 4.2 testAndSave flow

```
POST {baseUrl}/v1/messages
  { model, max_tokens: 1, messages: [{ role: "user", content: "hi" }] }
  Headers: x-api-key, anthropic-version: 2023-06-01

  200 OK   → API compatible, proceed
  401/403  → "API Key 无效或无权限"
  404      → "模型不存在: {model}"
  conn fail → "无法连接到 {baseUrl}"
  400      → "该 API 不兼容 Anthropic 格式"

→ save to config.json
→ detectCapabilities() (3-layer)
→ return { success, capabilities }
```

### 4.3 UI

```
✓ 连接成功
模态能力:  ✓文本  ✓图片  ✓PDF  ✗音频  ✗视频

--- or ---
✗ API Key 无效或无权限
配置未保存，请检查后重试
```

### 4.4 New WebSocket message

- Client sends: `{ type: 'settings.testAndSave', llm: { apiKey, model, baseUrl } }`
- Server responds: `{ type: 'settings.testResult', success, error?, capabilities? }`

## 5. Claude SDK Content Blocks (Batch 2)

### 5.1 buildContentBlocks()

```typescript
// server/utils/content-blocks.ts

function buildContentBlocks(
  text: string,
  media?: MediaAttachment[],
  capabilities?: DetectedCapabilities
): string | ContentBlock[]
```

Logic:
- No media → return string (backward compatible)
- Media + capabilities.image=true → construct image block
- Media + capabilities.image=false → degrade: process text only + attach notice

### 5.2 Integration points

All three send methods in `claude-session.ts`:
- `sendMessage()` (desktop): extract media from ws message
- `sendMessageForChatbot()` (WeCom/Feishu/WeChat): extract media from IncomingMessage
- `sendMessageForCron()`: no change needed

### 5.3 SDK send() verification

**MUST verify first**: whether `v2Session.send()` accepts `ContentBlock[]` array instead of string.
If not supported, fallback: save image to temp file → Claude reads via Read tool.

## 6. Desktop Image Upload (Batch 2)

### 6.1 ChatInput.tsx changes

- Add file input button (`<input type="file" accept="image/*,.pdf">`)
- Read selected file → FileReader.readAsDataURL → extract base64
- Show preview thumbnails (existing `FileAttachment` type)
- `onSend(text, attachments)` finally passes actual attachments

### 6.2 chat.send message extension

```json
{
  "type": "chat.send",
  "sessionId": "...",
  "content": "描述这张图",
  "media": [
    {
      "type": "image",
      "mimeType": "image/png",
      "base64Data": "iVBOR...",
      "fileName": "screenshot.png"
    }
  ]
}
```

## 7. WeCom Channel (Batch 3)

### 7.1 Migrate to @wecom/aibot-node-sdk

Replace current manual WebSocket connection with official SDK. Gains:
- `downloadFile(url, aesKey)` — auto AES-256-CBC decrypt
- `uploadMedia(buffer, options)` — chunked upload for sending media
- `replyStream(frame, streamId, content, finish, msgItem)` — mixed text+image streaming

### 7.2 Message type handling

```
aibot_msg_callback:
  msgtype=text    → existing logic
  msgtype=image   → downloadFile → base64 → MediaAttachment
  msgtype=voice   → voice.content is already text → treat as text (zero cost)
  msgtype=file    → downloadFile → check mimeType → image or PDF
  msgtype=video   → unsupported → friendly notice
  msgtype=mixed   → split text + image items
```

## 8. Feishu Channel (Batch 4)

### 8.1 Message type handling

```
im.message.receive_v1:
  msg_type=text   → existing logic
  msg_type=image  → image_key → im.resource.get → download → base64
  msg_type=audio  → recognition field (auto-transcription) → treat as text
  msg_type=file   → file_key → download → check mimeType
  msg_type=video  → unsupported → friendly notice
  msg_type=post   → extract text content
```

### 8.2 Requirements

- `im:resource` permission for media download
- Feishu SDK client for `im.resource.get` API calls

## 9. WeChat Channel (Batch 5)

### 9.1 Discovery phase

In `handleInboundMessage`, add logging for non-TEXT items:

```typescript
for (const item of msg.item_list) {
  if (item.type === MessageItemType.TEXT && item.text_item?.text) {
    textParts.push(item.text_item.text);
  } else if (item.type !== MessageItemType.TEXT) {
    this.log.info(`Non-text item received: type=${item.type} data=${JSON.stringify(item)}`);
  }
}
```

After capturing real data:
- Extend `MessageItem` interface with actual fields
- Implement media download if API provides URLs
- Apply same pattern as WeCom/Feishu

## 10. Degrade Strategy

When media is received but model doesn't support it:

```
image=false + has text → process text, append notice:
  "[系统提示：用户发送了图片，但当前模型不支持图片处理，已忽略]"

image=false + no text → reply friendly notice:
  "当前模型不支持图片理解。请切换到支持图片的模型（如 claude-sonnet-4-6），或以文字描述需求。"

audio/video → always unsupported notice (Claude doesn't support)
```

## 11. Files to Create/Modify

| File | Batch | Change |
|------|-------|--------|
| `server/model-capabilities.ts` | 1 | **NEW** — 3-layer capability detection |
| `server/types.ts` | 1 | Add `DetectedCapabilities`, `ModelTestResult` |
| `server/index.ts` | 1 | Add `settings.testAndSave` handler |
| `src/features/settings/LLMSettings.tsx` | 1 | Local draft + test+save + capability display |
| `src/stores/settings.ts` | 1 | Add `testAndSaveLlm()` action |
| `server/utils/content-blocks.ts` | 2 | **NEW** — `buildContentBlocks()` |
| `server/claude-session.ts` | 2 | Modify 3 send methods for content blocks |
| `server/chatbot/types.ts` | 2 | Add `MediaAttachment`, extend `IncomingMessage` |
| `src/features/chat/ChatInput.tsx` | 2 | File picker + preview + pass attachments |
| `src/stores/chat.ts` | 2 | Send media in `chat.send` message |
| `server/chatbot/wecom-bot-connection.ts` | 3 | Migrate to `@wecom/aibot-node-sdk` |
| `server/chatbot/feishu-bot-connection.ts` | 4 | Handle image/audio/file message types |
| `server/chatbot/weixin-bot-connection.ts` | 5 | Add non-TEXT item logging |
| `server/chatbot/weixin-types.ts` | 5 | Extend `MessageItem` after data capture |
| `server/chatbot/chatbot-session-manager.ts` | 2-5 | Pass media from IncomingMessage to Claude |

## 12. Risks

| Risk | Mitigation |
|------|-----------|
| `v2Session.send()` doesn't accept content block arrays | Verify first; fallback: temp file + Read tool |
 Save to temp file + Claude reads via Read tool |
| WeCom AES decryption complexity | Use official SDK's `downloadFile()` |
| Large files exceed Claude/memory limits | Per-message size limit + resize before sending | We40MB files → reject with notice | 
| PDF support mentioned but channel handlers don't detail | Spec follows analysis doc pattern |
| `DetectedCapabilities` not persisted | Save to `SmanConfig.llm.capabilities` | Server restart lost capability信息 | |
| `settings.testAndSave` has no concurrency control | save is atomic, test success → save → capabilities | |
| Memory management for per-message size limit + resize before send | |
| No persistence ( need re-add `capabilities` to `SmanConfig.llm` |
| **`DetectedCapabilities` should go in new file `model-capabilities.ts`, not `server/types.ts` | Frontend `LlmConfig`/`SmanSettings` needs `capabilities` field |
| `MediaAttachment.base64Data` should be required + add download-failure state: union type or optional |
 `base64Data` with `error` field |

 | `sendMessage` signature should accept `media` as optional trailing param |
 `buildContentBlocks` return type must fallback to string-only |
 Settings page rewrite more complex than spec implies | minor restructuring） |
| Risk | Mitigation |
|------|-----------|
| Claude SDK `send()` doesn't accept content block arrays | Verify first; fallback to temp file + Read tool |
| WeCom AES decryption complexity | Use official SDK's `downloadFile()` |
| Large images exceed 5MB Claude limit | Resize before sending (sharp or canvas) |
| Feishu requires `im:resource` permission | Document required permission |
| WeChat iLink API unknown for non-text items | Log first, implement after data capture |
