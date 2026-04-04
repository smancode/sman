import * as Lark from '@larksuiteoapi/node-sdk';
import { createLogger, type Logger } from '../utils/logger.js';
import type { IncomingMessage, ChatResponseSender, MediaAttachment } from './types.js';

interface FeishuBotConfig {
  appId: string;
  appSecret: string;
  onMessage: (msg: IncomingMessage, sender: ChatResponseSender) => Promise<void>;
}

export class FeishuBotConnection {
  private log: Logger;
  private client: Lark.Client;
  private wsClient: Lark.WSClient | null = null;
  private config: FeishuBotConfig;

  constructor(config: FeishuBotConfig) {
    this.config = config;
    this.log = createLogger('FeishuBot');
    this.client = new Lark.Client({
      appId: config.appId,
      appSecret: config.appSecret,
    });
  }

  start(): void {
    this.log.info('Starting Feishu bot long connection...');

    this.wsClient = new Lark.WSClient({
      appId: this.config.appId,
      appSecret: this.config.appSecret,
      loggerLevel: Lark.LoggerLevel.info,
    });

    this.wsClient.start({
      eventDispatcher: new Lark.EventDispatcher({}).register({
        'im.message.receive_v1': async (data: any) => {
          await this.handleMessage(data);
        },
      }),
    });

    this.log.info('Feishu bot connected');
  }

  stop(): void {
    this.wsClient = null;
    this.log.info('Feishu bot stopped');
  }

  private async handleMessage(data: any): Promise<void> {
    const message = data.message;
    if (!message) return;

    const userId = message.sender?.sender_id?.open_id || message.sender?.sender_id?.user_id;
    const chatType = message.chat_type === 'p2p' ? 'p2p' : 'group';
    const chatId = message.chat_id;
    const messageType: string = message.message_type;

    if (!userId) return;

    let content = '';
    let media: MediaAttachment[] | undefined;

    try {
      switch (messageType) {
        case 'text': {
          try {
            const parsed = JSON.parse(message.content);
            content = parsed.text?.trim() || '';
          } catch {
            content = message.content || '';
          }
          break;
        }

        case 'image': {
          const imageKey = this.extractFileKey(message.content, 'image_key');
          if (imageKey) {
            const attachment = await this.downloadFeishuFile(message.message_id, imageKey, 'image');
            if (attachment) {
              media = [attachment];
              content = '[图片]';
            }
          }
          if (!content) content = '[图片]';
          break;
        }

        case 'audio': {
          const fileKey = this.extractFileKey(message.content, 'file_key');
          if (fileKey) {
            const attachment = await this.downloadFeishuFile(message.message_id, fileKey, 'audio');
            if (attachment) {
              media = [attachment];
            }
          }
          content = '[语音消息]';
          break;
        }

        case 'file': {
          const fileKey = this.extractFileKey(message.content, 'file_key');
          const fileName = this.extractFileKey(message.content, 'file_name') || 'unknown';
          if (fileKey) {
            const attachment = await this.downloadFeishuFile(message.message_id, fileKey, 'document', fileName);
            if (attachment) {
              media = [attachment];
            }
          }
          content = `[文件: ${fileName}]`;
          break;
        }

        case 'video': {
          const fileKey = this.extractFileKey(message.content, 'file_key');
          if (fileKey) {
            const attachment = await this.downloadFeishuFile(message.message_id, fileKey, 'video');
            if (attachment) {
              media = [attachment];
            }
          }
          content = '[视频]';
          break;
        }

        default:
          this.log.info(`Unsupported Feishu msgtype: ${messageType}`);
          content = `[不支持的消息类型: ${messageType}]`;
      }
    } catch (err) {
      this.log.error(`Failed to process Feishu ${messageType} message`, { error: String(err) });
      content = content || `[消息处理失败: ${messageType}]`;
    }

    if (!content && (!media || media.length === 0)) return;

    const incoming: IncomingMessage = {
      platform: 'feishu',
      userId,
      content: content || ' ',
      requestId: message.message_id || `feishu-${Date.now()}`,
      chatType: chatType as 'p2p' | 'group',
      chatId,
      media,
    };

    const sender = this.createSender(chatId);
    await this.config.onMessage(incoming, sender);
  }

  /**
   * Extract a field from Feishu message content JSON.
   * Content format: {"text": "..."} or {"image_key": "..."} etc.
   */
  private extractFileKey(contentStr: string, field: string): string | undefined {
    try {
      const parsed = JSON.parse(contentStr);
      return parsed[field] || undefined;
    } catch {
      return undefined;
    }
  }

  /**
   * Download a file from Feishu API and convert to MediaAttachment.
   */
  private async downloadFeishuFile(
    messageId: string,
    fileKey: string,
    type: 'image' | 'audio' | 'video' | 'document',
    fileName?: string,
  ): Promise<MediaAttachment | null> {
    try {
      const resp = await this.client.im.messageResource.get({
        path: { message_id: messageId, file_key: fileKey },
        params: { type: 'file' },
      });

      if (!resp?.getReadableStream) {
        this.log.warn(`No stream returned for file_key=${fileKey}`);
        return null;
      }

      const chunks: Buffer[] = [];
      const stream = resp.getReadableStream();
      for await (const chunk of stream) {
        chunks.push(Buffer.from(chunk as Buffer));
      }
      const buffer = Buffer.concat(chunks);

      const mimeType = this.detectMimeTypeFromBuffer(buffer, type);

      return {
        type,
        fileName,
        mimeType,
        base64Data: buffer.toString('base64'),
      };
    } catch (err) {
      this.log.error(`Failed to download Feishu file ${fileKey}`, { error: String(err) });
      return null;
    }
  }

  private detectMimeTypeFromBuffer(buf: Buffer, fallbackType: string): string {
    if (buf.length < 4) return 'application/octet-stream';

    if (buf[0] === 0x89 && buf[1] === 0x50) return 'image/png';
    if (buf[0] === 0xff && buf[1] === 0xd8) return 'image/jpeg';
    if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'image/gif';
    if (buf[0] === 0x52 && buf[1] === 0x49 && buf.length >= 12 && buf[8] === 0x57 && buf[9] === 0x45 && buf[10] === 0x42 && buf[11] === 0x50) return 'image/webp';
    if (buf[0] === 0x25 && buf[1] === 0x50 && buf[2] === 0x44 && buf[3] === 0x46) return 'application/pdf';
    if (buf.length >= 8 && buf[4] === 0x66 && buf[5] === 0x74 && buf[6] === 0x79 && buf[7] === 0x70) return 'video/mp4';

    // Fallback by type
    switch (fallbackType) {
      case 'image': return 'image/png';
      case 'audio': return 'audio/amr';
      case 'video': return 'video/mp4';
      default: return 'application/octet-stream';
    }
  }

  private createSender(chatId: string): ChatResponseSender {
    let accumulated = '';
    const self = this;

    return {
      start() {},
      sendChunk(content: string) {
        accumulated += content;
      },
      async finish(fullContent: string) {
        const text = fullContent || accumulated;
        if (!text) return;

        const chunks = self.splitMessage(text, 3900);
        for (const chunk of chunks) {
          try {
            await self.client.im.message.create({
              params: { receive_id_type: 'chat_id' },
              data: {
                receive_id: chatId,
                msg_type: 'text',
                content: JSON.stringify({ text: chunk }),
              },
            });
          } catch (err) {
            self.log.error('Failed to send Feishu message', { error: err });
          }
        }
      },
      async error(message: string) {
        try {
          await self.client.im.message.create({
            params: { receive_id_type: 'chat_id' },
            data: {
              receive_id: chatId,
              msg_type: 'text',
              content: JSON.stringify({ text: message }),
            },
          });
        } catch (err) {
          self.log.error('Failed to send Feishu error message', { error: err });
        }
      },
    };
  }

  private splitMessage(text: string, maxLen: number): string[] {
    if (text.length <= maxLen) return [text];
    const chunks: string[] = [];
    for (let i = 0; i < text.length; i += maxLen) {
      chunks.push(text.substring(i, i + maxLen));
    }
    return chunks;
  }
}
