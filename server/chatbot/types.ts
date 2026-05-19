export interface WeComBotProfile {
  id: string;
  label: string;
  botId: string;
  secret: string;
  mode: 'full' | 'query' | 'collect';
  workspace: string;
  allowedSkills: string[];
  enabled: boolean;
  collectPrompt?: string;
}

export interface ChatbotConfig {
  enabled: boolean;
  wecom: {
    enabled: boolean;
    bots: WeComBotProfile[];
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
  sendThinking(content: string): void;
  sendToolStatus(toolName: string, status: 'start' | 'end'): void;
  finish(fullContent: string): void;
  error(message: string): void;
}

export interface CommandResult {
  command: 'cd' | 'pwd' | 'workspaces' | 'add' | 'help' | 'status' | 'new';
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
  chatType: 'single' | 'group' | 'p2p';
  createdAt: string;
  lastActiveAt: string;
}

export interface ChatbotWorkspace {
  path: string;
  name: string;
  addedAt: string;
}

export interface MediaAttachment {
  type: 'image' | 'audio' | 'video' | 'document';
  fileName?: string;
  mimeType: string;
  base64Data: string;
  transcription?: string;
}

export interface IncomingMessage {
  platform: 'wecom' | 'feishu' | 'weixin';
  userId: string;
  content: string;
  requestId: string;
  chatType: 'single' | 'group' | 'p2p';
  chatId: string;
  media?: MediaAttachment[];
  botProfileId: string;
}
