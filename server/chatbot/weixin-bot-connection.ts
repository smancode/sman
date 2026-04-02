/**
 * WeChat Personal Bot Connection
 * Handles QR login, long-polling monitor, message dispatch, and session lifecycle.
 */
import crypto from 'crypto';
import QRCode from 'qrcode';
import { createLogger, type Logger } from '../utils/logger.js';
import type { IncomingMessage, ChatResponseSender } from './types.js';
import type {
  WeixinConnectionStatus,
  WeixinQrStartResult,
  WeixinQrWaitResult,
  WeixinMessage,
} from './weixin-types.js';
import { SESSION_EXPIRED_ERRCODE, MessageItemType } from './weixin-types.js';
import * as WeixinApi from './weixin-api.js';
import * as WeixinStore from './weixin-store.js';

// ── Config ──

const ILINK_BASE_URL = 'https://ilinkai.weixin.qq.com';
const MAX_QR_REFRESH_COUNT = 3;
const MONITOR_BACKOFF_MS = 30_000;
const CONSECUTIVE_FAILURE_THRESHOLD = 3;
const MESSAGE_SPLIT_MAX_LEN = 3900;

interface WeixinBotConfig {
  homeDir: string;
  onMessage: (msg: IncomingMessage, sender: ChatResponseSender) => Promise<void>;
  onStatusChange: (status: WeixinConnectionStatus) => void;
}

interface ActiveLogin {
  qrcode: string;
  qrcodeUrl: string;
  createdAt: number;
  currentApiBaseUrl: string;
}

// ── Connection Class ──

export class WeixinBotConnection {
  private log: Logger;
  private config: WeixinBotConfig;
  private stopped = false;

  // Connection state
  private status: WeixinConnectionStatus = 'idle';
  private accountId: string | null = null;
  private token: string | null = null;
  private baseUrl = ILINK_BASE_URL;

  // Active QR login session
  private activeLogins = new Map<string, ActiveLogin>();

  // Monitor control
  private monitorAbort: AbortController | null = null;

  // Context tokens per user (for reply threading)
  private contextTokens = new Map<string, string>();

  constructor(config: WeixinBotConfig) {
    this.config = config;
    this.log = createLogger('WeixinBot');
  }

  // ── Lifecycle ──

  start(): void {
    this.stopped = false;

    // Try to load existing account
    const accountIds = WeixinStore.listAccountIds(this.config.homeDir);
    if (accountIds.length > 0) {
      const accountId = accountIds[0]; // Use first (typically only) account
      const account = WeixinStore.loadAccount(this.config.homeDir, accountId);
      if (account?.token) {
        this.accountId = accountId;
        this.token = account.token;
        this.baseUrl = account.baseUrl || ILINK_BASE_URL;
        this.log.info(`Loaded saved account ${accountId}, starting monitor...`);
        this.startMonitor();
        return;
      }
    }

    this.log.info('No saved WeChat account, waiting for QR login');
    this.setStatus('idle');
  }

  stop(): void {
    this.stopped = true;
    this.monitorAbort?.abort();
    this.monitorAbort = null;
    this.activeLogins.clear();
    this.log.info('WeChat bot stopped');
  }

  // ── QR Login ──

  async startQRLogin(): Promise<WeixinQrStartResult> {
    this.log.info('Starting QR login flow...');

    const resp = await WeixinApi.getBotQRCode();
    if (!resp.qrcode || !resp.qrcode_img_content) {
      throw new Error('Failed to get QR code from iLink API');
    }

    // Generate QR code image from the scan URL
    const qrcodeDataUrl = await QRCode.toDataURL(resp.qrcode_img_content, {
      width: 256,
      margin: 2,
      color: { dark: '#000000', light: '#ffffff' },
    });

    const sessionKey = crypto.randomUUID();
    this.activeLogins.set(sessionKey, {
      qrcode: resp.qrcode,
      qrcodeUrl: resp.qrcode_img_content,
      createdAt: Date.now(),
      currentApiBaseUrl: ILINK_BASE_URL,
    });

    this.log.info(`QR code generated, sessionKey=${sessionKey}`);
    return { qrcodeUrl: qrcodeDataUrl, sessionKey };
  }

  /**
   * Single-shot QR status check — called by the frontend polling loop.
   * Each call queries getQRCodeStatus once and returns immediately.
   * The frontend controls the polling cadence (every 3s).
   */
  async waitForLogin(sessionKey: string): Promise<WeixinQrWaitResult> {
    const activeLogin = this.activeLogins.get(sessionKey);
    if (!activeLogin) {
      return { connected: false, message: '无效的登录会话' };
    }

    try {
      const resp = await WeixinApi.getQRCodeStatus(activeLogin.qrcode);

      switch (resp.status) {
        case 'wait':
          return { connected: false, qrStatus: 'wait', message: '等待扫码中...' };

        case 'scaned':
          this.log.info('QR code scanned, waiting for confirmation...');
          return { connected: false, qrStatus: 'scaned', message: '已扫码，请在微信上确认...' };

        case 'scaned_but_redirect':
          if (resp.redirect_host) {
            activeLogin.currentApiBaseUrl = `https://${resp.redirect_host}`;
            this.log.info(`IDC redirect to ${activeLogin.currentApiBaseUrl}`);
          }
          return { connected: false, qrStatus: 'scaned_but_redirect', message: '重定向中...' };

        case 'confirmed': {
          const botToken = resp.bot_token!;
          const accountId = resp.ilink_bot_id || `weixin-${Date.now()}`;
          const baseUrl = resp.baseurl || ILINK_BASE_URL;
          const userId = resp.ilink_user_id;

          // Save account
          WeixinStore.saveAccount(this.config.homeDir, accountId, {
            token: botToken,
            baseUrl,
            userId,
          });

          // Update instance state
          this.accountId = accountId;
          this.token = botToken;
          this.baseUrl = baseUrl;

          // Cleanup login session
          this.activeLogins.delete(sessionKey);

          this.log.info(`WeChat login confirmed! accountId=${accountId}`);
          this.startMonitor();

          return {
            connected: true,
            qrStatus: 'confirmed',
            botToken,
            accountId,
            baseUrl,
            userId,
            message: '连接成功',
          };
        }

        case 'expired':
          this.activeLogins.delete(sessionKey);
          this.setStatus('idle');
          return { connected: false, qrStatus: 'expired', message: '二维码已过期，请重新获取' };

        default:
          this.log.warn(`Unknown QR status: ${resp.status}`);
          return { connected: false, message: `未知状态: ${resp.status}` };
      }
    } catch (err) {
      // AbortError from timeout is normal for long-poll — treat as 'wait'
      if (err instanceof DOMException && err.name === 'AbortError') {
        return { connected: false, qrStatus: 'wait', message: '等待扫码中...' };
      }
      const message = err instanceof Error ? err.message : String(err);
      this.log.debug(`QR poll error: ${message}`);
      return { connected: false, qrStatus: 'wait', message: `查询失败: ${message}` };
    }
  }

  // ── Disconnect ──

  disconnect(): void {
    this.monitorAbort?.abort();
    this.monitorAbort = null;

    if (this.accountId) {
      WeixinStore.clearAccount(this.config.homeDir, this.accountId);
      this.log.info(`WeChat account ${this.accountId} disconnected`);
    }

    this.accountId = null;
    this.token = null;
    this.baseUrl = ILINK_BASE_URL;
    this.contextTokens.clear();
    this.setStatus('idle');
  }

  // ── Status ──

  getConnectionStatus(): WeixinConnectionStatus {
    return this.status;
  }

  private setStatus(status: WeixinConnectionStatus): void {
    this.status = status;
    this.config.onStatusChange(status);
  }

  // ── Monitor Loop ──

  private startMonitor(): void {
    this.monitorAbort?.abort();
    this.monitorAbort = new AbortController();
    this.setStatus('connected');
    this.monitorLoop().catch((err) => {
      this.log.error(`Monitor loop crashed: ${err instanceof Error ? err.message : String(err)}`);
    });
  }

  private async monitorLoop(): Promise<void> {
    if (!this.accountId || !this.token) return;

    let getUpdatesBuf = WeixinStore.loadCursor(this.config.homeDir, this.accountId) || '';
    let consecutiveFailures = 0;

    this.log.info('Monitor loop started');

    while (!this.stopped) {
      try {
        const resp = await WeixinApi.getUpdates({
          baseUrl: this.baseUrl,
          token: this.token,
          getUpdatesBuf,
        });

        // Session expired
        if (resp.errcode === SESSION_EXPIRED_ERRCODE) {
          this.log.warn('WeChat session expired (errcode -14)');
          this.setStatus('disconnected');
          return;
        }

        // Reset failure counter
        consecutiveFailures = 0;

        // Save cursor
        if (resp.get_updates_buf) {
          getUpdatesBuf = resp.get_updates_buf;
          WeixinStore.saveCursor(this.config.homeDir, this.accountId!, getUpdatesBuf);
        }

        // Process messages
        if (resp.msgs && resp.msgs.length > 0) {
          for (const msg of resp.msgs) {
            await this.handleInboundMessage(msg);
          }
        }
      } catch (err) {
        // AbortError is normal (long-poll timeout or shutdown)
        if (err instanceof DOMException && err.name === 'AbortError') {
          if (this.stopped) return;
          continue;
        }

        consecutiveFailures++;
        this.log.error(
          `getUpdates error (${consecutiveFailures} consecutive): ${err instanceof Error ? err.message : String(err)}`,
        );

        if (consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD) {
          this.log.warn(`Too many consecutive failures, backing off ${MONITOR_BACKOFF_MS}ms`);
          await new Promise((resolve) => setTimeout(resolve, MONITOR_BACKOFF_MS));
          consecutiveFailures = 0;
        } else {
          await new Promise((resolve) => setTimeout(resolve, 2000));
        }
      }
    }
  }

  // ── Inbound Message Handling ──

  private async handleInboundMessage(msg: WeixinMessage): Promise<void> {
    const userId = msg.from_user_id;
    if (!userId || !msg.item_list) return;

    // Extract text from message items
    const textParts: string[] = [];
    for (const item of msg.item_list) {
      if (item.type === MessageItemType.TEXT && item.text_item?.text) {
        textParts.push(item.text_item.text);
      }
    }

    const content = textParts.join('\n').trim();
    if (!content) return;

    // Store context token for reply threading
    if (msg.context_token) {
      this.contextTokens.set(userId, msg.context_token);
    }

    const incoming: IncomingMessage = {
      platform: 'weixin',
      userId,
      content,
      requestId: `weixin-${msg.message_id ?? Date.now()}`,
      chatType: 'single',
      chatId: userId,
    };

    const sender = this.createSender(userId);
    await this.config.onMessage(incoming, sender);
  }

  // ── ChatResponseSender ──

  private createSender(userId: string): ChatResponseSender {
    const self = this;
    let accumulated = '';

    return {
      start() {},
      sendChunk(content: string) {
        accumulated += content;
      },
      async finish(fullContent: string) {
        const text = fullContent || accumulated;
        if (!text || !self.token) return;

        const chunks = self.splitMessage(text, MESSAGE_SPLIT_MAX_LEN);
        const contextToken = self.contextTokens.get(userId);

        for (const chunk of chunks) {
          try {
            await WeixinApi.sendMessage({
              baseUrl: self.baseUrl,
              token: self.token,
              toUserId: userId,
              text: chunk,
              contextToken,
            });
          } catch (err) {
            self.log.error(`Failed to send message to ${userId}: ${err instanceof Error ? err.message : String(err)}`);
          }
        }
      },
      async error(message: string) {
        if (!self.token) return;
        try {
          await WeixinApi.sendMessage({
            baseUrl: self.baseUrl,
            token: self.token,
            toUserId: userId,
            text: message,
          });
        } catch (err) {
          self.log.error(`Failed to send error message to ${userId}: ${err instanceof Error ? err.message : String(err)}`);
        }
      },
    };
  }

  // ── Helpers ──

  private splitMessage(text: string, maxLen: number): string[] {
    if (text.length <= maxLen) return [text];
    const chunks: string[] = [];
    for (let i = 0; i < text.length; i += maxLen) {
      chunks.push(text.substring(i, i + maxLen));
    }
    return chunks;
  }
}
