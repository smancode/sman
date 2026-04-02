import { describe, it, expect, vi, beforeEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';

// Mock weixin-api before importing connection
vi.mock('../../../server/chatbot/weixin-api.js', () => ({
  getBotQRCode: vi.fn(),
  getQRCodeStatus: vi.fn(),
  getUpdates: vi.fn(),
  sendMessage: vi.fn(),
}));

// Mock weixin-store
vi.mock('../../../server/chatbot/weixin-store.js', () => ({
  listAccountIds: vi.fn(() => []),
  loadAccount: vi.fn(() => null),
  saveAccount: vi.fn(),
  clearAccount: vi.fn(),
  saveCursor: vi.fn(),
  loadCursor: vi.fn(() => undefined),
}));

// Mock qrcode library
vi.mock('qrcode', () => ({
  default: {
    toDataURL: vi.fn(() => Promise.resolve('data:image/png;base64,mock-qr-data')),
  },
}));

describe('WeixinBotConnection', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-weixin-test-'));
  });

  it('should create and start without saved account (idle)', async () => {
    const { WeixinBotConnection } = await import(
      '../../../server/chatbot/weixin-bot-connection.js'
    );
    const onStatusChange = vi.fn();
    const conn = new WeixinBotConnection({
      homeDir: tmpDir,
      onMessage: vi.fn(),
      onStatusChange,
    });

    conn.start();
    expect(conn.getConnectionStatus()).toBe('idle');
    expect(onStatusChange).toHaveBeenCalledWith('idle');
    conn.stop();
  });

  it('should handle QR login flow', async () => {
    const { WeixinBotConnection } = await import(
      '../../../server/chatbot/weixin-bot-connection.js'
    );
    const { getBotQRCode } = await import('../../../server/chatbot/weixin-api.js');
    const { getQRCodeStatus } = await import('../../../server/chatbot/weixin-api.js');

    const mockGetBotQRCode = getBotQRCode as ReturnType<typeof vi.fn>;
    const mockGetQRCodeStatus = getQRCodeStatus as ReturnType<typeof vi.fn>;

    // Mock QR code generation
    mockGetBotQRCode.mockResolvedValue({
      qrcode: 'test-qrcode-123',
      qrcode_img_content: 'https://example.com/qr.png',
      ret: 0,
    });

    const onStatusChange = vi.fn();
    const conn = new WeixinBotConnection({
      homeDir: tmpDir,
      onMessage: vi.fn(),
      onStatusChange,
    });

    conn.start();

    // Start QR login
    const qrResult = await conn.startQRLogin();
    expect(qrResult.qrcodeUrl).toBe('data:image/png;base64,mock-qr-data');
    expect(qrResult.sessionKey).toBeDefined();

    // Mock QR status: wait
    mockGetQRCodeStatus.mockResolvedValue({
      ret: 0,
      status: 'wait',
    });

    const waitResult = await conn.waitForLogin(qrResult.sessionKey);
    expect(waitResult.connected).toBe(false);
    expect(waitResult.qrStatus).toBe('wait');

    conn.stop();
  });

  it('should handle AbortError in waitForLogin gracefully', async () => {
    const { WeixinBotConnection } = await import(
      '../../../server/chatbot/weixin-bot-connection.js'
    );
    const { getBotQRCode } = await import('../../../server/chatbot/weixin-api.js');
    const { getQRCodeStatus } = await import('../../../server/chatbot/weixin-api.js');

    const mockGetBotQRCode = getBotQRCode as ReturnType<typeof vi.fn>;
    const mockGetQRCodeStatus = getQRCodeStatus as ReturnType<typeof vi.fn>;

    mockGetBotQRCode.mockResolvedValue({
      qrcode: 'test-qrcode-abort',
      qrcode_img_content: 'https://example.com/qr-abort.png',
      ret: 0,
    });

    // Simulate AbortError (timeout)
    const abortError = new DOMException('The operation was aborted', 'AbortError');
    mockGetQRCodeStatus.mockRejectedValue(abortError);

    const conn = new WeixinBotConnection({
      homeDir: tmpDir,
      onMessage: vi.fn(),
      onStatusChange: vi.fn(),
    });

    conn.start();
    const qrResult = await conn.startQRLogin();

    // AbortError should return 'wait' instead of error
    const result = await conn.waitForLogin(qrResult.sessionKey);
    expect(result.connected).toBe(false);
    expect(result.qrStatus).toBe('wait');

    conn.stop();
  });

  it('should handle QR confirmed and start monitor', async () => {
    const { WeixinBotConnection } = await import(
      '../../../server/chatbot/weixin-bot-connection.js'
    );
    const { getBotQRCode } = await import('../../../server/chatbot/weixin-api.js');
    const { getQRCodeStatus } = await import('../../../server/chatbot/weixin-api.js');
    const { saveAccount } = await import('../../../server/chatbot/weixin-store.js');

    const mockGetBotQRCode = getBotQRCode as ReturnType<typeof vi.fn>;
    const mockGetQRCodeStatus = getQRCodeStatus as ReturnType<typeof vi.fn>;

    mockGetBotQRCode.mockResolvedValue({
      qrcode: 'test-qrcode-confirm',
      qrcode_img_content: 'https://example.com/qr-confirm.png',
      ret: 0,
    });

    mockGetQRCodeStatus.mockResolvedValue({
      ret: 0,
      status: 'confirmed',
      bot_token: 'test-bot-token',
      ilink_bot_id: 'test-bot-id',
      baseurl: 'https://ilinkai.weixin.qq.com',
      ilink_user_id: 'test-user-id',
    });

    const onStatusChange = vi.fn();
    const conn = new WeixinBotConnection({
      homeDir: tmpDir,
      onMessage: vi.fn(),
      onStatusChange,
    });

    conn.start();
    const qrResult = await conn.startQRLogin();
    const result = await conn.waitForLogin(qrResult.sessionKey);

    expect(result.connected).toBe(true);
    expect(result.qrStatus).toBe('confirmed');
    expect(result.botToken).toBe('test-bot-token');
    expect(saveAccount).toHaveBeenCalled();
    expect(conn.getConnectionStatus()).toBe('connected');

    conn.stop();
  });

  it('should handle QR expired', async () => {
    const { WeixinBotConnection } = await import(
      '../../../server/chatbot/weixin-bot-connection.js'
    );
    const { getBotQRCode } = await import('../../../server/chatbot/weixin-api.js');
    const { getQRCodeStatus } = await import('../../../server/chatbot/weixin-api.js');

    const mockGetBotQRCode = getBotQRCode as ReturnType<typeof vi.fn>;
    const mockGetQRCodeStatus = getQRCodeStatus as ReturnType<typeof vi.fn>;

    mockGetBotQRCode.mockResolvedValue({
      qrcode: 'test-qrcode-expired',
      qrcode_img_content: 'https://example.com/qr-expired.png',
      ret: 0,
    });

    mockGetQRCodeStatus.mockResolvedValue({
      ret: 0,
      status: 'expired',
    });

    const onStatusChange = vi.fn();
    const conn = new WeixinBotConnection({
      homeDir: tmpDir,
      onMessage: vi.fn(),
      onStatusChange,
    });

    conn.start();
    const qrResult = await conn.startQRLogin();
    const result = await conn.waitForLogin(qrResult.sessionKey);

    expect(result.connected).toBe(false);
    expect(result.qrStatus).toBe('expired');

    conn.stop();
  });
});
