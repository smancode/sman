import { describe, it, expect, vi } from 'vitest';

describe('WeComBotConnection', () => {
  it('should export a class with start/stop methods', async () => {
    const { WeComBotConnection } = await import('../../../server/chatbot/wecom-bot-connection.js');
    expect(typeof WeComBotConnection).toBe('function');
    const conn = new WeComBotConnection({
      botId: 'test-bot',
      secret: 'test-secret',
      onMessage: vi.fn(),
    });
    expect(typeof conn.start).toBe('function');
    expect(typeof conn.stop).toBe('function');
    conn.stop();
  });
});
