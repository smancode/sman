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

import { unstable_v2_createSession, type SDKSession, type SDKMessage, type McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import { createLogger, type Logger } from './utils/logger.js';
import type { SessionStore, Message } from './session-store.js';
import type { SmanConfig } from './types.js';
import { buildMcpServers } from './mcp-config.js';
import { createWebAccessMcpServer } from './web-access/index.js';
import type { WebAccessService } from './web-access/index.js';
import { createCapabilityGatewayMcpServer, cleanupLoadedCapabilities } from './capabilities/gateway-mcp-server.js';
import type { CapabilityRegistry } from './capabilities/registry.js';
import { createBraveSearchMcpServer } from './web-search/brave-mcp-server.js';
import { createTavilySearchMcpServer } from './web-search/tavily-mcp-server.js';
import { createWebSearchMcpServer, isAnthropicFirstParty } from './web-search/mcp-server.js';
import { createBaiduSearchMcpServer } from './web-search/baidu-mcp-server.js';

// Chrome site discovery removed from system prompt — now served on-demand via web_access_find_url MCP tool
import { buildContentBlocks, type ContentBlock } from './utils/content-blocks.js';
import type { MediaAttachment } from './chatbot/types.js';
import { UserProfileManager } from './user-profile.js';
import path from 'path';
import crypto from 'crypto';
import fs from 'fs';

import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Normalize workspace path to canonical form.
 * Uses realpathSync to resolve symlinks and get OS-level canonical casing.
 * On Windows: resolves UNC paths, drive letter case, 8.3 short names.
 * On macOS: resolves /var → /private/var, /tmp → /private/tmp.
 */
export function normalizeWorkspacePath(workspace: string): string {
  const resolved = path.resolve(workspace);
  if (!fs.existsSync(resolved)) {
    throw new Error(`Workspace does not exist: ${resolved}`);
  }
  try {
    return fs.realpathSync(resolved);
  } catch {
    return resolved;
  }
}

// Resolve project root for plugin loading
// dev mode (tsx): __dirname = .../smanbase/server/
// prod mode (compiled, non-asar): __dirname = .../smanbase/dist/server/server/
// prod mode (Electron asar): __dirname = .../app.asar/dist/server/server/
//   plugins are unpacked to: .../app.asar.unpacked/plugins/
//   IMPORTANT: Electron patches fs to read inside asar, but claude CLI subprocess cannot.
//   So we MUST resolve to app.asar.unpacked/ path for plugins.
function resolveProjectRoot(): string {
  // 1. Direct relative (dev mode: __dirname = server/ → root has plugins/)
  if (fs.existsSync(path.join(__dirname, '..', 'plugins'))) {
    return path.resolve(__dirname, '..');
  }

  // 2. Electron asar: __dirname contains "app.asar"
  //    The asar path works for our Node process (Electron patches fs),
  //    but claude CLI subprocess cannot read inside asar.
  //    Must redirect to app.asar.unpacked/ for real filesystem access.
  if (__dirname.includes('app.asar')) {
    const unpackedRoot = __dirname.replace('app.asar', 'app.asar.unpacked');
    if (fs.existsSync(path.join(unpackedRoot, '..', '..', '..', 'plugins'))) {
      return path.resolve(unpackedRoot, '..', '..', '..');
    }
  }

  // 3. Compiled dist layout (non-asar): __dirname = dist/server/server/ → root = ../../..
  if (fs.existsSync(path.join(__dirname, '..', '..', '..', 'plugins'))) {
    return path.resolve(__dirname, '..', '..', '..');
  }

  // 4. Fallback — return best guess and let caller handle missing plugins
  return path.resolve(__dirname, '..', '..', '..');
}

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

/** dev-workflow pipeline steps (1-based, matching SKILL.md) */
type WorkflowStep = 1 | 2 | 3 | 4 | 5 | 6;

/** Tracks dev-workflow progress per session */
interface WorkflowState {
  currentStep: WorkflowStep | null;
  active: boolean;
  completedSteps: WorkflowStep[];
  stepEnteredAt: number;
  startedAt: number | null;
  taskSummary: string;
}

export class ClaudeSessionManager {
  private sessions = new Map<string, ActiveSession>();
  private v2Sessions = new Map<string, V2SessionInfo>();
  private activeStreams = new Map<string, AbortController>();
  /** Timestamp of the last activeStreams mutation (set/delete/clear) */
  private lastStreamActivityAt = 0;

  private markStreamStart(sessionId: string, controller: AbortController): void {
    this.activeStreams.set(sessionId, controller);
    this.lastStreamActivityAt = Date.now();
  }

  private markStreamEnd(sessionId: string): void {
    this.activeStreams.delete(sessionId);
    this.lastStreamActivityAt = Date.now();
  }

  private markAllStreamsCleared(): void {
    this.activeStreams.clear();
    this.lastStreamActivityAt = Date.now();
  }
  /** Resolves when the current stream loop for a session fully exits (finally block ran) */
  private streamDone = new Map<string, Promise<void>>();
  /** Active stream() generators — used to call interrupt() on abort to unblock the for-await loop */
  private activeQueries = new Map<string, AsyncGenerator<unknown, void>>();
  /** Tracks in-flight preheat promises so sendMessage can await them */
  private preheatPromises = new Map<string, Promise<void>>();
  private sdkSessionIds = new Map<string, string>();
  /** dev-workflow progress per session (runtime only, not persisted) */
  private workflowStates = new Map<string, WorkflowState>();
  /** Sessions in AUTO/YOLO mode — skip all confirmations and run straight through */
  private autoModeSessions = new Set<string>();
  /** Cumulative token usage per session: input + output tokens accumulated across all turns */
  private sessionTokenUsage = new Map<string, { inputTokens: number; outputTokens: number }>();
  private log: Logger;
  private config: SmanConfig | null = null;
  private cleanupTimer: ReturnType<typeof setInterval> | null = null;
  private configGeneration = 0;
  private webAccessService: WebAccessService | null = null;
  private userProfile: UserProfileManager | null = null;
  private knowledgeExtractor: import('./knowledge-extractor.js').KnowledgeExtractor | null = null;
  private capabilityRegistry: CapabilityRegistry | null = null;
  /** Serializes getOrCreateV2Session calls to prevent process.chdir races */
  private v2CreateChain: Promise<void> = Promise.resolve();
  /** Pending AskUserQuestion promises: sessionId -> { resolve, askId, questions, timer } */
  private pendingAskUser = new Map<string, {
    resolve: (result: { behavior: string; updatedInput?: Record<string, unknown> }) => void;
    askId: string;
    questions: unknown[];
    timer: ReturnType<typeof setTimeout>;
  }>();
  /** Last known wsSend callback per session (for canUseTool AskUserQuestion bridge) */
  private sessionWsSend = new Map<string, WsSend>();

  setUserProfile(manager: UserProfileManager): void {
    this.userProfile = manager;
    // Profile updates only run LLM when no sessions are actively streaming
    manager.setIdleCheck(() => this.activeStreams.size === 0);
    this.log.info('UserProfileManager injected');
  }

  setKnowledgeExtractor(extractor: import('./knowledge-extractor.js').KnowledgeExtractor): void {
    this.knowledgeExtractor = extractor;
    extractor.setActivityTimestampProvider(() => this.getLastStreamActivityAt());
    this.log.info('KnowledgeExtractor injected');
  }

  /** Update AUTO/YOLO mode for a session */
  setAutoMode(sessionId: string, enabled: boolean): void {
    if (enabled) {
      this.autoModeSessions.add(sessionId);
    } else {
      this.autoModeSessions.delete(sessionId);
    }
    this.log.info(`AUTO mode ${enabled ? 'enabled' : 'disabled'} for session ${sessionId}`);
  }

  private static readonly SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes
  private static readonly CLEANUP_INTERVAL_MS = 60 * 1000; // 1 minute
  private static readonly STREAM_STALL_MS = 2 * 60 * 1000; // 2 minutes no data = stalled
  private static readonly SEND_TIMEOUT_MS = 120 * 1000; // 2 minutes timeout for v2Session.send()
  private static readonly SESSION_CREATE_TIMEOUT_MS = 60 * 1000; // 1 minute timeout for session creation
  private static readonly TOOL_STALL_MS = 10 * 60 * 1000; // 10 minutes hard limit for tool/sub-agent execution
  private static readonly AUTO_RETRY_ERRORS = new Set(['stall', 'process_dead', 'v2_session_lost', 'session_expired', 'bad_request', 'server_error', 'overloaded', 'network_error']);

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
  /** Resolve a pending AskUserQuestion promise with the user's answers */
  resolveAskUser(sessionId: string, askId: string, answers: Record<string, string[]>): boolean {
    const pending = this.pendingAskUser.get(sessionId);
    if (!pending || pending.askId !== askId) return false;
    clearTimeout(pending.timer);
    this.pendingAskUser.delete(sessionId);
    this.log.info(`AskUserQuestion answered for session ${sessionId}`);
    pending.resolve({
      behavior: 'allow',
      updatedInput: { questions: pending.questions, answers } as unknown as Record<string, unknown>,
    });
    return true;
  }

  /** Cancel a pending AskUserQuestion (on abort/close) */
  private cancelPendingAskUser(sessionId: string): void {
    const pending = this.pendingAskUser.get(sessionId);
    if (pending) {
      clearTimeout(pending.timer);
      this.pendingAskUser.delete(sessionId);
      pending.resolve({ behavior: 'deny' });
      this.log.info(`Cancelled pending AskUserQuestion for session ${sessionId}`);
    }
  }

  private buildSmanContext(projectName: string, sessionId?: string): string {
    const lines: string[] = [];
    const workflowState = sessionId ? this.workflowStates.get(sessionId) : undefined;
    const stepNames = ['约束加载', '需求分析', '写实施计划', '逐任务执行', '集成验证', '代码优化', '总结沉淀'] as const;

    // ── 段 1：硬性触发条件 ──
    lines.push('[Sman 开发流程 - 必须遵守]');
    lines.push('');
    lines.push('## 何时必须走 dev-workflow（调用 /dev-workflow skill）');
    lines.push('以下任一条件满足，必须调用 dev-workflow:');
    lines.push('- 新功能开发（新增页面、接口、模块、组件）');
    lines.push('- 重构（改动 >3 个文件，或影响多个模块）');
    lines.push('- 需求不明确（用户描述模糊，需要先澄清再动手）');
    lines.push('- 用户说"帮我做/开发/实现/设计/重构/加一个/新功能" + 具体功能描述');
    lines.push('- 预计改动 >30 行代码');
    lines.push('- 多步骤任务（先改 A 再改 B 再测试 C）');
    lines.push('');
    lines.push('## 何时可以走直接模式');
    lines.push('所有条件必须同时满足:');
    lines.push('1. 用户明确说"快速处理/直接改/不要流程"');
    lines.push('2. 改动明确且单一（改一个 bug、改一行配置、改一个文案）');
    lines.push('3. 预计 <=30 行，<=2 个文件');
    lines.push('4. 不涉及架构或跨模块改动');
    lines.push('5. 交付前仍需自检');
    lines.push('');
    lines.push('判断优先级：不确定 → 走 dev-workflow。宁可多走流程，不可跳过。');

    // ── 段 2：动态进度注入（仅在 workflow 激活时）──
    if (workflowState?.active && workflowState.currentStep !== null) {
      const cs = workflowState.currentStep;
      const csName = stepNames[cs - 1] ?? '未知';
      lines.push('');
      lines.push('## [当前 dev-workflow 进度 - 你正在执行此流程，不可偏离]');
      lines.push(`当前步骤: Step ${cs} - ${csName}`);
      lines.push(`已完成步骤: ${workflowState.completedSteps.map(s => `Step ${s}`).join(', ') || '无'}`);
      lines.push(`任务: ${workflowState.taskSummary || '(未记录)'}`);
      lines.push('');
      lines.push('完成当前步骤后，必须调用 workflow_update 工具通知后端进入下一步。');
      // 给出当前步骤的明确指令
      const stepHints: Record<number, string> = {
        1: '当前在需求分析阶段。使用 superpowers:brainstorming，向用户提问澄清需求，确认方案后调用 workflow_update(step=2)。',
        2: '当前在写计划阶段。使用 superpowers:writing-plans，完成计划后交用户确认，确认后调用 workflow_update(step=3)。',
        3: '当前在逐任务执行阶段。使用 superpowers:subagent-driven-development，逐个任务派 Agent 执行。全部完成后调用 workflow_update(step=4)。',
        4: '当前在集成验证阶段。使用 superpowers:verification-before-completion，验证通过后调用 workflow_update(step=5)。',
        5: '当前在代码优化阶段。派优化 Agent 消除重复、简化逻辑。完成后调用 workflow_update(step=6)。',
        6: '当前在总结沉淀阶段。完成 Memory 记录、规则提取、Skills 审计后，调用 workflow_update(step=-1) 结束流程。',
      };
      if (stepHints[cs]) {
        lines.push(`→ ${stepHints[cs]}`);
      }
    }

    // ── 段 2.5：AUTO/YOLO 模式 ──
    if (sessionId && this.autoModeSessions.has(sessionId)) {
      lines.push('');
      lines.push('## [AUTO/YOLO 模式 - 最高优先级指令]');
      lines.push('用户开启了 AUTO 模式。以下规则覆盖所有 skill 中的"等用户确认"要求：');
      lines.push('1. 不要停下来等用户确认。spec、plan、方案选择等所有需要用户确认的环节，全部用你的最佳判断自行决定并继续推进。');
      lines.push('2. 不要用 AskUserQuestion 问用户。直接做决定。');
      lines.push('3. brainstorming 中的"逐个提问"→ 改为"基于用户初始描述，自行做出合理假设，直接产出 spec"。');
      lines.push('4. writing-plans 中的"Ready to execute?"→ 直接进入执行。');
      lines.push('5. dev-workflow 每步之间的确认 → 全部跳过，自动推进到下一步。');
      lines.push('6. 只在遇到严重歧义或无法做出的技术决策时才停下来。');
    }

    // ── 段 3：身份和行为要求 ──
    lines.push('');
    lines.push(`[Sman 身份 - 你是 Sman 智能业务系统助手，始终中文回复。用户画像身份优先。Project: ${projectName}。你的 session_id: ${sessionId ?? 'unknown'}。扩展能力按需挂载: capability_list 发现 → capability_load 激活。]`);
    lines.push('[Sman 行为要求]');
    lines.push('');
    lines.push('## 进度告知（最重要！）');
    lines.push('用户要看到进度，不是干等。每开始一个步骤，先说一句话告诉用户你在做什么。');
    lines.push('好的体验："正在分析需求..." → "搜索相关代码..." → "已修改3个文件，编译通过"');
    lines.push('差的体验：沉默很久 → 一大坨输出');
    lines.push('');
    lines.push('## 输出风格');
    lines.push('1. 先说你在做什么（一句话），然后执行');
    lines.push('2. 文件变更：只给路径 + 改动摘要（如 "UserService.java:42 新增参数校验"），不输出完整代码');
    lines.push('3. 编译测试结果：只给 ✓/✗ 状态，不输出完整日志');
    lines.push('4. 用户追问细节时才展开');
    lines.push('');
    lines.push('## 何时用子 Agent');
    lines.push('复杂任务（多模块、架构变更、>10次工具调用）→ 拆成多个 Task，每个 Task 一个子 agent，逐步汇报');
    lines.push('普通任务（几个文件的修改、调试）→ 直接在主会话里用工具完成，正常告知进度即可');
    lines.push('');
    lines.push('## 交付要求');
    lines.push('1. 交付前自检：编译通过？测试通过？不自检就是玩具');
    lines.push('2. 项目依赖复杂导致无法编译时，直接告诉用户，不要卡死重试');
    lines.push('3. 上下文过长时，主动建议用户开启新会话。不要硬撑。');
    lines.push('');
    lines.push('## 任务前提（一开始就把事情做对）');
    lines.push('动手前，确保满足以下条件，避免因模糊导致的反复拉扯：');
    lines.push('1. **深入理解需求** — 不只听表面，要理解用户真正想达成什么目标');
    lines.push('2. **获取足够上下文** — 读取必要的文件、了解现有实现，确保有足够信息支撑任务执行');
    lines.push('3. **确认可执行性** — 评估是否具备完成任务的条件（依赖、权限、环境），能交付结果再动手');
    lines.push('4. **不确定就问** — 任何不清楚的地方立即澄清，不要基于模糊假设做决定');
    lines.push('');
    lines.push('## 需求澄清（对话自省）');
    lines.push('当发现用户多次纠正你的理解时（用户说"不对"/"不是这样"/"应该是..."），说明理解有偏差，需要停下来对齐：');
    lines.push('1. 停止当前正在做的任务');
    lines.push('2. 调用 superpowers:clarify-requirements skill');
    lines.push('3. 用 3-5 个简短问句快速澄清需求');
    lines.push('4. 输出结构化的需求确认表');
    lines.push('5. 等待用户确认后继续（AUTO 模式下：如果理解清晰则自问自答，仍然不确定则停下来问用户）');

    return lines.join('\n');
  }

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

  /**
   * Resolve the model name to pass to Claude CLI.
   * Pass the user-configured model name directly.
   */
  /**
   * Private IP prefixes for internal enterprise proxies.
   * Internal proxies (e.g. http://172.x.x.x) validate model names against a whitelist
   * (e.g. "glm-5.1") and work fine with the real model name — CLI's extra requests
   * are handled by the proxy internally.
   * External proxies (e.g. https://open.bigmodel.cn) trigger CLI's internal model
   * capabilities/thinking logic which sends extra API requests → rate limited.
   * For these, pass "claude-sonnet-4-6" and let the proxy map it server-side.
   */
  private static readonly PRIVATE_IP_PREFIXES = ['172.', '10.', '198.', '127.', '192.168.', 'localhost'];

  private isPrivateNetworkUrl(url: string): boolean {
    try {
      const host = new URL(url).hostname;
      return ClaudeSessionManager.PRIVATE_IP_PREFIXES.some(p => host.startsWith(p));
    } catch {
      return false;
    }
  }

  private resolveCliModel(configModel: string): string {
    // Anthropic models pass through as-is
    const ANTHROPIC_MODEL_PREFIXES = ['claude-', 'anthropic-'];
    if (ANTHROPIC_MODEL_PREFIXES.some(p => configModel.toLowerCase().startsWith(p))) {
      return configModel;
    }
    // Internal enterprise proxy — pass real model name (proxy validates & maps)
    const baseUrl = this.config?.llm?.baseUrl;
    if (baseUrl && this.isPrivateNetworkUrl(baseUrl)) {
      this.log.info(`Internal proxy detected (${baseUrl}), passing real model "${configModel}"`);
      return configModel;
    }
    // External third-party proxy — use CLI default to avoid extra internal requests
    this.log.info(`External proxy detected (${baseUrl ?? 'default'}), mapping "${configModel}" → "claude-sonnet-4-6"`);
    return 'claude-sonnet-4-6';
  }

  /**
   * Prevents leaked vars (ANTHROPIC_AUTH_TOKEN, OPENAI_API_KEY, etc.)
   * from ~/.claude/settings.json or parent process from overriding sman's config.
   * Based on hello-halo's sdk-config.ts getCleanUserEnv() pattern.
   */
  private static readonly ENV_PREFIXES_TO_STRIP = ['ANTHROPIC_', 'OPENAI_', 'CLAUDE_'];

  private getCleanEnv(): Record<string, string | undefined> {
    const env = { ...process.env as Record<string, string | undefined> };
    for (const key of Object.keys(env)) {
      if (ClaudeSessionManager.ENV_PREFIXES_TO_STRIP.some(prefix => key.startsWith(prefix))) {
        delete env[key];
      }
    }
    // Clear proxy vars — CLI's undici auto-detects them and routes
    // localhost requests through the proxy, causing UND_ERR_SOCKET
    for (const key of ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy', 'NO_PROXY', 'no_proxy']) {
      delete env[key];
    }
    return env;
  }

  /**
   * Inject a permissive .claude/settings.json into the workspace if one doesn't exist.
   * This ensures project-level settings take precedence over potentially restrictive
   * global ~/.claude/settings.json, preventing hooks or deny rules from blocking tools.
   * Safe because sman uses bypassPermissions + canUseTool for actual control.
   */
  private injectProjectSettings(workspace: string, label: string = ''): void {
    try {
      const wsClaudeDir = path.join(workspace, '.claude');
      const wsSettingsFile = path.join(wsClaudeDir, 'settings.json');
      if (!fs.existsSync(wsSettingsFile)) {
        fs.mkdirSync(wsClaudeDir, { recursive: true });
        const permissiveSettings = {
          permissions: {
            allow: ['Bash', 'Write', 'Edit', 'MCP'],
          },
        };
        fs.writeFileSync(wsSettingsFile, JSON.stringify(permissiveSettings, null, 2) + '\n', 'utf-8');
        this.log.info(`[Settings] injected permissive settings.json into ${wsSettingsFile}${label ? ` (${label})` : ''}`);
      } else {
        this.log.info(`[Settings] project settings.json already exists at ${wsSettingsFile}, skipping${label ? ` (${label})` : ''}`);
      }
    } catch (e: any) {
      this.log.warn(`[Settings] failed to inject project settings.json${label ? ` (${label})` : ''}: ${e.message}`);
    }
  }

  private detectSearchFallback(ws: SmanConfig['webSearch'] | undefined): { name: string; server: McpSdkServerConfigWithInstance } | null {
    if (!ws) return null;

    // Priority: baidu > brave > tavily (prefer domestic providers)
    if (ws.baiduApiKey) {
      return { name: 'baidu-search', server: createBaiduSearchMcpServer(ws.baiduApiKey) };
    }
    if (ws.braveApiKey) {
      return { name: 'brave-search', server: createBraveSearchMcpServer(ws.braveApiKey) };
    }
    if (ws.tavilyApiKey) {
      return { name: 'tavily-search', server: createTavilySearchMcpServer(ws.tavilyApiKey) };
    }

    return null;
  }

  private buildSessionOptions(workspace: string): Record<string, any> {
    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    // Clean env: strip all ANTHROPIC_*/OPENAI_*/CLAUDE_* vars first, then set exactly what we need.
    // This prevents ~/.claude/settings.json env vars (ANTHROPIC_AUTH_TOKEN, etc.) from leaking
    // into the CLI subprocess and conflicting with our own config.
    const env = this.getCleanEnv();
    env['ANTHROPIC_API_KEY'] = this.config.llm.apiKey;
    if (this.config.llm.baseUrl) {
      env['ANTHROPIC_BASE_URL'] = this.config.llm.baseUrl;
    }

    // Disable non-essential CLI traffic (preconnect, telemetry, cost warnings, etc.)
    // These fire-and-forget requests hit the proxy endpoint and cause rate limiting.
    // Same approach as hello-halo's sdk-config.ts.
    env['CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC'] = '1';
    env['DISABLE_TELEMETRY'] = '1';
    env['DISABLE_COST_WARNINGS'] = '1';
    env['CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS'] = '1';
    env['DISABLE_INTERLEAVED_THINKING'] = '1';
    env['CLAUDE_CODE_REMOTE'] = 'true';
    env['CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING'] = '1';
    env['CLAUDE_CODE_DISABLE_COMMAND_INJECTION_CHECK'] = '1';

    // For non-Anthropic proxies: disable CLI behaviors that amplify rate limiting.
    // Third-party proxies (Zhipu, Kimi, MiniMax, enterprise internal) have stricter
    // rate limits and model whitelists than Anthropic's API.
    if (!isAnthropicFirstParty(this.config.llm.baseUrl)) {
      // Skip model validation (which sends extra API requests)
      env['ANTHROPIC_CUSTOM_MODEL_OPTION'] = this.config.llm.model;
      // Disable 429 retry (CLI defaults to 10 retries — each one consumes quota
      // and prolongs the rate limit window on shared API keys)
      env['CLAUDE_CODE_MAX_RETRIES'] = '0';
      // Skip claude.ai MCP server fetch (hits Anthropic API, not the proxy)
      env['ENABLE_CLAUDEAI_MCP_SERVERS'] = 'false';
    }
    // Use isolated config dir to avoid CLI reading ~/.claude/settings.json
    // which may contain conflicting env vars (ANTHROPIC_AUTH_TOKEN, etc.)
    const smanHome = process.env.SMANBASE_HOME || path.join(process.env.HOME || '/root', '.sman');
    env['CLAUDE_CONFIG_DIR'] = path.join(smanHome, 'claude-config');

    const claudeCodePath = this.getClaudeCodePath();


    // root/sudo cannot use --dangerously-skip-permissions (claude CLI rejects it).
    // Use environment variable bypass instead.
    const isRoot = process.getuid?.() === 0;
    if (isRoot) {
      env['CLAUDE_BYPASS_PERMISSIONS'] = '1';
    }

    // Load bundled plugins via --plugin-dir
    // superpowers: full plugin (skills + commands + agents + hooks) loaded via --plugin-dir
    // dev-workflow: single SKILL.md — injected into workspace .claude/skills/ for auto-discovery
    const projectRoot = resolveProjectRoot();
    const pluginsDir = path.join(projectRoot, 'plugins');
    const plugins: Array<{ type: 'local'; path: string }> = [];
    this.log.info(`[Plugins] projectRoot=${projectRoot}, pluginsDir=${pluginsDir}`);

    // Load superpowers plugin via --plugin-dir (has full plugin structure)
    const superpowersPath = path.join(pluginsDir, 'superpowers');
    if (fs.existsSync(superpowersPath)) {
      plugins.push({ type: 'local', path: superpowersPath });
      this.log.info(`[Plugins] loaded: superpowers from ${superpowersPath}`);
    } else {
      this.log.warn(`[Plugins] NOT FOUND: superpowers at ${superpowersPath}`);
    }

    // Also try loading dev-workflow via --plugin-dir as a bonus
    const devWorkflowPath = path.join(pluginsDir, 'dev-workflow');
    if (fs.existsSync(devWorkflowPath)) {
      plugins.push({ type: 'local', path: devWorkflowPath });
      this.log.info(`[Plugins] loaded: dev-workflow from ${devWorkflowPath}`);
    } else {
      this.log.warn(`[Plugins] NOT FOUND: dev-workflow at ${devWorkflowPath}`);
    }

    // CRITICAL: Inject dev-workflow SKILL.md into workspace .claude/skills/
    // This is the primary loading mechanism — CLI auto-discovers workspace skills,
    // no path resolution issues, works in all environments (dev, prod, asar, non-asar).
    // dev-workflow's SKILL.md references superpowers skills by name (superpowers:brainstorming etc),
    // so superpowers plugin must also be loaded (via --plugin-dir above).
    try {
      const wsSkillsDir = path.join(workspace, '.claude', 'skills', 'dev-workflow');
      const wsSkillFile = path.join(wsSkillsDir, 'SKILL.md');
      // Try multiple source locations for the bundled SKILL.md
      const candidateSources = [
        path.join(projectRoot, 'plugins', 'dev-workflow', 'skills', 'dev-workflow', 'SKILL.md'),
        path.join(__dirname, '..', '..', 'plugins', 'dev-workflow', 'skills', 'dev-workflow', 'SKILL.md'),
      ];
      const bundledSkill = candidateSources.find(s => fs.existsSync(s));
      if (!fs.existsSync(wsSkillFile) && bundledSkill) {
        fs.mkdirSync(wsSkillsDir, { recursive: true });
        fs.copyFileSync(bundledSkill, wsSkillFile);
        this.log.info(`[Skills] injected dev-workflow into ${wsSkillFile} (source: ${bundledSkill})`);
      } else if (!fs.existsSync(wsSkillFile)) {
        this.log.warn(`[Skills] could not find bundled dev-workflow SKILL.md to inject`);
      }
    } catch (e: any) {
      this.log.warn(`[Skills] failed to inject dev-workflow: ${e.message}`);
    }

    // Inject permissive project-level settings.json to prevent filesystem
    // hooks or restrictive permissions from the user's global ~/.claude/settings.json
    // or CLAUDE_CONFIG_DIR from blocking tool calls.
    this.injectProjectSettings(workspace);

    // Model resolution: CLI's internal Anthropic SDK may send additional requests
    // (model capabilities, prompt caching) based on the model name. Non-Anthropic
    // model names (e.g. "glm-5.1") can trigger rate limiting on third-party proxies
    // like Zhipu. Use the default Anthropic model name and let the proxy map it.
    // The model from config is stored for reference but not passed to CLI.
    const cliModel = this.resolveCliModel(this.config.llm.model);

    const opts: Record<string, any> = {
      model: cliModel,
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

    // Build MCP servers: explicit provider config (brave/tavily) takes precedence
    const mcpServers = buildMcpServers(this.config);
    opts.mcpServers = Object.keys(mcpServers).length > 0 ? mcpServers : {};

    // Web search MCP injection — all providers use in-process SDK servers (no npx).
    // - Explicit provider (searxng/baidu/brave/tavily): inject that provider as MCP tool
    // - 'builtin': Claude's built-in WebSearch is always available, but we also inject
    //   a fallback MCP web_search tool so Claude has a working alternative when built-in fails.
    //   Priority: baidu > brave > tavily > SearXNG
    const ws = this.config.webSearch;
    if (ws?.provider === 'searxng') {
      const webSearchServer = createWebSearchMcpServer();
      (opts.mcpServers as any)['web-search'] = webSearchServer;
      this.log.info('[search] SearXNG MCP registered as search provider');
    } else if (ws?.provider === 'baidu') {
      if (ws.baiduApiKey) {
        (opts.mcpServers as any)['baidu-search'] = createBaiduSearchMcpServer(ws.baiduApiKey);
        this.log.info('[search] Baidu AI Search MCP registered as search provider');
      } else {
        this.log.warn('[search] Baidu provider selected but no API Key configured');
      }
    } else if (ws?.provider === 'brave') {
      if (ws.braveApiKey) {
        (opts.mcpServers as any)['brave-search'] = createBraveSearchMcpServer(ws.braveApiKey);
        this.log.info('[search] Brave Search MCP registered as search provider');
      } else {
        this.log.warn('[search] Brave provider selected but no API Key configured');
      }
    } else if (ws?.provider === 'tavily') {
      if (ws.tavilyApiKey) {
        (opts.mcpServers as any)['tavily-search'] = createTavilySearchMcpServer(ws.tavilyApiKey);
        this.log.info('[search] Tavily Search MCP registered as search provider');
      } else {
        this.log.warn('[search] Tavily provider selected but no API Key configured');
      }
    } else if (ws?.provider === 'builtin' || !ws?.provider) {
      const fallback = this.detectSearchFallback(ws);
      if (fallback) {
        (opts.mcpServers as any)[fallback.name] = fallback.server;
        this.log.info(`[search] builtin mode — injected ${fallback.name} as fallback search`);
      } else {
        this.log.info('[search] builtin mode — no API key configured, using SearXNG as fallback');
        const webSearchServer = createWebSearchMcpServer();
        (opts.mcpServers as any)['web-search'] = webSearchServer;
      }
    }

    // Inject web-access MCP Server (in-process)
    if (this.webAccessService) {
      const webAccessServer = createWebAccessMcpServer(this.webAccessService);
      (opts.mcpServers as any)['web-access'] = webAccessServer;
    }

    // Inject capability gateway MCP Server (in-process, always present)
    if (this.capabilityRegistry) {
      const self = this;
      const gatewayServer = createCapabilityGatewayMcpServer({
        registry: this.capabilityRegistry,
        getActiveSession: (sid: string) => {
          const entry = this.v2Sessions.get(sid);
          return entry?.session ?? null;
        },
        pluginsDir,
        onWorkflowUpdate: (sid: string, step: number) => {
          self.updateWorkflowStep(sid, step);
        },
        onWorkflowActivate: (sid: string, taskSummary: string) => {
          self.activateWorkflow(sid, taskSummary);
        },
        onWorkflowReset: (sid: string) => {
          self.resetWorkflow(sid);
        },
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

    const env = this.getCleanEnv();
    env['ANTHROPIC_API_KEY'] = this.config.llm.apiKey;
    if (this.config.llm.baseUrl) {
      env['ANTHROPIC_BASE_URL'] = this.config.llm.baseUrl;
    }
    env['CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC'] = '1';
    env['DISABLE_TELEMETRY'] = '1';
    env['DISABLE_COST_WARNINGS'] = '1';
    env['CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS'] = '1';
    env['DISABLE_INTERLEAVED_THINKING'] = '1';
    env['CLAUDE_CODE_REMOTE'] = 'true';
    env['CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING'] = '1';
    env['CLAUDE_CODE_DISABLE_COMMAND_INJECTION_CHECK'] = '1';
    if (!isAnthropicFirstParty(this.config.llm.baseUrl)) {
      env['ANTHROPIC_CUSTOM_MODEL_OPTION'] = this.config.llm.model;
      env['CLAUDE_CODE_MAX_RETRIES'] = '0';
      env['ENABLE_CLAUDEAI_MCP_SERVERS'] = 'false';
    }
    const smanHome = process.env.SMANBASE_HOME || path.join(process.env.HOME || '/root', '.sman');
    env['CLAUDE_CONFIG_DIR'] = path.join(smanHome, 'claude-config');

    const claudeCodePath = this.getClaudeCodePath();

    const isRoot = process.getuid?.() === 0;
    if (isRoot) {
      env['CLAUDE_BYPASS_PERMISSIONS'] = '1';
    }

    // Inject permissive project-level settings.json (same as buildSessionOptions)
    this.injectProjectSettings(workspace, 'scanner');

    return {
      model: this.resolveCliModel(this.config.llm.model),
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
   * Get or create a V2 session for the given session ID.
   * Returns { session, isFresh } where isFresh=true means a new session was created
   * (resume failed or first creation), so caller should inject conversation history.
   */
  private async getOrCreateV2Session(sessionId: string): Promise<{ session: SDKSession; isFresh: boolean }> {
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
          return { session: existing.session, isFresh: false };
        } catch {
          this.log.info(`V2 session process dead (PID: ${pid}), recreating...`);
          this.closeV2Session(sessionId);
        }
      } else {
        // No pid info, try to use anyway
        existing.lastUsedAt = Date.now();
        return { session: existing.session, isFresh: false };
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
        return { session: existingAfter.session, isFresh: false };
      }

      // Create new V2 session
      const sessionInfo = this.sessions.get(sessionId);
      const isScanner = sessionInfo?.isScanner === true;

      const options = isScanner
        ? this.buildScannerSessionOptions(session.workspace)
        : this.buildSessionOptions(session.workspace);

      // Inject canUseTool callback for AskUserQuestion bridge (desktop sessions only)
      if (!isScanner) {
        const capturedSessionId = sessionId;
        (options as any).canUseTool = async (params: { toolName: string; input: Record<string, unknown> }) => {
          if (params.toolName !== 'AskUserQuestion') {
            return { behavior: 'allow' as const };
          }
          const wsSend = this.sessionWsSend.get(capturedSessionId);
          if (!wsSend) {
            this.log.warn(`No wsSend for session ${capturedSessionId}, denying AskUserQuestion`);
            return { behavior: 'deny' as const };
          }
          const questions = params.input?.questions ?? [];
          const askId = crypto.randomUUID();
          this.log.info(`AskUserQuestion intercepted for session ${capturedSessionId}, askId=${askId}`);

          wsSend(JSON.stringify({
            type: 'chat.ask_user',
            sessionId: capturedSessionId,
            askId,
            questions,
          }));

          return new Promise<{ behavior: string; updatedInput?: Record<string, unknown> }>((resolve) => {
            const ASK_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes
            const timer = setTimeout(() => {
              this.pendingAskUser.delete(capturedSessionId);
              this.log.warn(`AskUserQuestion timed out for session ${capturedSessionId}`);
              resolve({ behavior: 'deny' });
            }, ASK_TIMEOUT_MS);
            timer.unref(); // Don't prevent process exit

            this.pendingAskUser.set(capturedSessionId, { resolve, askId, questions: questions as unknown[], timer });
          });
        };
      }

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

      // Wrap session creation with timeout — CLI may hang during init (plugin loading, MCP connect, etc.)
      const createTimeout = ClaudeSessionManager.SESSION_CREATE_TIMEOUT_MS;
      const createWithTimeout = () => Promise.race([
        unstable_v2_createSession(options as any),
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error(`创建会话超时（${createTimeout / 1000}s），CLI 初始化可能卡住`)), createTimeout)
        ),
      ]);

      let v2Session: SDKSession;
      try {
        v2Session = await createWithTimeout();
      } catch (err) {
        const errMsg = String(err);
        // Resume failed: conversation data lost (idle timeout killed the process).
        // Fall back to fresh session — don't lose the user's conversation history.
        if (options.resume && /conversation.*not found|No conversation found/i.test(errMsg)) {
          this.log.warn(`Resume failed for ${sessionId}: ${errMsg}. Creating fresh session...`);
          this.sdkSessionIds.delete(sessionId);
          try { this.store.updateSdkSessionId(sessionId, ''); } catch { /* ignore */ }
          delete options.resume;
          v2Session = await createWithTimeout();
        } else {
          throw err;
        }
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

      return { session: v2Session, isFresh: true };
    } finally {
      resolveChain();
    }
  }

  // ── Workflow progress tracking ──

  /** AI notifies backend it entered a new dev-workflow step (via MCP tool) */
  updateWorkflowStep(sessionId: string, step: number): void {
    let state = this.workflowStates.get(sessionId);
    if (!state) {
      state = { currentStep: null, active: false, completedSteps: [], stepEnteredAt: Date.now(), startedAt: null, taskSummary: '' };
      this.workflowStates.set(sessionId, state);
    }
    if (state.currentStep !== null && !state.completedSteps.includes(state.currentStep)) {
      state.completedSteps.push(state.currentStep);
    }
    state.currentStep = step as WorkflowStep;
    state.stepEnteredAt = Date.now();
    if (!state.active) {
      state.active = true;
      state.startedAt = Date.now();
    }
    this.log.info(`Workflow step updated: session=${sessionId}, step=${step}, completed=[${state.completedSteps.join(',')}]`);
  }

  /** Activate workflow with task summary (called when AI enters Step 1) */
  activateWorkflow(sessionId: string, taskSummary: string): void {
    let state = this.workflowStates.get(sessionId);
    if (!state) {
      state = { currentStep: null, active: false, completedSteps: [], stepEnteredAt: Date.now(), startedAt: null, taskSummary: '' };
      this.workflowStates.set(sessionId, state);
    }
    state.active = true;
    state.startedAt = Date.now();
    state.taskSummary = taskSummary;
    this.log.info(`Workflow activated: session=${sessionId}, task="${taskSummary.slice(0, 60)}"`);
  }

  /** Reset workflow state (task completed or session closed) */
  resetWorkflow(sessionId: string): void {
    this.workflowStates.delete(sessionId);
    this.log.info(`Workflow reset: session=${sessionId}`);
  }

  closeV2Session(sessionId: string): void {
    this.preheatPromises.delete(sessionId);
    this.cancelPendingAskUser(sessionId);
    this.workflowStates.delete(sessionId);
    this.autoModeSessions.delete(sessionId);
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

  /** Timestamp (ms) of the last activeStreams mutation. 0 = never active. */
  getLastStreamActivityAt(): number {
    return this.lastStreamActivityAt;
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
    const resolved = normalizeWorkspacePath(workspace);
    const id = crypto.randomUUID();
    this.store.createSession({
      id,
      systemId: resolved,
      workspace: resolved,
    });

    const session: ActiveSession = {
      id,
      workspace: resolved,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
    };

    this.sessions.set(id, session);
    this.log.info(`Session created: ${id} for workspace ${resolved}`);
    return id;
  }

  /**
   * 创建纯内存会话 — 不写 SQLite，不污染主会话列表。
   * 用于地球路径步骤执行等临时场景。
   */
  createEphemeralSession(workspace: string): string {
    const id = `ephemeral-${crypto.randomUUID()}`;
    const session: ActiveSession = {
      id,
      workspace,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
    };
    this.sessions.set(id, session);
    this.log.info(`Ephemeral session created: ${id} for workspace ${workspace}`);
    return id;
  }

  /**
   * 清理纯内存会话 — 从 sessions Map 中移除
   */
  removeEphemeralSession(sessionId: string): void {
    this.sessions.delete(sessionId);
    this.sdkSessionIds.delete(sessionId);
  }

  /**
   * 用指定 ID 创建纯内存会话 — 不写 SQLite
   */
  createEphemeralSessionWithId(workspace: string, sessionId: string): string {
    const session: ActiveSession = {
      id: sessionId,
      workspace,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
    };
    this.sessions.set(sessionId, session);
    this.log.info(`Ephemeral session created with id: ${sessionId} for workspace ${workspace}`);
    return sessionId;
  }

  createSessionWithId(workspace: string, sessionId: string, isCron = true, isScanner = false): string {
    const resolved = normalizeWorkspacePath(workspace);

    const existing = this.sessions.get(sessionId);
    if (existing) {
      return sessionId;
    }

    this.store.createSession({
      id: sessionId,
      systemId: resolved,
      workspace: resolved,
      isCron,
    });

    const session: ActiveSession = {
      id: sessionId,
      workspace: resolved,
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

    // Queue behind active query — wait for the current turn to finish before sending.
    // The SDK does NOT support interrupting a turn mid-execution: sending a user message
    // while the CLI is in stop_reason=tool_use causes ede_diagnostic errors and 401s.
    if (this.activeStreams.has(sessionId)) {
      this.log.info(`Query in progress for session ${sessionId}, queuing new message behind current turn`);
      const done = this.streamDone.get(sessionId);
      if (done) await done;
      this.log.info(`Previous stream for ${sessionId} completed, now processing queued message`);
    }

    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    // Don't store auto-retry messages in the database — user didn't send them
    if (_retryCount === 0) {
      // Extract file paths from content text for persistence (format: [用户文件路径:[path1,path2]])
      const userContentBlocks: Array<import('./session-store.js').ContentBlock> = [];
      const pathMatch = content.match(/\[用户文件路径:\[([^\]]+)\]\]/);
      if (pathMatch) {
        const paths = pathMatch[1].split(',');
        for (const fp of paths) {
          const fn = fp.split('/').pop()?.split('\\').pop() ?? 'file';
          userContentBlocks.push({
            type: 'attached_file',
            fileName: fn,
            filePath: fp,
          } as import('./session-store.js').ContentBlock);
        }
      }
      // Persist base64 images so they survive session switches
      if (media && media.length > 0) {
        for (const m of media) {
          if (m.mimeType.startsWith('image/')) {
            userContentBlocks.push({
              type: 'image',
              source: {
                type: 'base64',
                media_type: m.mimeType,
                data: m.base64Data,
              },
            } as import('./session-store.js').ContentBlock);
          }
        }
      }
      this.store.addMessage(sessionId, {
        role: 'user',
        content,
        contentBlocks: userContentBlocks.length > 0 ? userContentBlocks : undefined,
      });
    }

    const abortController = new AbortController();
    this.markStreamStart(sessionId, abortController);
    this.sessionWsSend.set(sessionId, wsSend);

    // Track when this stream fully exits so the next sendMessage can await it
    let streamResolve!: () => void;
    const streamPromise = new Promise<void>(r => { streamResolve = r; });
    this.streamDone.set(sessionId, streamPromise);

    let stallChecker: ReturnType<typeof setInterval> | null = null;
    let partialSaveTimer: ReturnType<typeof setInterval> | null = null;

    // Notify frontend immediately — let UI show "..." animation before heavy setup
    wsSend(JSON.stringify({
      type: 'chat.start',
      sessionId,
    }));

    // If preheat is in progress, wait for it to finish so we don't create a duplicate V2 session
    const preheatPromise = this.preheatPromises.get(sessionId);
    if (preheatPromise) {
      await preheatPromise;
    }

    // Track streamed content — declared outside try so catch can save partial state on abort
    let fullContent = '';
    // Accumulated text across all turns and tool_use segments — never cleared.
    // fullContent is reset on each assistant event and tool_use start, so previous text
    // segments would be lost on abort/refresh without this accumulator.
    let accumulatedText = '';
    let currentThinking = '';
    let allThinking = '';
    let allToolUses: Array<{ id: string; name: string; input: string; summary?: string; elapsedSeconds?: number }> = [];
    let currentToolUse: { id: string; name: string; input: string; summary?: string; elapsedSeconds?: number } | null = null;
    let stallAbortReason: string | undefined;
    let needsSessionExpiredRetry = false;
    // Track whether this abort was user-initiated (stop button) vs system-initiated (stall/error).
    // User aborts preserve the V2 session so the user can "继续" without losing context.
    let userInitiatedAbort = false;

    try {
      this.log.info(`[sendMessage] ${sessionId}: getting/creating V2 session...`);
      const { session: v2Session, isFresh } = await this.getOrCreateV2Session(sessionId);
      this.log.info(`[sendMessage] ${sessionId}: V2 session ready, isFresh=${isFresh}, sending content (${content.length} chars)...`);

      // Inject user profile + Sman context into content for Claude, but don't store it
      const profilePrefix = this.userProfile?.getProfileForPrompt() ?? '';
      const workspace = session.workspace;
      const projectName = path.basename(workspace);
      const smanContext = this.buildSmanContext(projectName, sessionId);
      const messagePrefix = profilePrefix
        ? `${smanContext}\n${profilePrefix}`
        : smanContext;
      const capabilities = this.config?.llm?.capabilities;

      // Build content for send(): string or SDKUserMessage
      const builtContent = buildContentBlocks(content, media, capabilities);

      // Wrap send() with timeout — claude CLI may hang during init (e.g. incompatible API)
      const sendWithTimeout = async (payload: string | object) => {
        const timeoutMs = ClaudeSessionManager.SEND_TIMEOUT_MS;
        let timer: ReturnType<typeof setTimeout>;
        const timeoutPromise = new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            this.log.warn(`v2Session.send() timed out for session ${sessionId} after ${timeoutMs / 1000}s`);
            reject(new Error(`发送消息超时（${timeoutMs / 1000}s），模型服务可能未响应`));
          }, timeoutMs);
        });
        try {
          await Promise.race([v2Session.send(payload as any), timeoutPromise]);
        } finally {
          clearTimeout(timer!);
        }
      };

      if (isFresh) {
        // Fresh session (resume failed or first creation): inject recent conversation history
        // so Claude has context without relying on CLI's internal resume mechanism.
        const history = this.getHistory(sessionId);
        const recentTurns = this.buildHistoryContext(history, 3);
        const fullPrefix = recentTurns
          ? `${messagePrefix}\n\n${recentTurns}`
          : messagePrefix;

        if (typeof builtContent === 'string') {
          const contentWithPrefix = `${fullPrefix}\n\n${builtContent}`;
          await sendWithTimeout(contentWithPrefix);
        } else {
          const blocks = [{ type: 'text', text: fullPrefix }, ...builtContent];
          await sendWithTimeout({
            type: 'user',
            message: { role: 'user', content: blocks },
            parent_tool_use_id: null,
            session_id: sessionId,
          });
        }
      } else {
        // Existing session: normal send
        if (typeof builtContent === 'string') {
          const contentWithPrefix = `${messagePrefix}\n\n${builtContent}`;
          await sendWithTimeout(contentWithPrefix);
        } else {
          const blocks = [{ type: 'text', text: messagePrefix }, ...builtContent];
          await sendWithTimeout({
            type: 'user',
            message: { role: 'user', content: blocks },
            parent_tool_use_id: null,
            session_id: sessionId,
          });
        }
      }
      this.log.info(`[sendMessage] ${sessionId}: send() completed, starting stream...`);

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
        // Check if process is dead — abort immediately regardless of elapsed time
        const v2Info = this.v2Sessions.get(sessionId);
        const pid = v2Info ? (v2Info.session as any).pid : undefined;
        if (pid !== undefined) {
          try { process.kill(pid, 0); } catch {
            this.log.warn(`V2 session process dead (PID: ${pid}) for ${sessionId}, aborting...`);
            stallAbortReason = 'process_dead';
            abortController.abort();
            clearInterval(stallChecker!);
            return;
          }
        }
        if (elapsed > ClaudeSessionManager.STREAM_STALL_MS) {
          this.log.warn(`Stream stalled for session ${sessionId} (no data for ${Math.round(ClaudeSessionManager.STREAM_STALL_MS / 1000)}s), aborting...`);
          stallAbortReason = 'stall';
          abortController.abort();
          clearInterval(stallChecker!);
        }
      }, 30_000); // check every 30s
      stallChecker.unref();

      // Periodic partial save: persist in-progress assistant content every 3 seconds.
      // Ensures page refresh doesn't lose streamed content.
      partialSaveTimer = setInterval(() => {
        if (abortController.signal.aborted) {
          clearInterval(partialSaveTimer!);
          return;
        }
        const partialContent = (accumulatedText + fullContent).trim();
        const partialThinking = allThinking.trim();
        if (!partialContent && !partialThinking && allToolUses.length === 0 && !currentToolUse) return;

        const contentBlocks: Array<{ type: string; thinking?: string; id?: string; name?: string; input?: unknown; summary?: string }> = [];
        if (partialThinking) contentBlocks.push({ type: 'thinking', thinking: partialThinking });
        const tools = currentToolUse ? [...allToolUses, currentToolUse] : allToolUses;
        for (const tu of tools) {
          try {
            contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input: JSON.parse(tu.input), summary: tu.summary });
          } catch {
            contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input: tu.input, summary: tu.summary });
          }
        }
        this.store.upsertPartialMessage(sessionId, partialContent, contentBlocks.length > 0 ? contentBlocks : undefined);
      }, 3000);
      partialSaveTimer.unref();

      let firstEventReceived = false;
      let deltaCount = 0;
      const queryGen = v2Session.stream();
      this.activeQueries.set(sessionId, queryGen);
      let abortDrainStart = 0; // timestamp when we started draining after user abort
      for await (const sdkMsg of queryGen) {
        lastActivityAt = Date.now();
        if (abortController.signal.aborted) {
          // User abort: continue reading until we get a result from interrupt(),
          // so the CLI gracefully ends the current turn and the V2 session stays clean.
          // System abort: break immediately — the session will be closed anyway.
          const controller = this.activeStreams.get(sessionId);
          const isUserAbort = stallAbortReason === 'user' || (controller as any)?._abortReason === 'user';
          if (isUserAbort && sdkMsg.type !== 'result') {
            if (!abortDrainStart) abortDrainStart = Date.now();
            // Give up after 10s — CLI is truly stuck, will be cleaned up later
            if (Date.now() - abortDrainStart > 10_000) {
              this.log.warn(`Interrupt drain timeout for ${sessionId}, forcing break`);
              break;
            }
            continue;
          }
          break;
        }

        if (!firstEventReceived) {
          firstEventReceived = true;
          this.log.info(`[stream] ${sessionId}: first event received, type=${sdkMsg.type}`);
        }

        switch (sdkMsg.type) {
          case 'assistant': {
            // New assistant turn: previous text segment is done, flush it
            toolInProgress = false;
            // Flush previous tool use
            if (currentToolUse) {
              allToolUses.push(currentToolUse);
              wsSend(JSON.stringify({ type: 'chat.tool_end', sessionId, toolUseId: currentToolUse.id }));
              currentToolUse = null;
            }
            // Flush previous text segment so UI can freeze it
            if (fullContent.trim()) {
              accumulatedText += fullContent;
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
                  deltaCount++;
                  if (deltaCount <= 3) {
                    this.log.info(`[stream] ${sessionId}: sending text delta #${deltaCount}, len=${delta.content.length}`);
                  }
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
                    accumulatedText += fullContent;
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
              parentToolUseId: progress.parent_tool_use_id ?? null,
              taskId: progress.task_id ?? undefined,
            }));
            break;
          }

          case 'system': {
            const sys = sdkMsg as any;
            const subtype = sys.subtype;
            if (subtype === 'task_started') {
              wsSend(JSON.stringify({
                type: 'chat.task_started',
                sessionId,
                taskId: sys.task_id,
                toolUseId: sys.tool_use_id ?? undefined,
                description: sys.description ?? '',
                taskType: sys.task_type ?? undefined,
              }));
            } else if (subtype === 'task_progress') {
              wsSend(JSON.stringify({
                type: 'chat.task_progress',
                sessionId,
                taskId: sys.task_id,
                toolUseId: sys.tool_use_id ?? undefined,
                description: sys.description ?? '',
                lastToolName: sys.last_tool_name ?? undefined,
                summary: sys.summary ?? undefined,
                usage: sys.usage ?? undefined,
              }));
            } else if (subtype === 'task_notification') {
              const notifSummary = sys.summary ?? '';
              const notifToolUseId = sys.tool_use_id ?? '';
              // Attach summary to matching tool
              if (notifSummary && notifToolUseId) {
                const tool = allToolUses.find(t => t.id === notifToolUseId);
                if (tool) {
                  tool.summary = tool.summary || notifSummary;
                } else if (currentToolUse && currentToolUse.id === notifToolUseId) {
                  currentToolUse.summary = currentToolUse.summary || notifSummary;
                }
              }
              wsSend(JSON.stringify({
                type: 'chat.task_notification',
                sessionId,
                taskId: sys.task_id,
                toolUseId: notifToolUseId || undefined,
                status: sys.status, // completed | failed | stopped
                summary: notifSummary,
                usage: sys.usage ?? undefined,
              }));
            }
            break;
          }

          case 'tool_use_summary': {
            const summary = sdkMsg as any;
            const summaryText = summary.summary ?? '';
            const precedingIds: string[] = summary.preceding_tool_use_ids ?? [];
            // Attach summary to the last preceding tool in allToolUses or currentToolUse
            if (precedingIds.length > 0 && summaryText) {
              const lastId = precedingIds[precedingIds.length - 1];
              const tool = allToolUses.find(t => t.id === lastId);
              if (tool) {
                tool.summary = summaryText;
              } else if (currentToolUse && currentToolUse.id === lastId) {
                currentToolUse.summary = summaryText;
              }
            }
            wsSend(JSON.stringify({
              type: 'chat.tool_use_summary',
              sessionId,
              summary: summaryText,
              precedingToolUseIds: precedingIds,
            }));
            break;
          }

          case 'result': {
            const result = sdkMsg as any;
            const cost = result.total_cost_usd || 0;
            const isError = result.is_error;
            const stopReason = result.stop_reason ?? result.stopReason ?? 'unknown';
            const subagentType = result.subagent_type ?? result.type ?? '';
            this.log.info(`[stream] ${sessionId}: result event received, is_error=${isError}, stop_reason=${stopReason}, subagent_type=${subagentType}, deltas_sent=${deltaCount}, fullContent_len=${fullContent.length}`);

            // Clear partial save timer and remove partial messages before saving final
            if (partialSaveTimer) { clearInterval(partialSaveTimer); partialSaveTimer = null; }
            this.store.clearPartialMessages(sessionId);

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
              summary?: string;
              status?: 'completed';
              elapsedSeconds?: number;
            }> = [];
            if (allThinking.trim()) {
              contentBlocks.push({ type: 'thinking', thinking: allThinking.trim() });
            }
            for (const tu of allToolUses) {
              try {
                const input = JSON.parse(tu.input);
                contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input, summary: tu.summary, status: 'completed', elapsedSeconds: tu.elapsedSeconds });
              } catch {
                contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input: tu.input, summary: tu.summary, status: 'completed', elapsedSeconds: tu.elapsedSeconds });
              }
            }

            // Store assistant message with contentBlocks
            const finalContent = (accumulatedText + fullContent).trim() || '';
            if (finalContent || contentBlocks.length > 0) {
              this.store.addMessage(sessionId, {
                role: 'assistant',
                content: finalContent,
                contentBlocks: contentBlocks.length > 0 ? contentBlocks : undefined,
              });
            }

            let resultError: string | undefined;
            if (isError) {
              if ('errors' in result && result.errors?.length) {
                resultError = result.errors.join(', ');
              } else if ('error' in result && result.error) {
                resultError = typeof result.error === 'string' ? result.error : JSON.stringify(result.error);
              } else if ('result' in result && typeof result.result === 'string' && result.result) {
                // SDK puts error message in 'result' field (e.g. "API Error: Request rejected (429)...")
                resultError = result.result;
              } else if ('message' in result && result.message) {
                resultError = typeof result.message === 'string' ? result.message : JSON.stringify(result.message);
              } else if ('api_error_status' in result && result.api_error_status) {
                resultError = `API Error: HTTP ${result.api_error_status}`;
              }
              this.log.warn(`SDK error for session ${sessionId}`, { resultError, apiStatus: result.api_error_status });
            }
            const classified = resultError ? this.classifyErrorMessage(resultError) : undefined;

            // Context usage tracking.
            // Inspired by claude-hud: the SDK's result.usage contains API-level token counts
            // for THIS turn, which already includes the full context history (after autocompact).
            // We prefer SDK usage when available, falling back to manual counting only when necessary.
            const sdkUsage = result.usage as {
              input_tokens?: number;
              output_tokens?: number;
              cache_read_input_tokens?: number;
              cache_creation_input_tokens?: number;
            } | undefined;

            // Total context size: input_tokens (non-cached) + cache_read_input_tokens (cached).
            // These are mutually exclusive — their sum is the actual context window usage this turn.
            const sdkInputTokens = (sdkUsage?.input_tokens ?? 0) + (sdkUsage?.cache_read_input_tokens ?? 0);
            const sdkOutputTokens = sdkUsage?.output_tokens ?? 0;

            let totalInputTokens: number;
            let totalOutputTokens: number;

            if (sdkUsage && typeof sdkUsage.input_tokens === 'number') {
              // SDK usage is available — use it directly.
              // Note: input_tokens may be 0 on the first turn (no history yet), which is valid.
              totalInputTokens = sdkInputTokens;
              totalOutputTokens = sdkOutputTokens;
            } else {
              // SDK usage missing (undefined/null): this model's API doesn't report usage.
              // Third-party models (GLM, DeepSeek, etc.) often omit usage entirely.
              // Set to 0 to signal "unknown" — frontend will hide the HUD.
              totalInputTokens = 0;
              totalOutputTokens = 0;
            }

            this.store.updateTokenUsage(sessionId, totalInputTokens, totalOutputTokens);
            this.sessionTokenUsage.set(sessionId, {
              inputTokens: totalInputTokens,
              outputTokens: totalOutputTokens,
            });

            // Session expired in SDK result: close V2 session, retry with fresh session + history context
            if (isError && classified?.errorCode === 'session_expired' && _retryCount < 2) {
              this.log.info(`SDK session expired for ${sessionId}, closing V2 and retrying with fresh session (attempt ${_retryCount + 1})`);
              this.closeV2Session(sessionId);
              this.sdkSessionIds.delete(sessionId);
              try { this.store.updateSdkSessionId(sessionId, ''); } catch { /* ignore */ }
              needsSessionExpiredRetry = true;
              break; // break out of stream loop, fall through to retry
            }

            wsSend(JSON.stringify({
              type: isError ? 'chat.error' : 'chat.done',
              sessionId,
              cost,
              usage: {
                inputTokens: totalInputTokens,
                outputTokens: totalOutputTokens,
              },
              ...(isError ? { error: classified?.userMessage ?? resultError ?? '模型服务返回未知错误，请稍后重试', errorCode: classified?.errorCode ?? 'unknown', rawError: resultError } : {}),
            }));

            this.log.info(`Query completed for session ${sessionId}, cost: $${cost.toFixed(4)}, context: ${totalInputTokens} tokens (SDK: input=${sdkUsage?.input_tokens ?? 0}, cache_read=${sdkUsage?.cache_read_input_tokens ?? 0}, output=${sdkUsage?.output_tokens ?? 0}, model=${this.config?.llm?.model ?? 'unknown'})`);

            // Context length warning: notify frontend when approaching context limit
            if (!isError && totalInputTokens > 0) {
              const CONTEXT_WARN_THRESHOLD = 100_000; // ~50% of typical 200k window
              const CONTEXT_CRITICAL_THRESHOLD = 150_000; // ~75% of typical 200k window
              if (totalInputTokens >= CONTEXT_CRITICAL_THRESHOLD) {
                wsSend(JSON.stringify({
                  type: 'chat.context_warning',
                  sessionId,
                  level: 'critical',
                  inputTokens: totalInputTokens,
                  message: '对话上下文已接近模型上限，建议开启新会话继续',
                }));
              } else if (totalInputTokens >= CONTEXT_WARN_THRESHOLD) {
                wsSend(JSON.stringify({
                  type: 'chat.context_warning',
                  sessionId,
                  level: 'warning',
                  inputTokens: totalInputTokens,
                  message: '对话较长，如果感觉回复质量下降，建议开启新会话',
                }));
              }
            }

            // Fire-and-forget: update user profile after conversation turn
            if (this.userProfile && this.config?.llm?.userProfile !== false && !isError) {
              this.userProfile.updateProfile(content, finalContent);
            }
            // Fire-and-forget: record turn for knowledge extraction
            if (this.knowledgeExtractor && !isError) {
              const activeSession = this.sessions.get(sessionId);
              if (activeSession?.workspace) {
                this.knowledgeExtractor.recordTurn(activeSession.workspace);
              }
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
      if (partialSaveTimer) { clearInterval(partialSaveTimer); partialSaveTimer = null; }

      // Session expired retry: close stale session and resend with fresh session + history context
      if (needsSessionExpiredRetry) {
        this.log.info(`Retrying ${sessionId} with fresh session after session_expired`);
        return this.sendMessage(sessionId, content, wsSend, undefined, _retryCount + 1);
      }

      // Stream loop exited normally (break) — check if it was due to abort
      if (abortController.signal.aborted) {
        // Clear partial messages before saving final partial message
        this.store.clearPartialMessages(sessionId);
        this.savePartialAssistantMessage(sessionId, accumulatedText + fullContent, allThinking, allToolUses, currentToolUse);
        const reason = stallAbortReason || '';

        // Check if this was a user-initiated abort (stop button).
        // User aborts preserve the V2 session so the user can send "继续" without losing context.
        const controller = this.activeStreams.get(sessionId);
        userInitiatedAbort = reason === 'user' || (controller as any)?._abortReason === 'user';

        if (userInitiatedAbort) {
          // Keep V2 session alive — the CLI's interrupt() will gracefully end the current turn,
          // and the user's next message will continue in the same session without resume.
          this.log.info(`User abort for ${sessionId}, keeping V2 session alive`);
        } else {
          // System abort (stall, process dead, etc.) — close V2 session, it may be in a bad state.
          this.closeV2Session(sessionId);
          this.log.info(`System abort for ${sessionId}, V2 session closed (reason: ${reason})`);
        }

        wsSend(JSON.stringify({
          type: 'chat.aborted',
          sessionId,
          ...(reason ? { reason } : {}),
        }));
      }
    } catch (err: any) {
      if (stallChecker) clearInterval(stallChecker);
      if (partialSaveTimer) { clearInterval(partialSaveTimer); partialSaveTimer = null; }
      if (err?.name === 'AbortError' || abortController.signal.aborted) {
        // Save partial assistant content so the user doesn't lose what was already streamed
        this.store.clearPartialMessages(sessionId);
        this.savePartialAssistantMessage(sessionId, accumulatedText + fullContent, allThinking, allToolUses, currentToolUse);

        const reason = stallAbortReason || '';
        // Check if user-initiated abort
        const controller = this.activeStreams.get(sessionId);
        userInitiatedAbort = reason === 'user' || (controller as any)?._abortReason === 'user';

        // User abort: keep V2 session alive for "继续"
        if (userInitiatedAbort) {
          this.log.info(`User abort (catch) for ${sessionId}, keeping V2 session alive`);
          wsSend(JSON.stringify({ type: 'chat.aborted', sessionId }));
        }
        // Auto-retry for recoverable system errors (stall, process_dead, etc.) — max 2 retries
        else if (_retryCount < 2 && ClaudeSessionManager.AUTO_RETRY_ERRORS.has(reason)) {
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
        // System abort: close V2 session
        else {
          this.closeV2Session(sessionId);
          this.log.info(`System abort (catch) for ${sessionId}, V2 session closed (reason: ${reason})`);
          wsSend(JSON.stringify({
            type: 'chat.aborted',
            sessionId,
            ...(reason ? { reason } : {}),
          }));
        }
      } else {
        const { errorCode, userMessage } = this.classifyError(err);
        const rawMessage = err instanceof Error ? err.message : String(err);
        this.log.error(`Query error for session ${sessionId}`, { error: rawMessage, errorCode, errorName: err instanceof Error ? err.name : undefined, errorStack: err instanceof Error ? err.stack?.slice(0, 500) : undefined });

        // Auto-retry for recoverable API errors (400, 500, overloaded, network) — max 2 retries
        if (_retryCount < 2 && ClaudeSessionManager.AUTO_RETRY_ERRORS.has(errorCode)) {
          this.log.info(`Auto-retrying session ${sessionId} after ${errorCode} (attempt ${_retryCount + 1})`);
          this.closeV2Session(sessionId);
          // For session_expired: clear stale SDK session ID so next getOrCreateV2Session creates fresh
          if (errorCode === 'session_expired') {
            this.sdkSessionIds.delete(sessionId);
            try { this.store.updateSdkSessionId(sessionId, ''); } catch { /* ignore */ }
          }
          wsSend(JSON.stringify({
            type: 'chat.delta',
            sessionId,
            deltaType: 'text',
            content: errorCode === 'session_expired'
              ? '\n\n[会话已过期，正在恢复历史上下文并重试...]\n'
              : '\n\n[遇到临时错误，自动重试中...]\n',
          }));
          await new Promise(r => setTimeout(r, 2000));
          if (this.activeStreams.has(sessionId)) {
            this.log.info(`User sent new message during retry wait for ${sessionId}, canceling auto-retry`);
            wsSend(JSON.stringify({ type: 'chat.error', sessionId, error: userMessage, errorCode, rawError: rawMessage }));
            return;
          }
          // session_expired: retry with original user message (the failed turn never executed)
          const retryMsg = errorCode === 'session_expired' ? content : '继续刚才的工作，不要重复已完成的部分';
          return this.sendMessage(sessionId, retryMsg, wsSend, undefined, _retryCount + 1);
        }

        wsSend(JSON.stringify({ type: 'chat.error', sessionId, error: userMessage, errorCode, rawError: rawMessage }));
      }
    } finally {
      this.markStreamEnd(sessionId);
      this.activeQueries.delete(sessionId);
      this.sessionWsSend.delete(sessionId);
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
    // SDK session expired — must be caught before generic "not found"
    if (/No conversation found/i.test(msg)) {
      return { errorCode: 'session_expired', userMessage: '会话已过期，正在自动恢复...' };
    }
    if (/\b404\b/.test(msg) || /not found/i.test(msg)) {
      return { errorCode: 'not_found', userMessage: `请求返回 404，资源不存在: ${msg}` };
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
    onComplete?: (reply: string) => void,
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

    this.markStreamStart(sessionId, abortController);

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
      // Check if process is dead — abort immediately
      const cronV2Info = this.v2Sessions.get(sessionId);
      const cronPid = cronV2Info ? (cronV2Info.session as any).pid : undefined;
      if (cronPid !== undefined) {
        try { process.kill(cronPid, 0); } catch {
          this.log.warn(`Cron: V2 session process dead (PID: ${cronPid}) for ${sessionId}, aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
          return;
        }
      }
      if (elapsed > ClaudeSessionManager.STREAM_STALL_MS) {
        this.log.warn(`Cron stream stalled for session ${sessionId}, aborting...`);
        abortController.abort();
        clearInterval(stallChecker);
      }
    }, 30_000);
    stallChecker.unref();

    try {
      const { session: v2Session } = await this.getOrCreateV2Session(sessionId);

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
            // Notify caller with the accumulated reply
            if (onComplete && fullContent) {
              try { onComplete(fullContent); } catch (e) {
                this.log.warn(`onComplete callback error for ${sessionId}`, { error: String(e) });
              }
            }
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
      this.markStreamEnd(sessionId);
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
    onThinking?: (chunk: string) => void,
    onToolStatus?: (toolName: string, status: 'start' | 'end') => void,
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

    this.markStreamStart(sessionId, abortController);

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
      // Check if process is dead — abort immediately
      const chatbotV2Info = this.v2Sessions.get(sessionId);
      const chatbotPid = chatbotV2Info ? (chatbotV2Info.session as any).pid : undefined;
      if (chatbotPid !== undefined) {
        try { process.kill(chatbotPid, 0); } catch {
          this.log.warn(`Chatbot: V2 session process dead (PID: ${chatbotPid}) for ${sessionId}, aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
          return;
        }
      }
      if (elapsed > ClaudeSessionManager.STREAM_STALL_MS) {
        this.log.warn(`Chatbot stream stalled for session ${sessionId}, aborting...`);
        abortController.abort();
        clearInterval(stallChecker);
      }
    }, 30_000);
    stallChecker.unref();

    try {
      const { session: v2Session } = await this.getOrCreateV2Session(sessionId);

      // Inject user profile + Sman context into content for Claude
      const profilePrefix = this.userProfile?.getProfileForPrompt() ?? '';
      const workspace = session.workspace;
      const projectName = path.basename(workspace);
      const smanContext = this.buildSmanContext(projectName, sessionId);
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
      let thinkingChunks: string[] = [];
      let pendingToolName: string | null = null;

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
            break;
          }
          case 'stream_event': {
            const rawEvent = (sdkMsg as any).event;
            // Track thinking blocks: accumulate deltas, push complete text on block stop
            if (rawEvent.type === 'content_block_start' && rawEvent.content_block?.type === 'thinking') {
              thinkingChunks = [];
            } else if (rawEvent.type === 'content_block_delta' && rawEvent.delta?.type === 'thinking_delta') {
              thinkingChunks.push(rawEvent.delta.thinking);
            } else if (rawEvent.type === 'content_block_stop' && thinkingChunks.length > 0) {
              const fullThinking = thinkingChunks.join('');
              thinkingChunks = [];
              onThinking?.(fullThinking);
            }
            // Tool use start — notify with tool name
            if (rawEvent.type === 'content_block_start' && rawEvent.content_block?.type === 'tool_use') {
              const toolName = rawEvent.content_block.name || 'unknown';
              pendingToolName = toolName;
              onToolStatus?.(toolName, 'start');
            }
            // Tool result start — previous tool finished
            if (rawEvent.type === 'content_block_start' && rawEvent.content_block?.type === 'tool_result') {
              if (pendingToolName) {
                onToolStatus?.(pendingToolName, 'end');
                pendingToolName = null;
              }
            }
            // Handle text and tool_use deltas
            const delta = this.extractDeltaText(rawEvent);
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
            // Fire-and-forget: record turn for knowledge extraction
            if (this.knowledgeExtractor) {
              const chatbotSession = this.sessions.get(sessionId);
              if (chatbotSession?.workspace) {
                this.knowledgeExtractor.recordTurn(chatbotSession.workspace);
              }
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
      this.markStreamEnd(sessionId);
      this.streamDone.delete(sessionId);
      streamResolve();
    }
  }

  async sendMessageForStep(
    sessionId: string,
    content: string,
    abortController: AbortController,
    onDelta: (text: string) => void,
    systemPrompt?: string,
  ): Promise<string> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    if (this.activeStreams.has(sessionId)) {
      const done = this.streamDone.get(sessionId);
      if (done) {
        this.log.info(`sendMessageForStep: waiting for previous stream on session ${sessionId}`);
        await done;
      }
    }

    if (!this.config?.llm?.apiKey) {
      throw new Error('缺少 API Key，请在设置中配置');
    }
    if (!this.config?.llm?.model) {
      throw new Error('缺少 Model 配置，请在设置中选择模型');
    }

    // 步骤执行不写 SQLite — 纯内存操作，不污染主会话列表
    this.markStreamStart(sessionId, abortController);

    let streamResolve!: () => void;
    const streamPromise = new Promise<void>(r => { streamResolve = r; });
    this.streamDone.set(sessionId, streamPromise);

    // Stall detector
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
          this.log.warn(`sendMessageForStep: V2 session lost while tool in progress, aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
          return;
        }
        const pid = (v2Info.session as any).pid;
        if (pid !== undefined) {
          try { process.kill(pid, 0); } catch {
            this.log.warn(`sendMessageForStep: V2 session process dead (PID: ${pid}), aborting...`);
            abortController.abort();
            clearInterval(stallChecker);
            return;
          }
        }
        if (elapsed > ClaudeSessionManager.TOOL_STALL_MS) {
          this.log.warn(`sendMessageForStep: tool no-progress timeout (${Math.round(elapsed / 1000)}s), aborting...`);
          abortController.abort();
          clearInterval(stallChecker);
        }
        return;
      }
      if (elapsed > ClaudeSessionManager.STREAM_STALL_MS) {
        this.log.warn(`sendMessageForStep: stream stalled (${Math.round(elapsed / 1000)}s), aborting...`);
        abortController.abort();
        clearInterval(stallChecker);
      }
    }, 30_000);
    stallChecker.unref();

    try {
      const { session: v2Session } = await this.getOrCreateV2Session(sessionId);
      const workspace = session.workspace;

      // 步骤执行不注入完整的 buildSmanContext（避免 dev-workflow 等复杂流程）
      // 使用调用方提供的精简 systemPrompt，或直接发送内容
      const contentWithPrefix = systemPrompt ? `${systemPrompt}\n\n${content}` : content;
      await v2Session.send(contentWithPrefix);

      let fullContent = '';

      for await (const sdkMsg of v2Session.stream()) {
        lastActivityAt = Date.now();

        if (abortController.signal.aborted) break;

        switch (sdkMsg.type) {
          case 'assistant': {
            toolInProgress = false;
            break;
          }
          case 'stream_event': {
            const rawEvent = (sdkMsg as any).event;
            if (rawEvent.type === 'content_block_delta' && rawEvent.delta?.type === 'text_delta') {
              const text = rawEvent.delta.text;
              if (text) {
                fullContent += text;
                onDelta(text);
              }
            }
            // Track tool_use for stall detection
            if (rawEvent.type === 'content_block_start' && rawEvent.content_block?.type === 'tool_use') {
              toolInProgress = true;
            }
            if (rawEvent.type === 'content_block_delta' && rawEvent.delta?.type === 'input_json_delta') {
              toolInProgress = true;
            }
            break;
          }
          case 'result': {
            const result = sdkMsg as any;
            if (result.session_id) {
              this.sdkSessionIds.set(sessionId, result.session_id);
              // 不写 SQLite — 纯内存记录
            }
            // 不保存 assistant 消息到 SQLite
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
      this.markStreamEnd(sessionId);
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
    allToolUses: Array<{ id: string; name: string; input: string; summary?: string; elapsedSeconds?: number }>,
    currentToolUse: { id: string; name: string; input: string; summary?: string; elapsedSeconds?: number } | null,
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
      summary?: string;
      status?: 'completed';
      elapsedSeconds?: number;
    }> = [];

    if (allThinking.trim()) {
      contentBlocks.push({ type: 'thinking', thinking: allThinking.trim() });
    }
    for (const tu of allToolUses) {
      try {
        const input = JSON.parse(tu.input);
        contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input, summary: tu.summary, status: 'completed', elapsedSeconds: tu.elapsedSeconds });
      } catch {
        contentBlocks.push({ type: 'tool_use', id: tu.id, name: tu.name, input: tu.input, summary: tu.summary, status: 'completed', elapsedSeconds: tu.elapsedSeconds });
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
    this.cancelPendingAskUser(sessionId);
    const controller = this.activeStreams.get(sessionId);
    if (controller) {
      (controller as any)._abortReason = reason;
      controller.abort();
      // Do NOT delete from activeStreams here — sendMessage's finally block handles cleanup.
      // Do NOT close V2 session here — sendMessage's stream loop exit handler does it
      // after saving partial content and sending chat.aborted to the frontend.
      this.log.info(`Session aborted: ${sessionId}${reason ? ` (${reason})` : ''}`);
    }
    // Interrupt the active stream() generator to unblock the for-await loop.
    // The Query object (returned by v2Session.stream()) has an interrupt() method
    // that signals the CLI to stop the current tool execution and yield control back.
    const query = this.activeQueries.get(sessionId);
    if (query && typeof (query as any).interrupt === 'function') {
      this.log.info(`Interrupting active query for session ${sessionId}`);
      (query as any).interrupt().catch(() => {});
    }
  }

  /** Clear accumulated token usage for a session (called when session is deleted) */
  clearTokenUsage(sessionId: string): void {
    this.sessionTokenUsage.delete(sessionId);
  }

  listSessions(): ActiveSession[] {
    const allSessions = this.store.listSessions();
    return allSessions.map(s => {
      let active = this.sessions.get(s.id);
      if (!active) {
        // Normalize workspace for legacy sessions with non-canonical paths (e.g. \d\core → D:\core)
        let ws = s.workspace;
        try {
          const normalized = normalizeWorkspacePath(ws);
          if (normalized !== ws) {
            this.store.updateWorkspace(s.id, normalized);
            ws = normalized;
          }
        } catch { /* workspace may no longer exist */ }
        active = {
          id: s.id,
          workspace: ws,
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

  getHistory(sessionId: string): Array<Message & { timestamp: number }> {
    const messages = this.store.getMessages(sessionId);
    return messages.map(msg => ({
      ...msg,
      timestamp: new Date(msg.createdAt + 'Z').getTime(),
    }));
  }

  /**
   * Build a text context from recent conversation turns for fresh V2 sessions.
   * Each turn = user message + assistant reply. Returns empty string if no history.
   */
  private buildHistoryContext(history: Array<Message & { timestamp: number }>, turns: number): string {
    if (history.length === 0) return '';
    // Group messages into turns (user + assistant pairs)
    const turnsList: Array<{ user: string; assistant: string }> = [];
    let currentUser = '';
    for (const msg of history) {
      if (msg.role === 'user') {
        currentUser = msg.content;
      } else if (msg.role === 'assistant' && currentUser) {
        turnsList.push({ user: currentUser, assistant: msg.content });
        currentUser = '';
      }
    }
    if (turnsList.length === 0) return '';
    // Take last N turns
    const recent = turnsList.slice(-turns);
    const lines: string[] = ['[历史对话上下文 - 继续之前的讨论]', ''];
    for (let i = 0; i < recent.length; i++) {
      const t = recent[i];
      lines.push(`--- 第 ${i + 1} 轮 ---`);
      lines.push(`用户: ${t.user}`);
      lines.push(`助手: ${t.assistant}`);
      lines.push('');
    }
    lines.push('[继续当前对话]');
    return lines.join('\n');
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
    this.markAllStreamsCleared();
    for (const [sessionId] of this.v2Sessions) {
      this.closeV2Session(sessionId);
    }
    this.v2Sessions.clear();
    this.preheatPromises.clear();
    this.sessions.clear();
    this.sdkSessionIds.clear();
  }
}

function guessMimeType(fileName: string): string {
  const ext = fileName.split('.').pop()?.toLowerCase() ?? '';
  const map: Record<string, string> = {
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml',
    pdf: 'application/pdf',
    json: 'application/json', xml: 'application/xml',
    txt: 'text/plain', md: 'text/markdown', csv: 'text/csv', html: 'text/html', htm: 'text/html', css: 'text/css', js: 'text/javascript', ts: 'text/typescript',
  };
  return map[ext] ?? 'application/octet-stream';
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
