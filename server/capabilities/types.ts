/**
 * Capability system types — on-demand loading of skills/tools into Claude sessions.
 */

/** How a capability is activated at runtime */
export type CapabilityExecutionMode =
  | 'mcp-dynamic'        // Inject MCP tools via setMcpServers()
  | 'instruction-inject' // Return SKILL.md text for Claude to follow
  | 'cli-subprocess';    // Execute via child process, return result

/** A single capability entry in the registry */
export interface CapabilityEntry {
  /** Unique identifier, e.g. "office-skills" */
  id: string;
  /** Display name */
  name: string;
  /** One-line description for catalog display */
  description: string;
  /** Activation mechanism */
  executionMode: CapabilityExecutionMode;
  /** Keywords/phrases that suggest this capability should be activated */
  triggers: string[];
  /** Relative path to runner module from server/capabilities/ */
  runnerModule: string;
  /** Original plugin directory path (for loading SKILL.md/scripts) */
  pluginPath: string;
  /** Whether this capability is enabled */
  enabled: boolean;
  /** Capability version */
  version: string;
}

/** The top-level capabilities registry file */
export interface CapabilityManifest {
  version: string;
  capabilities: Record<string, CapabilityEntry>;
}

/** Runner interface — each capability module implements this */
export interface CapabilityRunner {
  /** For mcp-dynamic: return MCP tool definitions */
  getMcpTools?: () => Array<{
    name: string;
    description: string;
    schema: Record<string, any>;
    handler: (args: any, extra: any) => Promise<any>;
  }>;

  /** For instruction-inject: return the instruction text */
  getInstructions?: () => string;

  /** For cli-subprocess: execute a command */
  execute?: (command: string, args: Record<string, string>) => Promise<string>;
}

/** Options for creating the capability gateway MCP server */
export interface CapabilityGatewayOptions {
  /** The capability registry */
  registry: import('./registry.js').CapabilityRegistry;
  /** Callback to get active SDK session for a given session ID */
  getActiveSession: (sessionId: string) => any; // SDKSession
  /** Plugins base directory */
  pluginsDir: string;
  /** Callback to get LLM config for semantic search and experience learning */
  getLlmConfig?: () => SemanticSearchLlmConfig | null;
}

/** LLM configuration for semantic search */
export interface SemanticSearchLlmConfig {
  apiKey: string;
  model: string;
  baseUrl?: string;
}

/** Per-capability usage statistics */
export interface CapabilityUsageEntry {
  /** Total times this capability was loaded */
  count: number;
  /** ISO timestamp of last use */
  lastUsed: string | null;
  /** Success rate (0-1) */
  successRate: number;
  /** Recent task descriptions (max 10) */
  recentTasks: string[];
}

/** Usage tracking file format */
export interface CapabilityUsageManifest {
  version: string;
  usage: Record<string, CapabilityUsageEntry>;
}

/** User-learned capability (stored separately from standard capabilities) */
export interface UserCapabilityEntry {
  /** Unique ID */
  id: string;
  /** Display name */
  name: string;
  /** What pattern was learned */
  pattern: string;
  /** Parent capability that inspired this learning */
  learnedFrom: string;
  /** Shortcut keywords for matching */
  shortcuts: string[];
  /** Usage count */
  usageCount: number;
  /** ISO timestamp */
  createdAt: string;
  /** ISO timestamp */
  lastUsed: string | null;
}

/** User capabilities file format */
export interface UserCapabilityManifest {
  version: string;
  userCapabilities: Record<string, UserCapabilityEntry>;
}
