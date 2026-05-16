import http from 'http';
import crypto from 'crypto';
import { WebSocketServer, WebSocket } from 'ws';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { fileURLToPath } from 'url';
import { createLogger, type Logger } from './utils/logger.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Resolve project root directory containing plugins/
// dev mode (tsx): __dirname = .../smanbase/server/
// prod mode (compiled): __dirname = .../smanbase/dist/server/server/
function resolveProjectRoot(): string {
  if (fs.existsSync(path.join(__dirname, '..', 'plugins'))) {
    return path.resolve(__dirname, '..'); // dev: server/ → project root
  }
  return path.resolve(__dirname, '..', '..', '..'); // prod: dist/server/server/ → project root
}

import { SessionStore } from './session-store.js';
import { SkillsRegistry } from './skills-registry.js';
import { ClaudeSessionManager, normalizeWorkspacePath } from './claude-session.js';
import { WebAccessService } from './web-access/index.js';
import { SettingsManager } from './settings-manager.js';
import { UserProfileManager } from './user-profile.js';
import { CronTaskStore } from './cron-task-store.js';
import { CronScheduler } from './cron-scheduler.js';
import { CronExpressionParser } from 'cron-parser';
import { BatchStore } from './batch-store.js';
import { BatchEngine } from './batch-engine.js';
import { SmartPathStore } from './smart-path-store.js';
import { SmartPathEngine } from './smart-path-engine.js';
import { SmartPathScheduler } from './smart-path-scheduler.js';
import { handleListDir, handleReadFile, handleSaveFile, handleSearchSymbols, handleSearchFiles, validatePath } from './code-viewer-handler.js';
import { handleGitStatus, handleGitDiff, handleGitDiffFile, handleGitCommit, handleGitLog, handleGitBranchList, handleGitCheckout, handleGitFetch, handleGitRemoteDiff, handleGitGenerateCommit, handleGitLogGraph, handleGitLogSearch, handleGitAheadCommits, handleGitPush, setSessionManagerForPush } from './git-handler.js';

import { ChatbotStore } from './chatbot/chatbot-store.js';
import { ChatbotSessionManager } from './chatbot/chatbot-session-manager.js';
import { CapabilityRegistry } from './capabilities/registry.js';
import { initCapabilities } from './capabilities/init-registry.js';
import { WeComBotConnection } from './chatbot/wecom-bot-connection.js';
import { FeishuBotConnection } from './chatbot/feishu-bot-connection.js';
import { WeixinBotConnection } from './chatbot/weixin-bot-connection.js';
import { testAnthropicCompat, detectCapabilities, listModels } from './model-capabilities.js';
import { InitManager } from './init/init-manager.js';
import { initStardomBridge, getStardomBridge } from './stardom/index.js';
import { initHub, stopHub, getHubStatus, getHubWsClient, getEvaluationHandler, setActualPort as _setActualPort } from './hub/index.js';
import { buildEncryptedRequest, decrypt } from './hub/crypto.js';
import { getClientId } from './utils/network.js';
import { getServerBaseUrl, ensureServerBaseUrl } from './server-url.js';
import { BroadcastStore } from './broadcast-store.js';

const PORT = parseInt(process.env.PORT || '5880', 10);
const log = createLogger('Server');

/** Actual port the server is listening on (may differ from PORT after fallback) */
export let actualPort = PORT;

function getHomeDir(): string {
  const env = process.env.SMANBASE_HOME;
  if (env) return env;
  return path.join(os.homedir(), '.sman');
}

function ensureHomeDir(homeDir: string): void {
  const dirs = ['skills', 'logs', 'claude-config'];
  for (const dir of dirs) {
    fs.mkdirSync(path.join(homeDir, dir), { recursive: true });
  }

  // Write minimal settings.json in isolated claude-config dir
  // to prevent CLI from reading ~/.claude/settings.json which may contain
  // conflicting env vars (ANTHROPIC_AUTH_TOKEN, etc.)
  const claudeSettingsDir = path.join(homeDir, 'claude-config');
  const claudeSettingsPath = path.join(claudeSettingsDir, 'settings.json');
  if (!fs.existsSync(claudeSettingsPath)) {
    const minimalSettings = {
      permissions: {
        allow: [
          'Bash',
          'Write',
          'Edit',
          'MCP',
        ],
      },
    };
    fs.writeFileSync(claudeSettingsPath, JSON.stringify(minimalSettings, null, 2));
    log.info(`Created isolated CLI settings at ${claudeSettingsPath}`);
  }

  const configPath = path.join(homeDir, 'config.json');
  if (!fs.existsSync(configPath)) {
    const defaultConfig = {
      port: PORT,
      llm: { apiKey: '', model: '' },
      webSearch: {
        provider: 'builtin',
        braveApiKey: '',
        tavilyApiKey: '',
        maxUsesPerSession: 50,
      },
      auth: { token: '' },
    };
    fs.writeFileSync(configPath, JSON.stringify(defaultConfig, null, 2), { encoding: 'utf-8', mode: 0o600 });
    log.info(`Created default config at ${configPath}`);
  }

  const registryPath = path.join(homeDir, 'registry.json');
  if (!fs.existsSync(registryPath)) {
    const defaultRegistry = { version: '1.0', skills: {} };
    fs.writeFileSync(registryPath, JSON.stringify(defaultRegistry, null, 2), 'utf-8');
    log.info(`Created empty registry at ${registryPath}`);
  }
}

// Initialize
const homeDir = getHomeDir();
ensureHomeDir(homeDir);

const dbPath = path.join(homeDir, 'sman.db');
const store = new SessionStore(dbPath);
const broadcastStore = new BroadcastStore(store.getDatabase());
const skillsRegistry = new SkillsRegistry(homeDir);
const sessionManager = new ClaudeSessionManager(store);
setSessionManagerForPush(sessionManager);
const settingsManager = new SettingsManager(homeDir);

// User profile manager
const userProfileManager = new UserProfileManager(homeDir, settingsManager.getConfig());
sessionManager.setUserProfile(userProfileManager);

// Knowledge extractor (extracts knowledge from conversations into .sman/knowledge/)
import { KnowledgeExtractorStore } from './knowledge-extractor-store.js';
import { KnowledgeExtractor } from './knowledge-extractor.js';
const knowledgeExtractorStore = new KnowledgeExtractorStore(dbPath);
const knowledgeExtractor = new KnowledgeExtractor(store, knowledgeExtractorStore, settingsManager.getConfig());
sessionManager.setKnowledgeExtractor(knowledgeExtractor);

// Initialize auth token (generate on first run, reuse thereafter)
let authToken = settingsManager.ensureAuthToken();

// Cron task scheduler
const cronTaskStore = new CronTaskStore(dbPath);
const cronScheduler = new CronScheduler(cronTaskStore);
cronScheduler.setSessionStore(store);

// WebSocket client tracking (declared early for use by broadcast)
const clients = new Set<WebSocket>();
const authenticatedClients = new Set<WebSocket>();

// ── Client ↔ Session bidirectional mapping ──
// Tracks which sessions each client has subscribed to (for multi-tab support)
const clientToSessions = new Map<WebSocket, Set<string>>();

// Tracks which clients are subscribed to each session (for targeted message routing)
const sessionToClients = new Map<string, Set<WebSocket>>();

/**
 * Subscribe a client to a session (called when session list is loaded or session is switched)
 */
function subscribeClientToSession(ws: WebSocket, sessionId: string): void {
  // Add to client → sessions mapping
  if (!clientToSessions.has(ws)) {
    clientToSessions.set(ws, new Set());
  }
  clientToSessions.get(ws)!.add(sessionId);

  // Add to session → clients mapping
  if (!sessionToClients.has(sessionId)) {
    sessionToClients.set(sessionId, new Set());
  }
  sessionToClients.get(sessionId)!.add(ws);
}

/**
 * Unsubscribe a client from a session (called when session is deleted or client disconnects)
 */
function unsubscribeClientFromSession(ws: WebSocket, sessionId: string): void {
  // Remove from client → sessions mapping
  const sessions = clientToSessions.get(ws);
  if (sessions) {
    sessions.delete(sessionId);
    if (sessions.size === 0) {
      clientToSessions.delete(ws);
    }
  }

  // Remove from session → clients mapping
  const clients = sessionToClients.get(sessionId);
  if (clients) {
    clients.delete(ws);
    if (clients.size === 0) {
      sessionToClients.delete(sessionId);
    }
  }
}

/**
 * Unsubscribe a client from ALL sessions (called when client disconnects)
 */
function unsubscribeClientFromAllSessions(ws: WebSocket): void {
  const sessions = clientToSessions.get(ws);
  if (sessions) {
    for (const sessionId of sessions) {
      const clients = sessionToClients.get(sessionId);
      if (clients) {
        clients.delete(ws);
        if (clients.size === 0) {
          sessionToClients.delete(sessionId);
        }
      }
    }
    clientToSessions.delete(ws);
  }
}

/**
 * Get all clients subscribed to a specific session
 */
function getSessionClients(sessionId: string): Set<WebSocket> {
  return sessionToClients.get(sessionId) || new Set();
}

/**
 * Send a message to all clients subscribed to a specific session
 * Returns true if at least one client received the message
 */
function sendToSessionClients(sessionId: string, data: string): boolean {
  const clients = getSessionClients(sessionId);
  let sent = false;
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(data);
      sent = true;
    }
  }
  return sent;
}

/** Max buffered amount before applying backpressure (256KB) */
const WS_BACKPRESSURE_THRESHOLD = 256 * 1024;

function broadcast(data: string): void {
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      // Backpressure: skip non-critical messages if client is overwhelmed
      if ((client as any).bufferedAmount > WS_BACKPRESSURE_THRESHOLD) {
        // Only drop non-critical message types to preserve UX
        try {
          const parsed = JSON.parse(data);
          const droppableTypes = new Set([
            'chat.delta', 'chat.tool_delta', 'chat.tool_progress',
            'smartpath.stepExecutionProgress', 'batch.progress',
          ]);
          if (droppableTypes.has(parsed.type)) {
            continue; // Skip this client for this message
          }
        } catch {
          // Not JSON — send anyway (likely shouldn't happen)
        }
      }
      client.send(data);
    }
  }
}

function extractTextFromMessage(msg: any): string {
  if (msg.type === 'assistant') {
    const content = msg.message?.content;
    if (Array.isArray(content)) {
      return content
        .filter((block: any) => block.type === 'text')
        .map((block: any) => block.text)
        .join('');
    }
  }
  if (msg.type === 'stream_event') {
    return msg.event?.delta?.type === 'text_delta' ? msg.event.delta.text : '';
  }
  return '';
}

// Batch execution engine
const batchStore = new BatchStore(dbPath);
const batchEngine = new BatchEngine(batchStore);
batchEngine.setSessionManager(sessionManager);
batchEngine.setConfig(settingsManager.getConfig().llm);
batchEngine.setOnProgress((taskId, data) => {
  broadcast(JSON.stringify({ type: 'batch.progress', taskId, ...data }));
});
batchEngine.start();

// SmartPath
const smartPathStore = new SmartPathStore();
const smartPathEngine = new SmartPathEngine(smartPathStore, sessionManager);
const smartPathScheduler = new SmartPathScheduler(smartPathStore, smartPathEngine);
smartPathScheduler.setOnProgress((pathId, data) => {
  broadcast(JSON.stringify({ type: 'smartpath.scheduledRun', pathId, ...data }));
});

// Chatbot integration (WeCom + Feishu)
const chatbotStore = new ChatbotStore(dbPath);
const chatbotManager = new ChatbotSessionManager(
  homeDir,
  sessionManager,
  chatbotStore,
  (botProfileId: string) => {
    if (botProfileId === 'default') {
      return {
        id: 'default',
        label: 'Bot',
        botId: '',
        secret: '',
        mode: 'full' as const,
        workspace: '',
        allowedSkills: [],
        enabled: true,
      };
    }
    const config = settingsManager.getConfig().chatbot;
    if (!config?.wecom?.bots) return undefined;
    return config.wecom.bots.find((b: any) => b.id === botProfileId);
  },
  (sessionId: string, label: string) => {
    broadcast(JSON.stringify({ type: 'session.chatbotCreated', sessionId, label }));
  },
);

let wecomConnections: WeComBotConnection[] = [];
let feishuConnection: FeishuBotConnection | null = null;
let weixinConnection: WeixinBotConnection | null = null;

function startChatbotConnections(): void {
  const chatbotConfig = settingsManager.getConfig().chatbot;
  if (!chatbotConfig?.enabled) return;

  if (chatbotConfig.wecom.enabled && chatbotConfig.wecom.bots?.length > 0) {
    for (const bot of chatbotConfig.wecom.bots) {
      if (!bot.enabled || !bot.botId || !bot.secret) continue;
      const conn = new WeComBotConnection({
        botId: bot.botId,
        secret: bot.secret,
        botProfileId: bot.id,
        getMode: () => bot.mode,
        onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
      });
      conn.start();
      wecomConnections.push(conn);
    }
    log.info(`WeCom bot connections started: ${wecomConnections.length} bots`);
  }

  if (chatbotConfig.feishu.enabled && chatbotConfig.feishu.appId && chatbotConfig.feishu.appSecret) {
    feishuConnection = new FeishuBotConnection({
      appId: chatbotConfig.feishu.appId,
      appSecret: chatbotConfig.feishu.appSecret,
      onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
    });
    feishuConnection.start();
    log.info('Feishu bot connection started');
  }

  if (chatbotConfig.weixin?.enabled) {
    weixinConnection = new WeixinBotConnection({
      homeDir,
      onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
      onStatusChange: (status) => {
        broadcast(JSON.stringify({ type: 'chatbot.weixin.status', status }));
      },
    });
    weixinConnection.start();
    log.info('WeChat bot connection initialized');
  }
}

startChatbotConnections();

// Skills cache: 1-minute TTL to avoid frequent disk reads
// Key: `${workspace}:${skillType}`, Value: { data: any, expiresAt: number }
const skillsCache = new Map<string, { data: unknown; expiresAt: number }>();
const SKILLS_CACHE_TTL = 60_000; // 1 minute

function getCachedSkills(workspace: string, skillType: string): unknown | null {
  const key = `${workspace}:${skillType}`;
  const entry = skillsCache.get(key);
  if (!entry) return null;
  if (Date.now() > entry.expiresAt) {
    skillsCache.delete(key);
    return null;
  }
  return entry.data;
}

function setCachedSkills(workspace: string, skillType: string, data: unknown): void {
  const key = `${workspace}:${skillType}`;
  skillsCache.set(key, { data, expiresAt: Date.now() + SKILLS_CACHE_TTL });
};

// Feed config to session manager
sessionManager.updateConfig(settingsManager.getConfig());

// Initialize WebAccessService
const webAccessService = new WebAccessService();
webAccessService.detectEngine().then(() => {
  log.info(`WebAccess engine: ${webAccessService.getActiveEngineType() ?? 'unavailable'}`);
}).catch((err: any) => {
  log.warn(`WebAccess detection failed: ${err.message}`);
});
sessionManager.setWebAccessService(webAccessService);

// Initialize capability registry (on-demand capability loading)
const pluginsDir = path.join(resolveProjectRoot(), 'plugins');
initCapabilities(homeDir, pluginsDir);
const capabilityRegistry = new CapabilityRegistry(homeDir);
sessionManager.setCapabilityRegistry(capabilityRegistry);

// Initialize workspace auto-init manager
const initManager = new InitManager({
  pluginsDir,
  capabilityRegistry,
  llmConfig: () => {
    const config = settingsManager.getConfig();
    if (!config?.llm?.apiKey) return null;
    return {
      apiKey: config.llm.apiKey,
      model: config.llm.model || 'claude-sonnet-4-20250514',
      baseUrl: config.llm.baseUrl,
    };
  },
});

// Set up cron scheduler with session manager
cronScheduler.setSessionManager(sessionManager);
cronScheduler.start();

// Broadcast cron run status changes to all clients
cronScheduler.getExecutor().onRunStatusChange((taskId: string, status: string) => {
  const task = cronTaskStore.getTask(taskId);
  const latestRun = cronTaskStore.getLatestRun(taskId);
  broadcast(JSON.stringify({ type: 'cron.runStatusChanged', taskId, status, task, latestRun }));
});

// Start Smart Path scheduler after session manager is ready
smartPathScheduler.start([]); // will be populated when workspaces are discovered

// HTTP server with static file serving for production (Electron mode)
// __dirname after tsc compilation = dist/server/server/ (rootDir is project root)
// Frontend build output = dist/ (index.html, assets/)
const distDir = path.resolve(path.join(__dirname, '..', '..'));
const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

// ── CORS & Auth Helpers ──

const ALLOWED_ORIGINS = [
  // Dynamic: actual port is added after listen() resolves
  ...(process.env.CORS_ORIGINS
    ? process.env.CORS_ORIGINS.split(',')
        .map(s => s.trim())
        .filter(s => {
          try { new URL(s); return s.startsWith('http://') || s.startsWith('https://'); } catch { return false; }
        })
    : []),
];

function setCorsHeaders(req: http.IncomingMessage, res: http.ServerResponse): void {
  const origin = req.headers.origin || '';
  // Allow any localhost/127.0.0.1 origin (port may change after fallback)
  const isLocalOrigin = /^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(origin);
  if (isLocalOrigin || ALLOWED_ORIGINS.includes(origin)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  }
}

function isLoopback(req: http.IncomingMessage): boolean {
  const addr = req.socket.remoteAddress || '';
  return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';
}

function verifyHttpAuth(req: http.IncomingMessage): boolean {
  const header = req.headers.authorization || '';
  const match = header.match(/^Bearer\s+(.+)$/);
  if (!match) return false;
  return match[1] === authToken;
}

async function handleHubProxy(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
  let hubUrl = getServerBaseUrl(settingsManager);
  if (!hubUrl) {
    // Try resolving on-demand
    hubUrl = await ensureServerBaseUrl(settingsManager);
  }
  if (!hubUrl) {
    res.writeHead(503, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Hub not configured' }));
    return;
  }

  const urlObj = new URL(req.url!, `http://localhost`);
  // /api/hub/rooms → /api/hub/rooms, /api/hub/tasks?roomId=x → /api/hub/tasks?roomId=x
  const targetPath = urlObj.pathname.replace('/api/hub', '/api/hub') + urlObj.search;
  const targetUrl = `${hubUrl}${targetPath}`;
  const startTime = Date.now();
  log.info(`[hub-proxy] → ${req.method} ${targetUrl}`);

  try {
    // Read body from frontend
    const rawBody = req.method !== 'GET' && req.method !== 'HEAD'
      ? await new Promise<string>((resolve) => {
          let data = '';
          req.on('data', (chunk) => { data += chunk; });
          req.on('end', () => resolve(data));
        })
      : undefined;

    // Build PSK-encrypted payload
    let payload: Record<string, unknown> = {};
    if (rawBody) {
      try { payload = JSON.parse(rawBody); } catch {}
    }
    // For GET requests with query params, pass them in payload so sman-server can read
    if (req.method === 'GET' && urlObj.search) {
      const params = new URLSearchParams(urlObj.search);
      for (const [k, v] of params) {
        payload[k] = v;
      }
    }
    // Inject clientId for room listing so server can filter private rooms
    // Also inject ownerId for room creation
    if (targetPath.startsWith('/api/hub/rooms') && req.method === 'POST') {
      const cid = getClientId();
      payload.clientId = cid;
      if (payload.name) payload.ownerId = cid;
    }

    const encrypted = buildEncryptedRequest(payload);

    const controller = new AbortController();
    const fetchTimeout = setTimeout(() => controller.abort(), 3000);
    let fetchRes: Response;
    try {
      fetchRes = await fetch(targetUrl, {
        method: req.method === 'GET' ? 'POST' : req.method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(encrypted),
        signal: controller.signal,
      });
    } catch (fetchErr) {
      clearTimeout(fetchTimeout);
      log.warn(`[hub-proxy] ✗ ${targetUrl} fetch error after ${Date.now() - startTime}ms: ${(fetchErr as Error).message}`);
      throw fetchErr;
    }
    clearTimeout(fetchTimeout);
    log.info(`[hub-proxy] ← ${fetchRes.status} from ${targetUrl} in ${Date.now() - startTime}ms`);

    const responseBody = await fetchRes.text();

    if (!fetchRes.ok) {
      res.writeHead(fetchRes.status, { 'Content-Type': 'application/json' });
      res.end(responseBody);
      return;
    }

    // Decrypt sman-server response
    try {
      const encRes = JSON.parse(responseBody);
      if (encRes.payload) {
        const decrypted = decrypt(encRes.payload) as unknown;
        // Tag isOwner on room list responses, keep password for owner only
        if (targetPath.startsWith('/api/hub/rooms') && req.method === 'POST') {
          const rooms = Array.isArray(decrypted)
            ? decrypted
            : (decrypted as { rooms?: unknown[] })?.rooms;
          if (Array.isArray(rooms)) {
            const cid = getClientId();
            const cidUser = cid.split('@')[0];
            for (const room of rooms as { owner_id?: string; isOwner?: boolean; password?: string; hasPassword?: boolean }[]) {
              const ownerUser = (room.owner_id ?? '').split('@')[0];
              room.isOwner = room.owner_id === cid || ownerUser === cidUser;
              if (!room.isOwner) delete room.password;
            }
          }
        }
        // Room detail: keep password for owner, strip for others
        if (/^\/api\/hub\/rooms\/[^/]+$/.test(targetPath) && req.method === 'POST') {
          const detail = decrypted as { room?: { owner_id?: string; password?: string } } | null;
          if (detail?.room) {
            const cid = getClientId();
            const cidUser = cid.split('@')[0];
            const ownerUser = (detail.room.owner_id ?? '').split('@')[0];
            const isOwner = detail.room.owner_id === cid || ownerUser === cidUser;
            if (!isOwner) delete detail.room.password;
          }
        }
        // After creating a room via REST, join via WS and register agents
        if (targetPath === '/api/hub/rooms' && req.method === 'POST' && !Array.isArray(decrypted)) {
          const room = decrypted as { id?: string; name?: string } | null;
          if (room?.id && room?.name) {
            const wsClient = getHubWsClient();
            if (wsClient?.isConnected()) {
              const cid = getClientId();
              wsClient.send({ type: 'room.join', roomId: room.id, clientId: cid, displayName: cid });
            }
          }
        }
        // After creating a task via REST, trigger evaluation
        if (targetPath === '/api/hub/tasks' && req.method === 'POST' && !Array.isArray(decrypted)) {
          const task = decrypted as { id?: string; room_id?: string; title?: string; status?: string } | null;
          if (task?.id && task?.title) {
            const evalHandler = getEvaluationHandler();
            if (evalHandler) {
              evalHandler.handleMessage({ type: 'task.created', task });
            }
          }
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(decrypted));
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(responseBody);
      }
    } catch {
      // If decryption fails, pass through as-is
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(responseBody);
    }
  } catch (err) {
    log.error(`[hub-proxy] proxy error: ${(err as Error).message}`);
    res.writeHead(502, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: `Hub proxy error: ${(err as Error).message}` }));
  }
}

const server = http.createServer((req, res) => {
  // CORS preflight
  if (req.method === 'OPTIONS') {
    setCorsHeaders(req, res);
    res.writeHead(204);
    res.end();
    return;
  }

  // Set CORS headers on all responses
  setCorsHeaders(req, res);

  // Security headers
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  if (req.url?.startsWith('/api/')) {
    res.setHeader('Content-Security-Policy', "default-src 'none'");
  }

  // Public: health check (no auth)
  if (req.url === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toLocaleString('zh-CN', { hour12: false }) }));
    return;
  }

  // Public: hub diagnostics (no auth, for debugging)
  if (req.url === '/api/hub-status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(getHubStatus(settingsManager)));
    return;
  }

  // Public: get language setting (no auth, for pre-render i18n init)
  if (req.url === '/api/language') {
    const language = settingsManager.getConfig().language || 'zh-CN';
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ language }));
    return;
  }

  // Public: test SearXNG connectivity (uses curl which respects proxy env vars)
  if (req.url === '/api/searxng/test' && req.method === 'POST') {
    (async () => {
      const { execFile } = await import('child_process');
      const instances = [
        'https://searx.be',
        'https://search.sapti.me',
        'https://searxng.ch',
        'https://search.bus-hit.me',
        'https://searx.tiekoetter.com',
      ];
      const testInstance = (url: string): Promise<boolean> => new Promise((resolve) => {
        execFile('curl', ['-s', '-o', '/dev/null', '-w', '%{http_code}', '--max-time', '8', url], (err, stdout) => {
          if (err) { resolve(false); return; }
          const code = parseInt(stdout.trim(), 10);
          resolve(code >= 200 && code < 500);
        });
      });
      for (const baseUrl of instances) {
        const ok = await testInstance(baseUrl);
        if (ok) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, instance: baseUrl }));
          return;
        }
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false }));
    })();
    return;
  }

  // Public: open URL in system default browser
  if (req.url === '/api/open-external' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk: Buffer) => { body += chunk.toString(); });
    req.on('end', () => {
      try {
        const { url: targetUrl } = JSON.parse(body);
        if (!targetUrl || !targetUrl.startsWith('https://')) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Invalid URL' }));
          return;
        }
        import('child_process').then(({ execFile }) => {
          const cmd = process.platform === 'darwin' ? 'open'
            : process.platform === 'win32' ? 'cmd.exe' : 'xdg-open';
          const args = process.platform === 'win32'
            ? ['/c', 'start', '""', targetUrl]
            : [targetUrl];
          execFile(cmd, args, (err) => {
            if (err) {
              res.writeHead(500, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ error: 'Failed to open' }));
            } else {
              res.writeHead(200, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ ok: true }));
            }
          });
        });
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Invalid JSON' }));
      }
    });
    return;
  }

  // Public: token retrieval — loopback only
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

  // API endpoints (except health/token) require auth
  if (req.url?.startsWith('/api/') && !verifyHttpAuth(req)) {
    res.writeHead(401, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Unauthorized' }));
    return;
  }

  // Hub proxy — forward to sman-server REST API
  if (req.url?.startsWith('/api/hub/')) {
    handleHubProxy(req, res);
    return;
  }

  // Code image viewer API — serves image files from workspace
  if (req.url?.startsWith('/api/code/image')) {
    const urlObj = new URL(req.url, `http://localhost:${PORT}`);
    const workspace = urlObj.searchParams.get('workspace');
    const filePath = urlObj.searchParams.get('file');

    if (!workspace || !filePath) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Missing workspace or file parameter' }));
      return;
    }

    try {
      const resolved = validatePath(workspace, filePath);
      const stat = fs.statSync(resolved);
      if (!stat.isFile()) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Not a file' }));
        return;
      }

      const ext = path.extname(resolved).toLowerCase();
      const mimeMap: Record<string, string> = {
        '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
        '.gif': 'image/gif', '.bmp': 'image/bmp', '.ico': 'image/x-icon',
        '.svg': 'image/svg+xml', '.webp': 'image/webp',
      };
      const mime = mimeMap[ext] || 'application/octet-stream';

      const data = fs.readFileSync(resolved);
      res.writeHead(200, {
        'Content-Type': mime,
        'Content-Length': data.length,
        'Cache-Control': 'no-cache',
      });
      res.end(data);
    } catch {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'File not found' }));
    }
    return;
  }

  // Directory browser API
  if (req.url?.startsWith('/api/directory/read')) {
    const urlObj = new URL(req.url, `http://localhost:${PORT}`);
    const dirPath = urlObj.searchParams.get('path');

    if (!dirPath) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Missing path parameter' }));
      return;
    }

    try {
      const normalizedPath = normalizeWorkspacePath(dirPath);
      const entries = fs.readdirSync(normalizedPath, { withFileTypes: true });
      const result = entries
        .filter(entry => !entry.name.startsWith('.'))
        .map(entry => ({
          name: entry.name,
          path: path.join(normalizedPath, entry.name),
          isDirectory: entry.isDirectory(),
        }))
        .sort((a, b) => {
          if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
          return a.name.localeCompare(b.name);
        });

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ entries: result }));
    } catch {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Failed to read directory' }));
    }
    return;
  }

  // Home directory API - return user's home path
  if (req.url === '/api/directory/home') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ home: os.homedir() }));
    return;
  }

  // MCP API: List available tools (skills and paths)
  if (req.url === '/api/mcp/tools/list' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk.toString(); });
    req.on('end', () => {
      try {
        const parsed = JSON.parse(body || '{}');
        const requested = Array.isArray(parsed.workspaces) && parsed.workspaces.length > 0
          ? parsed.workspaces
          : store.getActiveWorkspaces();
        const tools: Array<{ id: string; name: string; description: string; type: 'skill' | 'path'; workspace: string }> = [];

        for (const ws of requested) {
          const projectSkills = skillsRegistry.getProjectSkills(ws);
          for (const skill of projectSkills) {
            tools.push({
              id: skill.id,
              name: skill.name,
              description: skill.description,
              type: 'skill',
              workspace: ws,
            });
          }
        }

        for (const ws of requested) {
          const paths = smartPathStore.listAll([ws]);
          for (const p of paths) {
            tools.push({
              id: p.id,
              name: p.name,
              description: p.description || '',
              type: 'path',
              workspace: ws,
            });
          }
        }

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ tools }));
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : String(err);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: errorMessage }));
      }
    });
    return;
  }

  // MCP API: Invoke a tool (skill or path)
  if (req.url === '/api/mcp/tools/invoke' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk.toString(); });
    req.on('end', async () => {
      try {
        const { workspace, toolType, toolId, parameters } = JSON.parse(body);

        if (!workspace || !toolType || !toolId) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Missing required parameters: workspace, toolType, toolId' }));
          return;
        }

        if (toolType === 'path') {
          const path = smartPathStore.get(toolId, workspace);
          if (!path) {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: `Path not found: ${toolId}` }));
            return;
          }

          try {
            const args = parameters as string | undefined;
            const { stepResults, blueprint } = await smartPathEngine.runWithResults(
              toolId, workspace, args, () => {},
            );

            const p = smartPathStore.get(toolId, workspace);
            const refs = smartPathStore.listReferences(workspace, toolId);
            let steps: Array<{ name?: string; userInput: string }>;
            try { steps = JSON.parse(path.steps || '[]'); } catch { steps = []; }
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
              status: 'success',
              result: `Path "${p?.name || toolId}" completed`,
              goal: blueprint.goal,
              steps: steps.map((s, i) => ({
                name: s.name || s.userInput,
                result: stepResults[i]?.slice(0, 500) || '',
              })),
              pathId: toolId,
              referencesCount: refs.length,
            }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ status: 'error', error: errorMessage }));
          }

        } else if (toolType === 'skill') {
          // Execute skill by creating a temporary session and sending the skill command
          const skill = skillsRegistry.getSkill(toolId);
          let skillName = toolId;
          if (!skill) {
            // Try project skills
            const projectSkills = skillsRegistry.getProjectSkills(workspace);
            const found = projectSkills.find(s => s.id === toolId);
            if (!found) {
              res.writeHead(404, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ error: `Skill not found: ${toolId}` }));
              return;
            }
            skillName = found.name;
          } else {
            skillName = skill.name;
          }

          // Create ephemeral session and execute skill
          const tempSessionId = sessionManager.createEphemeralSession(workspace);
          const abort = new AbortController();
          let fullResult = '';

          try {
            const prompt = parameters ? `/${toolId} ${parameters}` : `/${toolId}`;
            await sessionManager.sendMessageForCron(
              tempSessionId,
              prompt,
              abort,
              () => {},
            );

            // Get the last assistant message as result
            const messages = store.getMessages(tempSessionId, 1);
            const lastMsg = messages[messages.length - 1];
            if (lastMsg?.role === 'assistant') {
              fullResult = lastMsg.content;
            }

            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
              status: 'success',
              result: fullResult.trim(),
              skillId: toolId,
              skillName: skillName,
            }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ status: 'error', error: errorMessage }));
          } finally {
            sessionManager.closeV2Session(tempSessionId);
            sessionManager.removeEphemeralSession(tempSessionId);
          }
        } else {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: `Invalid toolType: ${toolType}. Must be 'skill' or 'path'` }));
        }
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : String(err);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: errorMessage }));
      }
    });
    return;
  }

  // Async skill trigger — returns immediately, executes in background
  if (req.url === '/api/mcp/tools/trigger' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk.toString(); });
    req.on('end', () => {
      try {
        const { workspace, toolId, parameters } = JSON.parse(body);
        if (!workspace || !toolId) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Missing required parameters: workspace, toolId' }));
          return;
        }

        const skill = skillsRegistry.getSkill(toolId);
        let found = !!skill;
        if (!found) {
          const projectSkills = skillsRegistry.getProjectSkills(workspace);
          found = projectSkills.some(s => s.id === toolId);
        }

        if (!found) {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: `Skill not found: ${toolId}` }));
          return;
        }

        // Ack immediately
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'accepted', workspace, toolId }));

        // Execute in background
        const tempSessionId = `ephemeral-${crypto.randomUUID()}`;
        // Create session record in SQLite for foreign key constraints
        store.createSession({ id: tempSessionId, systemId: tempSessionId, workspace, isCron: true });
        sessionManager.createEphemeralSessionWithId(workspace, tempSessionId);
        const abort = new AbortController();
        const prompt = parameters ? `/${toolId} ${parameters}` : `/${toolId}`;
        sessionManager.sendMessageForCron(tempSessionId, prompt, abort, () => {})
          .catch(err => log.error(`[AsyncTrigger] ${toolId} failed: ${err instanceof Error ? err.message : err}`))
          .finally(() => {
            sessionManager.closeV2Session(tempSessionId);
            sessionManager.removeEphemeralSession(tempSessionId);
          });
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : String(err);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: errorMessage }));
      }
    });
    return;
  }

  // WebSocket upgrade path — let WSS handle it
  if (req.url?.startsWith('/ws')) {
    res.writeHead(426);
    res.end();
    return;
  }

  // In production, serve static files from dist/
  if (fs.existsSync(distDir)) {
    const urlPath = req.url?.split('?')[0] || '/';
    let filePath = path.join(distDir, urlPath === '/' ? 'index.html' : urlPath);

    // Prevent path traversal: ensure resolved path stays within distDir
    const resolvedPath = path.resolve(filePath);
    if (!resolvedPath.startsWith(path.resolve(distDir))) {
      res.writeHead(403);
      res.end('Forbidden');
      return;
    }

    // SPA fallback: serve index.html for unknown paths
    if (!fs.existsSync(resolvedPath) || fs.statSync(resolvedPath).isDirectory()) {
      filePath = path.join(distDir, 'index.html');
    } else {
      filePath = resolvedPath;
    }

    if (fs.existsSync(filePath)) {
      const ext = path.extname(filePath);
      const contentType = MIME[ext] || 'application/octet-stream';
      res.writeHead(200, { 'Content-Type': contentType });
      fs.createReadStream(filePath).pipe(res);
      return;
    }
  }

  res.writeHead(404);
  res.end('Not found');
});

// WebSocket server with broadcast support
const wss = new WebSocketServer({
  server,
  path: '/ws',
  verifyClient: (info, callback) => {
    const origin = info.origin || '';
    // Allow connections with no origin (non-browser clients) or from local origins
    if (origin) {
      const isLocalOrigin = /^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(origin);
      if (!isLocalOrigin && !ALLOWED_ORIGINS.includes(origin)) {
        callback(false, 403, 'Forbidden: invalid origin');
        return;
      }
    }
    callback(true);
  },
});
interface WsMessage {
  type: string;
  sessionId?: string;
  workspace?: string;
  content?: string;
  [key: string]: unknown;
}


wss.on('connection', (ws: WebSocket) => {
  log.info('WebSocket client connected, awaiting authentication');

  // Authentication timeout: disconnect after 5 seconds if not authenticated
  const authTimeout = setTimeout(() => {
    if (!authenticatedClients.has(ws)) {
      log.warn('WebSocket client disconnected: auth timeout');
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

    // Handle auth.verify — the only allowed message before authentication
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

    try {
      switch (msg.type) {
        case 'session.create': {
          if (!msg.workspace) throw new Error('Missing workspace');
          const normalizedWorkspace = normalizeWorkspacePath(msg.workspace);
          const sessionId = sessionManager.createSession(normalizedWorkspace);
          ws.send(JSON.stringify({ type: 'session.created', sessionId, workspace: normalizedWorkspace }));

          // Auto-subscribe client to the newly created session
          subscribeClientToSession(ws, sessionId);

          // Auto-initialize workspace (async, non-blocking)
          initManager.handleSessionCreate(normalizedWorkspace, sessionId, ws).catch((err: any) => {
            log.warn(`Workspace init failed for ${normalizedWorkspace}: ${err.message}`);
          });
          break;
        }

        case 'session.list': {
          const allLocalSessions = sessionManager.listSessions();

          const botSessions = chatbotStore.getSessionsWithBotInfo()
            .filter(bs => allLocalSessions.some(s => s.id === bs.sessionId))
            .map(bs => {
              const s = allLocalSessions.find(s => s.id === bs.sessionId);
              // Look up bot mode from config
              const userKeyParts = bs.userKey.split(':');
              const botProfileId = userKeyParts.length >= 2 ? userKeyParts[1] : 'default';
              let botMode: 'full' | 'query' | 'collect' = 'full';
              if (botProfileId !== 'default') {
                const cfg = settingsManager.getConfig().chatbot;
                const botProfile = cfg?.wecom?.bots?.find((b: any) => b.id === botProfileId);
                if (botProfile?.mode) botMode = botProfile.mode;
              }
              return {
                id: bs.sessionId,
                workspace: bs.workspace,
                label: s?.label || '',
                createdAt: s?.createdAt,
                lastActiveAt: s?.lastActiveAt,
                source: 'bot' as const,
                botLabel: bs.botLabel,
                botMode,
              };
            });

          const botSessionIds = new Set(botSessions.map(bs => bs.id));

          const localSessions = allLocalSessions
            .filter(s => !botSessionIds.has(s.id))
            .map(s => ({
              id: s.id,
              workspace: s.workspace,
              label: s.label,
              createdAt: s.createdAt,
              lastActiveAt: s.lastActiveAt,
              source: 'local' as const,
              botLabel: null as string | null,
            }));

          const sessions = [...localSessions, ...botSessions];
          ws.send(JSON.stringify({ type: 'session.list', sessions }));

          for (const session of sessions) {
            subscribeClientToSession(ws, session.id);
          }

          break;
        }

        case 'session.updateLabel': {
          if (!msg.sessionId || typeof msg.label !== 'string') throw new Error('Missing sessionId or label');
          store.updateLabel(msg.sessionId, msg.label);
          // Send only to clients subscribed to this session (not broadcast to all)
          sendToSessionClients(msg.sessionId, JSON.stringify({ type: 'session.labelUpdated', sessionId: msg.sessionId, label: msg.label }));
          break;
        }

        case 'session.delete': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          const sessionId = msg.sessionId;

          // Extract knowledge and profile before marking as deleted
          const workspace = store.getSessionWorkspace(sessionId);
          const history = store.getMessages(sessionId, 4);
          if (workspace) {
            knowledgeExtractor.recordDeletion(workspace, sessionId);
          }
          if (history.length >= 2) {
            const lastUser = [...history].reverse().find(m => m.role === 'user');
            const lastAssistant = [...history].reverse().find(m => m.role === 'assistant');
            if (lastUser && lastAssistant) {
              userProfileManager.recordDeletion(lastUser.content, lastAssistant.content);
            }
          }

          sessionManager.abort(sessionId);
          sessionManager.clearTokenUsage(sessionId);
          sessionManager.closeV2Session(sessionId);
          store.deleteSession(sessionId);

          // Clean up chatbot_sessions if this was a bot session
          chatbotStore.deleteSessionBySessionId(sessionId);

          // Unsubscribe all clients from this deleted session
          const clients = getSessionClients(sessionId);
          for (const client of clients) {
            unsubscribeClientFromSession(client, sessionId);
          }

          ws.send(JSON.stringify({ type: 'session.deleted', sessionId }));
          break;
        }

        case 'session.history': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          const messages = sessionManager.getHistory(msg.sessionId);
          const tokenUsage = store.getTokenUsage(msg.sessionId);
          ws.send(JSON.stringify({
            type: 'session.history',
            sessionId: msg.sessionId,
            messages,
            usage: tokenUsage ?? null,
          }));
          break;
        }

        case 'session.preheat': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          sessionManager.preheatSession(msg.sessionId).catch(() => {});
          break;
        }

        case 'chat.send': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          if (!msg.content && !(msg as any).media?.length) throw new Error('Missing content or media');
          // Sync AUTO mode state from frontend
          if (msg.autoConfirm !== undefined) {
            sessionManager.setAutoMode(msg.sessionId, !!msg.autoConfirm);
          }
          // Capture sessionId for the closure
          const chatSessionId = msg.sessionId;
          const wsSend = (d: string) => {
            // Send to ALL clients subscribed to this session (supports multi-tab)
            // Only send to clients that have this session in their subscription list
            const sessionClients = getSessionClients(chatSessionId);
            let sentCount = 0;

            for (const client of sessionClients) {
              if (client.readyState === WebSocket.OPEN) {
                client.send(d);
                sentCount++;
              }
            }

            // If no subscribed clients are available, log a warning
            if (sentCount === 0) {
              log.warn(`No active subscribed client for session ${chatSessionId}, message dropped`);
            }
          };

          const content = msg.content ?? '';

          // Check for /path command: /pathName args...
          const pathCommandMatch = content.trim().match(/^\/([a-zA-Z0-9_\-一-龥]+)(?:\s+(.*))?$/);
          if (pathCommandMatch) {
            const pathName = pathCommandMatch[1];
            const pathArgs = pathCommandMatch[2] || '';
            const session = store.getSession(chatSessionId);
            if (session) {
              const workspace = session.workspace;
              const paths = smartPathStore.list(workspace);
              // Find path by name or id (exact match first, then fuzzy)
              const matchedPath = paths.find(p => p.name === pathName)
                || paths.find(p => p.id === pathName)
                || paths.find(p => p.name.toLowerCase() === pathName.toLowerCase());
              if (matchedPath) {
                const argsToUse = pathArgs || matchedPath.defaultArgs || '';
                const runPathId = matchedPath.id;
                // Resolve actual workspace (same logic as smartpath.run)
                const runAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
                const pathToRun = smartPathStore.get(runPathId, workspace, runAllWs);
                const runWorkspace = pathToRun?.workspace || matchedPath.workspace;
                const displayContent = argsToUse ? `/${matchedPath.name} ${argsToUse}` : `/${matchedPath.name}`;

                // Prevent parallel execution
                if (smartPathEngine.isRunning(runPathId)) {
                  store.addMessage(chatSessionId, { role: 'user', content: displayContent });
                  await sessionManager.sendMessage(chatSessionId, '路径正在执行中，请等待完成后再试。', wsSend, undefined, 1);
                  break;
                }

                // Phase 1: Send immediate feedback via sendMessage
                const media = (msg as any).media as import('./chatbot/types.js').MediaAttachment[] | undefined;
                store.addMessage(chatSessionId, { role: 'user', content: displayContent });
                await sessionManager.sendMessage(chatSessionId, `正在执行路径「${matchedPath.name}」...\n引擎分析中，完成后会通知结果。`, wsSend, media, 1);

                // Phase 2: Run engine in background, send results as new message
                smartPathEngine.runWithResults(
                  runPathId, runWorkspace, argsToUse,
                  () => {},
                ).then(async ({ stepResults: engineResults, blueprint: engineBlueprint }) => {
                  const summaryParts: string[] = [`**路径「${matchedPath.name}」执行完成**`];
                  summaryParts.push(`\n目标: ${engineBlueprint.goal}`);
                  const steps: Array<{ name?: string; userInput: string }> = JSON.parse(matchedPath.steps || '[]');
                  steps.forEach((s, i) => {
                    if (engineResults[i]) {
                      summaryParts.push(`\n**步骤${i + 1}: ${s.name || s.userInput}**`);
                      summaryParts.push(engineResults[i].slice(0, 500));
                    }
                  });
                  await sessionManager.sendMessage(chatSessionId, summaryParts.join('\n'), wsSend, undefined, 1);
                  // Update session label
                  const pathLabel = `/${matchedPath.name}`;
                  store.updateLabel(chatSessionId, pathLabel);
                  broadcast(JSON.stringify({ type: 'session.labelUpdated', sessionId: chatSessionId, label: pathLabel }));
                  // Notify path page
                  const updatedPath = smartPathStore.get(runPathId, runWorkspace);
                  const refs = smartPathStore.listReferences(runWorkspace, runPathId);
                  broadcast(JSON.stringify({ type: 'smartpath.completed', pathId: runPathId, path: updatedPath, references: refs }));
                }).catch(async (err) => {
                  const errMsg = err instanceof Error ? err.message : String(err);
                  await sessionManager.sendMessage(chatSessionId, `路径执行失败: ${errMsg}`, wsSend, undefined, 1);
                });
                break;
              }
            }
          }

          const media = (msg as any).media as import('./chatbot/types.js').MediaAttachment[] | undefined;
          await sessionManager.sendMessage(msg.sessionId, content, wsSend, media, 0);
          break;
        }

        case 'chat.abort': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          sessionManager.abort(msg.sessionId, 'user');
          // chat.aborted is sent by sendMessage's catch block after abort completes
          break;
        }

        case 'chat.answer_question': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          if (!msg.askId) throw new Error('Missing askId');
          const resolved = sessionManager.resolveAskUser(
            msg.sessionId,
            String(msg.askId),
            (msg.answers ?? {}) as Record<string, string[]>,
          );
          if (!resolved) {
            ws.send(JSON.stringify({ type: 'error', error: 'No pending question found (may have timed out)' }));
          }
          break;
        }

        // ── Skills Registry ──
        case 'skills.list': {
          const skills = skillsRegistry.listSkills();
          ws.send(JSON.stringify({ type: 'skills.list', skills }));
          break;
        }

        case 'skills.listProject': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          const session = store.getSession(msg.sessionId);
          if (!session) throw new Error('Session not found');
          const skills = skillsRegistry.getProjectSkills(session.workspace);
          const commands = skillsRegistry.getProjectCommands(session.workspace);
          const paths = smartPathStore.list(session.workspace).map(p => ({
            id: p.id,
            name: p.name,
            description: p.description || '',
          }));
          ws.send(JSON.stringify({ type: 'skills.listProject', skills, commands, paths }));
          break;
        }

        // ── Settings ──
        case 'settings.get': {
          await ensureServerBaseUrl(settingsManager);
          const config = settingsManager.getConfig();
          ws.send(JSON.stringify({ type: 'settings.get', config }));
          break;
        }

        case 'settings.update': {
          const { type: _t, auth: _auth, ...safeUpdates } = msg;
          // Prevent modifying auth.token via settings.update — use dedicated reset mechanism instead
          const config = settingsManager.updateConfig(safeUpdates as Partial<import('./types.js').SmanConfig>);
          sessionManager.updateConfig(config);
          userProfileManager.updateConfig(config);
          knowledgeExtractor.updateConfig(config);
          batchEngine.setConfig(config.llm);
          if ((safeUpdates as Record<string, unknown>).chatbot) {
            // Clean up sessions for deleted bots
            const chatbotUpdate = (safeUpdates as Record<string, unknown>).chatbot as Record<string, unknown> | undefined;
            const wecomUpdate = chatbotUpdate?.wecom as Record<string, unknown> | undefined;
            const newBots = (wecomUpdate?.bots ?? []) as Array<{ id: string; mode?: string }>;
            const newBotIds = new Set(newBots.map((b) => b.id));

            // Regenerate bot prompts and iterate CLAUDE.md
            for (const bot of newBots) {
              if (bot.mode === 'collect') {
                chatbotManager.ensureIterateClaudeMd(bot.id, (bot as { collectPrompt?: string }).collectPrompt);
              }
            }
            if (newBots.some((b) => b.mode === 'query' || b.mode === 'collect')) {
              chatbotManager.ensureBotPrompts();
            }
            const allBotSessions = chatbotStore.getSessionsWithBotInfo();
            const deletedBotProfileIds = new Set<string>();
            for (const bs of allBotSessions) {
              const parts = bs.userKey.split(':');
              const profileId = parts.length >= 2 ? parts[1] : '';
              if (profileId && profileId !== 'default' && !newBotIds.has(profileId)) {
                deletedBotProfileIds.add(profileId);
              }
            }
            for (const profileId of deletedBotProfileIds) {
              const deleted = chatbotStore.deleteSessionsByBotProfileId(profileId);
              for (const { sessionId } of deleted) {
                sessionManager.abort(sessionId);
                sessionManager.closeV2Session(sessionId);
                store.deleteSession(sessionId);
              }
              // Clean up iterate directory for deleted collect bots
              const iterateDir = path.join(os.homedir(), '.sman', 'iterate', profileId);
              if (fs.existsSync(iterateDir)) {
                fs.rmSync(iterateDir, { recursive: true, force: true });
              }
              log.info(`Cleaned up ${deleted.length} sessions for deleted bot profile: ${profileId}`);
            }

            wecomConnections.forEach(c => c.stop());
            wecomConnections = [];
            feishuConnection?.stop();
            weixinConnection?.stop();
            startChatbotConnections();
          }
          ws.send(JSON.stringify({ type: 'settings.updated', config }));
          break;
        }

        case 'settings.fetchModels': {
          const { apiKey, baseUrl } = msg as unknown as { apiKey: string; baseUrl?: string };
          if (!apiKey) {
            ws.send(JSON.stringify({ type: 'settings.modelsList', models: [], error: '缺少 API Key' }));
            break;
          }
          const result = await listModels(apiKey, baseUrl);
          ws.send(JSON.stringify({ type: 'settings.modelsList', models: result.models, unsupported: result.unsupported }));
          break;
        }

        case 'settings.testAndSave': {
          const { apiKey, model, baseUrl, profileName } = msg as unknown as { apiKey: string; model: string; baseUrl?: string; profileName?: string };
          if (!apiKey || !model) {
            ws.send(JSON.stringify({ type: 'settings.testResult', success: false, error: '缺少 API Key 或模型名称' }));
            break;
          }

          // Step 1: Test Anthropic compatibility
          const testResult = await testAnthropicCompat(apiKey, model, baseUrl);
          if (!testResult.success) {
            ws.send(JSON.stringify({ type: 'settings.testResult', success: false, error: testResult.error }));
            break;
          }

          // Step 2: Detect capabilities (3-layer)
          const capsResult = await detectCapabilities(apiKey, model, baseUrl);
          const finalCaps = capsResult.capabilities ?? testResult.capabilities;

          // Step 3: Build LLM profile
          const llmProfile: import('./types.js').LlmProfile = {
            name: profileName || '默认',
            apiKey,
            model,
            baseUrl,
            capabilities: finalCaps ?? { text: true, image: false, pdf: false, audio: false, video: false, source: 'api' as const },
          };

          // Step 4: Save to savedLlms list (upsert by name)
          const config = settingsManager.saveLlmProfile(llmProfile);
          sessionManager.updateConfig(config);
          userProfileManager.updateConfig(config);
          knowledgeExtractor.updateConfig(config);
          batchEngine.setConfig(config.llm);

          ws.send(JSON.stringify({
            type: 'settings.testResult',
            success: true,
            capabilities: finalCaps ?? { text: true, image: false, pdf: false, audio: false, video: false, source: 'api' as const },
            savedLlms: config.savedLlms,
          }));
          break;
        }

        case 'settings.selectLlmProfile': {
          const { profileName } = msg as unknown as { profileName: string };
          const config = settingsManager.selectLlmProfile(profileName);
          sessionManager.updateConfig(config);
          userProfileManager.updateConfig(config);
          knowledgeExtractor.updateConfig(config);
          batchEngine.setConfig(config.llm);
          ws.send(JSON.stringify({ type: 'settings.updated', config }));
          break;
        }

        case 'settings.deleteLlmProfile': {
          const { profileName } = msg as unknown as { profileName: string };
          const config = settingsManager.deleteLlmProfile(profileName);
          ws.send(JSON.stringify({ type: 'settings.updated', config }));
          break;
        }

        // ── Cron Tasks ──
        case 'cron.workspaces': {
          // 从会话列表中获取所有唯一的 workspace
          const sessions = store.listSessions();
          const workspaces = [...new Set(sessions.map(s => s.workspace))];
          ws.send(JSON.stringify({ type: 'cron.workspaces', workspaces }));
          break;
        }

        case 'cron.skills': {
          if (!msg.workspace) throw new Error('Missing workspace');

          // Try cache first (1-minute TTL)
          const cached = getCachedSkills(msg.workspace, 'cron');
          if (cached !== null) {
            ws.send(JSON.stringify({ type: 'cron.skills', workspace: msg.workspace, skills: cached }));
            break;
          }

          const skillsDir = path.join(msg.workspace, '.claude', 'skills');
          const skills: { name: string; hasCrontab: boolean; cronExpression?: string }[] = [];

          if (fs.existsSync(skillsDir)) {
            const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
            for (const entry of entries) {
              if (entry.isDirectory()) {
                const crontabPath = path.join(skillsDir, entry.name, 'crontab.md');
                const hasCrontab = fs.existsSync(crontabPath);
                let cronExpression: string | undefined;
                if (hasCrontab) {
                  try {
                    const content = fs.readFileSync(crontabPath, 'utf-8');
                    const { parseCrontabMd } = await import('./cron-scheduler.js');
                    const parsed = parseCrontabMd(content);
                    if (parsed) cronExpression = parsed.expression;
                  } catch { /* ignore */ }
                }
                skills.push({ name: entry.name, hasCrontab, cronExpression });
              }
            }
          }

          setCachedSkills(msg.workspace, 'cron', skills);
          ws.send(JSON.stringify({ type: 'cron.skills', workspace: msg.workspace, skills }));
          break;
        }

        case 'cron.list': {
          const tasks = cronTaskStore.listTasks().map(task => ({
            ...task,
            latestRun: cronTaskStore.getLatestRun(task.id),
            nextRunAt: cronScheduler.getNextRunAt(task.id),
          }));
          ws.send(JSON.stringify({ type: 'cron.list', tasks }));
          break;
        }

        case 'cron.create': {
          if (!msg.workspace || !msg.skillName || !msg.cronExpression) {
            throw new Error('Missing required fields');
          }
          try {
            CronExpressionParser.parse(msg.cronExpression as string);
          } catch {
            throw new Error('Invalid cron expression: ' + msg.cronExpression);
          }
          const task = cronTaskStore.createTask({
            workspace: msg.workspace as string,
            skillName: msg.skillName as string,
            cronExpression: msg.cronExpression as string,
            source: 'manual',
          });
          cronScheduler.schedule(task);
          ws.send(JSON.stringify({ type: 'cron.created', task }));
          broadcast(JSON.stringify({ type: 'cron.changed', action: 'created', task }));
          break;
        }

        case 'cron.update': {
          if (!msg.taskId) throw new Error('Missing taskId');
          if (msg.cronExpression) {
            try {
              CronExpressionParser.parse(msg.cronExpression as string);
            } catch {
              throw new Error('Invalid cron expression: ' + msg.cronExpression);
            }
          }
          const task = cronTaskStore.updateTask(msg.taskId as string, {
            workspace: msg.workspace as string | undefined,
            skillName: msg.skillName as string | undefined,
            cronExpression: msg.cronExpression as string | undefined,
            enabled: msg.enabled as boolean | undefined,
          });
          if (task) {
            if (task.enabled) {
              cronScheduler.schedule(task);
            } else {
              cronScheduler.unschedule(task.id);
            }
          }
          ws.send(JSON.stringify({ type: 'cron.updated', task }));
          broadcast(JSON.stringify({ type: 'cron.changed', action: 'updated', task }));
          break;
        }

        case 'cron.delete': {
          if (!msg.taskId) throw new Error('Missing taskId');
          cronScheduler.unschedule(msg.taskId as string);
          cronTaskStore.deleteTask(msg.taskId as string);
          ws.send(JSON.stringify({ type: 'cron.deleted', taskId: msg.taskId }));
          broadcast(JSON.stringify({ type: 'cron.changed', action: 'deleted', taskId: msg.taskId }));
          break;
        }

        case 'cron.runs': {
          if (!msg.taskId) throw new Error('Missing taskId');
          const limit = (msg.limit as number) || 20;
          const runs = cronTaskStore.listRuns(msg.taskId as string, limit);
          ws.send(JSON.stringify({ type: 'cron.runs', taskId: msg.taskId, runs }));
          break;
        }

        case 'cron.execute': {
          if (!msg.taskId) throw new Error('Missing taskId');
          // Fire-and-forget: 不 await，避免阻塞 WS message handler
          const execTaskId = msg.taskId as string;
          ws.send(JSON.stringify({ type: 'cron.executed', taskId: execTaskId }));
          cronScheduler.executeNow(execTaskId).catch((err) => {
            const errorMessage = err instanceof Error ? err.message : String(err);
            log.error(`Cron execute failed for task ${execTaskId}`, { error: errorMessage });
          });
          break;
        }

        case 'cron.scan': {
          try {
            const result = await cronScheduler.scanAndSync();
            const tasks = cronTaskStore.listTasks().map(task => ({
              ...task,
              latestRun: cronTaskStore.getLatestRun(task.id),
              nextRunAt: cronScheduler.getNextRunAt(task.id),
            }));
            ws.send(JSON.stringify({ type: 'cron.scanned', ...result, tasks }));
            broadcast(JSON.stringify({ type: 'cron.changed', action: 'scanned', tasks }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        // ── Batch Tasks ──
        case 'batch.list': {
          const tasks = batchStore.listTasks();
          ws.send(JSON.stringify({ type: 'batch.list', tasks }));
          break;
        }

        case 'batch.get': {
          if (!msg.taskId) throw new Error('Missing taskId');
          const task = batchStore.getTask(msg.taskId as string);
          ws.send(JSON.stringify({ type: 'batch.get', task }));
          break;
        }

        case 'batch.create': {
          if (!msg.workspace || !msg.skillName || !msg.mdContent || !msg.execTemplate) {
            throw new Error('Missing required fields');
          }
          const task = batchStore.createTask({
            workspace: msg.workspace as string,
            skillName: msg.skillName as string,
            mdContent: msg.mdContent as string,
            execTemplate: msg.execTemplate as string,
            envVars: msg.envVars as Record<string, string> | undefined,
            concurrency: msg.concurrency as number | undefined,
            retryOnFailure: msg.retryOnFailure as number | undefined,
          });
          ws.send(JSON.stringify({ type: 'batch.created', task }));
          break;
        }

        case 'batch.update': {
          if (!msg.taskId) throw new Error('Missing taskId');
          const { taskId, ...updates } = msg;
          const task = batchStore.updateTask(taskId as string, updates as any);
          ws.send(JSON.stringify({ type: 'batch.updated', task }));
          break;
        }

        case 'batch.delete': {
          if (!msg.taskId) throw new Error('Missing taskId');
          batchStore.deleteTask(msg.taskId as string);
          ws.send(JSON.stringify({ type: 'batch.deleted', taskId: msg.taskId }));
          break;
        }

        case 'batch.generate': {
          if (!msg.taskId) throw new Error('Missing taskId');
          try {
            const code = await batchEngine.generateCode(msg.taskId as string);
            ws.send(JSON.stringify({ type: 'batch.generated', taskId: msg.taskId, code }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'batch.test': {
          if (!msg.taskId) throw new Error('Missing taskId');
          try {
            const result = await batchEngine.testCode(msg.taskId as string);
            ws.send(JSON.stringify({ type: 'batch.tested', taskId: msg.taskId, ...result }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'batch.save': {
          if (!msg.taskId) throw new Error('Missing taskId');
          try {
            await batchEngine.save(msg.taskId as string);
            const task = batchStore.getTask(msg.taskId as string);
            ws.send(JSON.stringify({ type: 'batch.saved', task }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'batch.execute': {
          if (!msg.taskId) throw new Error('Missing taskId');
          try {
            // Execute in background, progress sent via batch.progress
            batchEngine.execute(msg.taskId as string).then(() => {
              const task = batchStore.getTask(msg.taskId as string);
              broadcast(JSON.stringify({ type: 'batch.completed', taskId: msg.taskId, task }));
            }).catch((err) => {
              const errorMessage = err instanceof Error ? err.message : String(err);
              broadcast(JSON.stringify({ type: 'chat.error', error: errorMessage }));
            });
            ws.send(JSON.stringify({ type: 'batch.started', taskId: msg.taskId }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'batch.pause': {
          if (!msg.taskId) throw new Error('Missing taskId');
          batchEngine.pause(msg.taskId as string);
          ws.send(JSON.stringify({ type: 'batch.paused', taskId: msg.taskId }));
          break;
        }

        case 'batch.resume': {
          if (!msg.taskId) throw new Error('Missing taskId');
          await batchEngine.resume(msg.taskId as string);
          ws.send(JSON.stringify({ type: 'batch.resumed', taskId: msg.taskId }));
          break;
        }

        case 'batch.cancel': {
          if (!msg.taskId) throw new Error('Missing taskId');
          batchEngine.cancel(msg.taskId as string);
          ws.send(JSON.stringify({ type: 'batch.cancelled', taskId: msg.taskId }));
          break;
        }

        case 'batch.items': {
          if (!msg.taskId) throw new Error('Missing taskId');
          const items = batchStore.listItems(msg.taskId as string, {
            status: msg.status as any,
            offset: msg.offset as number | undefined,
            limit: msg.limit as number | undefined,
          });
          ws.send(JSON.stringify({ type: 'batch.items', taskId: msg.taskId, items }));
          break;
        }

        case 'batch.retry': {
          if (!msg.taskId) throw new Error('Missing taskId');
          try {
            batchEngine.retryFailed(msg.taskId as string).then(() => {
              const task = batchStore.getTask(msg.taskId as string);
              broadcast(JSON.stringify({ type: 'batch.retried', taskId: msg.taskId, task }));
            }).catch((err) => {
              const errorMessage = err instanceof Error ? err.message : String(err);
              ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
            });
            ws.send(JSON.stringify({ type: 'batch.retrying', taskId: msg.taskId }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        // ── Smart Paths ──
        case 'smartpath.list': {
          const wsList = msg.workspaces as string[] | undefined;
          if (!wsList?.length) throw new Error('Missing workspaces');
          const paths = smartPathStore.listAll(wsList);
          // 确保 scheduler 覆盖所有 workspace
          smartPathScheduler.start(wsList);
          // 为每个有 cron 表达式的路径附加 nextRunAt
          const pathsWithNextRun = paths.map(p => ({
            ...p,
            nextRunAt: p.cronExpression ? smartPathScheduler.getNextRunAt(p.id) : null,
          }));
          ws.send(JSON.stringify({ type: 'smartpath.list', paths: pathsWithNextRun }));
          break;
        }

        case 'smartpath.create': {
          if (!msg.name || !msg.workspace || msg.steps === undefined) throw new Error('Missing required: name, workspace, steps');
          const p = smartPathStore.create({ name: msg.name as string, description: msg.description as string | undefined, workspace: msg.workspace as string, steps: msg.steps as string });
          ws.send(JSON.stringify({ type: 'smartpath.created', path: p }));
          break;
        }

        case 'smartpath.update': {
          if (!msg.pathId || !msg.workspace) throw new Error('Missing pathId or workspace');
          const { pathId, workspace, type: _t, ...updates } = msg;
          const allWs = [...new Set(store.listSessions().map(s => s.workspace))];
          const p = smartPathStore.update(pathId as string, workspace as string, updates as any, allWs);
          // 同步调度：cron 表达式变更时重新调度
          if (updates.cronExpression !== undefined || updates.status !== undefined) {
            if (p.cronExpression && p.status !== 'running') {
              smartPathScheduler.reschedule(p);
            } else {
              smartPathScheduler.unschedule(p.id);
            }
          }
          ws.send(JSON.stringify({ type: 'smartpath.updated', path: { ...p, nextRunAt: p.cronExpression ? smartPathScheduler.getNextRunAt(p.id) : null } }));
          break;
        }

        case 'smartpath.delete': {
          if (!msg.pathId || !msg.workspace) throw new Error('Missing pathId or workspace');
          const allWs = [...new Set(store.listSessions().map(s => s.workspace))];
          smartPathStore.del(msg.pathId as string, msg.workspace as string, allWs);
          ws.send(JSON.stringify({ type: 'smartpath.deleted', pathId: msg.pathId }));
          break;
        }

        case 'smartpath.abort': {
          if (!msg.pathId) throw new Error('Missing pathId');
          smartPathEngine.abort(msg.pathId as string);
          ws.send(JSON.stringify({ type: 'smartpath.aborted', pathId: msg.pathId }));
          break;
        }

        case 'smartpath.run': {
          if (!msg.pathId || !msg.workspace) throw new Error('Missing pathId or workspace');
          try {
            const runPathId = msg.pathId as string;
            const runWorkspace = msg.workspace as string;
            const runAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
            const pathToRun = smartPathStore.get(runPathId, runWorkspace, runAllWs);
            // 使用路径实际存储的 workspace（解决跨平台路径不一致问题）
            const actualRunWs = pathToRun?.workspace || runWorkspace;
            const runArgs = (msg.args as string) || pathToRun?.defaultArgs || '';
            smartPathEngine.run(
              runPathId,
              actualRunWs,
              (stepIndex, delta) => {
                broadcast(JSON.stringify({ type: 'smartpath.stepExecutionProgress', pathId: runPathId, stepIndex, delta }));
              },
              (stepIndex, result) => {
                broadcast(JSON.stringify({ type: 'smartpath.stepExecutionResult', pathId: runPathId, stepIndex, result }));
              },
              (data) => {
                broadcast(JSON.stringify({ type: 'smartpath.progress', pathId: runPathId, ...data }));
              },
              runArgs,
            ).then(() => {
              const p = smartPathStore.get(runPathId, actualRunWs);
              const refs = smartPathStore.listReferences(actualRunWs, runPathId);
              broadcast(JSON.stringify({ type: 'smartpath.completed', pathId: runPathId, path: p, references: refs }));
            }).catch((err) => {
              broadcast(JSON.stringify({ type: 'smartpath.failed', pathId: runPathId, error: err instanceof Error ? err.message : String(err) }));
            });
            ws.send(JSON.stringify({ type: 'smartpath.running', pathId: runPathId }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }

        case 'smartpath.runs': {
          if (!msg.pathId || !msg.workspace) throw new Error('Missing pathId or workspace');
          const runsWs = [...new Set(store.listSessions().map(s => s.workspace))];
          const runsPath = smartPathStore.get(msg.pathId as string, msg.workspace as string, runsWs);
          const runsActualWs = runsPath?.workspace || (msg.workspace as string);
          const runs = smartPathStore.listRuns(msg.pathId as string, runsActualWs);
          const reports = smartPathStore.listReports(msg.pathId as string, runsActualWs);
          ws.send(JSON.stringify({ type: 'smartpath.runs', pathId: msg.pathId, runs, reports }));
          break;
        }

        case 'smartpath.report': {
          if (!msg.pathId || !msg.workspace || !msg.fileName) throw new Error('Missing pathId, workspace or fileName');
          const reportWs = [...new Set(store.listSessions().map(s => s.workspace))];
          const reportPath = smartPathStore.get(msg.pathId as string, msg.workspace as string, reportWs);
          const reportActualWs = reportPath?.workspace || (msg.workspace as string);
          const content = smartPathStore.getReport(reportActualWs, msg.pathId as string, msg.fileName as string);
          ws.send(JSON.stringify({ type: 'smartpath.report', pathId: msg.pathId, fileName: msg.fileName, content }));
          break;
        }

        case 'smartpath.references': {
          if (!msg.pathId || !msg.workspace) throw new Error('Missing pathId or workspace');
          const refsWs = [...new Set(store.listSessions().map(s => s.workspace))];
          const refsPath = smartPathStore.get(msg.pathId as string, msg.workspace as string, refsWs);
          const refsActualWs = refsPath?.workspace || (msg.workspace as string);
          const refs = smartPathStore.listReferences(refsActualWs, msg.pathId as string);
          ws.send(JSON.stringify({ type: 'smartpath.references', pathId: msg.pathId, references: refs }));
          break;
        }

        case 'smartpath.reference.read': {
          if (!msg.pathId || !msg.workspace || !msg.fileName) throw new Error('Missing pathId, workspace or fileName');
          const refReadWs = [...new Set(store.listSessions().map(s => s.workspace))];
          const refReadPath = smartPathStore.get(msg.pathId as string, msg.workspace as string, refReadWs);
          const refReadActualWs = refReadPath?.workspace || (msg.workspace as string);
          const content = smartPathStore.getReference(refReadActualWs, msg.pathId as string, msg.fileName as string);
          ws.send(JSON.stringify({ type: 'smartpath.reference.content', pathId: msg.pathId, fileName: msg.fileName, content }));
          break;
        }

        case 'smartpath.generateStep': {
          const userInput = msg.userInput as string;
          const workspace = msg.workspace as string;
          const previousSteps = msg.previousSteps as Array<{ userInput: string; generatedContent?: string; executionResult?: string }>;
          const execute = msg.execute as boolean | undefined;
          if (!userInput?.trim()) throw new Error('Missing userInput');
          if (!workspace?.trim()) throw new Error('Missing workspace');

          try {
            if (execute) {
              // 流式执行模式 — 纯内存临时会话，不写 SQLite，不污染主会话列表
              const tempSessionId = sessionManager.createEphemeralSession(workspace);
              const abort = new AbortController();
              const pId = msg.pathId as string | undefined;
              const refsContext = buildWsReferencesContext(smartPathStore, workspace, pId);
              const prompt = buildStepExecutionPrompt(userInput, previousSteps, refsContext);
              const stepSystemPrompt = buildStepSystemPrompt();

              const stepIdx = msg.stepIndex as number | undefined;
              let result = '';
              try {
                result = await sessionManager.sendMessageForStep(
                  tempSessionId,
                  prompt,
                  abort,
                  (delta) => {
                    ws.send(JSON.stringify({ type: 'smartpath.stepExecutionProgress', pathId: pId, stepIndex: stepIdx, delta }));
                  },
                  stepSystemPrompt,
                );

                // 提取并保存 reference 文件
                const refRegex = /\[REFERENCE:([^\]]+)\]\s*\n```\s*\n([\s\S]*?)```/g;
                let refMatch;
                while ((refMatch = refRegex.exec(result)) !== null) {
                  if (pId) smartPathStore.saveReference(workspace, pId, refMatch[1].trim(), refMatch[2].trim());
                }
              } finally {
                // 清理内存中的临时会话和 V2 进程
                sessionManager.closeV2Session(tempSessionId);
                sessionManager.removeEphemeralSession(tempSessionId);
              }

              ws.send(JSON.stringify({
                type: 'smartpath.stepExecutionCompleted',
                pathId: pId,
                stepIndex: stepIdx,
                payload: { result: result.trim() },
              }));
            } else {
              // 原有生成方案逻辑
              const previousContext = previousSteps?.length
                ? ['Previous steps:', ...previousSteps.map((s, i) => `Step ${i + 1}: ${s.userInput}${s.generatedContent ? `\n  Solution: ${s.generatedContent.slice(0, 200)}...` : ''}`)].join('\n')
                : 'No previous steps.';

              const userPrompt = [
                'Generate an implementation solution for the following step.',
                `Workspace: ${workspace}`,
                '',
                previousContext,
                '',
                'Current Step:', userInput,
                '',
                'Provide: approach overview, code examples, file locations, key considerations.',
              ].join('\n');

              const tempSessionId = sessionManager.createSession(workspace);
              const abort = new AbortController();
              let fullResponse = '';
              const origSend = ws.send.bind(ws);
              (ws as any).send = (data: string) => {
                try { const p = JSON.parse(data); if (p.type === 'chat.delta') fullResponse += p.delta?.text || ''; if (p.type === 'chat.done') abort.abort(); } catch {}
                origSend(data);
              };
              await sessionManager.sendMessageForCron(tempSessionId, userPrompt, abort, () => {});
              (ws as any).send = origSend;
              ws.send(JSON.stringify({ type: 'smartpath.stepGenerated', payload: { generatedContent: fullResponse.trim() } }));
            }
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }

        case 'smartpath.orchestrate': {
          if (!msg.pathId || !msg.workspace) throw new Error('Missing pathId or workspace');
          try {
            const oPathId = msg.pathId as string;
            const oWorkspace = msg.workspace as string;
            const oAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
            const oPath = smartPathStore.get(oPathId, oWorkspace, oAllWs);
            const oActualWs = oPath?.workspace || oWorkspace;
            const oArgs = (msg.args as string) || oPath?.defaultArgs || '';

            const { blueprint, runId } = await smartPathEngine.orchestrateOnly(
              oPathId, oActualWs, oArgs,
              (stepIndex, delta) => {
                broadcast(JSON.stringify({ type: 'smartpath.stepExecutionProgress', pathId: oPathId, stepIndex, delta }));
              },
            );

            ws.send(JSON.stringify({ type: 'smartpath.orchestrated', pathId: oPathId, blueprint, runId }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }

        case 'smartpath.runStep': {
          if (!msg.pathId || !msg.workspace || !msg.runId || !msg.blueprint || msg.stepIndex === undefined) {
            throw new Error('Missing required: pathId, workspace, runId, blueprint, stepIndex');
          }
          try {
            const rsPathId = msg.pathId as string;
            const rsWorkspace = msg.workspace as string;
            const rsRunId = msg.runId as string;
            const rsBlueprint = msg.blueprint as import('./types.js').PathBlueprint;
            const rsStepIndex = msg.stepIndex as number;
            const rsPriorResults = (msg.priorResults as string[]) || [];
            const rsArgs = msg.args as string | undefined;

            const rsAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
            const rsPath = smartPathStore.get(rsPathId, rsWorkspace, rsAllWs);
            const rsActualWs = rsPath?.workspace || rsWorkspace;

            const rsResult = await smartPathEngine.runSingleStep(
              rsPathId, rsActualWs, rsRunId, rsBlueprint,
              rsStepIndex, rsBlueprint.stepPlans.length,
              rsPriorResults, rsArgs,
              (stepIndex, delta) => {
                broadcast(JSON.stringify({ type: 'smartpath.stepExecutionProgress', pathId: rsPathId, stepIndex, delta }));
              },
            );

            broadcast(JSON.stringify({ type: 'smartpath.stepExecutionResult', pathId: rsPathId, stepIndex: rsStepIndex, result: rsResult }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }

        case 'smartpath.finalize': {
          if (!msg.pathId || !msg.workspace || !msg.runId || !msg.blueprint || !msg.stepResults) {
            throw new Error('Missing required: pathId, workspace, runId, blueprint, stepResults');
          }
          try {
            const fPathId = msg.pathId as string;
            const fWorkspace = msg.workspace as string;
            const fRunId = msg.runId as string;
            const fBlueprint = msg.blueprint as import('./types.js').PathBlueprint;
            const fStepResults = msg.stepResults as string[];

            const fAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
            const fPath = smartPathStore.get(fPathId, fWorkspace, fAllWs);
            const fActualWs = fPath?.workspace || fWorkspace;

            await smartPathEngine.finalize(fPathId, fActualWs, fRunId, fBlueprint, fStepResults);

            const p = smartPathStore.get(fPathId, fActualWs);
            const refs = smartPathStore.listReferences(fActualWs, fPathId);
            broadcast(JSON.stringify({ type: 'smartpath.completed', pathId: fPathId, path: p, references: refs }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }

        // ── WeChat Personal Bot ──
        case 'chatbot.weixin.qr.request': {
          log.info('Received chatbot.weixin.qr.request');
          if (!weixinConnection) {
            ws.send(JSON.stringify({ type: 'chatbot.weixin.qr.error', error: 'WeChat bot not enabled' }));
            break;
          }
          try {
            const result = await weixinConnection.startQRLogin();
            ws.send(JSON.stringify({
              type: 'chatbot.weixin.qr.response',
              qrcodeUrl: result.qrcodeUrl,
              sessionKey: result.sessionKey,
            }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chatbot.weixin.qr.error', error: errorMessage }));
          }
          break;
        }

        case 'chatbot.weixin.qr.poll': {
          if (!weixinConnection || !msg.sessionKey) {
            ws.send(JSON.stringify({ type: 'chatbot.weixin.qr.status', status: 'error', message: 'Invalid state' }));
            break;
          }
          try {
            const result = await weixinConnection.waitForLogin(msg.sessionKey as string);
            ws.send(JSON.stringify({
              type: 'chatbot.weixin.qr.status',
              status: result.qrStatus || (result.connected ? 'confirmed' : 'wait'),
              connected: result.connected,
              message: result.message,
            }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chatbot.weixin.qr.status', status: 'error', message: errorMessage }));
          }
          break;
        }

        case 'chatbot.weixin.disconnect': {
          weixinConnection?.disconnect();
          ws.send(JSON.stringify({ type: 'chatbot.weixin.status', status: 'idle' }));
          break;
        }

        case 'chatbot.weixin.getStatus': {
          const status = weixinConnection?.getConnectionStatus() ?? 'idle';
          ws.send(JSON.stringify({ type: 'chatbot.weixin.status', status }));
          break;
        }

        case 'chatbot.listWorkspaceSkills': {
          const wsPath = msg.workspace as string;
          if (!wsPath || typeof wsPath !== 'string') {
            ws.send(JSON.stringify({ type: 'chatbot.listWorkspaceSkills', error: 'Missing workspace' }));
            break;
          }
          const skillsDir = path.join(wsPath, '.claude', 'skills');
          const skills: string[] = [];
          try {
            if (fs.existsSync(skillsDir)) {
              const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
              for (const entry of entries) {
                if (entry.isDirectory()) {
                  skills.push(entry.name);
                }
              }
            }
          } catch { /* ignore */ }
          ws.send(JSON.stringify({ type: 'chatbot.listWorkspaceSkills', skills }));
          break;
        }

        case 'chatbot.getCollectFiles': {
          const botId = String(msg.botProfileId || '');
          if (!botId) {
            ws.send(JSON.stringify({ type: 'chatbot.getCollectFiles', files: [] }));
            break;
          }
          const iterateDir = path.join(os.homedir(), '.sman', 'iterate', botId);
          if (!fs.existsSync(iterateDir)) {
            ws.send(JSON.stringify({ type: 'chatbot.getCollectFiles', files: [] }));
            break;
          }
          try {
            const files = fs.readdirSync(iterateDir)
              .filter(f => f.endsWith('-iter.md'))
              .sort()
              .reverse()
              .map(f => ({
                name: f,
                date: f.replace('-iter.md', ''),
                content: fs.readFileSync(path.join(iterateDir, f), 'utf-8'),
              }));
            ws.send(JSON.stringify({ type: 'chatbot.getCollectFiles', files }));
          } catch {
            ws.send(JSON.stringify({ type: 'chatbot.getCollectFiles', files: [] }));
          }
          break;
        }

        // ── Code Viewer ──────────────────────────────────────────
        case 'code.listDir': {
          if (!msg.workspace) {
            ws.send(JSON.stringify({ type: 'code.listDir', result: { error: 'Missing workspace' } }));
            break;
          }
          try {
            const result = handleListDir(String(msg.workspace), String(msg.dirPath || '.'));
            ws.send(JSON.stringify({ type: 'code.listDir', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'code.listDir', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'code.readFile': {
          if (!msg.workspace || !msg.filePath) {
            ws.send(JSON.stringify({ type: 'code.readFile', result: { error: 'Missing workspace or filePath' } }));
            break;
          }
          try {
            const result = handleReadFile(String(msg.workspace), String(msg.filePath));
            ws.send(JSON.stringify({ type: 'code.readFile', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'code.readFile', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'code.searchSymbols': {
          if (!msg.workspace || !msg.symbol) {
            ws.send(JSON.stringify({ type: 'code.searchSymbols', result: { error: 'Missing workspace or symbol' } }));
            break;
          }
          handleSearchSymbols(String(msg.workspace), String(msg.symbol), msg.fileExt ? String(msg.fileExt) : undefined)
            .then((result) => {
              ws.send(JSON.stringify({ type: 'code.searchSymbols', result }));
            })
            .catch((err) => {
              ws.send(JSON.stringify({ type: 'code.searchSymbols', result: { error: err instanceof Error ? err.message : String(err) } }));
            });
          break;
        }
        case 'code.saveFile': {
          if (!msg.workspace || !msg.filePath || typeof msg.content !== 'string') {
            ws.send(JSON.stringify({ type: 'code.saveFile', result: { error: 'Missing workspace, filePath or content' } }));
            break;
          }
          try {
            const result = handleSaveFile(String(msg.workspace), String(msg.filePath), String(msg.content));
            ws.send(JSON.stringify({ type: 'code.saveFile', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'code.saveFile', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'code.searchFiles': {
          if (!msg.workspace || !msg.query) {
            ws.send(JSON.stringify({ type: 'code.searchFiles', result: { error: 'Missing workspace or query' } }));
            break;
          }
          try {
            const result = handleSearchFiles(String(msg.workspace), String(msg.query), 50, msg.sourceOnly !== false);
            ws.send(JSON.stringify({ type: 'code.searchFiles', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'code.searchFiles', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }

        // ── Git ──────────────────────────────────────────────────
        case 'git.status': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.status', result: { error: 'Missing workspace' } })); break; }
          try {
            const result = handleGitStatus(String(msg.workspace));
            ws.send(JSON.stringify({ type: 'git.status', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.status', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.diff': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.diff', result: { error: 'Missing workspace' } })); break; }
          try {
            const result = handleGitDiff(String(msg.workspace), msg.filePath ? String(msg.filePath) : undefined, !!msg.staged);
            ws.send(JSON.stringify({ type: 'git.diff', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.diff', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.diffFile': {
          if (!msg.workspace || !msg.filePath) { ws.send(JSON.stringify({ type: 'git.diffFile', result: { error: 'Missing workspace or filePath' } })); break; }
          try {
            const result = handleGitDiffFile(String(msg.workspace), String(msg.filePath));
            ws.send(JSON.stringify({ type: 'git.diffFile', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.diffFile', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.commit': {
          if (!msg.workspace || !msg.message) { ws.send(JSON.stringify({ type: 'git.commit', result: { error: 'Missing workspace or message' } })); break; }
          try {
            const result = handleGitCommit(String(msg.workspace), String(msg.message), msg.files as string[] | undefined);
            ws.send(JSON.stringify({ type: 'git.commit', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.commit', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.log': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.log', result: { error: 'Missing workspace' } })); break; }
          try {
            const result = handleGitLog(String(msg.workspace), msg.maxCount ? Number(msg.maxCount) : undefined);
            ws.send(JSON.stringify({ type: 'git.log', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.log', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.logGraph': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.logGraph', result: { error: 'Missing workspace' } })); break; }
          try {
            const result = handleGitLogGraph(String(msg.workspace), msg.maxCount ? Number(msg.maxCount) : undefined);
            ws.send(JSON.stringify({ type: 'git.logGraph', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.logGraph', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.logSearch': {
          if (!msg.workspace || !msg.query) { ws.send(JSON.stringify({ type: 'git.logSearch', result: [] })); break; }
          try {
            const result = handleGitLogSearch(String(msg.workspace), String(msg.query));
            ws.send(JSON.stringify({ type: 'git.logSearch', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.logSearch', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.aheadCommits': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.aheadCommits', result: [] })); break; }
          try {
            const result = handleGitAheadCommits(String(msg.workspace));
            ws.send(JSON.stringify({ type: 'git.aheadCommits', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.aheadCommits', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.branchList': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.branchList', result: { error: 'Missing workspace' } })); break; }
          try {
            const result = handleGitBranchList(String(msg.workspace));
            ws.send(JSON.stringify({ type: 'git.branchList', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.branchList', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.checkout': {
          if (!msg.workspace || !msg.branch) { ws.send(JSON.stringify({ type: 'git.checkout', result: { error: 'Missing workspace or branch' } })); break; }
          try {
            const result = handleGitCheckout(String(msg.workspace), String(msg.branch));
            ws.send(JSON.stringify({ type: 'git.checkout', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.checkout', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.fetch': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.fetch', result: { error: 'Missing workspace' } })); break; }
          try {
            const result = handleGitFetch(String(msg.workspace));
            ws.send(JSON.stringify({ type: 'git.fetch', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.fetch', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.remoteDiff': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.remoteDiff', result: { error: 'Missing workspace' } })); break; }
          try {
            const result = handleGitRemoteDiff(String(msg.workspace));
            ws.send(JSON.stringify({ type: 'git.remoteDiff', result }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.remoteDiff', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.generateCommit': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.generateCommit', result: { error: 'Missing workspace' } })); break; }
          try {
            const files = Array.isArray(msg.files) ? msg.files.map(String) : undefined;
            handleGitGenerateCommit(String(msg.workspace), msg.template ? String(msg.template) : undefined, files)
              .then(result => ws.send(JSON.stringify({ type: 'git.generateCommit', result })))
              .catch(err => ws.send(JSON.stringify({ type: 'git.generateCommit', result: { error: err instanceof Error ? err.message : String(err) } })));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.generateCommit', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }
        case 'git.push': {
          if (!msg.workspace) { ws.send(JSON.stringify({ type: 'git.push', result: { error: 'Missing workspace' } })); break; }
          try {
            handleGitPush(String(msg.workspace))
              .then(result => ws.send(JSON.stringify({ type: 'git.push', result })))
              .catch(err => ws.send(JSON.stringify({ type: 'git.push', result: { error: err instanceof Error ? err.message : String(err) } })));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'git.push', result: { error: err instanceof Error ? err.message : String(err) } }));
          }
          break;
        }

        case 'hub:query': {
          await ensureServerBaseUrl(settingsManager);
          const broadcasts = broadcastStore.getRecent(7);
          log.info(`hub:query from WS client, returning ${broadcasts.length} broadcast(s)`);
          ws.send(JSON.stringify({ type: 'hub:broadcasts', data: broadcasts }));
          break;
        }

        case 'hub:status': {
          await ensureServerBaseUrl(settingsManager);
          ws.send(JSON.stringify({ type: 'hub:status', data: getHubStatus(settingsManager) }));
          break;
        }

        case 'error.report': {
          const hubUrl = getServerBaseUrl(settingsManager);
          const llmConfig = settingsManager.getConfig().llm;
          const report = {
            clientId: getClientId(),
            sessionId: msg.sessionId,
            errorCode: msg.errorCode,
            errorMessage: msg.errorMessage,
            rawError: msg.rawError,
            workspace: msg.workspace,
            lastUserMessage: msg.lastUserMessage,
            llmModel: llmConfig.model || undefined,
            llmBaseUrl: llmConfig.baseUrl || undefined,
            osInfo: `${os.platform()} ${os.release()} ${os.arch()}`,
          };
          // Push to sman-server if hub configured
          if (hubUrl) {
            try {
              const encrypted = buildEncryptedRequest(report);
              const controller = new AbortController();
              const tid = setTimeout(() => controller.abort(), 10_000);
              fetch(`${hubUrl}/api/error-report`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(encrypted),
                signal: controller.signal,
              }).then(res => {
                clearTimeout(tid);
                if (res.ok) {
                  log.info(`Error report pushed to hub`);
                  ws.send(JSON.stringify({ type: 'error.report.ack', success: true }));
                } else {
                  log.warn(`Error report push failed: ${res.status}`);
                  ws.send(JSON.stringify({ type: 'error.report.ack', success: false, error: `HTTP ${res.status}` }));
                }
              }).catch(fetchErr => {
                clearTimeout(tid);
                log.warn(`Error report push failed: ${fetchErr}`);
                ws.send(JSON.stringify({ type: 'error.report.ack', success: false, error: String(fetchErr) }));
              });
            } catch (encryptErr) {
              log.error(`Error report encrypt failed: ${encryptErr}`);
              ws.send(JSON.stringify({ type: 'error.report.ack', success: false, error: String(encryptErr) }));
            }
          } else {
            // No hub configured — save locally as fallback
            const reportDir = path.join(getHomeDir(), 'logs');
            fs.mkdirSync(reportDir, { recursive: true });
            const ts = new Date().toISOString().replace(/[:.]/g, '-');
            const reportPath = path.join(reportDir, `error-${ts}.json`);
            try {
              fs.writeFileSync(reportPath, JSON.stringify(report, null, 2), 'utf-8');
              log.info(`Error report saved locally: ${reportPath}`);
              ws.send(JSON.stringify({ type: 'error.report.ack', success: true, path: reportPath }));
            } catch (writeErr) {
              log.error(`Failed to save error report: ${writeErr}`);
              ws.send(JSON.stringify({ type: 'error.report.ack', success: false, error: String(writeErr) }));
            }
          }
          break;
        }

        default:
          if (msg.type?.startsWith('stardom.')) {
            await ensureServerBaseUrl(settingsManager);
            const bridge = getStardomBridge();
            if (bridge) {
              bridge.handleFrontendMessage(msg.type, (msg.payload ?? msg) as Record<string, unknown>, ws);
            } else {
              ws.send(JSON.stringify({ type: 'error', error: `Stardom not configured: ${msg.type}` }));
            }
          } else {
            ws.send(JSON.stringify({ type: 'error', error: `Unknown message type: ${msg.type}` }));
          }
          break;
      }
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      try {
        ws.send(JSON.stringify({ type: 'chat.error', sessionId: msg.sessionId, error: errorMessage }));
      } catch {
        // ws already closed, ignore
      }
      log.error('Message handling error', { error: errorMessage });
    }
  });

  ws.on('close', () => {
    clearTimeout(authTimeout);
    clients.delete(ws);
    authenticatedClients.delete(ws);
    // Clean up session subscriptions for this client
    unsubscribeClientFromAllSessions(ws);
    log.info('WebSocket client disconnected');
  });
});

// Graceful shutdown
function shutdown(): void {
  wecomConnections.forEach(c => c.stop());
  wecomConnections = [];
  feishuConnection?.stop();
  weixinConnection?.stop();
  chatbotManager.stop();
  batchEngine.stop();
  cronScheduler.stop();
  smartPathScheduler.stop();
  stopHub();
  sessionManager.close();
  knowledgeExtractorStore.close();
  wss.close();
  server.close();
}

/**
 * Auto-setup office-skills plugin dependencies.
 * Runs once at startup if venv/node_modules don't exist yet.
 * Non-blocking: errors are logged but don't prevent server startup.
 */
async function setupOfficeSkills(): Promise<void> {
  const officeDir = path.join(resolveProjectRoot(), 'plugins', 'office-skills');
  const venvDir = path.join(officeDir, 'venv');
  const nodeModulesDir = path.join(officeDir, 'node_modules');

  const needsPython = !fs.existsSync(venvDir);
  const needsNode = !fs.existsSync(nodeModulesDir);

  if (!needsPython && !needsNode) return;

  log.info('Office skills: setting up dependencies (first run)...');
  const { execFile } = await import('child_process');

  const pythonSetup = needsPython ? new Promise<void>((resolve) => {
    const reqFile = path.join(officeDir, 'requirements.txt');
    if (!fs.existsSync(reqFile)) {
      log.warn('Office skills: requirements.txt not found, skipping Python setup');
      return resolve();
    }

    log.info('Office skills: creating Python venv...');
    execFile('python3', ['-m', 'venv', venvDir], (err) => {
      if (err) {
        log.warn(`Office skills: failed to create venv: ${err.message}`);
        return resolve();
      }
      const pip = path.join(venvDir, 'bin', 'pip');
      const pipCmd = process.platform === 'win32'
        ? path.join(venvDir, 'Scripts', 'pip.exe')
        : pip;

      log.info('Office skills: installing Python packages...');
      execFile(pipCmd, ['install', '-q', '-r', reqFile], { timeout: 120_000 }, (err) => {
        if (err) {
          log.warn(`Office skills: pip install failed: ${err.message}`);
        } else {
          log.info('Office skills: Python packages installed');
        }
        resolve();
      });
    });
  }) : Promise.resolve();

  const nodeSetup = needsNode ? new Promise<void>((resolve) => {
    const pkgFile = path.join(officeDir, 'package.json');
    if (!fs.existsSync(pkgFile)) {
      log.warn('Office skills: package.json not found, skipping Node setup');
      return resolve();
    }

    log.info('Office skills: installing Node packages...');
    execFile('npm', ['install', '--production'], { cwd: officeDir, timeout: 180_000 }, (err) => {
      if (err) {
        log.warn(`Office skills: npm install failed: ${err.message}`);
      } else {
        log.info('Office skills: Node packages installed');
      }
      resolve();
    });
  }) : Promise.resolve();

  await Promise.all([pythonSetup, nodeSetup]);
  log.info('Office skills: setup complete');
}

// Export for Electron in-process usage
export { shutdown as stopServer };
export { server, homeDir };

/** Set the actual port after listen resolves. Called by both server and Electron. */
export function setActualPort(port: number): void {
  actualPort = port;
  _setActualPort(port);
}

/** Called by Electron's startServerInProcess() after listen().
 *  server/index.ts only calls initHub() inside the isMainModule block,
 *  which is false when loaded via dynamic import(). */
export async function startHub(): Promise<void> {
  await initHub(settingsManager, store, broadcastStore, sessionManager);
}

// Stardom Bridge（独立模块，未配置时无副作用）
initStardomBridge({
  sessionManager,
  settingsManager,
  skillsRegistry,
  broadcast: (data: string) => broadcast(data),
  homeDir,
});

// When run directly (dev mode: tsx server/index.ts), auto-start
// When imported by Electron, electron/main.ts calls startServer()
/**
 * 构建步骤执行提示词
 */
function buildStepExecutionPrompt(
  userInput: string,
  previousSteps: Array<{ userInput: string; generatedContent?: string; executionResult?: string }>,
  referencesContext?: string,
): string {
  const parts: string[] = [];
  if (referencesContext) {
    parts.push('[可复用资源 — 优先使用这些资源，不要重新生成]');
    parts.push(referencesContext);
    parts.push('');
  }
  const last = previousSteps.length > 0 ? previousSteps[previousSteps.length - 1] : null;
  if (last?.executionResult) {
    parts.push(`上个步骤执行结果：「${last.executionResult}」`);
  }
  parts.push(`本步骤输入：「${userInput}」`);
  return parts.join('\n');
}

/** 构建 references 上下文（WS handler 用） */
function buildWsReferencesContext(store: SmartPathStore, workspace: string, pathId: string | undefined): string {
  if (!pathId) return '';
  const runGuide = store.getRunGuide(workspace, pathId);
  const refs = store.listReferences(workspace, pathId).filter(r => r.fileName !== 'run.md');
  if (!runGuide && refs.length === 0) return '';
  const parts: string[] = [];
  if (runGuide) {
    parts.push('## 复用指南 (run.md)');
    parts.push(runGuide);
    parts.push('');
  }
  for (const ref of refs) {
    const content = store.getReference(workspace, pathId, ref.fileName);
    if (content) {
      parts.push(`## ${ref.fileName}`);
      parts.push(content.length > 2000 ? content.slice(0, 2000) + '\n...(truncated)' : content);
      parts.push('');
    }
  }
  return parts.join('\n');
}

/**
 * 步骤执行的精简 system prompt
 */
function buildStepSystemPrompt(): string {
  return [
    '[步骤执行模式 - 必须遵守]',
    '',
    '你正在执行一个自动化工作流的步骤。规则：',
    '1. 直接执行任务，给出简洁结果。不要询问用户，不要等待用户输入。',
    '2. 不要调用 dev-workflow 或任何需要多轮交互的流程。',
    '3. 能使用现有 skill/tool 直接完成就使用，不能的直接实现。',
    '4. 输出要简洁：执行了什么 + 结果。不要冗长解释。',
    '5. 最后用一行明确总结结果（以「执行结果：」开头）。',
    '6. 如果执行过程中生成了可复用的脚本或文档，在结果末尾用以下格式标注：',
    '   [REFERENCE:filename.ext]',
    '   ```',
    '   文件内容',
    '   ```',
    '   可以标注多个文件。这些文件会被保存到复用资源库，下次执行时可以直接复用。',
  ].join('\n');
}

const isMainModule = !process.env.SMAN_ELECTRON &&
                     !process.env.ELECTRON_RUN_AS_NODE &&
                     (process.argv[1]?.replace(/\\/g, '/').endsWith('server/index.ts') ||
                      process.argv[1]?.replace(/\\/g, '/').endsWith('server/index.js') ||
                      process.argv[1]?.replace(/\\/g, '/').endsWith('dist/server/index.js') ||
                      process.argv[1]?.replace(/\\/g, '/').endsWith('dist/server/server/index.js') ||
                      process.argv[1]?.replace(/\\/g, '/').endsWith('app/index.js'));

if (isMainModule) {
  process.on('SIGTERM', () => {
    log.info('SIGTERM received, shutting down...');
    shutdown();
    process.exit(0);
  });

  process.on('SIGINT', () => {
    log.info('SIGINT received, shutting down...');
    shutdown();
    process.exit(0);
  });

  const HOST = process.env.HOST || '127.0.0.1';

  // Well-known ports to skip (common dev tools, databases, caches, etc.)
  const SKIP_PORTS = new Set([
    3000, 3306, 4000, 5432, 5672, 6379, 7890, 8000, 8080, 8443, 8888, 9000, 9090, 9200, 9300, 27017,
  ]);
  const PORT_STEP = 100;
  const MAX_PORT_ATTEMPTS = 10;

  const tryListen = (port: number, attempts: number) => {
    let handled = false;
    const onError = (err: NodeJS.ErrnoException) => {
      if (handled) return;
      handled = true;
      server.removeListener('error', onError);
      wss.removeListener('error', onError);
      if (err.code === 'EADDRINUSE' && attempts > 0) {
        let nextPort = port + PORT_STEP;
        while (SKIP_PORTS.has(nextPort)) nextPort += PORT_STEP;
        log.warn(`Port ${port} in use, trying ${nextPort}...`);
        server.close(() => {
          tryListen(nextPort, attempts - 1);
        });
      } else {
        log.error(`Failed to listen on ${HOST}:${port}: ${err.message}`);
        process.exit(1);
      }
    };
    // Register on BOTH server and wss — ws re-emits http server errors on WebSocketServer
    server.on('error', onError);
    wss.on('error', onError);
    server.listen(port, HOST, () => {
      if (handled) return;
      handled = true;
      server.removeListener('error', onError);
      wss.removeListener('error', onError);
      const resolved = (server.address() as any)?.port ?? port;
      setActualPort(resolved);
      if (resolved !== PORT) {
        log.warn(`Port ${PORT} in use, using ${resolved} instead`);
      }
      log.info(`Sman server running on ${HOST}:${resolved}`);
      log.info(`Home directory: ${homeDir}`);
      log.info(`WebSocket endpoint: ws://${HOST}:${resolved}/ws`);
      log.info(`Health check: http://${HOST}:${resolved}/api/health`);

      initHub(settingsManager, store, broadcastStore, sessionManager).then(() => {
        log.info('Hub initialized');
      });
    });
  };
  tryListen(PORT, MAX_PORT_ATTEMPTS);

  // Auto-setup office-skills dependencies (non-blocking)
  setupOfficeSkills();
}
