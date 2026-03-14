# Codebase Concerns

**Analysis Date:** 2026-03-14

## Tech Debt

### No Test Coverage
- Issue: Zero test files in the `src/` directory. No unit, integration, or E2E tests exist for the frontend application.
- Files: Entire `/Users/nasakim/projects/smanweb/src/` directory
- Impact: Any code change risks introducing regressions. No safety net for refactoring.
- Fix approach: Add Vitest or Jest. Start with critical paths: `src/stores/chat.ts`, `src/lib/gateway-client.ts`, `src/features/chat/message-utils.ts`.

### Large File: ChatMessage.tsx (600 lines)
- Issue: `ChatMessage.tsx` exceeds the 300-line limit for dynamic files. Contains 7 sub-components in a single file.
- Files: `/Users/nasakim/projects/smanweb/src/features/chat/ChatMessage.tsx`
- Impact: Difficult to navigate, test, and maintain. Changes to one component risk affecting others.
- Fix approach: Extract components to separate files:
  - `MessageBubble.tsx`
  - `ThinkingBlock.tsx`
  - `ToolCard.tsx`
  - `ToolStatusBar.tsx`
  - `ImageThumbnail.tsx`
  - `ImagePreviewCard.tsx`
  - `ImageLightbox.tsx`
  - `FileCard.tsx`
  - `AssistantHoverBar.tsx`

### Large File: chat.ts Store (581 lines)
- Issue: Chat store exceeds 500-line limit. Contains complex state management, multiple action handlers, and helper functions.
- Files: `/Users/nasakim/projects/smanweb/src/stores/chat.ts`
- Impact: Difficult to understand state transitions. Complex `handleChatEvent` function with multiple branches.
- Fix approach: Split into:
  - `chat-store.ts` - Core state and simple actions
  - `chat-session-actions.ts` - Session management (loadSessions, switchSession, newSession, deleteSession)
  - `chat-event-handler.ts` - Event handling logic (handleChatEvent)
  - Extract helper functions (`toMs`, `getMessageText`, `isToolResultRole`, etc.) to `chat-utils.ts`

### Console Logging in Production Code
- Issue: Multiple `console.log` and `console.warn` statements in source code that will appear in production.
- Files:
  - `/Users/nasakim/projects/smanweb/src/stores/chat.ts` (lines 220, 352, 450, 490, 513, 530)
  - `/Users/nasakim/projects/smanweb/src/features/chat/index.tsx` (lines 49, 52, 63, 65)
  - `/Users/nasakim/projects/smanweb/src/components/common/ErrorBoundary.tsx` (line 31)
- Impact: Exposes internal state in browser console. Performance overhead in production.
- Fix approach: Implement a proper logging abstraction that:
  - Uses `console` in development
  - Integrates with monitoring service in production
  - Or strips logs in production build via Vite config

### Magic Strings for Event States
- Issue: Event state strings (`'started'`, `'delta'`, `'final'`, `'error'`, `'aborted'`) are not typed constants.
- Files: `/Users/nasakim/projects/smanweb/src/stores/chat.ts` (lines 461-569)
- Impact: Typos cause silent failures. No autocomplete or type safety.
- Fix approach: Define typed enum/const object:
  ```typescript
  const ChatEventState = {
    STARTED: 'started',
    DELTA: 'delta',
    FINAL: 'final',
    ERROR: 'error',
    ABORTED: 'aborted',
  } as const
  ```

## Known Bugs

### WebSocket Proxy Does Not Inject Auth Token
- Symptoms: The `gateway-proxy.ts` forwards messages verbatim but never uses `gatewayToken` parameter.
- Files: `/Users/nasakim/projects/smanweb/server/gateway-proxy.ts` (line 17, parameter declared but unused)
- Trigger: All WebSocket connections through the proxy
- Workaround: Client sends token in connect frame (current implementation)
- Fix approach: Either remove unused parameter or implement token injection into initial connect frame.

### Test Connection Uses Wrong Endpoint
- Symptoms: `testConnection()` fetches `/health` endpoint over HTTP, but Gateway uses WebSocket.
- Files: `/Users/nasakim/projects/smanweb/src/stores/gateway-connection.ts` (lines 88-91)
- Trigger: Clicking "Test" button in Connection Settings
- Workaround: None - may report false positives/negatives
- Fix approach: Establish a quick WebSocket connection and disconnect, or use a dedicated HTTP health endpoint if Gateway provides one.

## Security Considerations

### Hardcoded Default Token
- Risk: Default gateway token `'sman-default-token-change-in-production'` is committed to code.
- Files: `/Users/nasakim/projects/smanweb/server/index.ts` (line 29)
- Current mitigation: Docker compose uses environment variable with warning
- Recommendations: Enforce token configuration at startup - fail if using default in production mode.

### Token Stored in localStorage
- Risk: Auth token persisted to localStorage via Zustand persist middleware. Vulnerable to XSS attacks.
- Files: `/Users/nasakim/projects/smanweb/src/stores/gateway.ts` (lines 51-54)
- Current mitigation: None
- Recommendations:
  - Consider httpOnly cookies for token storage if same-origin
  - Add Content Security Policy headers
  - Sanitize any user input rendered to DOM

### No Input Validation on Gateway URL
- Risk: User can input any URL in Connection Settings, including potentially malicious targets.
- Files: `/Users/nasakim/projects/smanweb/src/features/settings/ConnectionSettings.tsx` (line 112)
- Current mitigation: None
- Recommendations: Validate URL format. Restrict to allowed protocols (ws://, wss://, http://, https://). Consider URL allowlist for production deployments.

### WebSocket Proxy Lacks Rate Limiting
- Risk: No rate limiting or connection limits on WebSocket proxy endpoint.
- Files: `/Users/nasakim/projects/smanweb/server/gateway-proxy.ts`
- Current mitigation: None
- Recommendations: Add connection rate limiting. Limit concurrent connections per IP. Add timeout for idle connections.

## Performance Bottlenecks

### Large Message History Load (200 messages)
- Problem: Chat history loads up to 200 messages without pagination.
- Files: `/Users/nasakim/projects/smanweb/src/stores/chat.ts` (line 317)
- Cause: Single large RPC call returns all history
- Improvement path: Implement virtualized scrolling with lazy loading. Load initial batch, fetch more on scroll up.

### Message Re-render on Any State Change
- Problem: `ChatMessage` components may re-render unnecessarily when unrelated state changes.
- Files: `/Users/nasakim/projects/smanweb/src/features/chat/ChatMessage.tsx`
- Cause: Zustand selectors select individual values but complex derived state may cause re-renders
- Improvement path: Profile with React DevTools. Memoize expensive computations. Consider using `useShallow` from zustand for object comparisons.

### No Debounce on Session Switch
- Problem: Rapid session switching may trigger multiple concurrent history loads.
- Files: `/Users/nasakim/projects/smanweb/src/stores/chat.ts` (lines 224-239)
- Cause: No cancellation of in-flight requests when switching
- Improvement path: Abort previous request on session switch. Add debounce to sidebar click handlers.

## Fragile Areas

### Gateway Client Singleton
- Files: `/Users/nasakim/projects/smanweb/src/lib/gateway-client.ts` (lines 328-348)
- Why fragile: Global singleton with implicit state. Testing requires module mocking. Multiple callers can modify config.
- Safe modification: Reset singleton between tests. Consider dependency injection pattern.
- Test coverage: None - critical path untested

### Event Handler State Machine
- Files: `/Users/nasakim/projects/smanweb/src/stores/chat.ts` (lines 447-570)
- Why fragile: Complex state transitions with multiple flags (`sending`, `activeRunId`, `streamingMessage`, `pendingFinal`). Race conditions possible between events.
- Safe modification: Add state machine library (xstate). Document valid state transitions. Add state invariants as assertions.
- Test coverage: None - event handling is untested

### Message Content Type Handling
- Files: `/Users/nasakim/projects/smanweb/src/features/chat/message-utils.ts`
- Why fragile: Must handle multiple content formats (Anthropic, OpenAI, Gateway-internal). Type guards are permissive (`as` casts).
- Safe modification: Add comprehensive type guards with runtime validation. Create zod schemas for each format.
- Test coverage: None - message parsing is untested

## Scaling Limits

### Single Gateway Process
- Current capacity: One gateway process per server instance
- Limit: Cannot horizontally scale gateway connections
- Scaling path: Add load balancer support. Consider sticky sessions for WebSocket. Extract gateway to separate service.

### No Session Persistence
- Current capacity: Sessions stored in Gateway process memory
- Limit: Sessions lost on gateway restart
- Scaling path: Implement session storage backend (Redis, database). Configure Gateway to use persistent session store.

### No Connection Pooling
- Current capacity: One WebSocket per browser tab
- Limit: Multiple tabs create multiple connections
- Scaling path: Use BroadcastChannel API to share single connection across tabs.

## Dependencies at Risk

### @anthropic-ai/claude-code (v2.1.76)
- Risk: Pre-release versioning (2.x). API may change.
- Impact: Bundling scripts may break on update
- Migration plan: Pin version. Monitor changelog. Test bundle process on update.

### openclaw (2026.3.12)
- Risk: Daily versioning scheme suggests frequent releases. Internal dependency.
- Impact: Gateway protocol may change
- Migration plan: Document protocol version compatibility. Add protocol version negotiation.

## Missing Critical Features

### No Offline Mode
- Problem: Application requires active Gateway connection. No offline capability.
- Blocks: Use in environments with intermittent connectivity

### No Error Recovery UI
- Problem: Errors displayed but no retry mechanism for failed messages.
- Blocks: User cannot recover from transient failures

### No Message Editing
- Problem: Cannot edit sent messages to refine prompts.
- Blocks: Cannot iterate on prompts without sending new messages

## Test Coverage Gaps

### Store Logic Untested
- What's not tested: All Zustand store actions and state transitions
- Files: `/Users/nasakim/projects/smanweb/src/stores/*.ts`
- Risk: State corruption bugs go undetected
- Priority: High

### WebSocket Client Untested
- What's not tested: Connection, reconnection, RPC, event handling
- Files: `/Users/nasakim/projects/smanweb/src/lib/gateway-client.ts`
- Risk: Connection failures in production difficult to diagnose
- Priority: High

### Message Parsing Untested
- What's not tested: Content extraction from various message formats
- Files: `/Users/nasakim/projects/smanweb/src/features/chat/message-utils.ts`
- Risk: Display bugs for certain message types
- Priority: Medium

### Component Rendering Untested
- What's not tested: ChatMessage, ChatInput, ConnectionSettings rendering
- Files: `/Users/nasakim/projects/smanweb/src/features/**/*.tsx`
- Risk: UI regressions
- Priority: Medium

---

*Concerns audit: 2026-03-14*
