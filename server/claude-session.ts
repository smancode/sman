/**
 * V2 Session Manager for Claude Agent SDK
 *
 * Manages persistent V2 SDK sessions with lifecycle control:
 * - Session reuse: keeps process alive between messages
 * - Idle timeout: 30min inactivity → close process
 * - Crash recovery: detects dead processes and recreates
 * - Resume support: persists SDK session_id for post-restart recovery
 *
 * Based on hello-halo's session-manager.ts pattern.
 */

import { unstable_v2_createSession, type SDKSession, type SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { createLogger, type Logger } from './utils/logger.js';
import type { SessionStore, Message } from './session-store.js';
import type { SmanConfig } from './types.js';
import { buildMcpServers } from './mcp-config.js';
import { createWebAccessMcpServer } from './web-access/index.js';
import type { WebAccessService } from './web-access/index.js';
import { createCapabilityGatewayMcpServer, cleanupLoadedCapabilities } from './capabilities/gateway-mcp-server.js';
import type { CapabilityRegistry } from './capabilities/registry.js';

// Chrome site discovery removed from system prompt — now served on-demand via web_access_find_url MCP tool
import { buildContentBlocks, type ContentBlock } from './utils/content-blocks.js';
import type { MediaAttachment } from './chatbot/types.js';
import { UserProfileManager } from './user-profile.js';
import path from 'path';
import fs from 'fs';

// Resolve project root for plugin loading
// dev mode (tsx): __dirname = .../smanbase/server/
// prod mode (compiled): __dirname = .../smanbase/dist/server/server/
function resolveProjectRoot(): string {
  if (fs.existsSync(path.join(__dirname, '..', 'plugins'))) {
    return path.resolve(__dirname, '..');
  }
  return path.resolve(__dirname, '..', '..', '..');
}
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export interface ActiveSession {
  id: string;
  workspace: string;
  label?: string;
  createdAt: string;
  lastActiveAt: string;
  isScanner?: boolean;
}

type WsSend = (data: string) => void;

interface V2SessionInfo {
  session: SDKSession;
  sessionId: string;       // our session ID
  workspace: string;
  createdAt: number;
  lastUsedAt: number;
  configGeneration: number;
}

export class ClaudeSessionManager {
  private sessions = new Map<string, ActiveSession>();
  private v2Sessions = new Map<string, V2SessionInfo>();
  private activeStreams = new Map<string, AbortController>();
  /** Resolves when the current stream loop for a session fully exits (finally block ran) */
  private streamDone = new Map<string, Promise<void>>();
  /** Tracks in-flight preheat promises so sendMessage can await them */
  private preheatPromises = new Map<string, Promise<void>>();
  private sdkSessionIds = new Map<string, string>();
  private log: Logger;
  private config: SmanConfig | null = null;
  private cleanupTimer: ReturnType<typeof setInterval> | null = null;
  private configGeneration = 0;
  private webAccessService: WebAccessService | null = null;
  private userProfile: UserProfileManager | null = null;
  private capabilityRegistry: CapabilityRegistry | null = null;
  /** Serializes getOrCreateV2Session calls to prevent process.chdir races */
  private v2CreateChain: Promise<void> = Promise.resolve();

  setUserProfile(manager: UserProfileManager): void {
    this.userProfile = manager;
    // Profile updates only run LLM when no sessions are actively streaming
    manager.setIdleCheck(() => this.activeStreams.size === 0);
    this.log.info('UserProfileManager injected');
  }

  private static readonly SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
  private static readonly CLEANUP_INTERVAL_MS = 60 * 1000; // 1 minute
  private static readonly STREAM_STALL_MS = 3 * 60 * 1000; // 3 minutes no data = stalled
  private static readonly TOOL_STALL_MS = 2 * 60 * 60 * 1000; // 2 hours hard limit even if process alive
  private static readonly AUTO_RETRY_ERRORS = new Set(['stall', 'process_dead', 'v2_session_lost', 'bad_request', 'server_error', 'overloaded', 'network_error']);

  constructor(private store: SessionStore) {
    this.log = createLogger('ClaudeSessionManager');
    this.startCleanup();
  }

  updateConfig(config: SmanConfig): void {
    this.config = config;
    this.configGeneration++;
  }

  setWebAccessService(service: WebAccessService): void {
    this.webAccessService = service;
    this.log.info('WebAccessService injected');
  }

  setCapabilityRegistry(registry: CapabilityRegistry): void {
    this.capabilityRegistry = registry;
    this.log.info('CapabilityRegistry injected');
  }

  /**
   * Get the path to bundled claude-code executable
   */
  private getClaudeCodePath(): string {
    const possiblePaths: string[] = [];

    // Production (Electron): try unpacked path first (asarUnpack in package.json)
    const resourcesPath = (process as any).resourcesPath;
    if (typeof resourcesPath === 'string') {
      // asarUnpack puts files in app.asar.unpacked/
      possiblePaths.push(
        path.join(resourcesPath, 'app.asar.unpacked', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js')
      );
      possiblePaths.push(
        path.join(resourcesPath, 'app.asar', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js')
      );
    }

    // Production: spawned child process — cwd = app root (set in electron/main.ts)
    possiblePaths.push(path.join(process.cwd(), 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js'));

    // Remote deploy: __dirname = /root/sman/app, node_modules is alongside compiled code
    possiblePaths.push(path.join(__dirname, 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js'));

    // Also try app.asar.unpacked relative to cwd
    possiblePaths.push(path.join(process.cwd(), '..', 'app.asar.unpacked', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js'));

    // Development: in project node_modules
    possiblePaths.push(path.join(process.cwd(), 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js'));

    // Try require.resolve
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

  private buildSessionOptions(workspace: string): Record<string, any> {
    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
    env['ANTHROPIC_API_KEY'] = this.config.llm.apiKey;
    if (this.config.llm.baseUrl) {
      env['ANTHROPIC_BASE_URL'] = this.config.llm.baseUrl;
    }

    const claudeCodePath = this.getClaudeCodePath();

    // root/sudo cannot use --dangerously-skip-permissions (claude CLI rejects it).
    // Use environment variable bypass instead.
    const isRoot = process.getuid?.() === 0;
    if (isRoot) {
      env['CLAUDE_BYPASS_PERMISSIONS'] = '1';
    }

    // Load bundled plugins — only reasoning/flow-guiding skills that need upfront context
    const pluginsDir = path.join(resolveProjectRoot(), 'plugins');
    const plugins: Array<{ type: 'local'; path: string }> = [];
    for (const name of ['superpowers', 'dev-workflow']) {
      const pluginPath = path.join(pluginsDir, name);
      if (fs.existsSync(pluginPath)) {
        plugins.push({ type: 'local', path: pluginPath });
      }
    }

    const opts: Record<string, any> = {
      model: this.config.llm.model,
      env,
      pathToClaudeCodeExecutable: claudeCodePath,
      cwd: workspace,
      // root/sudo: claude CLI rejects --permission-mode bypassPermissions.
      // Use CLAUDE_BYPASS_PERMISSIONS env var instead (set above).
      ...(isRoot ? {} : {
        permissionMode: 'bypassPermissions',
        allowDangerouslySkipPermissions: true,
      }),
      includePartialMessages: true,
      systemPrompt: {
        type: 'preset' as const,
        preset: 'claude_code',
      },
      settingSources: ['project'],
      plugins: plugins.length > 0 ? plugins : undefined,
      extraArgs: isRoot ? {} : {
        'dangerously-skip-permissions': null,
      },
    };

    // Build MCP servers if configured
    const mcpServers = buildMcpServers(this.config);
    opts.mcpServers = Object.keys(mcpServers).length > 0 ? mcpServers : {};

    // Inject web-access MCP Server (in-process)
    if (this.webAccessService) {
      const webAccessServer = createWebAccessMcpServer(this.webAccessService);
      (opts.mcpServers as any)['web-access'] = webAccessServer;
    }

    // Inject capability gateway MCP Server (in-process, always present)
    if (this.capabilityRegistry) {
      const gatewayServer = createCapabilityGatewayMcpServer({
        registry: this.capabilityRegistry,
        getActiveSession: (sid: string) => {
          const entry = this.v2Sessions.get(sid);
          return entry?.session ?? null;
        },
        pluginsDir,
      });
      (opts.mcpServers as any)['capability-gateway'] = gatewayServer;
    }

    return opts;
  }

  /**
   * Build minimal session options for scanner sessions.
   * Skips plugins, MCP servers, and web-access to keep scanner lightweight.
   */
  private buildScannerSessionOptions(workspace: string): Record<string, any> {
    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
    env['ANTHROPIC_API_KEY'] = this.config.llm.apiKey;
    if (this.config.llm.baseUrl) {
      env['ANTHROPIC_BASE_URL'] = this.config.llm.baseUrl;
    }

    const claudeCodePath = this.getClaudeCodePath();

    const isRoot = process.getuid?.() === 0;
    if (isRoot) {
      env['CLAUDE_BYPASS_PERMISSIONS'] = '1';
    }

    return {
      model: this.config.llm.model,
      env,
      pathToClaudeCodeExecutable: claudeCodePath,
      cwd: workspace,
      ...(isRoot ? {} : {
        permissionMode: 'bypassPermissions',
        allowDangerouslySkipPermissions: true,
      }),
      includePartialMessages: true,
      systemPrompt: {
        type: 'preset' as const,
        preset: 'claude_code',
      },
      settingSources: ['project'],
      extraArgs: isRoot ? {} : {
        'dangerously-skip-permissions': null,
      },
    };
  }

  /**
   * Get or create a V2 session for the given session ID
   */
  private async getOrCreateV2Session(sessionId: string): Promise<SDKSession> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    // Check if we have an existing V2 session
    const existing = this.v2Sessions.get(sessionId);
    if (existing) {
      // Check if process is still alive
      const pid = (existing.session as any).pid;
      if (pid !== undefined) {
        try {
          process.kill(pid, 0); // Signal 0 = check if alive
          existing.lastUsedAt = Date.now();
          return existing.session;
        } catch {
          this.log.info(`V2 session process dead (PID: ${pid}), recreating...`);
          this.closeV2Session(sessionId);
        }
      } else {
        // No pid info, try to use anyway
        existing.lastUsedAt = Date.now();
        return existing.session;
      }
    }

    // Serialize V2 session creation to prevent process.chdir races between concurrent calls.
    // Each call awaits the previous one before proceeding.
    let resolveChain!: () => void;
    const chainPromise = new Promise<void>(r => { resolveChain = r; });
    const previousChain = this.v2CreateChain;
    this.v2CreateChain = chainPromise;
    await previousChain;

    try {
      // Double-check after awaiting chain — another call may have created this session
      const existingAfter = this.v2Sessions.get(sessionId);
      if (existingAfter) {
        existingAfter.lastUsedAt = Date.now();
        return existingAfter.session;
      }

      // Create new V2 session
      const sessionInfo = this.sessions.get(sessionId);
      const isScanner = sessionInfo?.isScanner === true;

      const options = isScanner
        ? this.buildScannerSessionOptions(session.workspace)
        : this.buildSessionOptions(session.workspace);

      // Resume from persisted SDK session ID if available
      let sdkSessionId = this.sdkSessionIds.get(sessionId);
      if (!sdkSessionId) {
        sdkSessionId = this.store.getSdkSessionId(sessionId);
        if (sdkSessionId) {
          this.sdkSessionIds.set(sessionId, sdkSessionId);
        }
      }
      if (sdkSessionId) {
        options.resume = sdkSessionId;
        this.log.info(`Resuming V2 session with SDK session_id: ${sdkSessionId}`);
      }

      this.log.info(`Creating V2 session for ${sessionId}...`);

      // SDK's unstable_v2_createSession doesn't pass cwd to ProcessTransport,
      // so claude CLI inherits the parent process's cwd.
      // Fix: temporarily chdir to workspace before creating the session.
      const prevCwd = process.cwd();
      if (prevCwd !== session.workspace) {
        try { process.chdir(session.workspace); } catch {
          this.log.warn(`Failed to chdir to ${session.workspace}, using ${prevCwd}`);
        }
      }

      let v2Session: SDKSession;
      try {
        v2Session = await unstable_v2_createSession(options as any);
      } finally {
        if (prevCwd !== session.workspace) {
          try { process.chdir(prevCwd); } catch { /* ignore */ }
        }
      }

      const v2Info: V2SessionInfo = {
        session: v2Session,
        sessionId,
        workspace: session.workspace,
        createdAt: Date.now(),
        lastUsedAt: Date.now(),
        configGeneration: this.configGeneration,
      };
      this.v2Sessions.set(sessionId, v2Info);

      const pid = (v2Session as any).pid;
      this.log.info(`V2 session created for ${sessionId}, PID: ${pid ?? 'unknown'}`);

      return v2Session;
    } finally {
      resolveChain();
    }
  }

  closeV2Session(sessionId: string): void {
    this.preheatPromises.delete(sessionId);
    const info = this.v2Sessions.get(sessionId);
    if (info) {
      try {
        info.session.close();
      } catch {}
      this.v2Sessions.delete(sessionId);
      cleanupLoadedCapabilities(sessionId);
      this.log.info(`V2 session closed for ${sessionId}`);
    }
  }

  hasActiveStreams(): boolean {
    return this.activeStreams.size > 0;
  }

  private startCleanup(): void {
    if (this.cleanupTimer) return;
    const timer = setInterval(() => {
      const now = Date.now();
      for (const [sessionId, info] of this.v2Sessions) {
        // Don't close if there's an active stream
        if (this.activeStreams.has(sessionId)) continue;

        if (now - info.lastUsedAt > ClaudeSessionManager.SESSION_IDLE_TIMEOUT_MS) {
          this.log.info(`Closing idle V2 session ${sessionId} (idle ${Math.round((now - info.lastUsedAt) / 60000)}min)`);
          this.closeV2Session(sessionId);
        }
      }
    }, ClaudeSessionManager.CLEANUP_INTERVAL_MS);
    timer.unref(); // Don't prevent process exit
    this.cleanupTimer = timer;
  }

  // ── Public API ──

  /**
   * Preheat a session by creating the V2 process ahead of time.
   * Called when user starts typing (before actually sending a message).
   * Fire-and-forget: errors are logged but not thrown.
   */
  async preheatSession(sessionId: string): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) return;

    // Already has an active V2 session — nothing to do
    if (this.v2Sessions.has(sessionId)) {
      return;
    }

    // Already preheating — let the existing promise do the work
    if (this.preheatPromises.has(sessionId)) {
      return;
    }

    this.log.info(`Preheating V2 session for ${sessionId}...`);
    const promise = this.getOrCreateV2Session(sessionId)
      .then(() => {
        this.log.info(`V2 session preheated for ${sessionId}`);
      })
      .catch((err) => {
        this.log.warn(`Failed to preheat session ${sessionId}: ${err instanceof Error ? err.message : String(err)}`);
      })
      .finally(() => {
        this.preheatPromises.delete(sessionId);
      });
    this.preheatPromises.set(sessionId, promise);
  }

  createSession(workspace: string): string {
    if (!fs.existsSync(workspace)) {
      throw new Error(`Workspace does not exist: ${workspace}`);
    }

    const id = crypto.randomUUID();
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

  createSessionWithId(workspace: string, sessionId: string, isCron = true, isScanner = false): string {
    if (!fs.existsSync(workspace)) {
      throw new Error(`Workspace does not exist: ${workspace}`);
    }

    const existing = this.sessions.get(sessionId);
    if (existing) {
      return sessionId;
    }

    this.store.createSession({
      id: sessionId,
      systemId: workspace,
      workspace,
      isCron,
    });

    const session: ActiveSession = {
      id: sessionId,
      workspace,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
      ...(isScanner ? { isScanner: true } : {}),
    };

    this.sessions.set(sessionId, session);
    this.log.info(`Session created with custom ID: ${sessionId} for workspace ${workspace}`);
    return sessionId;
  }

  /**
   * Send a message via WebSocket (real-time streaming)
   */
  async sendMessage(sessionId: string, content: string, wsSend: WsSend, media?: MediaAttachment[], _retryCount = 0): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    // If there's an active query, abort it and wait for the stream to fully exit
    if (this.activeStreams.has(sessionId)) {
      this.log.info(`Aborting active query for session ${sessionId} to process new message`);
      this.abort(sessionId);
      // Wait for the previous stream to fully exit (finally block ran, activeStreams deleted)
      const done = this.streamDone.get(sessionId);
      if (done) await done;
    }

    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    // V2 sessions handle /slash commands natively — no conversion needed
    // Don't store auto-retry messages in the database — user didn't send them
    if (_retryCount === 0) {
      this.store.addMessage(sessionId, { role: 'user', content });
    }

    const abortController = new AbortController();
    this.activeStreams.set(sessionId, abortController);

    // Track when this stream fully exits so the next sendMessage can await it
    let streamResolve!: () => void;
    const streamPromise = new Promise<void>(r => { streamResolve = r; });
    this.streamDone.set(sessionId, streamPromise);

    let stallChecker: ReturnType<typeof setInterval> | null = null;

    // If preheat is in progress, wait for it to finish so we don't create a duplicate V2 session
    const preheatPromise = this.preheatPromises.get(sessionId);
    if (preheatPromise) {
      await preheatPromise;
    }

    // Track streamed content — declared outside try so catch can save partial state on abort
    let fullContent = '';
    let currentThinking = '';
    let allThinking = '';
    let allToolUses: Array<{ id: string; name: string; input: string }> = [];
    let currentToolUse: { id: string; name: string; input: string } | null = null;
    let stallAbortReason: string | undefined;

    try {
      const v2Session = await this.getOrCreateV2Session(sessionId);

      wsSend(JSON.stringify({
        type: 'chat.start',
        sessionId,
      }));

      // Inject user profile + Sman context into content for Claude, but don't store it
      const profilePrefix = this.userProfile?.getProfileForPrompt() ?? '';
      const workspace = session.workspace;
      const projectName = path.basename(workspace);
      const smanContext = `[Sman 身份 - 你是 Sman 智能业务系统助手，始终中文回复。用户画像身份优先。Project: ${projectName}。复杂任务建议走 dev-workflow。扩展能力按需挂载: capability_list 发现 → capability_load 激活。\n重要：收到消息后必须立刻先输出一句话说明你接下来要做什么，让用户知道你在工作，然后再开始执行。]`;
      const messagePrefix = profilePrefix
        ? `${smanContext}\n${profilePrefix}`
        : smanContext;
      const capabilities = this.config?.llm?.capabilities;

      // Build content for send(): string or SDKUserMessage
      const builtContent = buildContentBlocks(content, media, capabilities);

      if (typeof builtContent === 'string') {
        const contentWithPrefix = `${messagePrefix}\n\n${builtContent}`;
        await v2Session.send(contentWithPrefix);
      } else {
        // Content blocks array → construct SDKUserMessage
        const blocks = [{ type: 'text', text: messagePrefix }, ...builtContent];
        await v2Session.send({
          type: 'user',
          message: { role: 'user', content: blocks },
          parent_tool_use_id: null,
          session_id: sessionId,
        } as any);
      }

      let lastActivityAt = Date.now();
      let toolInProgress = false;
      let currentBlockType: 'text' | 'thinking' | 'tool_use' | 'tool_result' | null = null;
      let currentToolResultId: string | null = null;
      let currentToolResultContent = '';

      // Stall detector: abort if no data received for STREAM_STALL_MS
      // When tool/sub-agent is in progress, check if the V2 session process is still alive instead
      stallChecker = setInterval(() => {
        if (abortController.signal.aborted) {
          clearInterval(stallChecker!);
          return;
        }
        const elapsed = Date.now() - lastActivityAt;
        if (toolInProgress) {
          // Tool running — check if V2 session process is still alive
          const v2Info = this.v2Sessions.get(sessionId);
          if (!v2Info) {
            this.log.warn(`V2 session lost while tool in progress for ${sessionId}, aborting...`);
            stallAbortReason = 'v2_session_lost';
            abortController.abort();
            clearInterval(stallChecker!);
            return;
          }
          const pid = (v2Info.session as any).pid;
          if (pid !== undefined) {
            try {
              process.kill(pid, 0); // check if alive
              return; // process alive, keep waiting
            } catch {
              this.log.warn(`V2 session process dead (PID: ${pid}) while tool in progress for ${sessionId}, aborting...`);
              stallAbortReason = 'process_dead';
              abortController.abort();
              clearInterval(stallChecker!);
              return;
            }
          }
          // Process alive but exceeded hard limit — likely rate-limited/stuck
          if (elapsed > ClaudeSessionManager.TOOL_STALL_MS) {
            this.log.warn(`Tool/sub-agent hard limit reached for ${sessionId} (${Math.round(elapsed / 1000)}s), aborting...`);
            stallAbortReason = 'stall';
            abortController.abort();
            clearInterval(stallChecker!);
          }
          return;
        }
        if (elapsed > ClaudeSessionManager.STREAM_STALL_MS) {
          this.log.warn(`Stream stalled for session ${sessionId} (no data for ${Math.round(ClaudeSessionManager.STREAM_STALL_MS / 1000)}s), aborting...`);
          stallAbortReason = 'stall';
          abortController.abort();
          clearInterval(stallChecker!);
        }
      }, 30_000); // check every 30s
      stallChecker.unref();

      for await (const sdkMsg of v2Session.stream()) {
        lastActivityAt = Date.now();
        if (abortController.signal.aborted) break;

        switch (sdkMsg.type) {
          case 'assistant': {
            // New assistant turn: previous text segment is done, flush it
            toolInProgress = false;
            // Flush previous tool use
            if (currentToolUse) {
              allToolUses.push(currentToolUse);
              currentToolUse = null;
              wsSend(JSON.stringify({ type: 'chat.tool_end', sessionId }));
            }
            // Flush previous text segment so UI can freeze it
            if (fullContent.trim()) {
              wsSend(JSON.stringify({
                type: 'chat.segment',
                sessionId,
                segmentType: 'text',
              }));
              // Reset fullContent for the new turn
              fullContent = '';
            }
            // NOTE: We do NOT send text from the 'assistant' event as delta.
            // The SDK sends accumulated text here (includePartialMessages: true),
            // but we already streamed it token-by-token via 'stream_event' events.
            // Sending it again would cause duplicate content in the UI.
            // The text in this event becomes the authoritative fullContent for
            // error recovery / partial save, but UI rendering comes from stream_events.
            const text = this.extractTextContent(sdkMsg);
            if (text) {
              fullContent = text;
            }
            break;
          }

          case 'stream_event': {
            const rawEvent = (sdkMsg as any).event;
            // Track content block transitions
            if (rawEvent.type === 'content_block_start') {
              const blockType = rawEvent.content_block?.type;
              if (blockType === 'tool_result') {
                currentBlockType = 'tool_result';
                currentToolResultId = rawEvent.content_block.tool_use_id || null;
                const initialContent = typeof rawEvent.content_block.content === 'string'
                  ? rawEvent.content_block.content : '';
                currentToolResultContent = initialContent;
                if (initialContent) {
                  wsSend(JSON.stringify({
                    type: 'chat.tool_result',
                    sessionId,
                    toolUseId: currentToolResultId,
                    content: initialContent,
                  }));
                }
              } else if (blockType) {
                currentBlockType = blockType;
                currentToolResultId = null;
              }
            } else if (rawEvent.type === 'content_block_stop') {
              currentBlockType = null;
              currentToolResultId = null;
            }

            const delta = this.extractDeltaText(rawEvent);
            if (delta) {
              if (delta.type === 'text') {
                // Check if this text is inside a tool_result block
                if (currentBlockType === 'tool_result') {
                  currentToolResultContent += delta.content;
                  wsSend(JSON.stringify({
                    type: 'chat.tool_result',
                    sessionId,
                    toolUseId: currentToolResultId,
                    content: delta.content,
                  }));
                } else {
                  fullContent += delta.content;
                  wsSend(JSON.stringify({
                    type: 'chat.delta',
                    sessionId,
                    content: delta.content,
                    deltaType: 'text',
                  }));
                }
              } else if (delta.type === 'thinking') {
                currentThinking += delta.content;
                wsSend(JSON.stringify({
                  type: 'chat.delta',
                  sessionId,
                  content: delta.content,
                  deltaType: 'thinking',
                }));
              } else if (delta.type === 'tool_use') {
                toolInProgress = true;
                if (delta.name && delta.id) {
                  // Flush previous thinking/toolUse before starting new one
                  if (currentThinking.trim()) {
                    allThinking += currentThinking;
                  }
                  if (currentToolUse) {
                    allToolUses.push(currentToolUse);
                  }
                  // Freeze current text segment before tool starts
                  if (fullContent.trim()) {
                    wsSend(JSON.stringify({
                      type: 'chat.segment',
                      sessionId,
                      segmentType: 'text',
                    }));
                    fullContent = '';
                  }
                  currentToolUse = { id: delta.id, name: delta.name, input: '' };
                  wsSend(JSON.stringify({
                    type: 'chat.tool_start',
                    sessionId,
                    toolId: delta.id,
                    toolName: delta.name,
                  }));
                } else if (currentToolUse && delta.content) {
                  currentToolUse.input += delta.content;
                  wsSend(JSON.stringify({
                    type: 'chat.tool_delta',
                    sessionId,
                    toolId: currentToolUse.id,
                    content: delta.content,
                  }));
                }
              } else if (delta.type === 'tool_result') {
                // Initial content from content_block_start
                toolInProgress = true;
                currentToolResultContent += delta.content;
                wsSend(JSON.stringify({
                  type: 'chat.tool_result',
                  sessionId,
                  toolUseId: delta.id,
                  content: delta.content,
                }));
              }
            }
            break;
          }

          case 'tool_progress': {
            const progress = sdkMsg as any;
            toolInProgress = true;
            wsSend(JSON.stringify({
              type: 'chat.tool_progress',
              sessionId,
              toolUseId: progress.tool_use_id,
              toolName: progress.tool_name,
              elapsedSeconds: progress.elapsed_time_seconds,
            }));
            break;
          }

          case 'result': {
            const result = sdkMsg as any;
            const cost = result.total_cost_usd || 0;
            const isError = result.is_error;

            // Save SDK session ID
            if (result.session_id) {
              this.sdkSessionIds.set(sessionId, result.session_id);
              this.store.updateSdkSessionId(sessionId, result.session_id);
            }

            // Flush remaining thinking and tool_use
            if (currentThinking.trim()) {
              allThinking += currentThinking;
            }
            if (currentToolUse) {
              allToolUses.push(currentToolUse);
            }

            // Build contentBlocks for thinking + tool_use
            const contentBlocks: Array<{
              type: 'text' | 'thinking' | 'tool_use';
              text?: string;
              thinking?: string;
              id?: string;
              name?: string;
              input?: unknown;
            }> = [];
            if (allThinking.trim()) {
              contentBlocks.push({ type: 'thinking', thinking: allThinking.trim() });
            }
            for (const tu of allToolUses) {
              try {
                const input = JSON.parse(tu.input);
                contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input });
              } catch {
                contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input: tu.input });
              }
            }

            // Store assistant message with contentBlocks
            const finalContent = fullContent || '';
            if (finalContent || contentBlocks.length > 0) {
              this.store.addMessage(sessionId, {
                role: 'assistant',
                content: finalContent,
                contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
              });
            }

            const resultError = isError && 'errors' in result ? result.errors?.join(', ') : undefined;
            const classified = isError && resultError ? this.classifyErrorMessage(resultError) : undefined;
            wsSend(JSON.stringify({
              type: isError ? 'chat.error' : 'chat.done',
              sessionId,
              cost,
              usage: result.usage ? {
                inputTokens: result.usage.input_tokens,
                outputTokens: result.usage.output_tokens,
              } : undefined,
              ...(isError ? { error: classified?.userMessage ?? resultError ?? 'Unknown error', errorCode: classified?.errorCode, rawError: resultError } : {}),
            }));

            this.log.info(`Query completed for session ${sessionId}, cost: $${cost.toFixed(4)}`);

            // Fire-and-forget: update user profile after conversation turn
            if (this.userProfile && this.config?.llm?.userProfile !== false && !isError) {
              this.userProfile.updateProfile(content, finalContent);
            }
            break;
          }

          case 'system': {
            // Capture session_id from init message
            if ((sdkMsg as any).subtype === 'init' && (sdkMsg as any).session_id) {
              const sid = (sdkMsg as any).session_id;
              this.sdkSessionIds.set(sessionId, sid);
              this.store.updateSdkSessionId(sessionId, sid);
              this.log.info(`Captured SDK session_id: ${sid}`);
            }
            break;
          }
        }
      }
      if (stallChecker) clearInterval(stallChecker);
    } catch (err: any) {
      if (stallChecker) clearInterval(stallChecker);
      if (err?.name === 'AbortError' || abortController.signal.aborted) {
        // Save partial assistant content so the user doesn't lose what was already streamed
        this.savePartialAssistantMessage(sessionId, fullContent, allThinking, allToolUses, currentToolUse);

        // Auto-retry for recoverable errors (stall, process_dead, etc.) — max 2 retries
        const reason = stallAbortReason || '';
        if (_retryCount < 2 && ClaudeSessionManager.AUTO_RETRY_ERRORS.has(reason)) {
          this.log.info(`Auto-retrying session ${sessionId} after ${reason} (attempt ${_retryCount + 1})`);
          // Clean up the failed V2 session so a fresh one is created
          this.closeV2Session(sessionId);
          // Notify frontend that we're retrying
          wsSend(JSON.stringify({
            type: 'chat.delta',
            sessionId,
            deltaType: 'text',
            content: '\n\n[自动重试中...]\n',
          }));
          // Wait a moment before retrying — check if user sent a new message during wait
          await new Promise(r => setTimeout(r, 1000));
          if (this.activeStreams.has(sessionId)) {
            this.log.info(`User sent new message during retry wait for ${sessionId}, canceling auto-retry`);
            wsSend(JSON.stringify({ type: 'chat.aborted', sessionId, reason }));
            return;
          }
          // Retry with "继续" to pick up where we left off
          return this.sendMessage(sessionId, '继续刚才的工作，不要重复已完成的部分', wsSend, undefined, _retryCount + 1);
        }

        wsSend(JSON.stringify({
          type: 'chat.aborted',
          sessionId,
          ...(reason ? { reason } : {}),
        }));
      } else {
        const { errorCode, userMessage } = this.classifyError(err);
        const rawMessage = err instanceof Error ? err.message : String(err);
        this.log.error(`Query error for session ${sessionId}`, { error: rawMessage, errorCode });

        // Auto-retry for recoverable API errors (400, 500, overloaded, network) — max 2 retries
        if (_retryCount < 2 && ClaudeSessionManager.AUTO_RETRY_ERRORS.has(errorCode)) {
          this.log.info(`Auto-retrying session ${sessionId} after ${errorCode} (attempt ${_retryCount + 1})`);
          this.closeV2Session(sessionId);
          wsSend(JSON.stringify({
            type: 'chat.delta',
            sessionId,
            deltaType: 'text',
            content: '\n\n[遇到临时错误，自动重试中...]\n',
          }));
          await new Promise(r => setTimeout(r, 2000));
          if (this.activeStreams.has(sessionId)) {
            this.log.info(`User sent new message during retry wait for ${sessionId}, canceling auto-retry`);
            wsSend(JSON.stringify({ type: 'chat.error', sessionId, error: userMessage, errorCode, rawError: rawMessage }));
            return;
          }
          return this.sendMessage(sessionId, '继续刚才的工作，不要重复已完成的部分', wsSend, undefined, _retryCount + 1);
        }

        wsSend(JSON.stringify({ type: 'chat.error', sessionId, error: userMessage, errorCode, rawError: rawMessage }));
      }
    } finally {
      this.activeStreams.delete(sessionId);
      this.streamDone.delete(sessionId);
      streamResolve();
    }
  }

  /**
   * Classify an error from the Claude API into a user-friendly error code and message.
   */
  private classifyError(err: unknown): { errorCode: string; userMessage: string } {
    const msg = err instanceof Error ? err.message : String(err);
    return this.classifyErrorMessage(msg);
  }

  private classifyErrorMessage(msg: string): { errorCode: string; userMessage: string } {
    // HTTP status patterns
    if (/\b429\b/.test(msg) || /rate.?limit/i.test(msg) || /too many requests/i.test(msg)) {
      return { errorCode: 'rate_limit', userMessage: '请求过于频繁，请稍后再试' };
    }
    if (/\b400\b/.test(msg) || /bad request/i.test(msg)) {
      return { errorCode: 'bad_request', userMessage: '请求参数有误，请检查输入内容' };
    }
    if (/\b401\b/.test(msg) || /unauthorized/i.test(msg) || /invalid.*api.?key/i.test(msg)) {
      return { errorCode: 'auth_error', userMessage: 'API Key 无效或已过期，请在设置中检查' };
    }
    if (/\b403\b/.test(msg) || /forbidden/i.test(msg)) {
      return { errorCode: 'forbidden', userMessage: '无权访问此资源，请检查 API Key 权限' };
    }
    if (/\b404\b/.test(msg) || /not found/i.test(msg)) {
      return { errorCode: 'not_found', userMessage: '请求的资源不存在，请检查模型名称' };
    }
    if (/\b500\b/.test(msg) || /\b502\b/.test(msg) || /\b503\b/.test(msg) || /internal server error/i.test(msg) || /server error/i.test(msg)) {
      return { errorCode: 'server_error', userMessage: '模型服务暂时不可用，请稍后重试' };
    }
    if (/overloaded/i.test(msg) || /capacity/i.test(msg)) {
      return { errorCode: 'overloaded', userMessage: '模型当前负载过高，请稍后重试' };
    }
    if (/context.*(window|length|limit)/i.test(msg) || /max.*token/i.test(msg) || /too (many|long)/i.test(msg)) {
      return { errorCode: 'context_too_long', userMessage: '对话内容过长，超出模型上下文限制' };
    }
    if (/timeout/i.test(msg) || /timed? ?out/i.test(msg) || /ECONNRESET/i.test(msg) || /ECONNREFUSED/i.test(msg)) {
      return { errorCode: 'network_error', userMessage: '网络连接失败，请检查网络或模型服务地址' };
    }
    // Default
    return { errorCode: 'unknown', userMessage: msg || '未知错误' };
  }

  /**
   * Send message for cron tasks (headless execution)
   */
  async sendMessageForCron(
    sessionId: string,
    content: string,
    abortController: AbortController,
    onActivity: () => void,
  ): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    if (this.activeStreams.has(sessionId)) {
      throw new Error(`Session ${sessionId} already has an active query`);
    }

    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    this.store.addMessage(sessionId, { role: 'user', content });

    this.activeStreams.set(sessionId, abortController);

    // Stall detector for cron queries
    let lastActivityAt = Date.now();
    let toolInProgress = false;
    let stallChecker = setInterval(() => {
      if (abortController.signal.aborted) {
        clearInterval(stallChecker);
        return;
      }
      const elapsed = Date.now() - lastActivityAt;
      if (toolInProgress) {
        const v2Info = this.v2Sessions.get(sessionId);
        if (!v2Info) {
          this.log.warn(`Cron: V2 session lost while tool in progress for ${sessionId}, aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
          return;
        }
        const pid = (v2Info.session as any).pid;
        if (pid !== undefined) {
          try { process.kill(pid, 0); } catch {
            this.log.warn(`Cron: V2 session process dead (PID: ${pid}) for ${sessionId}, aborting...`);
            abortController.abort();
            clearInterval(stallChecker);
            return;
          }
        }
        if (elapsed > ClaudeSessionManager.TOOL_STALL_MS) {
          this.log.warn(`Cron: tool/sub-agent no-progress timeout for ${sessionId} (${Math.round(elapsed / 1000)}s), aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
        }
        return;
      }
      if (elapsed > ClaudeSessionManager.STREAM_STALL_MS) {
        this.log.warn(`Cron stream stalled for session ${sessionId}, aborting...`);
        abortController.abort();
        clearInterval(stallChecker);
      }
    }, 30_000);
    stallChecker.unref();

    try {
      const v2Session = await this.getOrCreateV2Session(sessionId);

      await v2Session.send(content);

      let fullContent = '';
      let msgCount = 0;

      for await (const sdkMsg of v2Session.stream()) {
        lastActivityAt = Date.now();
        msgCount++;
        if (msgCount <= 3 || msgCount % 10 === 0) {
          this.log.info(`Cron SDK message #${msgCount}: type=${sdkMsg.type}`);
        }
        onActivity();

        if (abortController.signal.aborted) break;

        switch (sdkMsg.type) {
          case 'assistant': {
            toolInProgress = false;
            const text = this.extractTextContent(sdkMsg);
            if (text) fullContent = text;
            break;
          }
          case 'stream_event': {
            const rawEvent = (sdkMsg as any).event;
            const delta = this.extractDeltaText(rawEvent);
            if (delta) {
              if (delta.type === 'text') {
                fullContent += delta.content;
              } else if (delta.type === 'tool_use') {
                toolInProgress = true;
                if (delta.name && delta.id) {
                  this.log.info(`Cron tool_start: ${delta.name} (id=${delta.id})`);
                }
              } else if (delta.type === 'tool_result') {
                this.log.info(`Cron tool_result for ${delta.id}: ${delta.content.slice(0, 100)}...`);
              }
            }
            break;
          }
          case 'result': {
            const result = sdkMsg as any;
            if (result.session_id) {
              this.sdkSessionIds.set(sessionId, result.session_id);
              this.store.updateSdkSessionId(sessionId, result.session_id);
            }
            if (fullContent) {
              this.store.addMessage(sessionId, { role: 'assistant', content: fullContent });
            }
            this.log.info(`Cron query completed for session ${sessionId}`);
            break;
          }
        }
      }
      clearInterval(stallChecker);
    } catch (err: any) {
      clearInterval(stallChecker);
      if (err?.name !== 'AbortError' && !abortController.signal.aborted) {
        throw err;
      }
    } finally {
      this.activeStreams.delete(sessionId);
    }
  }

  /**
   * Send message for chatbot (WeCom/Feishu) with streaming callback
   */
  async sendMessageForChatbot(
    sessionId: string,
    content: string,
    abortController: AbortController,
    onActivity: () => void,
    onResponse: (chunk: string) => void,
    media?: MediaAttachment[],
  ): Promise<string> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    // If there's an active stream, wait for it to fully exit to avoid stale SDK messages
    if (this.activeStreams.has(sessionId)) {
      const done = this.streamDone.get(sessionId);
      if (done) {
        this.log.info(`Chatbot: waiting for previous stream to exit on session ${sessionId}`);
        await done;
      }
    }

    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    this.store.addMessage(sessionId, { role: 'user', content });

    this.activeStreams.set(sessionId, abortController);

    // Track when this stream fully exits so the next call can await it
    let streamResolve!: () => void;
    const streamPromise = new Promise<void>(r => { streamResolve = r; });
    this.streamDone.set(sessionId, streamPromise);

    // Stall detector for chatbot queries (total timeout handled by caller)
    let lastActivityAt = Date.now();
    let toolInProgress = false;
    let stallChecker = setInterval(() => {
      if (abortController.signal.aborted) {
        clearInterval(stallChecker);
        return;
      }
      const elapsed = Date.now() - lastActivityAt;
      if (toolInProgress) {
        const v2Info = this.v2Sessions.get(sessionId);
        if (!v2Info) {
          this.log.warn(`Chatbot: V2 session lost while tool in progress for ${sessionId}, aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
          return;
        }
        const pid = (v2Info.session as any).pid;
        if (pid !== undefined) {
          try { process.kill(pid, 0); /* alive */ } catch {
            this.log.warn(`Chatbot: V2 session process dead (PID: ${pid}) for ${sessionId}, aborting...`);
            abortController.abort();
            clearInterval(stallChecker);
            return;
          }
        }
        if (elapsed > ClaudeSessionManager.TOOL_STALL_MS) {
          this.log.warn(`Chatbot: tool/sub-agent no-progress timeout for ${sessionId} (${Math.round(elapsed / 1000)}s), aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
        }
        return;
      }
      if (elapsed > ClaudeSessionManager.STREAM_STALL_MS) {
        this.log.warn(`Chatbot stream stalled for session ${sessionId}, aborting...`);
        abortController.abort();
        clearInterval(stallChecker);
      }
    }, 30_000);
    stallChecker.unref();

    try {
      const v2Session = await this.getOrCreateV2Session(sessionId);

      // Inject user profile + Sman context into content for Claude
      const profilePrefix = this.userProfile?.getProfileForPrompt() ?? '';
      const workspace = session.workspace;
      const projectName = path.basename(workspace);
      const smanContext = `[Sman 身份 - 你是 Sman 智能业务系统助手，始终中文回复。用户画像身份优先。Project: ${projectName}。复杂任务建议走 dev-workflow。扩展能力按需挂载: capability_list 发现 → capability_load 激活。\n重要：收到消息后必须立刻先输出一句话说明你接下来要做什么，让用户知道你在工作，然后再开始执行。]`;
      const messagePrefix = profilePrefix
        ? `${smanContext}\n${profilePrefix}`
        : smanContext;
      const capabilities = this.config?.llm?.capabilities;

      const builtContent = buildContentBlocks(content, media, capabilities);

      if (typeof builtContent === 'string') {
        const contentWithPrefix = `${messagePrefix}\n\n${builtContent}`;
        await v2Session.send(contentWithPrefix);
      } else {
        const blocks = [{ type: 'text', text: messagePrefix }, ...builtContent];
        await v2Session.send({
          type: 'user',
          message: { role: 'user', content: blocks },
          parent_tool_use_id: null,
          session_id: sessionId,
        } as any);
      }

      let fullContent = '';
      let msgCount = 0;

      for await (const sdkMsg of v2Session.stream()) {
        lastActivityAt = Date.now();
        msgCount++;
        if (msgCount <= 3 || msgCount % 10 === 0) {
          this.log.info(`Chatbot SDK message #${msgCount}: type=${sdkMsg.type}`);
        }

        if (abortController.signal.aborted) break;

        switch (sdkMsg.type) {
          case 'assistant': {
            toolInProgress = false;
            // The SDK sends accumulated text here (includePartialMessages: true),
            // but we already streamed it token-by-token via 'stream_event' events.
            // Do NOT overwrite fullContent here — keep the accumulated stream_event text.
            // Only use as a fallback if stream_events produced nothing.
            break;
          }
          case 'stream_event': {
            const delta = this.extractDeltaText((sdkMsg as any).event);
            if (delta) {
              if (delta.type === 'text' && delta.content !== '(no content)') {
                fullContent += delta.content;
                onResponse(delta.content);
              } else if (delta.type === 'tool_use') {
                toolInProgress = true;
              }
            }
            break;
          }
          case 'result': {
            const result = sdkMsg as any;
            if (result.session_id) {
              this.sdkSessionIds.set(sessionId, result.session_id);
              this.store.updateSdkSessionId(sessionId, result.session_id);
            }
            if (fullContent) {
              this.store.addMessage(sessionId, { role: 'assistant', content: fullContent });
            }

            // Fire-and-forget: update user profile after chatbot conversation turn
            if (this.userProfile && this.config?.llm?.userProfile !== false) {
              this.userProfile.updateProfile(content, fullContent);
            }
            break;
          }
        }
      }
      clearInterval(stallChecker);
      return fullContent;
    } catch (err: any) {
      clearInterval(stallChecker);
      if (err?.name !== 'AbortError' && !abortController.signal.aborted) {
        throw err;
      }
      return '';
    } finally {
      this.activeStreams.delete(sessionId);
      this.streamDone.delete(sessionId);
      streamResolve();
    }
  }

  // ── Utility methods ──

  private extractTextContent(message: any): string {
    const msg = message.message;
    if (!msg?.content) return '';
    return msg.content
      .filter((block: any) => block.type === 'text' && block.text !== '(no content)')
      .map((block: any) => block.text)
      .join('');
  }

  private extractDeltaText(event: any): { type: 'text' | 'thinking' | 'tool_use' | 'tool_result'; content: string; name?: string; id?: string } | null {
    if (event.type === 'content_block_delta') {
      if (event.delta?.type === 'text_delta') {
        return { type: 'text', content: event.delta.text };
      }
      if (event.delta?.type === 'thinking_delta') {
        return { type: 'thinking', content: event.delta.thinking };
      }
      if (event.delta?.type === 'input_json_delta') {
        return { type: 'tool_use', content: event.delta.partial_json };
      }
    }
    if (event.type === 'content_block_start' && event.content_block?.type === 'tool_use') {
      return {
        type: 'tool_use',
        content: '',
        name: event.content_block.name,
        id: event.content_block.id,
      };
    }
    // Tool result start
    if (event.type === 'content_block_start' && event.content_block?.type === 'tool_result') {
      const toolUseId = event.content_block.tool_use_id;
      const initialContent = typeof event.content_block.content === 'string'
        ? event.content_block.content
        : '';
      return {
        type: 'tool_result',
        content: initialContent,
        id: toolUseId,
      };
    }
    // Tool result text delta (when content is streamed)
    if (event.type === 'content_block_delta' && event.delta?.type === 'text_delta') {
      // This is already handled by text_delta above, but tool_result deltas
      // also use text_delta type. We need context to distinguish.
      // Since tool_result is always preceded by a tool_result start,
      // we track the current block type in the caller instead.
    }
    return null;
  }

  // ── Session management ──

  /**
   * Save partial assistant content when a stream is aborted mid-response.
   * Preserves whatever text/thinking/tool_use was already streamed so the user doesn't lose it.
   */
  private savePartialAssistantMessage(
    sessionId: string,
    fullContent: string,
    allThinking: string,
    allToolUses: Array<{ id: string; name: string; input: string }>,
    currentToolUse: { id: string; name: string; input: string } | null,
  ): void {
    // Flush any in-progress tool use
    if (currentToolUse) {
      allToolUses = [...allToolUses, currentToolUse];
    }

    const contentBlocks: Array<{
      type: 'text' | 'thinking' | 'tool_use';
      text?: string;
      thinking?: string;
      id?: string;
      name?: string;
      input?: unknown;
    }> = [];

    if (allThinking.trim()) {
      contentBlocks.push({ type: 'thinking', thinking: allThinking.trim() });
    }
    for (const tu of allToolUses) {
      try {
        const input = JSON.parse(tu.input);
        contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input });
      } catch {
        contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input: tu.input });
      }
    }

    const finalContent = fullContent.trim();
    if (finalContent || contentBlocks.length > 0) {
      this.store.addMessage(sessionId, {
        role: 'assistant',
        content: finalContent,
        contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
      });
      this.log.info(`Saved partial assistant message for session ${sessionId} (${finalContent.length} chars)`);
    }
  }

  abort(sessionId: string, reason?: string): void {
    const controller = this.activeStreams.get(sessionId);
    if (controller) {
      (controller as any)._abortReason = reason;
      controller.abort();
      this.activeStreams.delete(sessionId);
      this.log.info(`Session aborted: ${sessionId}${reason ? ` (${reason})` : ''}`);
    }
    // Also try to interrupt V2 session
    const info = this.v2Sessions.get(sessionId);
    if (info) {
      (info.session as any).interrupt?.()?.catch(() => {});
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
      } else {
        // Sync label from database (may have been updated by chatbot or other process)
        active.label = s.label;
      }
      return active;
    });
  }

  getHistory(sessionId: string): Array<Message & { timestamp: number; contentBlocks?: Array<{ type: 'text' | 'thinking' | 'tool_use'; text?: string; thinking?: string; id?: string; name?: string; input?: unknown }> }> {
    const messages = this.store.getMessages(sessionId);
    return messages.map(msg => ({
      ...msg,
      timestamp: new Date(msg.createdAt + 'Z').getTime(),
    }));
  }

  updateSessionLabel(sessionId: string, label: string): void {
    this.store.updateLabel(sessionId, label);
  }

  restoreSession(sessionId: string): boolean {
    const session = this.store.getSession(sessionId);
    if (!session) return false;
    // If the session exists in DB (possibly soft-deleted), restore it
    this.store.restoreSession(sessionId);
    // Also ensure it's in memory
    if (!this.sessions.has(sessionId)) {
      this.sessions.set(sessionId, {
        id: session.id,
        workspace: session.workspace,
        label: session.label,
        createdAt: session.createdAt,
        lastActiveAt: session.lastActiveAt,
      });
    }
    return true;
  }

  close(): void {
    if (this.cleanupTimer) {
      clearInterval(this.cleanupTimer);
      this.cleanupTimer = null;
    }
    for (const controller of this.activeStreams.values()) {
      controller.abort();
    }
    this.activeStreams.clear();
    for (const [sessionId] of this.v2Sessions) {
      this.closeV2Session(sessionId);
    }
    this.v2Sessions.clear();
    this.preheatPromises.clear();
    this.sessions.clear();
    this.sdkSessionIds.clear();
  }
}
