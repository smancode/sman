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

const PORT = parseInt(process.env.PORT || '5170', 10);
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

// HTTP server
const server = http.createServer((req, res) => {
  if (req.url === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toISOString() }));
    return;
  }
  res.writeHead(404);
  res.end();
});

// WebSocket server
const wss = new WebSocketServer({ server, path: '/ws' });

interface WsMessage {
  type: string;
  sessionId?: string;
  systemId?: string;
  content?: string;
  [key: string]: unknown;
}

wss.on('connection', (ws: WebSocket) => {
  log.info('WebSocket client connected');

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
    log.info('WebSocket client disconnected');
  });
});

// Graceful shutdown
process.on('SIGTERM', () => {
  log.info('SIGTERM received, shutting down...');
  wss.close();
  server.close(() => process.exit(0));
});

process.on('SIGINT', () => {
  log.info('SIGINT received, shutting down...');
  wss.close();
  server.close(() => process.exit(0));
});

server.listen(PORT, () => {
  log.info(`SmanBase server running on port ${PORT}`);
  log.info(`Home directory: ${homeDir}`);
  log.info(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  log.info(`Health check: http://localhost:${PORT}/api/health`);
});
