import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SessionStore } from '../../server/session-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SessionStore', () => {
  let store: SessionStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `smanbase-test-${Date.now()}.db`);
    store = new SessionStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should create a session', () => {
    const session = store.createSession({
      id: 'sess-1',
      systemId: 'projectA',
      workspace: '/data/projectA',
    });
    expect(session.id).toBe('sess-1');
    expect(session.systemId).toBe('projectA');
  });

  it('should get a session by id', () => {
    store.createSession({
      id: 'sess-2',
      systemId: 'projectB',
      workspace: '/data/projectB',
    });
    const session = store.getSession('sess-2');
    expect(session).toBeDefined();
    expect(session!.systemId).toBe('projectB');
  });

  it('should list sessions filtered by systemId', () => {
    store.createSession({ id: 's1', systemId: 'sysA', workspace: '/a' });
    store.createSession({ id: 's2', systemId: 'sysA', workspace: '/a' });
    store.createSession({ id: 's3', systemId: 'sysB', workspace: '/b' });

    const sessions = store.listSessions('sysA');
    expect(sessions).toHaveLength(2);
  });

  it('should add and retrieve messages', () => {
    store.createSession({ id: 'sess-3', systemId: 'sysA', workspace: '/a' });
    store.addMessage('sess-3', { role: 'user', content: 'hello' });
    store.addMessage('sess-3', { role: 'assistant', content: 'hi there' });

    const messages = store.getMessages('sess-3');
    expect(messages).toHaveLength(2);
    expect(messages[0].role).toBe('user');
    expect(messages[1].content).toBe('hi there');
  });

  it('should soft-delete a session (hide from list but keep data)', () => {
    store.createSession({ id: 'sess-4', systemId: 'sysA', workspace: '/a' });
    store.addMessage('sess-4', { role: 'user', content: 'hello' });

    store.deleteSession('sess-4');

    // Session should still exist in DB (getSession doesn't filter deleted)
    expect(store.getSession('sess-4')).toBeDefined();
    // But hidden from listSessions
    expect(store.listSessions('sysA')).toHaveLength(0);
    // Messages are preserved
    expect(store.getMessages('sess-4')).toHaveLength(1);
  });

  it('should restore a soft-deleted session', () => {
    store.createSession({ id: 'sess-4b', systemId: 'sysA', workspace: '/a' });
    store.deleteSession('sess-4b');
    expect(store.listSessions('sysA')).toHaveLength(0);

    store.restoreSession('sess-4b');

    expect(store.listSessions('sysA')).toHaveLength(1);
  });

  it('should update lastActiveAt on message add', async () => {
    store.createSession({ id: 'sess-5', systemId: 'sysA', workspace: '/a' });
    const before = store.getSession('sess-5')!.lastActiveAt;

    // Wait at least 1 second to ensure datetime('now') returns a different value
    await new Promise(resolve => setTimeout(resolve, 1100));

    store.addMessage('sess-5', { role: 'user', content: 'msg' });
    const after = store.getSession('sess-5')!.lastActiveAt;

    expect(after).not.toBe(before);
  });
});
