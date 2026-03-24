import { v4 as uuidv4 } from 'uuid';
import { query, type Query, type Options, type SDKMessage, type SDKResultMessage, type SDKAssistantMessage, type SDKPartialAssistantMessage } from '@anthropic-ai/claude-agent-sdk';
import { createLogger, type Logger } from './utils/logger.js';
import type { SessionStore, Message } from './session-store.js';
import type { SkillsRegistry } from './skills-registry.js';
import type { ProfileManager } from './profile-manager.js';
import type { SmanConfig } from './types.js';
import { buildMcpServers } from './mcp-config.js';

export interface ActiveSession {
  id: string;
  systemId: string;
  workspace: string;
  createdAt: string;
  lastActiveAt: string;
}

type WsSend = (data: string) => void;

interface SessionConfig {
  model: string;
  systemPromptAppend: string;
  skillDirs: string[];
  permissionMode: 'bypassPermissions' | 'default';
}

export class ClaudeSessionManager {
  private sessions = new Map<string, ActiveSession>();
  private activeQueries = new Map<string, Query>();
  private abortControllers = new Map<string, AbortController>();
  private log: Logger;
  private config: SmanConfig | null = null;

  constructor(
    private store: SessionStore,
    private skillsRegistry: SkillsRegistry,
    private profileManager: ProfileManager,
  ) {
    this.log = createLogger('ClaudeSessionManager');
  }

  updateConfig(config: SmanConfig): void {
    this.config = config;
  }

  private getSessionConfig(systemId: string): SessionConfig {
    const profile = this.profileManager.getProfile(systemId);
    if (!profile) throw new Error(`Profile not found: ${systemId}`);

    const skillDirs = this.skillsRegistry.getSkillDirs(profile.skills);

    let systemPromptAppend = '';
    if (profile.description) {
      systemPromptAppend += `\n## Business System\n${profile.name}: ${profile.description}\n`;
    }
    if (skillDirs.length > 0) {
      systemPromptAppend += `\n## Available Skills\nSkills from these directories are available: ${skillDirs.join(', ')}\n`;
    }
    if (profile.claudeMdTemplate) {
      systemPromptAppend += `\n${profile.claudeMdTemplate}\n`;
    }

    const model = this.config?.llm?.model || 'claude-sonnet-4-6';

    return {
      model,
      systemPromptAppend,
      skillDirs,
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
    };

    if (sessionConfig.skillDirs.length > 0) {
      opts.additionalDirectories = sessionConfig.skillDirs;
    }

    if (this.config) {
      const mcpServers = buildMcpServers(this.config);
      if (Object.keys(mcpServers).length > 0) {
        opts.mcpServers = mcpServers;
      }
    }

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

  createSession(systemId: string): string {
    const profile = this.profileManager.getProfile(systemId);
    if (!profile) {
      throw new Error(`Profile not found: ${systemId}`);
    }

    const id = uuidv4();
    this.store.createSession({
      id,
      systemId,
      workspace: profile.workspace,
    });

    const session: ActiveSession = {
      id,
      systemId,
      workspace: profile.workspace,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
    };

    this.sessions.set(id, session);
    this.log.info(`Session created: ${id} for system ${systemId}`);
    return id;
  }

  async sendMessage(sessionId: string, content: string, wsSend: WsSend): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    if (this.activeQueries.has(sessionId)) {
      throw new Error(`Session ${sessionId} already has an active query`);
    }

    this.store.addMessage(sessionId, { role: 'user', content });

    const sessionConfig = this.getSessionConfig(session.systemId);
    const abortController = new AbortController();
    this.abortControllers.set(sessionId, abortController);

    try {
      const options = this.buildOptions(sessionConfig, abortController);
      options.cwd = session.workspace;

      const q = query({
        prompt: content,
        options,
      });

      this.activeQueries.set(sessionId, q);

      let fullContent = '';
      wsSend(JSON.stringify({
        type: 'chat.start',
        sessionId,
      }));

      for await (const sdkMsg of q) {
        if (abortController.signal.aborted) {
          this.log.info(`Query aborted for session ${sessionId}`);
          break;
        }

        switch (sdkMsg.type) {
          case 'assistant': {
            const text = this.extractTextContent(sdkMsg);
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
            const result = sdkMsg as SDKResultMessage;
            const cost = result.total_cost_usd || 0;
            const isError = result.is_error;

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
            // Ignore system messages, user replays, etc.
            break;
        }
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

  listSessions(systemId?: string): ActiveSession[] {
    const allSessions = this.store.listSessions(systemId);
    return allSessions.map(s => {
      let active = this.sessions.get(s.id);
      if (!active) {
        active = {
          id: s.id,
          systemId: s.systemId,
          workspace: s.workspace,
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
  }
}
