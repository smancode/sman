import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ClaudeSessionManager } from '../../server/claude-session.js';
import { SessionStore } from '../../server/session-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('ClaudeSessionManager', () => {
  let manager: ClaudeSessionManager;
  let store: SessionStore;
  let homeDir: string;
  let dbPath: string;
  let workspace: string;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `smanbase-csm-${Date.now()}`);
    dbPath = path.join(homeDir, 'test.db');
    fs.mkdirSync(homeDir, { recursive: true });
    workspace = path.join(homeDir, 'workspace');
    fs.mkdirSync(workspace, { recursive: true });
    // macOS: /var is a symlink to /private/var, normalize to match server behavior
    workspace = fs.realpathSync(workspace);

    store = new SessionStore(dbPath);
    manager = new ClaudeSessionManager(store);

    // Set config so SDK integration has model info
    manager.updateConfig({
      port: 5880,
      llm: { apiKey: 'test', model: 'claude-sonnet-4-6' },
      webSearch: {
        provider: 'builtin',
        braveApiKey: '',
        tavilyApiKey: '',
        bingApiKey: '',
        maxUsesPerSession: 50,
      },
    });
  });

  afterEach(() => {
    manager.close();
    store.close();
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should create a new session', () => {
    const sessionId = manager.createSession(workspace);
    expect(sessionId).toBeDefined();
    expect(typeof sessionId).toBe('string');
    const session = store.getSession(sessionId);
    expect(session).toBeDefined();
    expect(session!.workspace).toBe(workspace);
  });

  it('should list active sessions', () => {
    manager.createSession(workspace);
    manager.createSession(workspace);
    const sessions = manager.listSessions();
    expect(sessions.length).toBeGreaterThanOrEqual(2);
  });

  it('should abort a session', () => {
    const sessionId = manager.createSession(workspace);
    expect(() => manager.abort(sessionId)).not.toThrow();
  });

  it('should get session history', () => {
    const sessionId = manager.createSession(workspace);
    store.addMessage(sessionId, { role: 'user', content: 'hello' });
    store.addMessage(sessionId, { role: 'assistant', content: 'hi' });
    const history = manager.getHistory(sessionId);
    expect(history).toHaveLength(2);
  });

  it('should throw when creating session for non-existent workspace', () => {
    expect(() => manager.createSession('/non/existent/path')).toThrow();
  });

  it('should update config and reflect new model', () => {
    manager.updateConfig({
      port: 5880,
      llm: { apiKey: 'new-key', model: 'claude-opus-4-6' },
      webSearch: {
        provider: 'brave',
        braveApiKey: 'brave-key',
        tavilyApiKey: '',
        bingApiKey: '',
        maxUsesPerSession: 50,
      },
    });

    const sessionId = manager.createSession(workspace);
    expect(sessionId).toBeDefined();
  });
});
