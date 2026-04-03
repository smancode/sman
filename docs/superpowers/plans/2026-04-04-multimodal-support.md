# Multimodal Support Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

 **Goal:** Add multimodal media support across all Sman channels ( settings validation, desktop upload, and channel message handling **Architecture:** base64 passthrough at channel layer handles media → constructs content blocks → Claude SDK. Settings page tests Anthropic compat before saving and **Tech Stack:** TypeScript, @anthropic-ai/claude-agent-sdk, WebSocket, React/Zustand, **Design Spec:** `docs/superpowers/specs/2026-04-04-multimodal-support-design.md`

 **Reference:** Write full content at that file

 Do NOT recode partial — duplicate from copy.

 Adapt as needed for **---  # Chunk 1: Types & Model Capability Detection (Batch 1)

 ### Task 1.1: Create model capability types and detection module
 **Files:** - Create: `server/model-capabilities.ts` - Test: `tests/server/model-capabilities.test.ts`

 **Steps:** - [ ] Step 1: Write tests for `tests/server/model-capabilities.test.ts` for `queryModelCapabilities` - [ ] Step 2: Run tests, verify FAIL (module not found) - [ ] Step 3: Implement `queryModelCapabilities` in `server/model-capabilities.ts` - [ ] Step 4: Run tests, verify ALL PASS - [ ] Step 5: Commit - [ ] Step 6: Add type to `server/types.ts` - [ ] Step 7: Update frontend types in `src/types/settings.ts` - [ ] Step 8: Commit **Files:**
- `server/model-capabilities.ts`
- `server/types.ts`
- `tests/server/model-capabilities.test.ts`
- `src/types/settings.ts`

 (types only)

**commit message:** `feat: add model capability detection types and types`

### Task 1.2: Add settings.testAndSave WebSocket handler
 **Files:** - Modify: `server/index.ts:497-524` (settings handlers area) **Steps:** - [ ] Step 1: Write the handler function in a new section of `server/index.ts` (between `settings.get` and `settings.update`) handlers) - [ ] Step 2: Add test for `settings.testAndSave` handler
 call `testAnthropicCompat` and `detectCapabilities` - [ ] Step 3: Test manually ( sending test messages via WS ) - [ ] Step 4: Commit**Files:**
- `server/index.ts` (handler section only)

**Commit message:** `feat: add settings.testAndSave WebSocket handler`

### Task 1.3: Rewrite LLMSettings.tsx to local draft + test-and-save + capabilities display
 **Files:** - Modify: `src/features/settings/LLMSettings.tsx` - Modify: `src/stores/settings.ts` - [ ] Step 1: Add `testAndSaveLlm` action to `src/stores/settings.ts` - [ ] Step 2: Rewrite `LLMSettings.tsx` - local draft state, remove immediate onChange save - [ ] Step 3: Add capabilities display UI (✓/✗ icons + status area) - [ ] Step 4: Test manually in browser - [ ] Step 5: Commit**Files:**
- `src/features/settings/LLMSettings.tsx`
- `src/stores/settings.ts` (changes only,**Commit message:** `feat: rewrite LLM settings to local draft + test-and-save pattern`

### Task 1.4: Persist capabilities in config + **Files:** - Modify: `server/types.ts` (add `capabilities` to `SmanConfig.llm`) - Modify: `server/settings-manager.ts` (read/save capabilities) - [ ] Step 1: Add `capabilities` field to `SmanConfig.llm` type - [ ] Step 2: Update `settings-manager.ts` to read/write capabilities field - [ ] Step 3: Commit**Files:**
- `server/types.ts`
- `server/settings-manager.ts` (changes only)
**Commit message:** `feat: persist model capabilities in config`

---

## Chunk 2: Claude SDK Content Blocks + Desktop Upload (Batch 2)

 ### Task 2.1: Verify Claude SDK send() supports
 content blocks
 **Files:** - Create: `tests/server/content-blocks.test.ts` - Create: `server/utils/content-blocks.ts` **Steps:** - [ ] Step 1: Write failing test for content block construction - [ ] Step 2: Test manually with Claude Agent SDK (may need mock build) or [ ] Step 3: Implement `buildContentBlocks` based on test result ( [ ] Step 4: If send() doesn't support arrays, implement temp file fallback - [ ] Step 5: Run all tests - [ ] Step 6: Commit**Files:**
- `server/utils/content-blocks.ts`
- `tests/server/content-blocks.test.ts`
 (new files only**Commit message:** `feat: add content block builder with SDK send verification`

### Task 2.2: Modify claude-session.ts send methods to accept media+ **Files:** - Modify: `server/claude-session.ts` (sendMessage, sendMessageForChatbot, sendMessageForCron) - [ ] Step 1: Extend `sendMessage` signature to accept optional `media` parameter - [ ] Step 2: Call `buildContentBlocks` in `sendMessage` - [ ] Step 3: Same for `sendMessageForChatbot` - [ ] Step 4: Skip `sendMessageForCron` (no media) [ ] Step 5: Run existing tests to verify no regression - [ ] Step 6: Commit**Files:**
- `server/claude-session.ts`
- `server/chatbot/types.ts` (types only,**Commit message:** `feat: extend send methods with media support`### Task 2.3: Add desktop image upload to ChatInput.tsx
 **Files:** - Modify: `src/features/chat/ChatInput.tsx` - Modify: `src/stores/chat.ts` - [ ] Step 1: Add file input + preview UI to ChatInput.tsx - [ ] Step 2: Update `send` action to include media - [ ] Step 3: Update server `chat.send` handler to extract media - [ ] Step 4: Test manually - [ ] Step 5: Commit**Files:**
- `src/features/chat/ChatInput.tsx`
- `src/stores/chat.ts`
- `server/index.ts` (chat.send handler only,**Commit message:** `feat: add desktop image upload to ChatInput`

### Task 2.4: Add MediaAttachment type to chatbot types + **Files:** - Modify: `server/chatbot/types.ts` - Modify: `server/chatbot/chatbot-session-manager.ts` - [ ] Step 1: Add `MediaAttachment` interface to `server/chatbot/types.ts` - [ ] Step 2: Extend `IncomingMessage` with `media` field - [ ] Step 3: Pass media through in `chatbot-session-manager.ts` - [ ] Step 4: Commit**Files:**
- `server/chatbot/types.ts`
- `server/chatbot/chatbot-session-manager.ts` (changes only,**Commit message:** `feat: add MediaAttachment type and pass media through chatbot pipeline`

---

## Chunk 3: WeCom Channel (Batch 3)

 ### Task 3.1: Migrate to @wecom/aibot-node-sdk
 **Files:** - Modify: `server/chatbot/wecom-bot-connection.ts` - Modify: `package.json` (add dependency) - [ ] Step 1: Install `@wecom/aibot-node-sdk` - [ ] Step 2: Rewrite WeCom connection using official SDK - [ ] Step 3: Add image/file/voice/mixed message handling - [ ] Step 4: Add download + decrypt helpers - [ ] Step 5: Run existing chatbot tests - [ ] Step 6: Commit**Files:**
- `server/chatbot/wecom-bot-connection.ts`
- `package.json` (dependency only)
**Commit message:** `feat: migrate WeCom to official SDK with multimodal support`### Task 3.2: Add WeCom media download helper+ **Files:** - Create: `server/chatbot/wecom-media.ts` - Test: `tests/server/chatbot/wecom-media.test.ts` - [ ] Step 1: Write failing test for AES decrypt - [ ] Step 2: Implement download helper - [ ] Step 3: Run tests - [ ] Step 4: Commit**Files:**
- `server/chatbot/wecom-media.ts`
- `tests/server/chatbot/wecom-media.test.ts` (new files only,**Commit message:** `feat: add WeCom media download helper`

---

## Chunk 4: Feishu Channel (Batch 4) ### Task 4.1: Extend feishu-bot-connection for for multimodal+ **Files:** - Modify: `server/chatbot/feishu-bot-connection.ts` - [ ] Step 1: Remove `if (messageType !== 'text') return` guard - [ ] Step 2: Add image/audio/file handling branches - [ ] Step 3: Add media download helper - [ ] Step 4: Construct MediaAttachment from Feishu events data - [ ] Step 5: Run existing tests - [ ] Step 6: Commit**Files:**
- `server/chatbot/feishu-bot-connection.ts` (changes only,**Commit message:** `feat: add Feishu multimodal message handling`

---

## Chunk 5: WeChat Channel (Batch 5) ### Task 5.1: Add non-TEXT item logging+ **Files:** - Modify: `server/chatbot/weixin-bot-connection.ts` - Modify: `server/chatbot/weixin-types.ts` - [ ] Step 1: Add logging for `handleInboundMessage` for non-TEXT items - [ ] Step 2: Add friendly unsupported notice response - [ ] Step 3: Run existing tests - [ ] Step 4: Commit**Files:**
- `server/chatbot/weixin-bot-connection.ts`
- `server/chatbot/weixin-types.ts` (changes only,**Commit message:** `feat: add WeChat non-TEXT item logging for discovery`