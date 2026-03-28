import * as Lark from '@larksuiteoapi/node-sdk';
import { createLogger, type Logger } from '../utils/logger.js';
import type { IncomingMessage, ChatResponseSender } from './types.js';

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
    const messageType = message.message_type;

    if (messageType !== 'text') return;

    let content: string;
    try {
      const parsed = JSON.parse(message.content);
      content = parsed.text?.trim() || '';
    } catch {
      content = message.content || '';
    }

    if (!content || !userId) return;

    const incoming: IncomingMessage = {
      platform: 'feishu',
      userId,
      content,
      requestId: message.message_id || `feishu-${Date.now()}`,
      chatType: chatType as 'p2p' | 'group',
      chatId,
    };

    const sender = this.createSender(chatId);
    await this.config.onMessage(incoming, sender);
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
