import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ChatbotStore } from '../../../server/chatbot/chatbot-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('ChatbotStore', () => {
  let store: ChatbotStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `chatbot-test-${Date.now()}.db`);
    store = new ChatbotStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('user state', () => {
    it('should create and get user state', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      const state = store.getUserState('wecom:user1');
      expect(state).toBeDefined();
      expect(state!.currentWorkspace).toBe('/data/projectA');
    });

    it('should return undefined for unknown user', () => {
      const state = store.getUserState('wecom:unknown');
      expect(state).toBeUndefined();
    });

    it('should update user workspace', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      store.setUserState('wecom:user1', '/data/projectB');
      const state = store.getUserState('wecom:user1');
      expect(state!.currentWorkspace).toBe('/data/projectB');
    });
  });

  describe('sessions', () => {
    it('should create and get session', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      store.setSession('wecom:user1', '/data/projectA', 'sess-1');
      const session = store.getSession('wecom:user1', '/data/projectA');
      expect(session).toBeDefined();
      expect(session!.sessionId).toBe('sess-1');
    });

    it('should update sdkSessionId', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      store.setSession('wecom:user1', '/data/projectA', 'sess-1');
      store.updateSdkSessionId('wecom:user1', '/data/projectA', 'sdk-123');
      const session = store.getSession('wecom:user1', '/data/projectA');
      expect(session!.sdkSessionId).toBe('sdk-123');
    });

    it('should update session id on conflict', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      store.setSession('wecom:user1', '/data/projectA', 'sess-1');
      store.setSession('wecom:user1', '/data/projectA', 'sess-2');
      const session = store.getSession('wecom:user1', '/data/projectA');
      expect(session!.sessionId).toBe('sess-2');
    });
  });

  describe('workspaces', () => {
    it('should add and list workspaces', () => {
      store.addWorkspace('/data/projectA', 'projectA');
      store.addWorkspace('/data/projectB', 'projectB');
      const workspaces = store.listWorkspaces();
      expect(workspaces).toHaveLength(2);
      expect(workspaces[0].path).toBe('/data/projectA');
    });

    it('should not duplicate workspace', () => {
      store.addWorkspace('/data/projectA', 'projectA');
      store.addWorkspace('/data/projectA', 'projectA');
      const workspaces = store.listWorkspaces();
      expect(workspaces).toHaveLength(1);
    });

    it('should find workspace by name', () => {
      store.addWorkspace('/data/my-project', 'my-project');
      const found = store.findWorkspace('my-project');
      expect(found).toBe('/data/my-project');
    });

    it('should find workspace by path suffix', () => {
      store.addWorkspace('/data/deep/nested/project', 'project');
      const found = store.findWorkspace('project');
      expect(found).toBe('/data/deep/nested/project');
    });

    it('should return null for unknown name', () => {
      const found = store.findWorkspace('non-existent');
      expect(found).toBeNull();
    });

    it('should check if workspace is registered', () => {
      store.addWorkspace('/data/projectA', 'projectA');
      expect(store.isWorkspaceRegistered('/data/projectA')).toBe(true);
      expect(store.isWorkspaceRegistered('/data/unknown')).toBe(false);
    });
  });
});
