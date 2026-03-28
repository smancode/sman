import { describe, it, expect, vi } from 'vitest';

describe('FeishuBotConnection', () => {
  it('should export a class with start/stop methods', async () => {
    const { FeishuBotConnection } = await import('../../../server/chatbot/feishu-bot-connection.js');
    expect(typeof FeishuBotConnection).toBe('function');
    const conn = new FeishuBotConnection({
      appId: 'test-app',
      appSecret: 'test-secret',
      onMessage: vi.fn(),
    });
    expect(typeof conn.start).toBe('function');
    expect(typeof conn.stop).toBe('function');
    conn.stop();
  });
});
