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
const IDLE_TIMEOUT_MINUTES = 15;
const IDLE_CHECK_INTERVAL_MS = 60 * 1000;

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
  private idleCheckTimer: ReturnType<typeof setInterval> | null = null;

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

  startIdleCheck(): void {
    if (this.idleCheckTimer) return;
    this.idleCheckTimer = setInterval(() => this.checkIdleSessions(), IDLE_CHECK_INTERVAL_MS);
    this.log.info(`Idle check started (interval=${IDLE_CHECK_INTERVAL_MS}ms, timeout=${IDLE_TIMEOUT_MINUTES}min)`);
  }

  private checkIdleSessions(): void {
    const idleSessions = this.store.getIdleSessions(IDLE_TIMEOUT_MINUTES);
    for (const session of idleSessions) {
      const parts = session.userKey.split(':');
      if (parts.length < 3) continue;
      const platform = parts[0];
      const botProfileId = parts[1];

      const botProfile = this.getBotProfile(botProfileId);
      if (!botProfile || botProfile.mode !== 'query') continue;

      if (this.activeQueries.has(session.userKey)) continue;

      this.log.info(`Idle timeout for ${session.userKey} (last active: ${session.lastActiveAt})`);

      this.resetSession(session.userKey, session.workspace, platform, botProfile);
    }
  }

  private resetSession(userKey: string, workspace: string, platform: string, botProfile: WeComBotProfile): void {
    const session = this.store.getSession(userKey, workspace);
    if (session?.sessionId) {
      this.sessionManager.abort(session.sessionId);
      this.sessionManager.closeV2Session(session.sessionId);
      this.sessionManager.softDeleteSession(session.sessionId);
    }
    this.store.deleteSession(userKey, workspace);
    this.ensureSession(userKey, workspace, platform, botProfile);
    this.store.setIdleReset(userKey, workspace);
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
  private ensureSession(userKey: string, workspacePath: string, platform: string, botProfile: WeComBotProfile, chatType?: 'single' | 'group' | 'p2p'): void {
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
      // Preserve chatType from old session when resetting
      if (!chatType) chatType = session.chatType;
      this.store.deleteSession(userKey, workspacePath);
    }

    const sessionId = `chatbot-${botProfile.id}-${Date.now()}-${uuidv4().substring(0, 8)}`;
    this.sessionManager.createSessionWithId(workspacePath, sessionId, false);

    const label = this.buildLabel(platform, userKey, workspacePath);
    this.sessionManager.updateSessionLabel(sessionId, label);

    this.store.setSession(userKey, workspacePath, sessionId, botProfile.label, chatType);

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

    // Notify user if their previous session was idle-reset
    if (mode === 'query' && this.store.consumeIdleReset(userKey)) {
      sender.sendChunk('[系统提示: 上次会话因长时间未响应已自动结束，当前为新会话]\n\n');
    }

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
      workspace = path.join(os.homedir(), '.sman', 'iterate', botProfile.id);
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
            await this.executeChatQuery(userKey, workspace, msg.content, sender, msg.media, mode, botProfile, msg.chatType);
            resolve();
          },
        });
      });
    }

    this.activeBotCount++;
    try {
      await this.executeChatQuery(userKey, workspace, msg.content, sender, msg.media, mode, botProfile, msg.chatType);
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
        this.handleNew(userKey, sender, mode, botProfile);
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

  private handleNew(userKey: string, sender: ChatResponseSender, mode?: 'full' | 'query' | 'collect', botProfile?: WeComBotProfile): void {
    // Resolve workspace the same way as handleMessage
    let workspace: string | undefined;
    if (mode === 'query' && botProfile?.workspace) {
      workspace = botProfile.workspace;
    } else if (mode === 'collect' && botProfile) {
      workspace = path.join(os.homedir(), '.sman', 'iterate', botProfile.id);
    } else {
      workspace = this.store.getUserState(userKey)?.currentWorkspace;
    }

    if (!workspace) {
      sender.finish('当前未设置工作目录，无需新建会话。');
      return;
    }

    const oldSession = this.store.getSession(userKey, workspace);

    // Close old V2 session process to release SDK context
    if (oldSession?.sessionId) {
      this.sessionManager.abort(oldSession.sessionId);
      this.sessionManager.closeV2Session(oldSession.sessionId);
      this.sessionManager.softDeleteSession(oldSession.sessionId);
    }

    const oldChatType = oldSession?.chatType;

    // Create a new session (ensureSession will overwrite the old mapping)
    this.store.deleteSession(userKey, workspace);

    const parts = userKey.split(':');
    const platform = parts[0];
    const botProfileId = parts[1];
    const resolvedProfile = botProfile ?? this.getBotProfile(botProfileId);
    if (!resolvedProfile) {
      sender.finish('Bot 配置已变更，无法新建会话。请联系管理员。');
      return;
    }
    this.ensureSession(userKey, workspace, platform, resolvedProfile, oldChatType);

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
    chatType?: 'single' | 'group' | 'p2p',
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
        this.ensureSession(userKey, workspace, userKey.split(':')[0], botProfile!, chatType);
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
    this.store.touchSession(userKey, workspace);

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
        botProfile?.label,
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
    if (this.idleCheckTimer) {
      clearInterval(this.idleCheckTimer);
      this.idleCheckTimer = null;
    }
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
  }

  /** Regenerate iterate CLAUDE.md for a specific bot (called on every config save with collect-mode bot) */
  ensureIterateClaudeMd(botProfileId: string, collectPrompt?: string): void {
    const iterateDir = path.join(os.homedir(), '.sman', 'iterate', botProfileId);
    this.ensureIterateDir(iterateDir);
    const claudeMd = path.join(iterateDir, 'CLAUDE.md');

    // If user provided custom prompt, use it directly as CLAUDE.md
    if (collectPrompt?.trim()) {
      fs.writeFileSync(claudeMd, collectPrompt.trim(), 'utf-8');
      this.log.info(`Regenerated iterate CLAUDE.md with custom prompt at ${claudeMd}`);
      return;
    }
    fs.writeFileSync(claudeMd, `# 反馈收集助手

你是一个反馈收集助手。

## 回复风格

- 简短自然，像个正常同事聊天，温暖但不啰嗦
- 欢迎用户提意见，真诚感谢，但别说"很高兴为您服务"这种客服话术
- 不要主动追问、不要嘘寒问暖、不要画蛇添足
- 用户说了有效反馈，比如"感谢反馈，我记下了 👍"
- 功能已存在就说一句怎么用，简洁明了

## 职责

1. 判断用户提到的功能是否已存在（参考下方产品能力）
2. 如果是有效反馈（非闲聊/问候），追加到当天的反馈文件
3. 不要记录无实质内容的消息

## 重要：判断已有功能

在记录反馈之前，你必须先判断用户说的功能/问题是否已经在 Sman 中实现。
- 如果功能已存在：告诉用户怎么用，不需要记录
- 如果功能不存在或体验有问题：先回复用户，然后记录

## Sman 产品能力（面向用户）

### 四端交互
- **桌面端** (Electron): 选择项目目录开始对话，支持 Windows / macOS / Linux
- **企业微信 Bot**: 在企微群/私聊中 @bot 对话，支持多个 Bot 绑定不同业务系统
- **飞书 Bot**: 飞书中直接与 bot 对话
- **微信 Bot**: 个人微信扫码登录后与 bot 对话

### 核心功能
- **AI 对话**: 基于 Claude，支持多轮对话、流式输出、图片/文件/PDF/音频/视频输入
- **代码编写与修改**: AI 可以直接在项目目录中读写代码、执行命令、搜索文件
- **代码查看器**: 聊天界面右侧集成，文件树浏览、代码高亮、符号搜索、在线编辑保存
- **Git 面板**: 聊天界面右侧集成，查看状态、Diff 对比、提交推送、切换分支
- **定时任务 (Cron)**: Cron 表达式驱动的自动化任务，绑定项目 + Skill 定时执行
- **地球路径 (SmartPath)**: 多步骤自动化工作流，逐步骤执行，前一步结果作为下一步上下文
- **协作星图 (Stardom)**: 多 Agent 协作网络，仪表盘 + 像素世界可视化
- **知识库**: 每个项目自动提取业务知识、开发规范、技术文档（.sman/knowledge/）

### Bot 模式（企微）
- **完全权限 Bot**: 可以执行所有操作，包括写代码、执行命令、切换项目
- **只读答疑 Bot**: 绑定单个项目，只能查阅代码和文档回答问题，不能修改
- **反馈收集 Bot**: 专门收集用户反馈、问题、建议，自动按日期记录到文件

### 其他能力
- **Web 搜索**: 支持 Brave / Tavily / Bing / 百度搜索
- **Web 浏览**: 内置浏览器工具，可以导航网页、截图、点击、填写表单
- **MCP Servers**: 支持加载外部 MCP 工具服务器扩展能力
- **Skills**: 项目级和全局级技能（.claude/skills/），可被定时任务和 Bot 调用
- **多语言**: 支持中文/英文界面切换
- **用户画像**: 自动学习用户偏好，个性化回复风格

## 反馈文件格式

文件名: YYYY-MM-DD-iter.md（如 2026-05-09-iter.md）
路径: ~/.sman/iterate/

## 追加格式

\`\`\`markdown
## 问题反馈 / 投诉 / 建议 / 功能请求 / 其他分类

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
- 已有功能的问题记为"问题反馈"，新需求记为"功能请求"
- 追加后记得保存文件
`, 'utf-8');
    this.log.info(`Regenerated iterate CLAUDE.md at ${claudeMd}`);
  }

  /** Regenerate bot prompt files in ~/.sman/bot-prompts/ */
  ensureBotPrompts(): void {
    const promptDir = path.join(os.homedir(), '.sman', 'bot-prompts');
    if (!fs.existsSync(promptDir)) {
      fs.mkdirSync(promptDir, { recursive: true });
    }

    fs.writeFileSync(path.join(promptDir, 'query.md'), `你是 {{botName}}，一个只读答疑助手，绑定项目: {{projectName}}。

## 规则
- 你可以查阅代码和文档来回答问题，但不能修改任何文件
- 你可用的技能: {{skillList}}
- 如果用户要求修改文件或执行命令，告知只有查询权限
- 不要输出 //help 等命令提示
- 如果用户问你是谁，你的名字是 {{botName}}

## 回复风格
- 直接回答问题，不要铺垫和总结
- 只回答用户问的，不要主动扩展到无关话题
- 除非用户追问，否则不要补充"你可能还想了解XXX"
- 代码片段只贴关键部分，不要贴整个文件
- 简洁高效，能一句话说清的不要写一段
`, 'utf-8');

    fs.writeFileSync(path.join(promptDir, 'collect.md'), `你是一个反馈收集助手。

## 规则
- 倾听用户反馈，简短自然地回复
- 将有价值的反馈追加到当天的文件中: {{workspace}}/YYYY-MM-DD-iter.md
- 严格遵循 CLAUDE.md 中的格式追加反馈
- 不要输出 //help 等命令提示
`, 'utf-8');

    this.log.info(`Regenerated bot prompts in ${promptDir}`);
  }
}
