import { v4 as uuidv4 } from 'uuid';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { createLogger, type Logger } from '../utils/logger.js';
import type { ClaudeSessionManager } from '../claude-session.js';
import { ChatbotStore } from './chatbot-store.js';
import { parseChatCommand } from './chat-command-parser.js';
import type { IncomingMessage, ChatResponseSender, WeComBotProfile } from './types.js';

const QUERY_TIMEOUT_MS = 5 * 60 * 1000;

export class ChatbotSessionManager {
  private log: Logger;
  private store: ChatbotStore;
  private sessionManager: ClaudeSessionManager;
  private activeQueries = new Map<string, AbortController>();
  private onSessionCreated?: (sessionId: string, label: string) => void;
  private activeBotCount = 0;
  private maxBotConcurrency = 2;
  private botQueue: Array<{ userKey: string; sender: ChatResponseSender; execute: () => Promise<void> }> = [];
  private getBotProfile: (botProfileId: string) => WeComBotProfile | undefined;

  constructor(
    private homeDir: string,
    sessionManager: ClaudeSessionManager,
    store: ChatbotStore,
    getBotProfile: (botProfileId: string) => WeComBotProfile | undefined,
    onSessionCreated?: (sessionId: string, label: string) => void,
  ) {
    this.log = createLogger('ChatbotSessionManager');
    this.sessionManager = sessionManager;
    this.store = store;
    this.getBotProfile = getBotProfile;
    this.onSessionCreated = onSessionCreated;
  }

  // ── Workspace helpers (unified with desktop sessions) ──

  /** Build session label for chatbot platforms */
  private buildLabel(platform: string, userKey: string, workspacePath: string): string {
    const now = new Date();
    const ts = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}${String(now.getSeconds()).padStart(2, '0')}`;
    switch (platform) {
      case 'wecom': {
        const userId = userKey.split(':').slice(1).join(':');
        return `WeCom:${userId} ${ts}`;
      }
      case 'feishu': {
        const userId = userKey.split(':').slice(1).join(':');
        return `Feishu:${userId} ${ts}`;
      }
      case 'weixin':
      default:
        return `Weixin ${ts}`;
    }
  }

  /** Expand ~ to home directory */
  private expandPath(p: string): string {
    if (p.startsWith('~/')) {
      return path.join(os.homedir(), p.slice(2));
    }
    return p;
  }

  /** Get all unique workspaces from desktop sessions */
  private getDesktopWorkspaces(): Array<{ path: string; name: string }> {
    const sessions = this.sessionManager.listSessions();
    const seen = new Set<string>();
    const workspaces: Array<{ path: string; name: string }> = [];
    for (const s of sessions) {
      if (s.workspace && !seen.has(s.workspace)) {
        seen.add(s.workspace);
        workspaces.push({ path: s.workspace, name: path.basename(s.workspace) });
      }
    }
    return workspaces;
  }

  /** Find workspace by name substring or path */
  private resolveWorkspace(query: string): string | null {
    const expanded = this.expandPath(query.trim());

    // Exact path match from desktop sessions
    if (expanded.startsWith('/')) {
      const ws = this.getDesktopWorkspaces().find(w => w.path === expanded);
      if (ws) return ws.path;
    }

    // Name match from desktop sessions
    const workspaces = this.getDesktopWorkspaces();
    const exact = workspaces.find(w => w.name === query.trim());
    if (exact) return exact.path;
    const partial = workspaces.find(w => w.name.toLowerCase().includes(query.trim().toLowerCase()));
    if (partial) return partial.path;

    // Last resort: expanded path exists on disk
    if (expanded.startsWith('/') && fs.existsSync(expanded)) {
      return expanded;
    }

    return null;
  }

  /** Ensure a chatbot session exists for this userKey+workspace */
  private ensureSession(userKey: string, workspacePath: string, platform: string, botProfile: WeComBotProfile): void {
    let session = this.store.getSession(userKey, workspacePath);
    if (session) {
      // Try to restore soft-deleted session
      const restored = this.sessionManager.restoreSession(session.sessionId);
      // Verify the session still exists
      const active = this.sessionManager.listSessions().find(s => s.id === session!.sessionId);
      if (active) {
        // Notify frontend to refresh sidebar (session may have been restored from soft-delete)
        if (restored) {
          this.onSessionCreated?.(session.sessionId, active.label || '');
        }
        return;
      }
      // Session was hard-deleted — clean up stale chatbot_sessions record and recreate
      this.log.info(`Session ${session.sessionId} was deleted, recreating...`);
      this.store.deleteSession(userKey, workspacePath);
    }

    const sessionId = `chatbot-${botProfile.id}-${Date.now()}-${uuidv4().substring(0, 8)}`;
    this.sessionManager.createSessionWithId(workspacePath, sessionId, false);

    const label = this.buildLabel(platform, userKey, workspacePath);
    this.sessionManager.updateSessionLabel(sessionId, label);

    this.store.setSession(userKey, workspacePath, sessionId, botProfile.label);

    // Notify frontend to refresh sidebar
    this.onSessionCreated?.(sessionId, label);
  }

  // ── Message handling ──

  async handleMessage(msg: IncomingMessage, sender: ChatResponseSender): Promise<void> {
    const botProfile = this.getBotProfile(msg.botProfileId);
    if (!botProfile) {
      sender.finish('Bot 配置未找到，请联系管理员。');
      return;
    }

    const userKey = `${msg.platform}:${botProfile.id}:${msg.userId}`;
    const mode = botProfile.mode;

    const parseResult = parseChatCommand(msg.content);

    if (parseResult.isCommand) {
      if (parseResult.command) {
        await this.handleCommand(userKey, parseResult.command, sender, mode, botProfile);
      } else {
        this.handleHelp(sender, true, mode);
      }
      return;
    }

    // Resolve workspace based on mode
    let workspace: string;
    if (mode === 'query') {
      workspace = botProfile.workspace;
      if (!workspace) {
        sender.finish('Bot 未绑定项目目录，请联系管理员配置。');
        return;
      }
    } else if (mode === 'collect') {
      workspace = path.join(os.homedir(), '.sman', 'iterate');
      this.ensureIterateDir(workspace);
    } else {
      let userState = this.store.getUserState(userKey);
      if (!userState?.currentWorkspace) {
        const workspaces = this.getDesktopWorkspaces();
        if (workspaces.length > 0) {
          workspace = workspaces[0].path;
          this.store.setUserState(userKey, workspace);
        } else {
          sender.finish('暂无可用的项目目录。请先在桌面端打开一个项目。');
          return;
        }
      } else {
        workspace = userState.currentWorkspace;
      }
    }

    // Reject if this user already has an active query
    if (this.activeQueries.has(userKey)) {
      sender.finish('你有一条消息正在处理中，请稍后再试。');
      return;
    }

    // Global bot concurrency control
    if (this.activeBotCount >= this.maxBotConcurrency) {
      const queuePos = this.botQueue.length + 1;
      sender.sendChunk(`当前有 ${this.activeBotCount} 个请求在处理中，你排在第 ${queuePos} 位，请稍候...`);
      return new Promise<void>((resolve) => {
        this.botQueue.push({
          userKey,
          sender,
          execute: async () => {
            sender.sendChunk('开始处理你的问题...');
            await this.executeChatQuery(userKey, workspace, msg.content, sender, msg.media, mode, botProfile);
            resolve();
          },
        });
      });
    }

    this.activeBotCount++;
    try {
      await this.executeChatQuery(userKey, workspace, msg.content, sender, msg.media, mode, botProfile);
    } finally {
      this.activeBotCount--;
      this.processQueue();
    }
  }

  private processQueue(): void {
    while (this.botQueue.length > 0 && this.activeBotCount < this.maxBotConcurrency) {
      const next = this.botQueue.shift()!;
      if (this.activeQueries.has(next.userKey)) {
        next.sender.finish('你有一条消息正在处理中，跳过排队消息。');
        continue;
      }
      this.activeBotCount++;
      next.execute().finally(() => {
        this.activeBotCount--;
        this.processQueue();
      });
    }
  }

  // ── Commands ──

  private async handleCommand(
    userKey: string,
    command: { command: string; args: string; rawCommand: string },
    sender: ChatResponseSender,
    mode: 'full' | 'query' | 'collect',
    botProfile: WeComBotProfile,
  ): Promise<void> {
    const isRestricted = mode === 'query' || mode === 'collect';

    switch (command.command) {
      case 'cd':
        if (isRestricted) {
          sender.finish(mode === 'collect'
            ? '当前为反馈收集模式，不支持切换项目。'
            : '当前为只读答疑模式，不支持切换项目。');
          return;
        }
        await this.handleCd(userKey, command.args, sender);
        break;
      case 'workspaces':
        if (isRestricted) {
          sender.finish(mode === 'collect'
            ? '反馈收集模式，无需切换项目。'
            : `当前绑定项目: ${path.basename(botProfile.workspace)}\n(只读模式，不支持切换)`);
          return;
        }
        this.handleWorkspaces(sender);
        break;
      case 'pwd':
        if (isRestricted) {
          sender.finish(mode === 'collect'
            ? '反馈收集模式，自动记录到 ~/.sman/iterate/'
            : `当前项目: ${botProfile.workspace}`);
        } else {
          this.handlePwd(userKey, sender);
        }
        break;
      case 'help':
        this.handleHelp(sender, false, mode);
        break;
      case 'status':
        this.handleStatus(userKey, sender);
        break;
      case 'new':
        this.handleNew(userKey, sender);
        break;
      default:
        sender.finish(`未知命令: //${command.rawCommand}\n使用 //help 查看所有命令。`);
    }
  }

  private async handleCd(userKey: string, args: string, sender: ChatResponseSender): Promise<void> {
    if (!args) {
      sender.finish('用法: //cd <项目名或路径>\n例如: //cd my-project 或 //cd ~/projects/my-project');
      return;
    }

    // Support numeric index: //cd 1
    const numericIndex = parseInt(args, 10);
    let workspacePath: string | null;
    if (!isNaN(numericIndex) && numericIndex > 0) {
      const workspaces = this.getDesktopWorkspaces();
      if (numericIndex > workspaces.length) {
        const list = workspaces.map((w, i) => `${i + 1}. ${w.name} (${w.path})`).join('\n');
        sender.finish(`序号 ${numericIndex} 超出范围。\n可用项目:\n${list}\n\n使用 //cd <项目名> or <数字> 切换。`);
        return;
      }
      workspacePath = workspaces[numericIndex - 1].path;
    } else {
      workspacePath = this.resolveWorkspace(args);
    }

    if (!workspacePath) {
      const workspaces = this.getDesktopWorkspaces();
      const names = workspaces.map(w => w.name).join(', ');
      sender.finish(
        `项目 "${args}" 未找到。\n` +
        `桌面端已打开的项目: ${names || '无'}\n` +
        `提示: 支持使用 ~ 代表用户主目录，例如 //cd ~/projects/my-project`,
      );
      return;
    }

    if (!fs.existsSync(workspacePath)) {
      sender.finish(`目录不存在: ${workspacePath}`);
      return;
    }

    this.store.setUserState(userKey, workspacePath);
    const parts = userKey.split(':');
    const botProfileId = parts.length >= 2 ? parts[1] : '';
    const botProfile = this.getBotProfile(botProfileId);
    if (botProfile) {
      this.ensureSession(userKey, workspacePath, parts[0], botProfile);
    }

    const projectName = path.basename(workspacePath);
    sender.finish(`已切换到: ${projectName} (${workspacePath})`);
  }

  private handlePwd(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    if (!state?.currentWorkspace) {
      sender.finish('当前未设置工作目录。\n使用 //cd <项目名或路径> 切换工作目录。');
      return;
    }
    sender.finish(`当前工作目录: ${state.currentWorkspace}`);
  }

  private handleWorkspaces(sender: ChatResponseSender): void {
    const workspaces = this.getDesktopWorkspaces();
    if (workspaces.length === 0) {
      sender.finish('暂无可用的项目目录。请先在桌面端打开一个项目。');
      return;
    }
    const list = workspaces.map((w, i) => `${i + 1}. ${w.name} (${w.path})`).join('\n');
    sender.finish(`可用项目:\n${list}\n\n使用 //cd <项目名>  or <数字> 切换。`);
  }

  private handleHelp(sender: ChatResponseSender, showUnknownHint = false, mode: 'full' | 'query' | 'collect' = 'full'): void {
    const prefix = showUnknownHint ? '未知命令，' : '';
    if (mode === 'collect') {
      sender.finish(
        `${prefix}可用命令:\n` +
        `//new - 新建会话，清空上下文重新开始\n` +
        `//help - 显示此帮助信息\n` +
        `//status - 显示当前状态\n\n` +
        `直接发送消息即可反馈问题或建议。`,
      );
    } else if (mode === 'query') {
      sender.finish(
        `${prefix}可用命令:\n` +
        `//new - 新建会话，清空上下文重新开始\n` +
        `//help - 显示此帮助信息\n` +
        `//status - 显示当前状态\n\n` +
        `直接发送消息即可提问。`,
      );
    } else {
      sender.finish(
        `${prefix}可用系统命令:\n` +
        `//workspaces  or  //wss - 列出桌面端已打开的项目\n` +
        `//cd <项目名或路径> - 切换工作目录 (支持 ~ 路径)\n` +
        `//pwd - 显示当前工作目录\n` +
        `//new - 新建会话，清空上下文重新开始\n` +
        `//help - 显示此帮助信息\n\n` +
        `直接发送消息开启对话。`,
      );
    }
  }

  private handleStatus(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    const workspace = state?.currentWorkspace || '未设置';
    const active = this.activeQueries.has(userKey) ? '处理中' : '空闲';
    sender.finish(`状态: ${active}\n工作目录: ${workspace}`);
  }

  private handleNew(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    if (!state?.currentWorkspace) {
      sender.finish('当前未设置工作目录，无需新建会话。');
      return;
    }

    const workspace = state.currentWorkspace;
    const oldSession = this.store.getSession(userKey, workspace);

    // Close old V2 session process to release SDK context
    if (oldSession?.sessionId) {
      this.sessionManager.abort(oldSession.sessionId);
      this.sessionManager.closeV2Session(oldSession.sessionId);
    }

    // Create a new session (ensureSession will overwrite the old mapping)
    this.store.deleteSession(userKey, workspace);

    const parts = userKey.split(':');
    const botProfileId = parts[1];
    const botProfile = this.getBotProfile(botProfileId);
    if (!botProfile) {
      sender.finish('Bot 配置已变更，无法新建会话。请联系管理员。');
      return;
    }
    this.ensureSession(userKey, workspace, parts[0], botProfile);

    const projectName = path.basename(workspace);
    sender.finish(`已新建会话: ${projectName}\n旧会话的聊天记录仍可在桌面端查看。`);
  }

  // ── Query execution ──

  private async executeChatQuery(
    userKey: string,
    workspace: string,
    content: string,
    sender: ChatResponseSender,
    media?: import('./types.js').MediaAttachment[],
    mode: 'full' | 'query' | 'collect' = 'full',
    botProfile?: WeComBotProfile,
  ): Promise<void> {
    // In query/collect mode, check if workspace changed and clean up stale sessions
    let workspaceChanged = false;
    if (mode === 'query' || mode === 'collect') {
      const staleSessions = this.store.getSessionsByUserKey(userKey);
      for (const s of staleSessions) {
        if (s.workspace !== workspace) {
          this.log.info(`Bot workspace changed for ${userKey}: ${s.workspace} -> ${workspace}, cleaning up old session ${s.sessionId}`);
          this.sessionManager.abort(s.sessionId);
          this.sessionManager.closeV2Session(s.sessionId);
          this.store.deleteSession(userKey, s.workspace);
          workspaceChanged = true;
        }
      }
    }

    const session = this.store.getSession(userKey, workspace);
    if (!session) {
      // Session missing — recreate it
      try {
        this.ensureSession(userKey, workspace, userKey.split(':')[0], botProfile!);
      } catch (err) {
        sender.error(`创建会话失败: ${err instanceof Error ? err.message : String(err)}`);
        return;
      }
      if (workspaceChanged) {
        sender.sendChunk(`[系统提示: Bot 绑定项目已变更，上下文已重置，新项目: ${path.basename(workspace)}]\n\n`);
      }
    }

    const sessionId = this.store.getSession(userKey, workspace)!.sessionId;
    const abortController = new AbortController();
    this.activeQueries.set(userKey, abortController);

    const timeout = setTimeout(() => {
      abortController.abort();
      this.log.info(`Query timeout for ${userKey}`);
    }, QUERY_TIMEOUT_MS);

    try {
      sender.start();

      const fullContent = await this.sessionManager.sendMessageForChatbot(
        sessionId,
        content,
        abortController,
        () => {},
        (chunk) => sender.sendChunk(chunk),
        media,
        (chunk) => sender.sendThinking(chunk),
        (toolName, status) => sender.sendToolStatus(toolName, status),
        mode,
        botProfile?.allowedSkills,
      );

      // Strip all "(no content)" placeholders from the final content
      const cleaned = fullContent.replace(/\(no content\)/g, '').trim();
      sender.finish(cleaned || '(空响应)');
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      if (abortController.signal.aborted) {
        sender.error('请求超时，请稍后再试。');
      } else {
        sender.error(`处理失败: ${message}`);
      }
    } finally {
      clearTimeout(timeout);
      this.activeQueries.delete(userKey);
    }
  }

  stop(): void {
    for (const [, controller] of this.activeQueries) {
      controller.abort();
    }
    this.activeQueries.clear();
    this.botQueue = [];
    this.activeBotCount = 0;
  }

  // ── Iterate (collect mode) helpers ──

  private ensureIterateDir(iterateDir: string): void {
    if (!fs.existsSync(iterateDir)) {
      fs.mkdirSync(iterateDir, { recursive: true });
    }
    const claudeMd = path.join(iterateDir, 'CLAUDE.md');
    if (!fs.existsSync(claudeMd)) {
      fs.writeFileSync(claudeMd, `# 反馈收集助手

你是一个专门的反馈收集助手。你的职责是：

1. 以简洁、温暖、略带幽默的方式回复用户
2. 倾听用户的问题反馈、投诉、建议等
3. 根据对话内容，判断是否需要记录为反馈条目
4. 如果是有效反馈（非闲聊/问候），追加到当天的反馈文件中

## 反馈文件格式

文件名: YYYY-MM-DD-iter.md（如 2026-05-09-iter.md）
路径: ~/.sman/iterate/

## 追加格式

\`\`\`markdown
## 问题反馈 / 投诉 / 建议 / 其他分类

- **用户**: 企微用户
- **内容**: 用户的反馈内容摘要
- **时间**: YYYY-MM-DD HH:mm:ss

---

\`\`\`

## 规则

- 每条反馈用 \`##\` 标题分类
- 同一天的所有反馈追加到同一个文件
- 只记录有实质内容的反馈，忽略纯寒暄
- 用户的原话尽量保留，可以适当概括但不改原意
- 追加后记得保存文件
`, 'utf-8');
      this.log.info(`Created iterate CLAUDE.md at ${claudeMd}`);
    }
  }
}
