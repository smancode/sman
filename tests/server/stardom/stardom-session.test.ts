// tests/server/stardom/stardom-session.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { StardomSession } from '../../../server/stardom/stardom-session.js';
import { StardomStore } from '../../../server/stardom/stardom-store.js';
import type { StardomSessionDeps } from '../../../server/stardom/types.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('StardomSession', () => {
  let session: StardomSession;
  let store: StardomStore;
  let dbPath: string;
  let broadcastMessages: string[];
  let deps: StardomSessionDeps;

  const mockCreateSessionWithId = vi.fn();
  const mockSendMessageForCron = vi.fn();
  const mockAbort = vi.fn();
  const mockClientSend = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    dbPath = path.join(os.tmpdir(), `stardom-session-test-${Date.now()}.db`);
    store = new StardomStore(dbPath);
    broadcastMessages = [];

    deps = {
      sessionManager: {
        createSessionWithId: mockCreateSessionWithId,
        sendMessageForCron: mockSendMessageForCron,
        abort: mockAbort,
      } as any,
      client: { send: mockClientSend } as any,
      store,
      broadcast: (data: string) => broadcastMessages.push(data),
      homeDir: os.homedir(),
      maxConcurrentTasks: 3,
    };

    mockCreateSessionWithId.mockReturnValue('stardom-task-001');
    mockSendMessageForCron.mockResolvedValue(undefined);

    session = new StardomSession(deps);
  });

  afterEach(() => {
    session.stopAll();
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('startCollaboration', () => {
    it('should create a Claude session and send initial question', async () => {
      await session.startCollaboration(
        'task-001',
        '支付系统怎么查？',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );

      expect(mockCreateSessionWithId).toHaveBeenCalledWith(
        '/tmp/test-workspace',
        'stardom-task-001',
        true,
      );

      expect(mockSendMessageForCron).toHaveBeenCalledTimes(1);
      const [sessionId, content, abortController, onActivity] = mockSendMessageForCron.mock.calls[0];
      expect(sessionId).toBe('stardom-task-001');
      expect(content).toContain('小李');
      expect(content).toContain('支付系统怎么查？');
      expect(abortController).toBeInstanceOf(AbortController);
      expect(typeof onActivity).toBe('function');

      expect(session.getActiveCount()).toBe(1);
    });

    it('should reject when slots are full', async () => {
      for (let i = 0; i < 3; i++) {
        await session.startCollaboration(
          `task-${i}`,
          `问题 ${i}`,
          `agent-${i}`,
          `Agent ${i}`,
          '/tmp/test-workspace',
        );
      }

      await expect(
        session.startCollaboration('task-overflow', '溢出问题', 'agent-x', 'X', '/tmp/test-workspace'),
      ).rejects.toThrow('协作槽位已满');

      expect(session.getActiveCount()).toBe(3);
    });

    it('should save initial system message to chat store', async () => {
      await session.startCollaboration(
        'task-001',
        '支付系统怎么查？',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );

      const messages = store.listChatMessages('task-001');
      expect(messages.length).toBeGreaterThanOrEqual(1);
      expect(messages[0].from).toBe('system');
      expect(messages[0].text).toContain('小李');
    });
  });

  describe('sendCollaborationMessage', () => {
    it('should send a message to existing collaboration session', async () => {
      await session.startCollaboration(
        'task-001',
        '初始问题',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );

      mockSendMessageForCron.mockClear();

      await session.sendCollaborationMessage('task-001', '对方追问了更多细节');

      expect(mockSendMessageForCron).toHaveBeenCalledTimes(1);
      const [sessionId, content] = mockSendMessageForCron.mock.calls[0];
      expect(sessionId).toBe('stardom-task-001');
      expect(content).toContain('对方追问了更多细节');
    });

    it('should throw when task has no active collaboration', async () => {
      await expect(
        session.sendCollaborationMessage('non-existent', 'text'),
      ).rejects.toThrow('没有活跃的协作');
    });

    it('should save message to chat store', async () => {
      await session.startCollaboration(
        'task-001',
        '初始问题',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );
      mockSendMessageForCron.mockClear();

      await session.sendCollaborationMessage('task-001', '来自集市的消息');

      const messages = store.listChatMessages('task-001');
      const chatMsg = messages.find(m => m.from === 'remote');
      expect(chatMsg).toBeDefined();
      expect(chatMsg!.text).toBe('来自集市的消息');
    });
  });

  describe('abortCollaboration', () => {
    it('should abort active collaboration and cleanup', async () => {
      await session.startCollaboration(
        'task-001',
        '初始问题',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );

      expect(session.getActiveCount()).toBe(1);

      session.abortCollaboration('task-001');

      expect(mockAbort).toHaveBeenCalledWith('stardom-task-001');
      expect(session.getActiveCount()).toBe(0);
    });

    it('should be safe to abort non-existent collaboration', () => {
      expect(() => session.abortCollaboration('non-existent')).not.toThrow();
    });
  });

  describe('completeCollaboration', () => {
    it('should end collaboration, cleanup, and send task.complete to stardom', async () => {
      await session.startCollaboration(
        'task-001',
        '初始问题',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );

      session.completeCollaboration('task-001', 5, '很有帮助');

      expect(mockAbort).toHaveBeenCalledWith('stardom-task-001');
      expect(session.getActiveCount()).toBe(0);

      expect(mockClientSend).toHaveBeenCalledTimes(1);
      const sent = mockClientSend.mock.calls[0][0];
      expect(sent.type).toBe('task.complete');
      expect(sent.payload.taskId).toBe('task-001');
      expect(sent.payload.rating).toBe(5);
      expect(sent.payload.feedback).toBe('很有帮助');
    });

    it('should update task status in store', async () => {
      store.saveTask({
        taskId: 'task-001',
        direction: 'incoming',
        requesterAgentId: 'agent-002',
        requesterName: '小李',
        question: '测试',
        status: 'chatting',
        createdAt: new Date().toISOString(),
      });

      await session.startCollaboration(
        'task-001',
        '初始问题',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );

      session.completeCollaboration('task-001', 4, '不错');

      const task = store.getTask('task-001');
      expect(task!.status).toBe('completed');
      expect(task!.rating).toBe(4);
    });
  });

  describe('hasAvailableSlot', () => {
    it('should return true when slots available', () => {
      expect(session.hasAvailableSlot()).toBe(true);
    });

    it('should return false when all slots used', async () => {
      for (let i = 0; i < 3; i++) {
        await session.startCollaboration(
          `task-${i}`,
          `问题 ${i}`,
          `agent-${i}`,
          `Agent ${i}`,
          '/tmp/test-workspace',
        );
      }
      expect(session.hasAvailableSlot()).toBe(false);
    });
  });

  describe('stopAll', () => {
    it('should abort all active collaborations', async () => {
      for (let i = 0; i < 2; i++) {
        await session.startCollaboration(
          `task-${i}`,
          `问题 ${i}`,
          `agent-${i}`,
          `Agent ${i}`,
          '/tmp/test-workspace',
        );
      }

      session.stopAll();

      expect(session.getActiveCount()).toBe(0);
      expect(mockAbort).toHaveBeenCalledTimes(2);
    });
  });

  describe('sendClaudeReplyToStardom', () => {
    it('should save reply, send task.chat to stardom, and broadcast', async () => {
      await session.startCollaboration(
        'task-001',
        '初始问题',
        'agent-002',
        '小李',
        '/tmp/test-workspace',
      );

      session.sendClaudeReplyToStardom('task-001', '这是 Claude 的回复');

      // 验证保存到 store
      const messages = store.listChatMessages('task-001');
      const localMsg = messages.find(m => m.from === 'local');
      expect(localMsg).toBeDefined();
      expect(localMsg!.text).toBe('这是 Claude 的回复');

      // 验证发送到集市
      expect(mockClientSend).toHaveBeenCalledTimes(1);
      expect(mockClientSend.mock.calls[0][0].type).toBe('task.chat');
      expect(mockClientSend.mock.calls[0][0].payload.text).toBe('这是 Claude 的回复');

      // 验证广播到前端
      const broadcast = broadcastMessages.find(m => m.includes('stardom.task.chat.delta'));
      expect(broadcast).toBeDefined();
    });

    it('should skip empty reply', () => {
      session.sendClaudeReplyToStardom('task-001', '  ');
      expect(mockClientSend).not.toHaveBeenCalled();
    });
  });
});
