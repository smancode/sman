import { v4 as uuidv4 } from 'uuid';
import fs from 'fs';
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

  constructor(
    private homeDir: string,
    sessionManager: ClaudeSessionManager,
    store: ChatbotStore,
  ) {
    this.log = createLogger('ChatbotSessionManager');
    this.sessionManager = sessionManager;
    this.store = store;
  }

  async handleMessage(msg: IncomingMessage, sender: ChatResponseSender): Promise<void> {
    const userKey = `${msg.platform}:${msg.userId}`;
    const parseResult = parseChatCommand(msg.content);

    if (parseResult.isCommand && parseResult.command) {
      await this.handleCommand(userKey, parseResult.command, sender);
      return;
    }

    const userState = this.store.getUserState(userKey);
    if (!userState?.currentWorkspace) {
      sender.finish('尚未设置工作目录。请使用 /cd <项目名或路径> 切换工作目录。\n使用 /help 查看所有命令。');
      return;
    }

    if (this.activeQueries.has(userKey)) {
      sender.finish('当前还有未完成的请求，请稍后再试。');
      return;
    }

    await this.executeChatQuery(userKey, userState.currentWorkspace, msg.content, sender);
  }

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
      case 'add':
        this.handleAdd(command.args, sender);
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
      sender.finish('用法: /cd <项目名或路径>');
      return;
    }

    let workspacePath: string | null = null;

    if (args.startsWith('/')) {
      if (this.store.isWorkspaceRegistered(args)) {
        workspacePath = args;
      }
    } else {
      workspacePath = this.store.findWorkspace(args);
    }

    if (!workspacePath) {
      const registered = this.store.listWorkspaces().map(w => w.name).join(', ');
      sender.finish(`目录 "${args}" 未注册。\n已注册的项目: ${registered || '无'}\n使用 /add <路径> 注册新目录。`);
      return;
    }

    if (!fs.existsSync(workspacePath)) {
      sender.finish(`目录不存在: ${workspacePath}`);
      return;
    }

    this.store.setUserState(userKey, workspacePath);

    let session = this.store.getSession(userKey, workspacePath);
    if (!session) {
      const sessionId = `chatbot-${Date.now()}-${uuidv4().substring(0, 8)}`;
      try {
        this.sessionManager.createSessionWithId(workspacePath, sessionId);
        this.store.setSession(userKey, workspacePath, sessionId);
        session = this.store.getSession(userKey, workspacePath);
      } catch (err) {
        sender.finish(`创建会话失败: ${err instanceof Error ? err.message : String(err)}`);
        return;
      }
    }

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
    const workspaces = this.store.listWorkspaces();
    if (workspaces.length === 0) {
      sender.finish('暂无注册的工作目录。\n使用 /add <路径> 注册新目录。');
      return;
    }
    const list = workspaces.map((w, i) => `${i + 1}. ${w.name} (${w.path})`).join('\n');
    sender.finish(`已知工作目录:\n${list}`);
  }

  private handleAdd(args: string, sender: ChatResponseSender): void {
    if (!args) {
      sender.finish('用法: /add <目录路径>');
      return;
    }
    const wsPath = args.trim();
    if (!fs.existsSync(wsPath)) {
      sender.finish(`目录不存在: ${wsPath}`);
      return;
    }
    const name = path.basename(wsPath);
    this.store.addWorkspace(wsPath, name);
    sender.finish(`已注册: ${name} (${wsPath})`);
  }

  private handleHelp(sender: ChatResponseSender): void {
    sender.finish(
      `可用命令:\n` +
      `/cd <项目名或路径> - 切换工作目录\n` +
      `/pwd - 显示当前工作目录\n` +
      `/workspaces - 列出已知工作目录\n` +
      `/add <路径> - 注册新的工作目录\n` +
      `/status - 显示连接状态\n` +
      `/help - 显示此帮助信息\n\n` +
      `直接发送消息即可与 Claude 对话。`
    );
  }

  private handleStatus(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    const workspace = state?.currentWorkspace || '未设置';
    const active = this.activeQueries.has(userKey) ? '处理中' : '空闲';
    sender.finish(`状态: ${active}\n工作目录: ${workspace}`);
  }

  private async executeChatQuery(
    userKey: string,
    workspace: string,
    content: string,
    sender: ChatResponseSender,
  ): Promise<void> {
    const session = this.store.getSession(userKey, workspace);
    if (!session) {
      sender.error('会话不存在，请重新 /cd 到目标目录。');
      return;
    }

    const abortController = new AbortController();
    this.activeQueries.set(userKey, abortController);

    const timeout = setTimeout(() => {
      abortController.abort();
      this.log.info(`Query timeout for ${userKey}`);
    }, QUERY_TIMEOUT_MS);

    try {
      sender.start();

      const fullContent = await this.sessionManager.sendMessageForChatbot(
        session.sessionId,
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
