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
import { TaskMonitor } from './task-monitor.js';

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
      llm: { apiKey: '', model: 'claude-sonnet-4-6' },
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
const sessionManager = new ClaudeSessionManager(store, skillsRegistry);
const settingsManager = new SettingsManager(homeDir);

// Feed config to session manager
sessionManager.updateConfig(settingsManager.getConfig());

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
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toISOString() }));
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

// TaskMonitor setup - one per workspace
const taskMonitors = new Map<string, TaskMonitor>();

function startTaskMonitor(workspace: string): void {
  if (taskMonitors.has(workspace)) return;
  if (!fs.existsSync(workspace)) return;

  const monitor = new TaskMonitor(
    workspace,
    60000,
    (notification) => {
      const { type: notifyType, task, content } = notification;
      broadcast(JSON.stringify({
        type: 'task.notification',
        workspace,
        taskType: notifyType,
        task,
        content,
      }));
    },
  );
  monitor.start();
  monitor.updateConfig(settingsManager.getConfig());
  taskMonitors.set(workspace, monitor);
  log.info(`TaskMonitor started for ${workspace}`);
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
          // Start task monitor for this workspace
          startTaskMonitor(msg.workspace);
          break;
        }

        case 'session.list': {
          const sessions = sessionManager.listSessions().map(s => ({
            id: s.id,
            systemId: s.systemId,
            workspace: s.workspace,
            label: s.label,
            createdAt: s.createdAt,
            lastActiveAt: s.lastActiveAt,
          }));
          ws.send(JSON.stringify({ type: 'session.list', sessions }));
          break;
        }

        case 'session.updateLabel': {
          if (!msg.sessionId || !msg.label) throw new Error('Missing sessionId or label');
          store.updateLabel(msg.sessionId, msg.label);
          ws.send(JSON.stringify({ type: 'session.labelUpdated', sessionId: msg.sessionId, label: msg.label }));
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
          // Push updated config to all task monitors
          for (const monitor of taskMonitors.values()) {
            monitor.updateConfig(config);
          }
          ws.send(JSON.stringify({ type: 'settings.updated', config }));
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

// Start task monitors for existing sessions' workspaces
function initTaskMonitors(): void {
  const sessions = store.listSessions();
  const workspaces = new Set(sessions.map(s => s.workspace).filter(Boolean));
  for (const workspace of workspaces) {
    startTaskMonitor(workspace);
  }
}

// Graceful shutdown
process.on('SIGTERM', () => {
  log.info('SIGTERM received, shutting down...');
  for (const monitor of taskMonitors.values()) monitor.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});

process.on('SIGINT', () => {
  log.info('SIGINT received, shutting down...');
  for (const monitor of taskMonitors.values()) monitor.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});

server.listen(PORT, () => {
  log.info(`Sman server running on port ${PORT}`);
  log.info(`Home directory: ${homeDir}`);
  log.info(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  log.info(`Health check: http://localhost:${PORT}/api/health`);
  initTaskMonitors();
});
