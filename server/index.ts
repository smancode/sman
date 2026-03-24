import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { createLogger, type Logger } from './utils/logger.js';
import { SessionStore } from './session-store.js';
import { SkillsRegistry } from './skills-registry.js';
import { ProfileManager } from './profile-manager.js';
import { ClaudeSessionManager } from './claude-session.js';
import { SettingsManager } from './settings-manager.js';
import { TaskMonitor } from './task-monitor.js';

const PORT = parseInt(process.env.PORT || '5880', 10);
const log = createLogger('Server');

function getHomeDir(): string {
  const env = process.env.SMANBASE_HOME;
  if (env) return env;
  return path.join(os.homedir(), '.smanbase');
}

function ensureHomeDir(homeDir: string): void {
  const dirs = ['skills', 'profiles', 'logs'];
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

const dbPath = path.join(homeDir, 'smanbase.db');
const store = new SessionStore(dbPath);
const skillsRegistry = new SkillsRegistry(homeDir);
const profileManager = new ProfileManager(homeDir);
const sessionManager = new ClaudeSessionManager(store, skillsRegistry, profileManager);
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
  const msg = data;
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(msg);
    }
  }
}

interface WsMessage {
  type: string;
  sessionId?: string;
  systemId?: string;
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
          if (!msg.systemId) throw new Error('Missing systemId');
          const sessionId = sessionManager.createSession(msg.systemId);
          ws.send(JSON.stringify({ type: 'session.created', sessionId, systemId: msg.systemId }));
          break;
        }

        case 'session.list': {
          const sessions = sessionManager.listSessions(msg.systemId as string);
          ws.send(JSON.stringify({ type: 'session.list', sessions }));
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

        // ── Profile (Business System) CRUD ──
        case 'profile.list': {
          const profiles = profileManager.listProfiles();
          ws.send(JSON.stringify({ type: 'profile.list', profiles }));
          break;
        }

        case 'profile.get': {
          if (!msg.systemId) throw new Error('Missing systemId');
          const profile = profileManager.getProfile(msg.systemId);
          if (!profile) throw new Error(`Profile not found: ${msg.systemId}`);
          ws.send(JSON.stringify({ type: 'profile.get', profile }));
          break;
        }

        case 'profile.create': {
          const required = ['systemId', 'name', 'workspace', 'description', 'skills'];
          for (const field of required) {
            if (!msg[field]) throw new Error(`Missing ${field}`);
          }
          const profile = profileManager.createProfile({
            systemId: String(msg.systemId),
            name: String(msg.name),
            workspace: String(msg.workspace),
            description: String(msg.description),
            skills: msg.skills as string[],
            autoTriggers: msg.autoTriggers as { onInit?: string[]; onConversationStart?: string[] } | undefined,
            claudeMdTemplate: msg.claudeMdTemplate as string | undefined,
          });
          ws.send(JSON.stringify({ type: 'profile.created', profile }));
          break;
        }

        case 'profile.update': {
          if (!msg.systemId) throw new Error('Missing systemId');
          const updates: Record<string, unknown> = { ...msg };
          delete updates.type;
          delete updates.systemId;
          const profile = profileManager.updateProfile(String(msg.systemId), updates);
          ws.send(JSON.stringify({ type: 'profile.updated', profile }));
          break;
        }

        case 'profile.delete': {
          if (!msg.systemId) throw new Error('Missing systemId');
          profileManager.deleteProfile(String(msg.systemId));
          ws.send(JSON.stringify({ type: 'profile.deleted', systemId: msg.systemId }));
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

// TaskMonitor setup - one per business system workspace
const taskMonitors = new Map<string, TaskMonitor>();

function startTaskMonitorForSystem(systemId: string, workspace: string): void {
  if (taskMonitors.has(systemId)) return;
  const monitor = new TaskMonitor(
    workspace,
    60000,
    (notification) => {
      const { type: notifyType, task, content } = notification;
      broadcast(JSON.stringify({
        type: 'task.notification',
        systemId,
        taskType: notifyType,
        task,
        content,
      }));
    },
  );
  monitor.start();
  // Feed current config to monitor so it passes API key/model to Claude SDK
  monitor.updateConfig(settingsManager.getConfig());
  taskMonitors.set(systemId, monitor);
  log.info(`TaskMonitor started for system ${systemId}`);
}

// Start monitors for existing profiles
function initTaskMonitors(): void {
  const profiles = profileManager.listProfiles();
  for (const profile of profiles) {
    if (profile.workspace && fs.existsSync(profile.workspace)) {
      startTaskMonitorForSystem(profile.systemId, profile.workspace);
    }
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
  log.info(`SmanBase server running on port ${PORT}`);
  log.info(`Home directory: ${homeDir}`);
  log.info(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  log.info(`Health check: http://localhost:${PORT}/api/health`);
  initTaskMonitors();
});
