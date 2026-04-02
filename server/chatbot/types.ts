export interface ChatbotConfig {
  enabled: boolean;
  wecom: {
    enabled: boolean;
    botId: string;
    secret: string;
  };
  feishu: {
    enabled: boolean;
    appId: string;
    appSecret: string;
  };
  weixin: {
    enabled: boolean;
  };
}

export interface ChatResponseSender {
  start(): void;
  sendChunk(content: string): void;
  finish(fullContent: string): void;
  error(message: string): void;
}

export interface CommandResult {
  command: 'cd' | 'pwd' | 'workspaces' | 'add' | 'help' | 'status';
  args: string;
  rawCommand: string;
}

export interface ParseResult {
  isCommand: boolean;
  command?: CommandResult;
}

export interface ChatbotUserState {
  currentWorkspace: string;
  lastActiveAt: string;
}

export interface ChatbotSession {
  sessionId: string;
  sdkSessionId?: string;
  createdAt: string;
  lastActiveAt: string;
}

export interface ChatbotWorkspace {
  path: string;
  name: string;
  addedAt: string;
}

export interface IncomingMessage {
  platform: 'wecom' | 'feishu' | 'weixin';
  userId: string;
  content: string;
  requestId: string;
  chatType: 'single' | 'group' | 'p2p';
  chatId: string;
}
