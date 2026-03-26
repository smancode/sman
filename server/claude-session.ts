import { v4 as uuidv4 } from 'uuid';
import { query, type Query, type Options, type SDKMessage, type SDKResultMessage, type SDKAssistantMessage, type SDKPartialAssistantMessage } from '@anthropic-ai/claude-agent-sdk';
import { createLogger, type Logger } from './utils/logger.js';
import type { SessionStore, Message } from './session-store.js';
import type { SmanConfig } from './types.js';
import { buildMcpServers } from './mcp-config.js';
import path from 'path';
import fs from 'fs';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);

export interface ActiveSession {
  id: string;
  workspace: string;
  label?: string;
  createdAt: string;
  lastActiveAt: string;
}

type WsSend = (data: string) => void;

interface SessionConfig {
  model: string;
  systemPromptAppend: string;
  permissionMode: 'bypassPermissions' | 'default';
}

export class ClaudeSessionManager {
  private sessions = new Map<string, ActiveSession>();
  private activeQueries = new Map<string, Query>();
  private abortControllers = new Map<string, AbortController>();
  private sdkSessionIds = new Map<string, string>(); // sessionId -> SDK session_id
  private log: Logger;
  private config: SmanConfig | null = null;

  constructor(private store: SessionStore) {
    this.log = createLogger('ClaudeSessionManager');
  }

  updateConfig(config: SmanConfig): void {
    this.config = config;
  }

  /**
   * Get the path to bundled claude-code executable
   */
  private getClaudeCodePath(): string {
    // Try to find claude-code cli.js in node_modules
    const possiblePaths: string[] = [];

    // Production: bundled in Electron app (resourcesPath is set by Electron)
    const resourcesPath = (process as any).resourcesPath;
    if (typeof resourcesPath === 'string') {
      possiblePaths.push(
        path.join(resourcesPath, 'app.asar', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js')
      );
    }

    // Development: in project node_modules
    possiblePaths.push(path.join(process.cwd(), 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js'));

    // Try require.resolve as well
    try {
      const packagePath = require.resolve('@anthropic-ai/claude-code/package.json');
      possiblePaths.push(path.join(path.dirname(packagePath), 'cli.js'));
    } catch {
      // Ignore
    }

    for (const p of possiblePaths) {
      if (fs.existsSync(p)) {
        this.log.info(`Found claude-code at: ${p}`);
        return p;
      }
    }

    this.log.warn('Could not find bundled claude-code, will use system claude command');
    return '';
  }

  private getSessionConfig(workspace: string): SessionConfig {
    let systemPromptAppend = '';
    const projectName = path.basename(workspace);
    systemPromptAppend += `\n## Project\nWorking on project: ${projectName}\nWorkspace: ${workspace}\n`;

    const model = this.config?.llm?.model || '';

    return {
      model,
      systemPromptAppend,
      permissionMode: 'bypassPermissions',
    };
  }

  private buildOptions(sessionConfig: SessionConfig, abortController: AbortController): Options {
    // Build env from config: pass API key and base URL to Claude Code subprocess
    const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
    if (this.config?.llm?.apiKey) {
      env['ANTHROPIC_API_KEY'] = this.config.llm.apiKey;
    }
    if (this.config?.llm?.baseUrl) {
      env['ANTHROPIC_BASE_URL'] = this.config.llm.baseUrl;
    }

    // Find bundled claude-code executable
    const claudeCodePath = this.getClaudeCodePath();

    const opts: Options = {
      cwd: undefined as unknown as string, // Will be set per-session
      abortController,
      permissionMode: sessionConfig.permissionMode,
      allowDangerouslySkipPermissions: true,
      model: sessionConfig.model,
      includePartialMessages: true,
      env,
      systemPrompt: {
        type: 'preset',
        preset: 'claude_code',
        append: sessionConfig.systemPromptAppend || undefined,
      },
      pathToClaudeCodeExecutable: claudeCodePath,
      // Load project settings to enable .claude/skills/ directory
      settingSources: ['project'],
    };

    if (this.config) {
      const mcpServers = buildMcpServers(this.config);
      if (Object.keys(mcpServers).length > 0) {
        opts.mcpServers = mcpServers;
      }
    }

    this.log.info(`[DEBUG] buildOptions: model=${sessionConfig.model}`);

    return opts;
  }

  private extractTextContent(message: SDKAssistantMessage): string {
    const msg = message.message;
    if (!msg?.content) return '';
    return msg.content
      .filter((block: any) => block.type === 'text')
      .map((block: any) => block.text)
      .join('');
  }

  private extractDeltaText(event: any): string | null {
    if (event.type === 'content_block_delta' && event.delta?.type === 'text_delta') {
      return event.delta.text;
    }
    return null;
  }

  createSession(workspace: string): string {
    if (!fs.existsSync(workspace)) {
      throw new Error(`Workspace does not exist: ${workspace}`);
    }

    const id = uuidv4();
    // Use workspace path as systemId for backwards compatibility with DB schema
    this.store.createSession({
      id,
      systemId: workspace,
      workspace,
    });

    const session: ActiveSession = {
      id,
      workspace,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
    };

    this.sessions.set(id, session);
    this.log.info(`Session created: ${id} for workspace ${workspace}`);
    return id;
  }

  async sendMessage(sessionId: string, content: string, wsSend: WsSend): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    if (this.activeQueries.has(sessionId)) {
      throw new Error(`Session ${sessionId} already has an active query`);
    }

    // 校验 LLM 配置
    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    // Convert /skillId args -> [触发skill:skillId] args
    let actualContent = content;
    if (content.startsWith('/')) {
      const parts = content.substring(1).split(' ');
      const skillId = parts[0];
      const args = parts.slice(1).join(' ');
      actualContent = args.trim()
        ? `[触发skill:${skillId}] ${args}`
        : `[触发skill:${skillId}]`;
      this.log.info(`Converted skill command: ${content} -> ${actualContent}`);
    }

    this.store.addMessage(sessionId, { role: 'user', content: actualContent });

    const sessionConfig = this.getSessionConfig(session.workspace);
    const abortController = new AbortController();
    this.abortControllers.set(sessionId, abortController);

    try {
      const options = this.buildOptions(sessionConfig, abortController);
      options.cwd = session.workspace;

      // Resume existing SDK session if available
      const sdkSessionId = this.sdkSessionIds.get(sessionId);
      this.log.info(`[DEBUG] sendMessage called: sessionId=${sessionId}, sdkSessionId=${sdkSessionId || 'none'}, sdkSessionIds.size=${this.sdkSessionIds.size}`);
      if (sdkSessionId) {
        options.resume = sdkSessionId;
      }

      this.log.info(`Starting query for session ${sessionId}, resume=${sdkSessionId || 'none'}, content: ${content.substring(0, 50)}...`);

      const q = query({
        prompt: actualContent,
        options,
      });

      this.activeQueries.set(sessionId, q);

      let fullContent = '';
      let messageCount = 0;
      let hasResult = false;
      wsSend(JSON.stringify({
        type: 'chat.start',
        sessionId,
      }));

      for await (const sdkMsg of q) {
        messageCount++;
        this.log.info(`Received SDK message ${messageCount}: type=${sdkMsg.type}`);

        if (abortController.signal.aborted) {
          this.log.info(`Query aborted for session ${sessionId}`);
          break;
        }

        switch (sdkMsg.type) {
          case 'assistant': {
            const text = this.extractTextContent(sdkMsg);
            this.log.info(`Assistant message: ${text.substring(0, 100)}...`);
            if (text) {
              fullContent = text;
              wsSend(JSON.stringify({
                type: 'chat.delta',
                sessionId,
                content: text,
              }));
            }
            break;
          }

          case 'stream_event': {
            const delta = this.extractDeltaText((sdkMsg as SDKPartialAssistantMessage).event);
            if (delta) {
              fullContent += delta;
              wsSend(JSON.stringify({
                type: 'chat.delta',
                sessionId,
                content: delta,
              }));
            }
            break;
          }

          case 'result': {
            hasResult = true;
            const result = sdkMsg as SDKResultMessage;
            const cost = result.total_cost_usd || 0;
            const isError = result.is_error;

            this.log.info(`Result message: isError=${isError}, session_id=${result.session_id}`);

            this.log.info(`[DEBUG] Result received: sessionId=${sessionId}, result.session_id=${result.session_id}, isError=${isError}`);

            // Save SDK session ID for resuming conversation
            if (result.session_id) {
              this.sdkSessionIds.set(sessionId, result.session_id);
              this.log.info(`[DEBUG] Saved session_id: ${result.session_id} for ${sessionId}, sdkSessionIds.size=${this.sdkSessionIds.size}`);
            }

            if (fullContent) {
              this.store.addMessage(sessionId, { role: 'assistant', content: fullContent });
            }

            wsSend(JSON.stringify({
              type: isError ? 'chat.error' : 'chat.done',
              sessionId,
              cost,
              usage: result.usage ? {
                inputTokens: result.usage.input_tokens,
                outputTokens: result.usage.output_tokens,
              } : undefined,
              ...(isError && 'errors' in result ? { error: result.errors?.join(', ') } : {}),
            }));

            this.log.info(`Query completed for session ${sessionId}, cost: $${cost.toFixed(4)}`);
            break;
          }

          default:
            this.log.info(`Ignoring message type: ${sdkMsg.type}, details: ${JSON.stringify(sdkMsg).substring(0, 200)}`);
            // Ignore system messages, user replays, etc.
            break;
        }
      }

      this.log.info(`Query loop ended for session ${sessionId}, received ${messageCount} messages, fullContent length: ${fullContent.length}`);

      // If we got no result message, send an error
      if (messageCount === 0) {
        this.log.error(`No messages received from SDK for session ${sessionId}`);
        wsSend(JSON.stringify({
          type: 'chat.error',
          sessionId,
          error: 'No response from Claude SDK',
        }));
      }
    } catch (err: any) {
      if (err?.name === 'AbortError' || abortController.signal.aborted) {
        wsSend(JSON.stringify({
          type: 'chat.aborted',
          sessionId,
        }));
        this.log.info(`Query aborted for session ${sessionId}`);
      } else {
        const errorMessage = err instanceof Error ? err.message : String(err);
        this.log.error(`Query error for session ${sessionId}`, { error: errorMessage });
        wsSend(JSON.stringify({
          type: 'chat.error',
          sessionId,
          error: errorMessage,
        }));
      }
    } finally {
      this.activeQueries.delete(sessionId);
      this.abortControllers.delete(sessionId);
    }
  }

  abort(sessionId: string): void {
    const controller = this.abortControllers.get(sessionId);
    if (controller) {
      controller.abort();
      this.abortControllers.delete(sessionId);
      this.activeQueries.delete(sessionId);
      this.log.info(`Session aborted: ${sessionId}`);
    }
  }

  listSessions(): ActiveSession[] {
    const allSessions = this.store.listSessions();
    return allSessions.map(s => {
      let active = this.sessions.get(s.id);
      if (!active) {
        active = {
          id: s.id,
          workspace: s.workspace,
          label: s.label,
          createdAt: s.createdAt,
          lastActiveAt: s.lastActiveAt,
        };
        this.sessions.set(s.id, active);
      }
      return active;
    });
  }

  getHistory(sessionId: string): Message[] {
    return this.store.getMessages(sessionId);
  }

  close(): void {
    for (const controller of this.abortControllers.values()) {
      controller.abort();
    }
    this.abortControllers.clear();
    this.activeQueries.clear();
    this.sessions.clear();
    this.sdkSessionIds.clear();
  }
}
