import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ClaudeSessionManager } from '../../server/claude-session.js';
import { SessionStore } from '../../server/session-store.js';
import { SkillsRegistry } from '../../server/skills-registry.js';
import { ProfileManager } from '../../server/profile-manager.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('ClaudeSessionManager', () => {
  let manager: ClaudeSessionManager;
  let store: SessionStore;
  let skillsRegistry: SkillsRegistry;
  let profileManager: ProfileManager;
  let homeDir: string;
  let dbPath: string;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `smanbase-csm-${Date.now()}`);
    dbPath = path.join(homeDir, 'test.db');
    fs.mkdirSync(homeDir, { recursive: true });
    fs.mkdirSync(path.join(homeDir, 'skills'), { recursive: true });
    fs.mkdirSync(path.join(homeDir, 'profiles'), { recursive: true });

    store = new SessionStore(dbPath);
    skillsRegistry = new SkillsRegistry(homeDir);
    profileManager = new ProfileManager(homeDir);

    profileManager.createProfile({
      systemId: 'projectA',
      name: 'Project A',
      workspace: '/tmp/fake-workspace',
      description: 'Test project',
      skills: [],
    });

    manager = new ClaudeSessionManager(store, skillsRegistry, profileManager);
  });

  afterEach(() => {
    manager.close();
    store.close();
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should create a new session', () => {
    const sessionId = manager.createSession('projectA');
    expect(sessionId).toBeDefined();
    expect(typeof sessionId).toBe('string');
    const session = store.getSession(sessionId);
    expect(session).toBeDefined();
    expect(session!.systemId).toBe('projectA');
  });

  it('should list active sessions', () => {
    const s1 = manager.createSession('projectA');
    const s2 = manager.createSession('projectA');
    const sessions = manager.listSessions('projectA');
    expect(sessions).toHaveLength(2);
    expect(sessions.map(s => s.id)).toContain(s1);
    expect(sessions.map(s => s.id)).toContain(s2);
  });

  it('should abort a session', () => {
    const sessionId = manager.createSession('projectA');
    expect(() => manager.abort(sessionId)).not.toThrow();
  });

  it('should get session history', () => {
    const sessionId = manager.createSession('projectA');
    store.addMessage(sessionId, { role: 'user', content: 'hello' });
    store.addMessage(sessionId, { role: 'assistant', content: 'hi' });
    const history = manager.getHistory(sessionId);
    expect(history).toHaveLength(2);
  });

  it('should throw when creating session for unknown system', () => {
    expect(() => manager.createSession('unknown-system')).toThrow();
  });
});
