# Backend Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Token authentication, localhost binding, and CORS protection to the Sman backend so only authorized clients can access it.

**Architecture:** Fixed Token stored in `~/.sman/config.json`. Backend generates Token on first startup. WebSocket connections must authenticate within 5 seconds. HTTP APIs require Bearer Token (except health check and token retrieval). CORS limits origins to localhost.

**Tech Stack:** Node.js crypto, ws WebSocketServer verifyClient, HTTP middleware pattern.

**Spec:** `docs/superpowers/specs/2026-03-29-backend-security-design.md`

---

## Chunk 1: Backend Token Generation & Storage

### Task 1: Add auth field to SmanConfig type

**Files:**
- Modify: `server/types.ts:28-42`

- [ ] **Step 1: Add auth field to SmanConfig**

```typescript
// In server/types.ts, add to SmanConfig:
export interface SmanConfig {
  port: number;
  llm: {
    apiKey: string;
    model: string;
    baseUrl?: string;
  };
  webSearch: {
    provider: 'builtin' | 'brave' | 'tavily';
    braveApiKey: string;
    tavilyApiKey: string;
    maxUsesPerSession: number;
  };
  chatbot: import('./chatbot/types.js').ChatbotConfig;
  auth: {
    token: string;
  };
}
```

- [ ] **Step 2: Update DEFAULT_CONFIG in settings-manager.ts**

```typescript
// In server/settings-manager.ts, update DEFAULT_CONFIG:
const DEFAULT_CONFIG: SmanConfig = {
  port: 5880,
  llm: { apiKey: '', model: '' },
  webSearch: {
    provider: 'builtin',
    braveApiKey: '',
    tavilyApiKey: '',
    maxUsesPerSession: 50,
  },
  chatbot: {
    enabled: false,
    wecom: { enabled: false, botId: '', secret: '' },
    feishu: { enabled: false, appId: '', appSecret: '' },
  },
  auth: {
    token: '',  // Will be generated on first startup
  },
};
```

- [ ] **Step 3: Add ensureAuthToken method to SettingsManager**

```typescript
// In server/settings-manager.ts, add import:
import crypto from 'crypto';

// Add method to SettingsManager class:
ensureAuthToken(): string {
  const config = this.read();
  if (config.auth?.token) {
    return config.auth.token;
  }
  const token = crypto.randomBytes(32).toString('hex');
  config.auth = { token };
  this.write(config);
  this.log.info('Generated new auth token');
  return token;
}
```

- [ ] **Step 4: Commit**

```bash
git add server/types.ts server/settings-manager.ts
git commit -m "feat(server): add auth.token field to SmanConfig and token generation"
```

---

### Task 2: Write tests for SettingsManager token generation

**Files:**
- Create: `tests/server/settings-manager.test.ts`

- [ ] **Step 1: Write tests**

```typescript
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SettingsManager } from '../../server/settings-manager.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SettingsManager', () => {
  let homeDir: string;
  let manager: SettingsManager;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `sman-test-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    manager = new SettingsManager(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  describe('ensureAuthToken', () => {
    it('should generate a token on first call', () => {
      const token = manager.ensureAuthToken();
      expect(token).toBeDefined();
      expect(token.length).toBe(64); // 32 bytes hex = 64 chars
    });

    it('should return the same token on subsequent calls', () => {
      const token1 = manager.ensureAuthToken();
      const token2 = manager.ensureAuthToken();
      expect(token1).toBe(token2);
    });

    it('should persist token to config.json', () => {
      const token = manager.ensureAuthToken();
      const config = JSON.parse(fs.readFileSync(path.join(homeDir, 'config.json'), 'utf-8'));
      expect(config.auth.token).toBe(token);
    });

    it('should use existing token from config.json', () => {
      // Pre-write a config with a token
      const existingToken = 'abcdef1234567890'.repeat(4);
      fs.writeFileSync(
        path.join(homeDir, 'config.json'),
        JSON.stringify({ auth: { token: existingToken } }, null, 2),
        'utf-8'
      );
      const token = manager.ensureAuthToken();
      expect(token).toBe(existingToken);
    });
  });
});
```

- [ ] **Step 2: Run tests**

Run: `npx vitest run tests/server/settings-manager.test.ts`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add tests/server/settings-manager.test.ts
git commit -m "test(server): add tests for SettingsManager token generation"
```

---

## Chunk 2: Backend Authentication Middleware

### Task 3: Add localhost binding and HTTP auth middleware to server/index.ts

**Files:**
- Modify: `server/index.ts:24-26` (add crypto import)
- Modify: `server/index.ts:168-246` (HTTP auth middleware)
- Modify: `server/index.ts:682` (localhost binding)

- [ ] **Step 1: Add auth token initialization after settingsManager creation**

After line 71 (`const settingsManager = new SettingsManager(homeDir);`), add:

```typescript
// Initialize auth token
const authToken = settingsManager.ensureAuthToken();
```

- [ ] **Step 2: Add HOST constant and update server.listen**

Replace line 682:

```typescript
// Before:
server.listen(PORT, () => { ... });

// After:
const HOST = process.env.HOST || '127.0.0.1';
server.listen(PORT, HOST, () => {
  log.info(`Sman server running on ${HOST}:${PORT}`);
  log.info(`Home directory: ${homeDir}`);
  log.info(`WebSocket endpoint: ws://${HOST}:${PORT}/ws`);
  log.info(`Health check: http://${HOST}:${PORT}/api/health`);
});
```

- [ ] **Step 3: Add CORS headers helper function**

Add before the `server` creation (around line 167):

```typescript
const ALLOWED_ORIGINS = [
  'http://localhost:5880',
  'http://localhost:5881',
  'http://127.0.0.1:5880',
  'http://127.0.0.1:5881',
];

function setCorsHeaders(req: http.IncomingMessage, res: http.ServerResponse): void {
  const origin = req.headers.origin || '';
  if (ALLOWED_ORIGINS.includes(origin)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  }
}
```

- [ ] **Step 4: Add HTTP authentication helper**

```typescript
function isLoopback(req: http.IncomingMessage): boolean {
  const remoteAddress = req.socket.remoteAddress || '';
  return remoteAddress === '127.0.0.1' || remoteAddress === '::1' || remoteAddress === '::ffff:127.0.0.1';
}

function verifyHttpAuth(req: http.IncomingMessage): boolean {
  const header = req.headers.authorization || '';
  const match = header.match(/^Bearer\s+(.+)$/);
  if (!match) return false;
  return match[1] === authToken;
}
```

- [ ] **Step 5: Modify HTTP request handler to add auth + CORS**

Inside the `http.createServer` callback, at the very beginning, add:

```typescript
// Handle CORS preflight
if (req.method === 'OPTIONS') {
  setCorsHeaders(req, res);
  res.writeHead(204);
  res.end();
  return;
}

// Set CORS headers on all responses
setCorsHeaders(req, res);

// Public endpoints (no auth required)
if (req.url === '/api/health') {
  // ... existing health check code unchanged
}

// Token retrieval - loopback only, no auth
if (req.url === '/api/auth/token') {
  if (!isLoopback(req)) {
    res.writeHead(403, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Forbidden' }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ token: authToken }));
  return;
}

// All other endpoints require authentication
if (!verifyHttpAuth(req)) {
  res.writeHead(401, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Unauthorized' }));
  return;
}
```

The directory API and static file serving code remain unchanged — they are now protected by the auth check above.

- [ ] **Step 6: Commit**

```bash
git add server/index.ts
git commit -m "feat(server): add HTTP auth middleware, CORS headers, and localhost binding"
```

---

### Task 4: Add WebSocket authentication

**Files:**
- Modify: `server/index.ts:249-269` (WSS creation and connection handler)

- [ ] **Step 1: Add verifyClient with Origin check**

Replace the WebSocketServer creation (line 249):

```typescript
// Before:
const wss = new WebSocketServer({ server, path: '/ws' });

// After:
const wss = new WebSocketServer({
  server,
  path: '/ws',
  verifyClient: (info, callback) => {
    const origin = info.origin || '';
    if (origin && !ALLOWED_ORIGINS.includes(origin)) {
      callback(false, 403, 'Forbidden: invalid origin');
      return;
    }
    callback(true);
  },
});
```

- [ ] **Step 2: Add auth tracking and timeout to WebSocket connections**

Replace the connection handler. Track authenticated state per connection:

```typescript
// Authenticated clients tracking
const authenticatedClients = new Set<WebSocket>();

wss.on('connection', (ws: WebSocket) => {
  log.info('WebSocket client connected, awaiting authentication');

  // Authentication timeout: disconnect after 5 seconds if not authenticated
  const authTimeout = setTimeout(() => {
    if (!authenticatedClients.has(ws)) {
      log.warn('WebSocket client disconnected: auth timeout');
      ws.send(JSON.stringify({ type: 'auth.timeout' }));
      ws.close(4001, 'Authentication timeout');
    }
  }, 5000);

  ws.on('message', async (data) => {
    let msg: WsMessage;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      ws.send(JSON.stringify({ type: 'error', error: 'Invalid JSON' }));
      return;
    }

    // Handle auth.verify - the only allowed message before authentication
    if (msg.type === 'auth.verify') {
      if (msg.token === authToken) {
        clearTimeout(authTimeout);
        clients.add(ws);
        authenticatedClients.add(ws);
        ws.send(JSON.stringify({ type: 'auth.verified' }));
        log.info('WebSocket client authenticated');
      } else {
        ws.send(JSON.stringify({ type: 'auth.failed', error: 'Invalid token' }));
        ws.close(4002, 'Invalid token');
      }
      return;
    }

    // Reject all other messages before authentication
    if (!authenticatedClients.has(ws)) {
      ws.send(JSON.stringify({ type: 'error', error: 'Authentication required' }));
      return;
    }

    // ... existing try/catch with switch(msg.type) stays here unchanged
  });

  ws.on('close', () => {
    clearTimeout(authTimeout);
    clients.delete(ws);
    authenticatedClients.delete(ws);
    log.info('WebSocket client disconnected');
  });
});
```

- [ ] **Step 3: Commit**

```bash
git add server/index.ts
git commit -m "feat(server): add WebSocket Token authentication with 5s timeout"
```

---

## Chunk 3: Frontend Changes

### Task 5: Update WsClient to handle authentication

**Files:**
- Modify: `src/lib/ws-client.ts`

- [ ] **Step 1: Add token parameter and auth flow to WsClient**

```typescript
export class WsClient {
  private ws: WebSocket | null = null;
  private handlers = new Map<string, Set<EventHandler>>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private currentDelay: number;
  private _closed = false;
  private _token: string;

  private readonly port: number;
  private readonly autoReconnect: boolean;
  private readonly reconnectDelay: number;
  private readonly maxReconnectDelay: number;

  constructor(options: WsClientOptions = {}) {
    this.port = options.port ?? 5880;
    this.autoReconnect = options.autoReconnect ?? true;
    this.reconnectDelay = options.reconnectDelay ?? 1000;
    this.maxReconnectDelay = options.maxReconnectDelay ?? 30000;
    this.currentDelay = this.reconnectDelay;
    this._token = options.token ?? '';
  }

  set token(value: string) {
    this._token = value;
  }

  // ... connect() method modified:
  connect(): void {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    this._closed = false;
    if (this.ws) {
      this.ws.close();
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.hostname;
    this.ws = new WebSocket(`${protocol}//${host}:${this.port}/ws`);

    this.ws.onopen = () => {
      this.currentDelay = this.reconnectDelay;
      // Send auth message immediately after connection
      if (this._token) {
        this.ws!.send(JSON.stringify({ type: 'auth.verify', token: this._token }));
      }
    };

    this.ws.onmessage = (event: MessageEvent) => {
      try {
        const msg = JSON.parse(String(event.data));
        if (msg.type === 'auth.verified') {
          this.emit('connected');
          return;
        }
        if (msg.type === 'auth.failed') {
          this.emit('authFailed', msg);
          this._closed = true;
          this.ws?.close();
          return;
        }
        if (msg.type) {
          this.emit(msg.type, msg);
        }
        this.emit('message', msg);
      } catch {
        // Ignore non-JSON messages
      }
    };

    this.ws.onclose = () => {
      this.emit('disconnected');
      if (this.autoReconnect && !this._closed) {
        this.scheduleReconnect();
      }
    };

    this.ws.onerror = () => {
      // onerror fires before onclose; errors are surfaced via onclose
    };
  }
  // ... rest unchanged
}
```

Also update the interface:

```typescript
interface WsClientOptions {
  port?: number;
  autoReconnect?: boolean;
  reconnectDelay?: number;
  maxReconnectDelay?: number;
  token?: string;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/ws-client.ts
git commit -m "feat(frontend): add Token authentication to WsClient"
```

---

### Task 6: Update WsClientOptions type and ws-connection store

**Files:**
- Modify: `src/stores/ws-connection.ts`

- [ ] **Step 1: Update ws-connection store to support token**

The store needs to:
1. Fetch token from `/api/auth/token` on first load (local mode)
2. Pass token to WsClient
3. Listen for authFailed event

```typescript
import { create } from 'zustand';
import { WsClient } from '@/lib/ws-client';

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'auth_failed';

interface WsConnectionState {
  status: ConnectionStatus;
  client: WsClient | null;
  connect: () => void;
  disconnect: () => void;
  initToken: () => Promise<void>;
}

let singletonClient: WsClient | null = null;
let listenersRegistered = false;

function getClient(): WsClient {
  if (!singletonClient) {
    singletonClient = new WsClient({ port: 5880 });
  }
  return singletonClient;
}

export const useWsConnection = create<WsConnectionState>((set) => {
  const client = getClient();

  if (!listenersRegistered) {
    client.on('connected', () => set({ status: 'connected' }));
    client.on('disconnected', () => set({ status: 'disconnected' }));
    client.on('authFailed', () => set({ status: 'auth_failed' }));
    listenersRegistered = true;
  }

  return {
    status: 'disconnected',
    client,

    async initToken() {
      try {
        const res = await fetch('/api/auth/token');
        if (res.ok) {
          const data = await res.json();
          client.token = data.token;
        }
      } catch {
        // Not running locally, token must be configured manually
      }
    },

    connect() {
      set({ status: 'connecting' });
      client.connect();
    },

    disconnect() {
      set({ status: 'disconnected' });
      client.disconnect();
    },
  };
});
```

- [ ] **Step 2: Commit**

```bash
git add src/stores/ws-connection.ts
git commit -m "feat(frontend): add token init and authFailed handling to ws-connection store"
```

---

### Task 7: Update DirectorySelectorDialog to use authenticated fetch

**Files:**
- Modify: `src/components/DirectorySelectorDialog.tsx:31-49`

- [ ] **Step 1: Add Authorization header to fetch calls**

Add a helper function at the top of the component, and use it for both fetch calls:

```typescript
// Add inside the component, before fetchDirectory:
const authFetch = async (url: string) => {
  const token = useWsConnection.getState().client?.['_token'] || '';
  return fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
};

// Replace fetch calls:
// fetch(`/api/directory/read?path=...`) → authFetch(`/api/directory/read?path=...`)
// fetch('/api/directory/home') → authFetch('/api/directory/home')
```

Actually, since the token is stored in WsClient and not easily accessible, a cleaner approach is to store the token in a module-level variable. Let's create a simple auth helper:

**Create:** `src/lib/auth.ts`

```typescript
/** Module-level auth token storage for HTTP requests */
let _token = '';

export function setAuthToken(token: string): void {
  _token = token;
}

export function getAuthToken(): string {
  return _token;
}

export function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  if (_token) {
    headers.set('Authorization', `Bearer ${_token}`);
  }
  return fetch(url, { ...options, headers });
}
```

Then update:
- `ws-connection.ts` → call `setAuthToken(data.token)` after fetching token
- `DirectorySelectorDialog.tsx` → use `authFetch()` instead of `fetch()`

- [ ] **Step 2: Commit**

```bash
git add src/lib/auth.ts src/components/DirectorySelectorDialog.tsx src/stores/ws-connection.ts
git commit -m "feat(frontend): add authFetch helper and authenticated directory API calls"
```

---

### Task 8: Add backend connection settings to settings page

**Files:**
- Create: `src/features/settings/BackendSettings.tsx`
- Modify: `src/features/settings/index.tsx`
- Modify: `src/types/settings.ts`

- [ ] **Step 1: Add backend config to frontend settings type**

Check what's in `src/types/settings.ts` first and add `auth` field if present there, or keep it separate in the BackendSettings component using localStorage.

Since backend URL and token are connection-level config (not stored on server), use localStorage:

```typescript
// In src/features/settings/BackendSettings.tsx:
import { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { useWsConnection } from '@/stores/ws-connection';
import { setAuthToken } from '@/lib/auth';

const STORAGE_KEY_URL = 'sman-backend-url';
const STORAGE_KEY_TOKEN = 'sman-backend-token';

export function BackendSettings() {
  const { status, connect, disconnect, initToken } = useWsConnection();
  const [url, setUrl] = useState(() => localStorage.getItem(STORAGE_KEY_URL) || 'ws://localhost:5880');
  const [token, setToken] = useState(() => localStorage.getItem(STORAGE_KEY_TOKEN) || '');
  const [saved, setSaved] = useState(false);

  // Auto-init token on mount (local mode)
  useEffect(() => {
    if (!token) {
      initToken().then(() => {
        // After initToken, the client already has the token
        // We can read it back for display
      });
    }
  }, []);

  const handleSave = () => {
    localStorage.setItem(STORAGE_KEY_URL, url);
    localStorage.setItem(STORAGE_KEY_TOKEN, token);
    setAuthToken(token);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const handleReconnect = () => {
    disconnect();
    setTimeout(() => connect(), 500);
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>后端连接</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label>后端地址</Label>
          <Input
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="ws://localhost:5880"
          />
        </div>
        <div className="space-y-2">
          <Label>认证 Token</Label>
          <Input
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="本机模式自动获取，远程模式需手动输入"
          />
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" onClick={handleSave}>
            {saved ? '已保存' : '保存'}
          </Button>
          <Button size="sm" variant="outline" onClick={handleReconnect}>
            重新连接
          </Button>
          <span className="text-sm text-muted-foreground ml-2">
            状态: {status === 'connected' ? '已连接' : status === 'connecting' ? '连接中' : status === 'auth_failed' ? '认证失败' : '未连接'}
          </span>
        </div>
      </CardContent>
    </Card>
  );
}
```

- [ ] **Step 2: Add BackendSettings to settings page**

```typescript
// In src/features/settings/index.tsx, add import:
import { BackendSettings } from './BackendSettings';

// Add <BackendSettings /> as the first item in the settings list:
<div className="max-w-2xl space-y-6">
  <BackendSettings />
  <ChatbotSettings />
  <LLMSettings />
  <WebSearchSettings />
</div>
```

- [ ] **Step 3: Commit**

```bash
git add src/features/settings/BackendSettings.tsx src/features/settings/index.tsx
git commit -m "feat(frontend): add backend connection settings page with URL and Token config"
```

---

## Chunk 4: Integration & Smoke Test

### Task 9: Manual smoke test verification

- [ ] **Step 1: Start backend in dev mode**

Run: `pnpm dev:server`

Verify in console output:
- `Sman server running on 127.0.0.1:5880` (not `0.0.0.0`)
- `Generated new auth token` (first run only)

- [ ] **Step 2: Test health endpoint (no auth)**

Run: `curl http://127.0.0.1:5880/api/health`

Expected: `{"status":"ok","timestamp":"..."}`

- [ ] **Step 3: Test token retrieval (loopback only)**

Run: `curl http://127.0.0.1:5880/api/auth/token`

Expected: `{"token":"<64-char-hex>"}`

- [ ] **Step 4: Test authenticated HTTP API**

Run: `TOKEN=$(curl -s http://127.0.0.1:5880/api/auth/token | jq -r .token) && curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:5880/api/directory/read?path=/Users`

Expected: `{"entries":[...]}`

- [ ] **Step 5: Test unauthenticated HTTP API rejection**

Run: `curl http://127.0.0.1:5880/api/directory/read?path=/Users`

Expected: `{"error":"Unauthorized"}` with status 401

- [ ] **Step 6: Test WebSocket auth**

Run: `wscat -c ws://127.0.0.1:5880/ws`

1. Send `{"type":"session.list"}` immediately → Expected: `{"type":"error","error":"Authentication required"}`
2. Send `{"type":"auth.verify","token":"wrong"}` → Expected: connection closed
3. Reconnect, send `{"type":"auth.verify","token":"<real-token>"}` → Expected: `{"type":"auth.verified"}`
4. Now send `{"type":"session.list"}` → Expected: `{"type":"session.list","sessions":[...]}`

- [ ] **Step 7: Test frontend integration**

Run: `pnpm dev` (both frontend and backend)

1. Open `http://localhost:5881` in browser
2. Verify WebSocket connects and authenticates automatically
3. Navigate to Settings → verify "后端连接" section shows status "已连接"
4. Verify directory selector still works (browse directories)
5. Verify chat functionality still works

- [ ] **Step 8: Run all existing tests**

Run: `npx vitest run`

Expected: All tests PASS (existing tests should not be affected)

- [ ] **Step 9: Commit final state**

```bash
git add -A
git commit -m "feat(security): complete backend security hardening - Token auth, CORS, localhost binding"
```

---

## File Change Summary

| File | Action | Purpose |
|------|--------|---------|
| `server/types.ts` | Modify | Add `auth.token` to SmanConfig |
| `server/settings-manager.ts` | Modify | Add `ensureAuthToken()` method |
| `server/index.ts` | Modify | Auth middleware, CORS, localhost binding, WS auth |
| `src/lib/auth.ts` | Create | Module-level token storage + authFetch helper |
| `src/lib/ws-client.ts` | Modify | Token param, auth.verify on connect |
| `src/stores/ws-connection.ts` | Modify | Token init, authFailed handling |
| `src/components/DirectorySelectorDialog.tsx` | Modify | Use authFetch instead of fetch |
| `src/features/settings/BackendSettings.tsx` | Create | Backend URL + Token settings UI |
| `src/features/settings/index.tsx` | Modify | Include BackendSettings |
| `tests/server/settings-manager.test.ts` | Create | Tests for token generation |
