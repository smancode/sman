import http from 'http';
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
import { ClaudeSessionManager } from './claude-session.js';
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
import { ChatbotStore } from './chatbot/chatbot-store.js';
import { ChatbotSessionManager } from './chatbot/chatbot-session-manager.js';
import { CapabilityRegistry } from './capabilities/registry.js';
import { initCapabilities } from './capabilities/init-registry.js';
import { WeComBotConnection } from './chatbot/wecom-bot-connection.js';
import { FeishuBotConnection } from './chatbot/feishu-bot-connection.js';
import { WeixinBotConnection } from './chatbot/weixin-bot-connection.js';
import { testAnthropicCompat, detectCapabilities } from './model-capabilities.js';
import { InitManager } from './init/init-manager.js';
import { initBazaarBridge, getBazaarBridge } from './bazaar/index.js';

const PORT = parseInt(process.env.PORT || '5880', 10);
const log = createLogger('Server');

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
    fs.writeFileSync(configPath, JSON.stringify(defaultConfig, null, 2), 'utf-8');
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
const skillsRegistry = new SkillsRegistry(homeDir);
const sessionManager = new ClaudeSessionManager(store);
const settingsManager = new SettingsManager(homeDir);

// User profile manager
const userProfileManager = new UserProfileManager(homeDir, settingsManager.getConfig());
sessionManager.setUserProfile(userProfileManager);

// Initialize auth token (generate on first run, reuse thereafter)
let authToken = settingsManager.ensureAuthToken();

// Cron task scheduler
const cronTaskStore = new CronTaskStore(dbPath);
const cronScheduler = new CronScheduler(cronTaskStore);
cronScheduler.setSessionStore(store);

// WebSocket client tracking (declared early for use by broadcast)
const clients = new Set<WebSocket>();
const authenticatedClients = new Set<WebSocket>();

function broadcast(data: string): void {
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
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

// SmartPath execution engine
const smartPathStore = new SmartPathStore(homeDir);
const smartPathEngine = new SmartPathEngine(smartPathStore, skillsRegistry, sessionManager);

// Chatbot integration (WeCom + Feishu)
const chatbotStore = new ChatbotStore(dbPath);
const chatbotManager = new ChatbotSessionManager(homeDir, sessionManager, chatbotStore, (sessionId: string, label: string) => {
  broadcast(JSON.stringify({ type: 'session.chatbotCreated', sessionId, label }));
});

let wecomConnection: WeComBotConnection | null = null;
let feishuConnection: FeishuBotConnection | null = null;
let weixinConnection: WeixinBotConnection | null = null;

function startChatbotConnections(): void {
  const chatbotConfig = settingsManager.getConfig().chatbot;
  if (!chatbotConfig?.enabled) return;

  if (chatbotConfig.wecom.enabled && chatbotConfig.wecom.botId && chatbotConfig.wecom.secret) {
    wecomConnection = new WeComBotConnection({
      botId: chatbotConfig.wecom.botId,
      secret: chatbotConfig.wecom.secret,
      onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
    });
    wecomConnection.start();
    log.info('WeCom bot connection started');
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
  'http://localhost:5880',
  'http://localhost:5881',
  'http://127.0.0.1:5880',
  'http://127.0.0.1:5881',
  ...(process.env.CORS_ORIGINS ? process.env.CORS_ORIGINS.split(',').map(s => s.trim()) : []),
];

function setCorsHeaders(req: http.IncomingMessage, res: http.ServerResponse): void {
  const origin = req.headers.origin || '';
  if (ALLOWED_ORIGINS.includes(origin)) {
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

  // Public: health check (no auth)
  if (req.url === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toLocaleString('zh-CN', { hour12: false }) }));
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
        import('child_process').then(({ exec }) => {
          const cmd = process.platform === 'darwin'
            ? `open "${targetUrl}"`
            : process.platform === 'win32'
              ? `start "" "${targetUrl}"`
              : `xdg-open "${targetUrl}"`;
          exec(cmd, (err) => {
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
      const normalizedPath = path.normalize(dirPath);
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
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error';
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Failed to read directory', message }));
    }
    return;
  }

  // Home directory API - return user's home path
  if (req.url === '/api/directory/home') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ home: os.homedir() }));
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

    // SPA fallback: serve index.html for unknown paths
    if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
      filePath = path.join(distDir, 'index.html');
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
    // Allow connections with no origin (non-browser clients) or from allowed origins
    if (origin && !ALLOWED_ORIGINS.includes(origin)) {
      callback(false, 403, 'Forbidden: invalid origin');
      return;
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
          const sessionId = sessionManager.createSession(msg.workspace);
          ws.send(JSON.stringify({ type: 'session.created', sessionId, workspace: msg.workspace }));

          // Auto-initialize workspace (async, non-blocking)
          initManager.handleSessionCreate(msg.workspace, sessionId, ws).catch((err: any) => {
            log.warn(`Workspace init failed for ${msg.workspace}: ${err.message}`);
          });
          break;
        }

        case 'session.list': {
          const sessions = sessionManager.listSessions().map(s => ({
            id: s.id,
            workspace: s.workspace,
            label: s.label,
            createdAt: s.createdAt,
            lastActiveAt: s.lastActiveAt,
          }));
          ws.send(JSON.stringify({ type: 'session.list', sessions }));
          break;
        }

        case 'session.updateLabel': {
          if (!msg.sessionId || typeof msg.label !== 'string') throw new Error('Missing sessionId or label');
          store.updateLabel(msg.sessionId, msg.label);
          // Broadcast to all clients so SessionTree updates immediately
          broadcast(JSON.stringify({ type: 'session.labelUpdated', sessionId: msg.sessionId, label: msg.label }));
          break;
        }

        case 'session.delete': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          sessionManager.abort(msg.sessionId);
          sessionManager.clearTokenUsage(msg.sessionId);
          store.deleteSession(msg.sessionId);
          ws.send(JSON.stringify({ type: 'session.deleted', sessionId: msg.sessionId }));
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
          // Capture sessionId for the closure
          const chatSessionId = msg.sessionId;
          const wsSend = (d: string) => {
            // Try original client first
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(d);
              return;
            }
            // Client disconnected — find any other authenticated client to deliver to.
            // This avoids broadcasting session-specific messages to all clients.
            for (const client of authenticatedClients) {
              if (client.readyState === WebSocket.OPEN) {
                client.send(d);
                return;
              }
            }
            // No client available — message is lost, but this is acceptable for a disconnected user
            log.warn(`No active client to deliver message for session ${chatSessionId}`);
          };
          const media = (msg as any).media as import('./chatbot/types.js').MediaAttachment[] | undefined;
          await sessionManager.sendMessage(msg.sessionId, msg.content ?? '', wsSend, media);
          break;
        }

        case 'chat.abort': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          sessionManager.abort(msg.sessionId);
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
          ws.send(JSON.stringify({ type: 'skills.listProject', skills }));
          break;
        }

        // ── Settings ──
        case 'settings.get': {
          const config = settingsManager.getConfig();
          ws.send(JSON.stringify({ type: 'settings.get', config }));
          break;
        }

        case 'settings.update': {
          const { type: _t, ...updates } = msg;
          const config = settingsManager.updateConfig(updates as Partial<import('./types.js').SmanConfig>);
          sessionManager.updateConfig(config);
          userProfileManager.updateConfig(config);
          batchEngine.setConfig(config.llm);
          // Sync in-memory auth token if changed
          if ((updates as Record<string, unknown>).auth && typeof (updates as Record<string, unknown>).auth === 'object') {
            const authUpdate = (updates as Record<string, unknown>).auth as Record<string, unknown>;
            if (authUpdate.token) {
              authToken = String(authUpdate.token);
            }
          }
          if ((updates as Record<string, unknown>).chatbot) {
            wecomConnection?.stop();
            feishuConnection?.stop();
            weixinConnection?.stop();
            startChatbotConnections();
          }
          ws.send(JSON.stringify({ type: 'settings.updated', config }));
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
          const paths = smartPathStore.listPaths();
          ws.send(JSON.stringify({ type: 'smartpath.list', paths }));
          break;
        }

        case 'smartpath.create': {
          if (!msg.name || !msg.workspace || !msg.steps) {
            throw new Error('Missing required fields: name, workspace, steps');
          }
          const path = smartPathStore.createPath({
            name: msg.name as string,
            workspace: msg.workspace as string,
            steps: msg.steps as string,
            status: msg.status as any,
          });
          ws.send(JSON.stringify({ type: 'smartpath.created', path }));
          break;
        }

        case 'smartpath.update': {
          if (!msg.pathId) throw new Error('Missing pathId');
          const { pathId, ...updates } = msg;
          const path = smartPathStore.updatePath(pathId as string, updates as any);
          ws.send(JSON.stringify({ type: 'smartpath.updated', path }));
          break;
        }

        case 'smartpath.delete': {
          if (!msg.pathId) throw new Error('Missing pathId');
          smartPathStore.deletePath(msg.pathId as string);
          ws.send(JSON.stringify({ type: 'smartpath.deleted', pathId: msg.pathId }));
          break;
        }

        case 'smartpath.run': {
          if (!msg.pathId) throw new Error('Missing pathId');
          try {
            smartPathEngine.runPath(msg.pathId as string, (data) => {
              broadcast(JSON.stringify({ type: 'smartpath.progress', pathId: msg.pathId, ...data }));
            }).then(() => {
              const path = smartPathStore.getPath(msg.pathId as string);
              broadcast(JSON.stringify({ type: 'smartpath.completed', pathId: msg.pathId, path }));
            }).catch((err) => {
              const errorMessage = err instanceof Error ? err.message : String(err);
              broadcast(JSON.stringify({ type: 'smartpath.failed', pathId: msg.pathId, error: errorMessage }));
            });
            ws.send(JSON.stringify({ type: 'smartpath.running', pathId: msg.pathId }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'smartpath.runs': {
          if (!msg.pathId) throw new Error('Missing pathId');
          const runs = smartPathStore.listRuns(msg.pathId as string);
          ws.send(JSON.stringify({ type: 'smartpath.runs', pathId: msg.pathId, runs }));
          break;
        }

        case 'smartpath.generatePython': {
          const description = msg.description as string;
          const workspace = msg.workspace as string;
          if (!description) throw new Error('Missing description');

          try {
            const { query } = await import('@anthropic-ai/claude-agent-sdk');
            const llmConfig = settingsManager.getConfig().llm;

            const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
            if (llmConfig.apiKey) env['ANTHROPIC_API_KEY'] = llmConfig.apiKey;
            if (llmConfig.baseUrl) env['ANTHROPIC_BASE_URL'] = llmConfig.baseUrl;

            // Get available skills for context
            const availableSkills = skillsRegistry.listSkills();
            const skillsList = availableSkills.map(s => `- ${s.id}: ${s.description || s.name}`).join('\n');

            const prompt = [
              'Generate a Python script based on the following description.',
              '',
              'Context:',
              `- Workspace: ${workspace}`,
              `- Available Skills:\n${skillsList || '(none)'}`,
              '',
              'Skills Definition:',
              'A skill is a predefined capability that can be invoked to perform specific tasks.',
              'Skills are identified by their ID (e.g., "test-driven-development", "systematic-debugging").',
              '',
              'Description:',
              description,
              '',
              'Requirements:',
              '- The script receives input data via a `ctx` variable (dict-like).',
              '- Output MUST be a JSON object printed to stdout using `print(json.dumps(result))`.',
              '- Do not include any interactive input prompts.',
              '- Use `import json` at the top.',
            ].join('\n');

            // Model resolution: non-Anthropic model names (e.g. third-party proxies)
            // cause the SDK to send unrecognized model names to the API endpoint.
            // Use a standard Anthropic model name and let the proxy map it server-side.
            const configModel = llmConfig.model;
            const resolvedModel = configModel.toLowerCase().startsWith('claude-') || configModel.toLowerCase().startsWith('anthropic-')
              ? configModel
              : 'claude-sonnet-4-6';

            const q = query({
              prompt,
              options: {
                cwd: workspace || process.cwd(),
                model: resolvedModel,
                permissionMode: 'bypassPermissions' as const,
                allowDangerouslySkipPermissions: true,
                env,
                systemPrompt: {
                  type: 'preset' as const,
                  preset: 'claude_code' as const,
                  append: 'You are a Python script generator. Generate ONLY the script code, wrapped in a code block.',
                },
              } as any,
            });

            let fullText = '';
            for await (const msg of q) {
              fullText += extractTextFromMessage(msg);
            }

            const codeMatch = fullText.match(/```(?:python)?\n([\s\S]*?)```/);
            const code = codeMatch ? codeMatch[1].trim() : fullText.trim();

            ws.send(JSON.stringify({ type: 'smartpath.pythonGenerated', code }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'smartpath.generateFromNL': {
          const description = msg.description as string;
          const workspace = msg.workspace as string;

          // Strict validation
          if (!description || !description.trim()) {
            throw new Error('Missing description parameter');
          }
          if (!workspace || !workspace.trim()) {
            throw new Error('Missing workspace parameter');
          }

          try {
            const llmConfig = settingsManager.getConfig().llm;
            if (!llmConfig.apiKey) {
              throw new Error('LLM API key not configured. Please check settings.');
            }

            // Get available skills
            const availableSkills = skillsRegistry.listSkills();
            const skillsList = availableSkills.map(s => `- ${s.id}: ${s.description || s.name}`).join('\n');

            const systemPrompt = 'You are an execution plan generator. Generate ONLY the JSON plan, wrapped in a code block.';
            const userPrompt = [
              'Generate an execution plan based on the following description.',
              '',
              'Context:',
              `- Workspace: ${workspace}`,
              '',
              'Description:',
              description,
              '',
              'Output Format:',
              'Return a JSON object with this structure:',
              '```json',
              '{',
              '  "name": "Plan Name",',
              '  "description": "Original description",',
              '  "steps": [',
              '    {',
              '      "mode": "serial",',
              '      "actions": [',
              '        { "description": "Step 1: ..." },',
              '        { "description": "Step 2: ..." }',
              '      ]',
              '    }',
              '  ]',
              '}',
              '```',
              '',
              'Each action should be a clear, concise description of what needs to be done.',
            ].join('\n');

            // Use sessionManager to support all LLM providers
            const tempSessionId = sessionManager.createSession(workspace);
            const abortController = new AbortController();

            let fullResponse = '';
            const originalSend = ws.send.bind(ws);
            const interceptedSend = (data: string) => {
              const parsed = JSON.parse(data);
              if (parsed.type === 'chat.delta') {
                fullResponse += parsed.delta?.text || '';
              }
              if (parsed.type === 'chat.done') {
                abortController.abort();
              }
              originalSend(data);
            };
            (ws as any).send = interceptedSend;

            await sessionManager.sendMessageForCron(
              tempSessionId,
              userPrompt,
              abortController,
              () => {}
            );

            (ws as any).send = originalSend;

            // Extract JSON from response
            const jsonMatch = fullResponse.match(/```json\n([\s\S]*?)\n```/);
            const jsonStr = jsonMatch ? jsonMatch[1].trim() : fullResponse.trim();

            const plan = JSON.parse(jsonStr);

            // Validate generated plan
            if (!plan.steps || !Array.isArray(plan.steps) || plan.steps.length === 0) {
              throw new Error('Generated plan has no steps');
            }

            ws.send(JSON.stringify({ type: 'smartpath.generated', payload: { plan } }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            console.error('SmartPath generation error:', errorMessage);
            console.error('Stack:', err instanceof Error ? err.stack : 'No stack');
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'smartpath.generateStep': {
          const userInput = msg.userInput as string;
          const workspace = msg.workspace as string;
          const previousSteps = msg.previousSteps as Array<{ userInput: string; generatedContent?: string }>;

          // Strict validation
          if (!userInput || !userInput.trim()) {
            throw new Error('Missing userInput parameter');
          }
          if (!workspace || !workspace.trim()) {
            throw new Error('Missing workspace parameter');
          }

          try {
            const llmConfig = settingsManager.getConfig().llm;
            if (!llmConfig.apiKey) {
              throw new Error('LLM API key not configured. Please check settings.');
            }

            // Build context from previous steps
            const previousContext = previousSteps && previousSteps.length > 0
              ? [
                'Previous steps and their results:',
                ...previousSteps.map((step, i) => [
                  `Step ${i + 1}:`,
                  `  User Request: ${step.userInput}`,
                  step.generatedContent ? `  Generated Solution: ${step.generatedContent.slice(0, 200)}...` : '',
                ].join('\n')),
              ].join('\n')
              : 'No previous steps. This is the first step.';

            const systemPrompt = 'You are an expert software engineer. Generate practical implementation solutions based on user requirements.';
            const userPrompt = [
              'Generate an implementation solution for the following step.',
              '',
              'Context:',
              `- Workspace: ${workspace}`,
              '',
              previousContext,
              '',
              'Current Step Request:',
              userInput,
              '',
              'Output Format:',
              'Provide a clear, actionable solution including:',
              '1. Approach overview',
              '2. Specific code examples (if applicable)',
              '3. File locations or structure',
              '4. Key considerations or constraints',
            ].join('\n');

            // Use sessionManager to support all LLM providers
            const tempSessionId = sessionManager.createSession(workspace);
            const abortController = new AbortController();

            let fullResponse = '';
            const originalSend = ws.send.bind(ws);
            const interceptedSend = (data: string) => {
              try {
                const parsed = JSON.parse(data);
                if (parsed.type === 'chat.delta') {
                  fullResponse += parsed.delta?.text || '';
                }
                if (parsed.type === 'chat.done') {
                  abortController.abort();
                }
              } catch {}
              originalSend(data);
            };
            (ws as any).send = interceptedSend;

            await sessionManager.sendMessageForCron(
              tempSessionId,
              userPrompt,
              abortController,
              () => {}
            );

            (ws as any).send = originalSend;

            ws.send(JSON.stringify({
              type: 'smartpath.stepGenerated',
              payload: { generatedContent: fullResponse.trim() }
            }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            console.error('SmartPath step generation error:', errorMessage);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }

        case 'smartpath.listFiles': {
          const workspace = msg.workspace as string;
          if (!workspace) throw new Error('Missing workspace');

          const store = new SmartPathStore(workspace);
          const plans = store.listPlans(workspace);
          const files = plans.map(p => ({
            id: p.id,
            name: p.name,
            description: p.description,
            status: p.status,
            createdAt: p.createdAt,
            updatedAt: p.updatedAt,
          }));
          ws.send(JSON.stringify({ type: 'smartpath.fileList', payload: { files } }));
          break;
        }

        case 'smartpath.loadFile': {
          const workspace = msg.workspace as string;
          const planId = msg.planId as string;
          if (!workspace) throw new Error('Missing workspace');
          if (!planId) throw new Error('Missing planId');

          const store = new SmartPathStore(workspace);
          const filePath = path.join(workspace, '.sman', 'paths', `${planId}.md`);
          const plan = store.loadPlan(filePath);
          ws.send(JSON.stringify({ type: 'smartpath.fileLoaded', payload: { plan } }));
          break;
        }

        case 'smartpath.saveFile': {
          const planId = msg.planId as string;
          const plan = msg.plan as { name: string; description: string; steps: any[]; workspace: string };
          const workspace = msg.workspace as string;
          if (!planId) throw new Error('Missing planId');
          if (!plan) throw new Error('Missing plan');
          if (!workspace) throw new Error('Missing workspace');

          const store = new SmartPathStore(workspace);
          const filePath = path.join(workspace, '.sman', 'paths', `${planId}.md`);

          // Load existing plan to preserve metadata
          const existingPlan = fs.existsSync(filePath) ? store.loadPlan(filePath) : null;

          const planData = {
            id: planId,
            name: plan.name,
            description: plan.description,
            workspace,
            steps: plan.steps || [],
            status: existingPlan?.status || 'draft',
            createdAt: existingPlan?.createdAt || new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          };

          store.savePlan(planData);
          ws.send(JSON.stringify({ type: 'smartpath.fileSaved', payload: { filePath } }));
          break;
        }

        case 'smartpath.deleteFile': {
          const workspace = msg.workspace as string;
          const planId = msg.planId as string;
          if (!workspace) throw new Error('Missing workspace');
          if (!planId) throw new Error('Missing planId');

          const store = new SmartPathStore(workspace);
          const filePath = path.join(workspace, '.sman', 'paths', `${planId}.md`);
          store.deletePlan(filePath);
          ws.send(JSON.stringify({ type: 'smartpath.fileDeleted', payload: { success: true } }));
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

        default:
          if (msg.type?.startsWith('bazaar.')) {
            const bridge = getBazaarBridge();
            if (bridge) {
              bridge.handleFrontendMessage(msg.type, (msg.payload ?? msg) as Record<string, unknown>, ws);
            } else {
              ws.send(JSON.stringify({ type: 'error', error: `Bazaar not configured: ${msg.type}` }));
            }
          } else {
            ws.send(JSON.stringify({ type: 'error', error: `Unknown message type: ${msg.type}` }));
          }
          break;
      }
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      ws.send(JSON.stringify({ type: 'chat.error', sessionId: msg.sessionId, error: errorMessage }));
      log.error('Message handling error', { error: errorMessage });
    }
  });

  ws.on('close', () => {
    clearTimeout(authTimeout);
    clients.delete(ws);
    authenticatedClients.delete(ws);
    log.info('WebSocket client disconnected');
  });
});

// Graceful shutdown
function shutdown(): void {
  wecomConnection?.stop();
  feishuConnection?.stop();
  weixinConnection?.stop();
  chatbotManager.stop();
  batchEngine.stop();
  cronScheduler.stop();
  sessionManager.close();
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

// Bazaar Bridge（独立模块，未配置时无副作用）
initBazaarBridge({
  sessionManager,
  settingsManager,
  skillsRegistry,
  broadcast: (data: string) => broadcast(data),
  homeDir,
});

// When run directly (dev mode: tsx server/index.ts), auto-start
// When imported by Electron, electron/main.ts calls startServer()
const isMainModule = process.argv[1]?.replace(/\\/g, '/').endsWith('server/index.ts') ||
                     process.argv[1]?.replace(/\\/g, '/').endsWith('server/index.js') ||
                     process.argv[1]?.replace(/\\/g, '/').endsWith('dist/server/index.js') ||
                     process.argv[1]?.replace(/\\/g, '/').endsWith('app/index.js');

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
  server.listen(PORT, HOST, () => {
    log.info(`Sman server running on ${HOST}:${PORT}`);
    log.info(`Home directory: ${homeDir}`);
    log.info(`WebSocket endpoint: ws://${HOST}:${PORT}/ws`);
    log.info(`Health check: http://${HOST}:${PORT}/api/health`);
  });

  // Auto-setup office-skills dependencies (non-blocking)
  setupOfficeSkills();
}
