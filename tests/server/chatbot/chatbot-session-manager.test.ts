import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ChatbotSessionManager } from '../../../server/chatbot/chatbot-session-manager.js';
import { ChatbotStore } from '../../../server/chatbot/chatbot-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

const mockCreateSessionWithId = vi.fn();
const mockSendMessageForChatbot = vi.fn();
const mockRestoreSession = vi.fn();
const mockAbort = vi.fn();
const mockUpdateSessionLabel = vi.fn();
const mockSessionManager = {
  createSessionWithId: mockCreateSessionWithId,
  sendMessageForChatbot: mockSendMessageForChatbot,
  restoreSession: mockRestoreSession,
  abort: mockAbort,
  updateSessionLabel: mockUpdateSessionLabel,
  listSessions: vi.fn().mockReturnValue([]),
} as any;

describe('ChatbotSessionManager', () => {
  let manager: ChatbotSessionManager;
  let store: ChatbotStore;
  let dbPath: string;
  let homeDir: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `chatbot-mgr-test-${Date.now()}.db`);
    homeDir = path.join(os.tmpdir(), `chatbot-mgr-home-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    store = new ChatbotStore(dbPath);
    manager = new ChatbotSessionManager(homeDir, mockSessionManager, store);
    vi.clearAllMocks();
    mockSessionManager.listSessions.mockReturnValue([]);
  });

  afterEach(() => {
    manager.stop();
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  function createSender(): { sender: any; responses: string[]; errors: string[] } {
    const responses: string[] = [];
    const errors: string[] = [];
    const sender = {
      start: () => {},
      sendChunk: () => {},
      finish: (c: string) => responses.push(c),
      error: (m: string) => errors.push(m),
    };
    return { sender, responses, errors };
  }

  describe('command routing', () => {
    it('should handle //help command', async () => {
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//help',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('//cd');
      expect(responses[0]).toContain('//pwd');
      expect(responses[0]).toContain('//workspaces');
    });

    it('should handle //pwd without workspace', async () => {
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//pwd',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('未设置');
    });

    it('should handle //pwd with workspace', async () => {
      store.addWorkspace('/data/projectA', 'projectA');
      store.setUserState('wecom:user1', '/data/projectA');
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//pwd',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('/data/projectA');
    });

    it('should handle //workspaces when empty', async () => {
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//workspaces',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('暂无');
    });

    it('should handle //workspaces with registered workspaces', async () => {
      store.addWorkspace('/data/projectA', 'projectA');
      store.addWorkspace('/data/projectB', 'projectB');
      // Manager gets workspaces from desktop sessions, not store
      mockSessionManager.listSessions.mockReturnValue([
        { id: 's1', workspace: '/data/projectA' },
        { id: 's2', workspace: '/data/projectB' },
      ]);
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//workspaces',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('projectA');
      expect(responses[0]).toContain('projectB');
    });

    it('should handle //status', async () => {
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//status',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('空闲');
    });
  });

  describe('//cd command', () => {
    it('should switch to registered workspace', async () => {
      const testDir = path.join(homeDir, 'projectA');
      fs.mkdirSync(testDir, { recursive: true });
      store.addWorkspace(testDir, 'projectA');
      // Manager resolves workspace from desktop sessions
      mockSessionManager.listSessions.mockReturnValue([
        { id: 's1', workspace: testDir },
      ]);

      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//cd projectA',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('projectA');
      expect(responses[0]).toContain(testDir);
      expect(mockCreateSessionWithId).toHaveBeenCalledWith(testDir, expect.any(String), false);
    });

    it('should reject unregistered workspace', async () => {
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//cd unknown-project',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('未找到');
    });

    it('should reject non-existent path', async () => {
      store.addWorkspace('/non/existent/path', 'path');
      // resolveWorkspace needs desktop session to find the workspace
      mockSessionManager.listSessions.mockReturnValue([
        { id: 's1', workspace: '/non/existent/path' },
      ]);
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//cd path',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('不存在');
    });

    it('should require argument for //cd', async () => {
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '//cd',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('用法');
    });
  });

  describe('chat messages', () => {
    it('should reject chat when no workspace set', async () => {
      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: 'hello',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      // No desktop workspaces → tells user to open one in desktop
      expect(responses[0]).toContain('桌面端');
    });

    it('should forward chat to session manager', async () => {
      const testDir = path.join(homeDir, 'projectA');
      fs.mkdirSync(testDir, { recursive: true });
      store.addWorkspace(testDir, 'projectA');
      store.setUserState('wecom:user1', testDir);
      store.setSession('wecom:user1', testDir, 'sess-1');

      mockSendMessageForChatbot.mockResolvedValueOnce('Hello! How can I help?');

      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: 'hello',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(mockSendMessageForChatbot).toHaveBeenCalledWith(
        'sess-1',
        'hello',
        expect.any(AbortController),
        expect.any(Function),
        expect.any(Function),
        undefined,
        expect.any(Function),
      );
      expect(responses[0]).toBe('Hello! How can I help?');
    });

    it('should handle feishu platform messages', async () => {
      const testDir = path.join(homeDir, 'projectA');
      fs.mkdirSync(testDir, { recursive: true });
      store.addWorkspace(testDir, 'projectA');
      store.setUserState('feishu:ou_123', testDir);
      store.setSession('feishu:ou_123', testDir, 'sess-fs-1');

      mockSendMessageForChatbot.mockResolvedValueOnce('Feishu response');

      const { sender, responses } = createSender();
      await manager.handleMessage({
        platform: 'feishu',
        userId: 'ou_123',
        content: '你好',
        requestId: 'req-fs-1',
        chatType: 'p2p',
        chatId: 'oc_xxx',
      }, sender);

      expect(responses[0]).toBe('Feishu response');
    });
  });
});
