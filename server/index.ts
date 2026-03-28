import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { fileURLToPath } from 'url';
import { createLogger, type Logger } from './utils/logger.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
import { SessionStore } from './session-store.js';
import { SkillsRegistry } from './skills-registry.js';
import { ClaudeSessionManager } from './claude-session.js';
import { SettingsManager } from './settings-manager.js';
import { CronTaskStore } from './cron-task-store.js';
import { CronScheduler } from './cron-scheduler.js';
import { BatchStore } from './batch-store.js';
import { BatchEngine } from './batch-engine.js';
import { ChatbotStore } from './chatbot/chatbot-store.js';
import { ChatbotSessionManager } from './chatbot/chatbot-session-manager.js';
import { WeComBotConnection } from './chatbot/wecom-bot-connection.js';
import { FeishuBotConnection } from './chatbot/feishu-bot-connection.js';

const PORT = parseInt(process.env.PORT || '5880', 10);
const log = createLogger('Server');

function getHomeDir(): string {
  const env = process.env.SMANBASE_HOME;
  if (env) return env;
  return path.join(os.homedir(), '.sman');
}

function ensureHomeDir(homeDir: string): void {
  const dirs = ['skills', 'logs'];
  for (const dir of dirs) {
    fs.mkdirSync(path.join(homeDir, dir), { recursive: true });
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

// Cron task scheduler
const cronTaskStore = new CronTaskStore(dbPath);
const cronScheduler = new CronScheduler(cronTaskStore);

// Batch execution engine
const batchStore = new BatchStore(dbPath);
const batchEngine = new BatchEngine(batchStore);
batchEngine.setSessionManager(sessionManager);
batchEngine.setConfig(settingsManager.getConfig().llm);
batchEngine.setOnProgress((taskId, data) => {
  broadcast(JSON.stringify({ type: 'batch.progress', taskId, ...data }));
});
batchEngine.start();

// Chatbot integration (WeCom + Feishu)
const chatbotStore = new ChatbotStore(dbPath);
const chatbotManager = new ChatbotSessionManager(homeDir, sessionManager, chatbotStore);

let wecomConnection: WeComBotConnection | null = null;
let feishuConnection: FeishuBotConnection | null = null;

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
}

startChatbotConnections();

// Start cron scheduler (loads enabled tasks and schedules them)
cronScheduler.start()

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

// Set up cron scheduler with session manager
cronScheduler.setSessionManager(sessionManager);
cronScheduler.start();

// HTTP server with static file serving for production (Electron mode)
const distDir = path.resolve(path.join(__dirname, 'dist'));
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

const server = http.createServer((req, res) => {
  if (req.url === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toLocaleString('zh-CN', { hour12: false }) }));
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
const wss = new WebSocketServer({ server, path: '/ws' });
const clients = new Set<WebSocket>();

function broadcast(data: string): void {
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(data);
    }
  }
}

interface WsMessage {
  type: string;
  sessionId?: string;
  workspace?: string;
  content?: string;
  [key: string]: unknown;
}


wss.on('connection', (ws: WebSocket) => {
  log.info('WebSocket client connected');
  clients.add(ws);

  ws.on('message', async (data) => {
    let msg: WsMessage;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      ws.send(JSON.stringify({ type: 'error', error: 'Invalid JSON' }));
      return;
    }

    try {
      switch (msg.type) {
        case 'session.create': {
          if (!msg.workspace) throw new Error('Missing workspace');
          const sessionId = sessionManager.createSession(msg.workspace);
          ws.send(JSON.stringify({ type: 'session.created', sessionId, workspace: msg.workspace }));
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
          store.deleteSession(msg.sessionId);
          ws.send(JSON.stringify({ type: 'session.deleted', sessionId: msg.sessionId }));
          break;
        }

        case 'session.history': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          const messages = sessionManager.getHistory(msg.sessionId);
          ws.send(JSON.stringify({ type: 'session.history', sessionId: msg.sessionId, messages }));
          break;
        }

        case 'chat.send': {
          if (!msg.sessionId || !msg.content) throw new Error('Missing sessionId or content');
          const wsSend = (d: string) => {
            if (ws.readyState === WebSocket.OPEN) ws.send(d);
          };
          await sessionManager.sendMessage(msg.sessionId, msg.content, wsSend);
          break;
        }

        case 'chat.abort': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          sessionManager.abort(msg.sessionId);
          ws.send(JSON.stringify({ type: 'chat.aborted', sessionId: msg.sessionId }));
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
          batchEngine.setConfig(config.llm);
          if (updates.chatbot) {
            wecomConnection?.stop();
            feishuConnection?.stop();
            startChatbotConnections();
          }
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
          const skills: { name: string; hasCrontab: boolean }[] = [];

          if (fs.existsSync(skillsDir)) {
            const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
            for (const entry of entries) {
              if (entry.isDirectory()) {
                const crontabPath = path.join(skillsDir, entry.name, 'crontab.md');
                skills.push({
                  name: entry.name,
                  hasCrontab: fs.existsSync(crontabPath),
                });
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
          if (!msg.workspace || !msg.skillName || !msg.intervalMinutes) {
            throw new Error('Missing required fields');
          }
          const task = cronTaskStore.createTask({
            workspace: msg.workspace as string,
            skillName: msg.skillName as string,
            intervalMinutes: msg.intervalMinutes as number,
          });
          cronScheduler.schedule(task);
          ws.send(JSON.stringify({ type: 'cron.created', task }));
          break;
        }

        case 'cron.update': {
          if (!msg.taskId) throw new Error('Missing taskId');
          const task = cronTaskStore.updateTask(msg.taskId as string, {
            workspace: msg.workspace as string | undefined,
            skillName: msg.skillName as string | undefined,
            intervalMinutes: msg.intervalMinutes as number | undefined,
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
          break;
        }

        case 'cron.delete': {
          if (!msg.taskId) throw new Error('Missing taskId');
          cronScheduler.unschedule(msg.taskId as string);
          cronTaskStore.deleteTask(msg.taskId as string);
          ws.send(JSON.stringify({ type: 'cron.deleted', taskId: msg.taskId }));
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
          try {
            await cronScheduler.executeNow(msg.taskId as string);
            ws.send(JSON.stringify({ type: 'cron.executed', taskId: msg.taskId }));
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

        default:
          ws.send(JSON.stringify({ type: 'error', error: `Unknown message type: ${msg.type}` }));
      }
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      ws.send(JSON.stringify({ type: 'chat.error', sessionId: msg.sessionId, error: errorMessage }));
      log.error('Message handling error', { error: errorMessage });
    }
  });

  ws.on('close', () => {
    clients.delete(ws);
    log.info('WebSocket client disconnected');
  });
});

// Graceful shutdown
process.on('SIGTERM', () => {
  log.info('SIGTERM received, shutting down...');
  wecomConnection?.stop();
  feishuConnection?.stop();
  chatbotManager.stop();
  batchEngine.stop();
  cronScheduler.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});

process.on('SIGINT', () => {
  log.info('SIGINT received, shutting down...');
  wecomConnection?.stop();
  feishuConnection?.stop();
  chatbotManager.stop();
  batchEngine.stop();
  cronScheduler.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});

server.listen(PORT, () => {
  log.info(`Sman server running on port ${PORT}`);
  log.info(`Home directory: ${homeDir}`);
  log.info(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  log.info(`Health check: http://localhost:${PORT}/api/health`);
});
