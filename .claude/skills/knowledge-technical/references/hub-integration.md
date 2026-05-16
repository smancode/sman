# Hub Integration Architecture

## Overview
Multi-agent collaboration platform with centralized server, encrypted communication, and distributed task execution.

## Server Discovery Mechanism
**File**: `server/server-url.ts`

### Probe Strategy
1. **Priority Order**:
   - Cached URL from successful probe
   - Config value `hub.serverBaseUrl` / `hub.serverUrl`
   - Environment `SMAN_HUB_URL`
   - External default: `https://www.smancode.com/server`
   - Fallback URL: `SMAN_FALLBACK_URL` env or `hub.fallbackUrl`

2. **Health Check**: HEAD `/api/health` with 3s timeout
3. **Auto-persist**: First successful probe written to config
4. **Cache invalidation**: Manual trigger after config changes

```typescript
// Core API
await resolveServerBaseUrl(sm);  // Probe + cache
getServerBaseUrl(sm);            // Sync read (cached)
ensureServerBaseUrl(sm);         // Conditional probe
invalidateServerBaseUrlCache();  // Force re-probe
```

## Cryptography Layer
**File**: `server/hub/crypto.ts`

### Encryption Details
- **Algorithm**: AES-256-GCM
- **IV Length**: 12 bytes
- **Auth Tag**: 16 bytes
- **Key Length**: 32 bytes (UTF-8)
- **PSK Version**: 1

### Key Loading Priority
1. Environment: `SMAN_PSK` (exact 32 chars)
2. User override: `~/.sman/hub.key`
3. Bundled key: `hub.key` alongside executable (extraResources)
4. Default: `'sman-hub-aes256-key!!2026-32b!!!'`

### Request Structure
```typescript
interface EncryptedRequest {
  payload: string;      // base64(IV + ciphertext + authTag)
  timestamp: number;    // Unix seconds
  pskVersion: 1;
}
```

## Client-Server Protocol
**File**: `server/hub/client.ts`

### Heartbeat (POST /api/report)
- **Interval**: 15 minutes
- **Timeout**: 5 seconds
- **Payload**: Encrypted `ReportPayload`
  - `clientId`: hostname@IP
  - `version`: Sman version
  - `hostname`, `ip`, `port`
  - `reportTime`: ISO timestamp
  - `activeSessions`: count
  - `workspaces`: array of active workspace paths

### Broadcast Fetch (POST /api/broadcasts)
- **Payload**: Encrypted `BroadcastQueryPayload`
  - `clientId`: hostname@IP
  - `since`: ISO timestamp (last sync)
- **Response**: Encrypted array of `BroadcastMessage`
  - `id`, `type`, `title`, `content`
  - `createdAt`, `expiresAt`

## Task Distribution
**File**: `server/hub/task-worker.ts`

### Evaluation Flow
1. **Fetch**: POST `/api/evaluation/tasks` (encrypted)
2. **Execute**: Run task in isolated context
3. **Report**: POST `/api/evaluation/report` (encrypted)

### Task Types
- `skill-test`: Skill validation
- `knowledge-extraction`: Knowledge base update
- `code-review`: PR review

## Security Considerations
- All network traffic encrypted with AES-256-GCM
- PSK rotation via `pskVersion` field (future)
- No sensitive data in logs (debug only)
- Timeout-based abort for network requests
- Graceful degradation when server unreachable

## Error Handling
- Network failures: Silent retry next interval
- Decryption failures: Log + discard message
- Invalid responses: Skip without crash
- Server unresponsive: Mark offline, retry later

## Integration Points
- **Settings Manager**: Config read/write
- **Session Store**: Active session/workspace counts
- **Broadcast Store**: Local message cache
- **Local MCP API**: Trigger skills via `/api/mcp/tools/trigger`
