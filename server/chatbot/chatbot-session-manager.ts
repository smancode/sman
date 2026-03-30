import { v4 as uuidv4 } from 'uuid';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { createLogger, type Logger } from '../utils/logger.js';
import type { ClaudeSessionManager } from '../claude-session.js';
import { ChatbotStore } from './chatbot-store.js';
import { parseChatCommand } from './chat-command-parser.js';
import type { IncomingMessage, ChatResponseSender } from './types.js';

const QUERY_TIMEOUT_MS = 5 * 60 * 1000;

export class ChatbotSessionManager {
  private log: Logger;
  private store: ChatbotStore;
  private sessionManager: ClaudeSessionManager;
  private activeQueries = new Map<string, AbortController>();
  private onSessionCreated?: (sessionId: string, label: string) => void;

  constructor(
    private homeDir: string,
    sessionManager: ClaudeSessionManager,
    store: ChatbotStore,
    onSessionCreated?: (sessionId: string, label: string) => void,
  ) {
    this.log = createLogger('ChatbotSessionManager');
    this.sessionManager = sessionManager;
    this.store = store;
    this.onSessionCreated = onSessionCreated;
  }

  // ── Workspace helpers (unified with desktop sessions) ──

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
  private ensureSession(userKey: string, workspacePath: string, platform: string): void {
    let session = this.store.getSession(userKey, workspacePath);
    if (session) {
      // Verify the session still exists in the main session manager (may have been deleted via UI)
      const active = this.sessionManager.listSessions().find(s => s.id === session!.sessionId);
      if (active) return;
      // Session was deleted — clean up stale chatbot_sessions record and recreate
      this.log.info(`Session ${session.sessionId} was deleted, recreating...`);
      this.store.deleteSession(userKey, workspacePath);
    }

    const sessionId = `chatbot-${Date.now()}-${uuidv4().substring(0, 8)}`;
    this.sessionManager.createSessionWithId(workspacePath, sessionId, false);

    const platformPrefix = platform === 'wecom' ? 'WeCom' : 'Feishu';
    const userId = userKey.split(':').slice(1).join(':');
    this.sessionManager.updateSessionLabel(
      sessionId,
      `${platformPrefix}: ${userId} - ${path.basename(workspacePath)}`,
    );

    this.store.setSession(userKey, workspacePath, sessionId);

    // Notify frontend to refresh sidebar
    const label = `${platformPrefix}: ${userId} - ${path.basename(workspacePath)}`;
    this.onSessionCreated?.(sessionId, label);
  }

  // ── Message handling ──

  async handleMessage(msg: IncomingMessage, sender: ChatResponseSender): Promise<void> {
    const userKey = `${msg.platform}:${msg.userId}`;
    const parseResult = parseChatCommand(msg.content);

    if (parseResult.isCommand && parseResult.command) {
      await this.handleCommand(userKey, parseResult.command, sender);
      return;
    }

    // Auto-select first available workspace if user has none set
    let userState = this.store.getUserState(userKey);
    if (!userState?.currentWorkspace) {
      const workspaces = this.getDesktopWorkspaces();
      if (workspaces.length > 0) {
        const defaultWorkspace = workspaces[0].path;
        this.store.setUserState(userKey, defaultWorkspace);
        this.ensureSession(userKey, defaultWorkspace, msg.platform);
        userState = this.store.getUserState(userKey);
      }
    }

    if (!userState?.currentWorkspace) {
      sender.finish('暂无可用的项目目录。请先在桌面端打开一个项目。');
      return;
    }

    if (this.activeQueries.has(userKey)) {
      sender.finish('当前还有未完成的请求，请稍后再试。');
      return;
    }

    await this.executeChatQuery(userKey, userState.currentWorkspace, msg.content, sender);
  }

  // ── Commands ──

  private async handleCommand(
    userKey: string,
    command: { command: string; args: string },
    sender: ChatResponseSender,
  ): Promise<void> {
    switch (command.command) {
      case 'cd':
        await this.handleCd(userKey, command.args, sender);
        break;
      case 'pwd':
        this.handlePwd(userKey, sender);
        break;
      case 'workspaces':
        this.handleWorkspaces(sender);
        break;
      case 'help':
        this.handleHelp(sender);
        break;
      case 'status':
        this.handleStatus(userKey, sender);
        break;
      default:
        sender.finish(`未知命令: /${command.command}\n使用 /help 查看所有命令。`);
    }
  }

  private async handleCd(userKey: string, args: string, sender: ChatResponseSender): Promise<void> {
    if (!args) {
      sender.finish('用法: /cd <项目名或路径>\n例如: /cd my-project 或 /cd ~/projects/my-project');
      return;
    }

    const workspacePath = this.resolveWorkspace(args);

    if (!workspacePath) {
      const workspaces = this.getDesktopWorkspaces();
      const names = workspaces.map(w => w.name).join(', ');
      sender.finish(
        `项目 "${args}" 未找到。\n` +
        `桌面端已打开的项目: ${names || '无'}\n` +
        `提示: 支持使用 ~ 代表用户主目录，例如 /cd ~/projects/my-project`,
      );
      return;
    }

    if (!fs.existsSync(workspacePath)) {
      sender.finish(`目录不存在: ${workspacePath}`);
      return;
    }

    this.store.setUserState(userKey, workspacePath);
    this.ensureSession(userKey, workspacePath, userKey.split(':')[0]);

    const projectName = path.basename(workspacePath);
    sender.finish(`已切换到: ${projectName} (${workspacePath})`);
  }

  private handlePwd(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    if (!state?.currentWorkspace) {
      sender.finish('当前未设置工作目录。\n使用 /cd <项目名或路径> 切换工作目录。');
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
    sender.finish(`可用项目:\n${list}\n\n使用 /cd <项目名> 切换。`);
  }

  private handleHelp(sender: ChatResponseSender): void {
    sender.finish(
      `可用命令:\n` +
      `/cd <项目名或路径> - 切换工作目录 (支持 ~ 路径)\n` +
      `/pwd - 显示当前工作目录\n` +
      `/workspaces - 列出桌面端已打开的项目\n` +
      `/status - 显示连接状态\n` +
      `/help - 显示此帮助信息\n\n` +
      `直接发送消息即可与 Claude 对话。`,
    );
  }

  private handleStatus(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    const workspace = state?.currentWorkspace || '未设置';
    const active = this.activeQueries.has(userKey) ? '处理中' : '空闲';
    sender.finish(`状态: ${active}\n工作目录: ${workspace}`);
  }

  // ── Query execution ──

  private async executeChatQuery(
    userKey: string,
    workspace: string,
    content: string,
    sender: ChatResponseSender,
  ): Promise<void> {
    const session = this.store.getSession(userKey, workspace);
    if (!session) {
      // Session missing — recreate it
      try {
        this.ensureSession(userKey, workspace, userKey.split(':')[0]);
      } catch (err) {
        sender.error(`创建会话失败: ${err instanceof Error ? err.message : String(err)}`);
        return;
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
      );

      sender.finish(fullContent || '(空响应)');
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
  }
}
