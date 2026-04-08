import WebSocket from 'ws';
import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from '../utils/logger.js';
import type { IncomingMessage, ChatResponseSender, MediaAttachment } from './types.js';
import { downloadAndDecrypt, wecomMsgtypeToMediaType } from './wecom-media.js';

interface WeComBotConfig {
  botId: string;
  secret: string;
  onMessage: (msg: IncomingMessage, sender: ChatResponseSender) => Promise<void>;
}

const WECOM_WS_URL = 'wss://openws.work.weixin.qq.com';
const HEARTBEAT_INTERVAL_MS = 30_000;
const RECONNECT_MAX_ATTEMPTS = 100;
const RECONNECT_BASE_DELAY_MS = 1000;
// WeCom rate limit: 30 messages/min per conversation.
// Throttle stream updates to avoid hitting the limit.
const STREAM_THROTTLE_MS = 1000;

export class WeComBotConnection {
  private log: Logger;
  private ws: WebSocket | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectAttempts = 0;
  private stopped = false;
  private config: WeComBotConfig;

  constructor(config: WeComBotConfig) {
    this.config = config;
    this.log = createLogger('WeComBot');
  }

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.cleanup();
  }

  private connect(): void {
    if (this.stopped) return;

    this.log.info(`Connecting to WeCom: ${WECOM_WS_URL}`);
    this.ws = new WebSocket(WECOM_WS_URL);

    this.ws.on('open', () => {
      this.log.info('WebSocket connected, sending subscribe...');
      this.subscribe();
      this.startHeartbeat();
      this.reconnectAttempts = 0;
    });

    this.ws.on('message', (data) => {
      this.handleRawMessage(data.toString());
    });

    this.ws.on('close', (code, reason) => {
      this.log.info(`WebSocket closed: code=${code}, reason=${reason.toString()}`);
      this.stopHeartbeat();
      this.scheduleReconnect();
    });

    this.ws.on('error', (err) => {
      this.log.error('WebSocket error', { error: err.message });
    });
  }

  private subscribe(): void {
    this.send({
      cmd: 'aibot_subscribe',
      headers: { req_id: uuidv4() },
      body: {
        bot_id: this.config.botId,
        secret: this.config.secret,
      },
    });
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      this.send({ cmd: 'ping', headers: { req_id: uuidv4() } });
    }, HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    if (this.reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
      this.log.error('Max reconnect attempts reached, giving up');
      return;
    }
    const delay = Math.min(
      RECONNECT_BASE_DELAY_MS * Math.pow(2, this.reconnectAttempts),
      60000,
    );
    this.reconnectAttempts++;
    this.log.info(`Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
    setTimeout(() => this.connect(), delay);
  }

  private async handleRawMessage(raw: string): Promise<void> {
    let msg: any;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }

    const cmd = msg.cmd;

    if (cmd === 'aibot_msg_callback') {
      await this.handleMsgCallback(msg);
    } else if (cmd === 'aibot_event_callback') {
      this.handleEventCallback(msg);
    } else if (msg.errcode !== undefined) {
      if (msg.errcode === 0) {
        this.log.info(`Command response OK`);
      } else {
        this.log.error(`Command error: ${msg.errcode} ${msg.errmsg}`);
      }
    }
  }

  private async handleMsgCallback(msg: any): Promise<void> {
    const userId = msg.body?.from?.userid;
    const msgtype: string = msg.body?.msgtype || 'text';
    const chatType = msg.body?.chattype === 'group' ? 'group' : 'single';
    const chatId = msg.body?.chatid || userId;
    const requestId = msg.headers?.req_id;

    if (!userId) return;

    let content = '';
    let media: MediaAttachment[] | undefined;

    try {
      switch (msgtype) {
        case 'text': {
          const rawContent = msg.body?.text?.content || '';
          content = rawContent.replace(/@[^\s]+\s?/g, '').trim();
          break;
        }

        case 'image': {
          const imageUrl = msg.body?.image?.url;
          const aesKey = msg.body?.image?.aeskey;
          if (imageUrl && aesKey) {
            const { buffer, mimeType } = await downloadAndDecrypt(imageUrl, aesKey);
            media = [{
              type: 'image',
              mimeType,
              base64Data: buffer.toString('base64'),
            }];
            content = '[图片]';
          }
          break;
        }

        case 'voice': {
          // WeCom voice messages include pre-transcribed text in voice.content
          const voiceContent = msg.body?.voice?.content;
          if (voiceContent) {
            content = String(voiceContent).replace(/@[^\s]+\s?/g, '').trim();
          }
          // Also download the audio file for transcription fallback
          const voiceUrl = msg.body?.voice?.url;
          const voiceAesKey = msg.body?.voice?.aeskey;
          if (voiceUrl && voiceAesKey) {
            const { buffer, mimeType } = await downloadAndDecrypt(voiceUrl, voiceAesKey);
            media = [{
              type: 'audio',
              mimeType,
              base64Data: buffer.toString('base64'),
              transcription: content || undefined,
            }];
          }
          if (!content) content = '[语音消息]';
          break;
        }

        case 'file': {
          const fileUrl = msg.body?.file?.url;
          const fileAesKey = msg.body?.file?.aeskey;
          const fileName = msg.body?.file?.filename || 'unknown';
          if (fileUrl && fileAesKey) {
            const { buffer, mimeType } = await downloadAndDecrypt(fileUrl, fileAesKey);
            media = [{
              type: 'document',
              fileName,
              mimeType,
              base64Data: buffer.toString('base64'),
            }];
            content = `[文件: ${fileName}]`;
          }
          break;
        }

        case 'video': {
          const videoUrl = msg.body?.video?.url;
          const videoAesKey = msg.body?.video?.aeskey;
          if (videoUrl && videoAesKey) {
            const { buffer, mimeType } = await downloadAndDecrypt(videoUrl, videoAesKey);
            media = [{
              type: 'video',
              mimeType,
              base64Data: buffer.toString('base64'),
            }];
            content = '[视频]';
          }
          break;
        }

        case 'mixed': {
          // Mixed messages contain multiple items (text + image etc.)
          const items: Array<any> = msg.body?.mixed?.items || [];
          const textParts: string[] = [];
          const mediaList: MediaAttachment[] = [];

          for (const item of items) {
            const itemType: string = item.msgtype;
            if (itemType === 'text' && item.text?.content) {
              textParts.push(String(item.text.content).replace(/@[^\s]+\s?/g, '').trim());
            } else if (itemType === 'image' && item.image?.url && item.image?.aeskey) {
              try {
                const { buffer, mimeType } = await downloadAndDecrypt(item.image.url, item.image.aeskey);
                mediaList.push({
                  type: 'image',
                  mimeType,
                  base64Data: buffer.toString('base64'),
                });
              } catch (err) {
                this.log.warn('Failed to download mixed image item', { error: String(err) });
              }
            }
          }

          content = textParts.join('\n').trim();
          if (mediaList.length > 0) {
            media = mediaList;
            if (!content) content = '[图文消息]';
          }
          if (!content && mediaList.length === 0) return;
          break;
        }

        default:
          this.log.info(`Unsupported WeCom msgtype: ${msgtype}`);
          content = `[不支持的消息类型: ${msgtype}]`;
      }
    } catch (err) {
      this.log.error(`Failed to process WeCom ${msgtype} message`, { error: String(err) });
      content = content || `[消息处理失败: ${msgtype}]`;
    }

    if (!content && (!media || media.length === 0)) return;

    const incoming: IncomingMessage = {
      platform: 'wecom',
      userId,
      content: content || ' ',
      requestId: requestId || uuidv4(),
      chatType: chatType as 'single' | 'group',
      chatId: chatId || userId,
      media,
    };

    const sender = this.createSender(requestId);
    await this.config.onMessage(incoming, sender);
  }

  private handleEventCallback(msg: any): void {
    const eventType = msg.body?.event?.eventtype;
    const requestId = msg.headers?.req_id;

    if (eventType === 'enter_chat') {
      this.send({
        cmd: 'aibot_respond_welcome_msg',
        headers: { req_id: requestId },
        body: {
          msgtype: 'text',
          text: { content: '欢迎使用 Sman，输入 //help 查看可用命令。' },
        },
      });
    }
  }

  private createSender(requestId?: string): ChatResponseSender {
    const reqId = requestId || uuidv4();
    const streamId = `stream-${Date.now()}-${uuidv4().substring(0, 8)}`;
    let started = false;
    const self = this;

    let accumulated = '';
    let throttleTimer: ReturnType<typeof setTimeout> | null = null;
    let pendingContent = '';

    const flushStream = () => {
      if (!pendingContent) return;
      self.send({
        cmd: 'aibot_respond_msg',
        headers: { req_id: reqId },
        body: {
          msgtype: 'stream',
          stream: { id: streamId, finish: false, content: pendingContent },
        },
      });
      throttleTimer = null;
    };

    return {
      start() { started = true; },
      sendChunk(content: string) {
        if (!started) return;
        accumulated += content;
        pendingContent = accumulated;
        if (!throttleTimer) {
          throttleTimer = setTimeout(flushStream, STREAM_THROTTLE_MS);
        }
      },
      finish(fullContent: string) {
        // Cancel pending throttle and flush immediately
        if (throttleTimer) {
          clearTimeout(throttleTimer);
          throttleTimer = null;
        }
        if (started) {
          self.send({
            cmd: 'aibot_respond_msg',
            headers: { req_id: reqId },
            body: {
              msgtype: 'stream',
              stream: { id: streamId, finish: true, content: fullContent },
            },
          });
        } else {
          self.send({
            cmd: 'aibot_respond_msg',
            headers: { req_id: reqId },
            body: {
              msgtype: 'markdown',
              markdown: { content: fullContent },
            },
          });
        }
      },
      error(message: string) {
        if (throttleTimer) {
          clearTimeout(throttleTimer);
          throttleTimer = null;
        }
        self.send({
          cmd: 'aibot_respond_msg',
          headers: { req_id: reqId },
          body: {
            msgtype: 'text',
            text: { content: message },
          },
        });
      },
    };
  }

  private send(data: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    }
  }

  private cleanup(): void {
    this.stopHeartbeat();
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}
