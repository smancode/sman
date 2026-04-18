# Send/Abort UX Redesign

## Problem

Current behavior when user sends message2 while message1 is still streaming:
- Frontend has complex "wait for chat.done → reload history → send new message" queue logic
- The flow feels "weird" — user doesn't understand what's happening
- Backend already queues messages behind active streams

## Design

### Desktop (Sman Electron/Web)

**Rule: Cannot send while response is in progress. Must stop first.**

1. `sending=true` → Send button transforms into Stop button (square icon ■)
2. Click Stop → sends `chat.abort` to backend → button grays out (aborting)
3. Backend finishes receiving in-flight messages → sends `chat.aborted`
4. Frontend receives `chat.aborted` → button reverts to Send icon (▶)
5. User can now type and send normally

**Input field**: Remains editable during streaming. User can pre-type next message.

**Button states**:
| State | Input empty | Input has text |
|-------|-------------|----------------|
| Idle  | ▶ (gray, disabled) | ▶ (active, clickable) |
| Sending | ■ (Stop, clickable) | ■ (Stop, clickable) |
| Aborting | ■ (gray, disabled) | ■ (gray, disabled) |

### Chatbot (WeCom/Weixin/Feishu)

**No change.** Keep existing queue logic:
- `sendMessageForChatbot` awaits `streamDone` before sending
- Message appears to queue behind current response

## Changes

### Files Modified (2)

1. **`src/features/chat/ChatInput.tsx`**
   - Import `Square` icon from lucide-react
   - When `sending=true`: show Stop button instead of Send button
   - Click Stop → call `abortRun()` instead of `handleSend()`
   - Add `aborting` state: after clicking Stop, button grays out until `chat.aborted` received

2. **`src/stores/chat.ts`**
   - Add guard at top of `sendMessage`: `if (get().sending) return;`
   - Remove lines 537-582: the "wait for chat.done → reload history → send" queue logic
   - No other changes needed

### Files NOT Modified

- `server/claude-session.ts` — backend queue logic remains for chatbot defense
- `server/index.ts` — WebSocket handlers unchanged
- Chatbot handlers — existing queue behavior preserved
