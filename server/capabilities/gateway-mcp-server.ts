/**
 * Capability Gateway MCP Server — exposes capability_list/load/run tools.
 *
 * This is a lightweight in-process MCP server injected into every session.
 * It lets Claude discover and activate capabilities on demand.
 */

import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import type { CapabilityGatewayOptions, CapabilityEntry, CapabilityRunner } from './types.js';
import { createOfficeSkillsMcpTools } from './office-skills-runner.js';
import { createFrontendSlidesInstructions } from './frontend-slides-runner.js';
import { createGenericInstructions } from './generic-instruction-runner.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

/** Track dynamically loaded capability MCP servers per session */
const loadedServers = new Map<string, Map<string, McpSdkServerConfigWithInstance>>();

export function createCapabilityGatewayMcpServer(options: CapabilityGatewayOptions): McpSdkServerConfigWithInstance {
  const { registry, getActiveSession, pluginsDir } = options;

  const listTool = tool(
    'capability_list',
    'List all available on-demand capabilities. '
    + 'Call this when you need to discover what extended capabilities are available '
    + '(office documents, presentations, QA testing, etc.). '
    + 'Search by keywords (exact OR match) or natural language (semantic match).',
    {
      keywords: z.array(z.string()).optional().describe('Keywords for exact OR matching against triggers/names (e.g., ["PPT", "Word"])'),
      natural_language: z.string().optional().describe('Natural language description for semantic matching when keywords miss (e.g., "我需要做季度销售PPT")'),
    },
    async (args: any) => {
      const keywords: string[] = args.keywords ?? [];
      const naturalLanguage: string = args.natural_language ?? '';

      // Step 1: Exact OR keyword matching
      let capabilities = keywords.length > 0
        ? registry.searchByKeywords(keywords)
        : registry.listCapabilities();

      // Step 2: If keyword match missed and we have natural language, try LLM semantic search
      if (capabilities.length === 0 && naturalLanguage.trim()) {
        const llmConfig = options.getLlmConfig?.();
        if (llmConfig) {
          const semanticIds = await registry.searchSemantic(naturalLanguage, keywords, llmConfig);
          if (semanticIds.length > 0) {
            // Map IDs back to CapabilityEntry
            const semanticCaps = semanticIds
              .map((id) => registry.getCapability(id))
              .filter((c): c is CapabilityEntry => c != null);
            // Also include user capability matches as synthetic entries
            for (const id of semanticIds) {
              if (!semanticCaps.find((c) => c.id === id)) {
                const userCap = registry.getUserCapability(id);
                if (userCap) {
                  semanticCaps.push({
                    id: userCap.id,
                    name: userCap.name,
                    description: userCap.pattern,
                    executionMode: 'instruction-inject',
                    triggers: userCap.shortcuts,
                    runnerModule: '',
                    pluginPath: '',
                    enabled: true,
                    version: '1.0.0',
                  });
                }
              }
            }
            capabilities = semanticCaps;
          }
        }
      }

      // Also include user capabilities that match keywords (always search both)
      if (keywords.length > 0) {
        const userCaps = registry.listUserCapabilities();
        for (const uc of userCaps) {
          if (capabilities.find((c) => c.id === uc.id)) continue;
          const haystack = [uc.id, uc.name, uc.pattern, ...uc.shortcuts].join(' ').toLowerCase();
          const matches = keywords.some((kw) => haystack.includes(kw.toLowerCase()));
          if (matches) {
            capabilities.push({
              id: uc.id,
              name: uc.name,
              description: uc.pattern,
              executionMode: 'instruction-inject',
              triggers: uc.shortcuts,
              runnerModule: '',
              pluginPath: '',
              enabled: true,
              version: '1.0.0',
            });
          }
        }
      }

      if (capabilities.length === 0) {
        return textResult(naturalLanguage || keywords.length > 0
          ? `No capabilities matching your request. Available capabilities may not cover this task.`
          : 'No capabilities available.');
      }

      const lines = capabilities.map((c) => {
        const triggerStr = c.triggers.slice(0, 5).join(', ');
        const usage = registry.getUsage(c.id);
        const usageNote = usage ? ` (used ${usage.count}x)` : '';
        return `- **${c.id}**: ${c.name} — ${c.description}${usageNote}\n  Triggers: ${triggerStr}\n  Mode: ${c.executionMode}`;
      });

      return textResult(
        `Available capabilities (${capabilities.length}):\n\n${lines.join('\n\n')}\n\n`
        + 'To activate a capability, call `capability_load` with the capability ID.',
      );
    },
  );

  const loadTool = tool(
    'capability_load',
    'Load and activate a capability by ID. '
    + 'This makes the capability\'s tools or instructions available in the current session. '
    + 'Call `capability_list` first to discover available capabilities.',
    {
      capability_id: z.string().describe('The capability ID to load (e.g., "office-skills")'),
      session_id: z.string().describe('Current session ID for dynamic MCP injection'),
    },
    async (args: any) => {
      const cap = registry.getCapability(args.capability_id);
      if (!cap) {
        return errorResult(`Unknown capability: "${args.capability_id}". Call capability_list to see available options.`);
      }
      if (!cap.enabled) {
        return errorResult(`Capability "${cap.name}" is currently disabled.`);
      }

      switch (cap.executionMode) {
        case 'mcp-dynamic':
          registry.recordUsage(cap.id, args.task_description, true);
          return await handleMcpDynamic(cap, args.session_id);
        case 'instruction-inject':
          registry.recordUsage(cap.id, args.task_description, true);
          return handleInstructionInject(cap);
        case 'cli-subprocess':
          return textResult(
            `Capability "${cap.name}" is available as a CLI command.\n`
            + `Runner module: ${cap.runnerModule}\n`
            + `Use capability_run to execute commands.`,
          );
        default:
          return errorResult(`Unknown execution mode: ${cap.executionMode}`);
      }
    },
  );

  const runTool = tool(
    'capability_run',
    'Execute a capability command directly. Use for simple execution tasks '
    + 'where full MCP tool exposure is not needed.',
    {
      capability_id: z.string().describe('The capability ID to execute'),
      command: z.string().describe('Sub-command to execute'),
    },
    async (args: any) => {
      const cap = registry.getCapability(args.capability_id);
      if (!cap) {
        return errorResult(`Unknown capability: "${args.capability_id}".`);
      }

      // For CLI-subprocess mode, delegate to runner
      if (cap.executionMode !== 'cli-subprocess') {
        return errorResult(
          `Capability "${cap.name}" uses ${cap.executionMode} mode. `
          + `Use capability_load instead of capability_run.`,
        );
      }

      try {
        const runner = loadRunner(cap, pluginsDir);
        if (!runner.execute) {
          return errorResult(`Capability "${cap.name}" does not support direct execution.`);
        }
        const result = await runner.execute(args.command, args.args ?? {});
        return textResult(result);
      } catch (e: any) {
        return errorResult(`Execution failed: ${e.message}`);
      }
    },
  );

  async function handleMcpDynamic(cap: CapabilityEntry, sessionId: string): Promise<ToolResult> {
    const session = getActiveSession(sessionId);
    if (!session) {
      return errorResult(`No active session found for ID: ${sessionId}`);
    }

    try {
      // Create runner-specific MCP tools
      const mcpTools = createRunnerMcpTools(cap, pluginsDir);
      const serverName = `capability-${cap.id}`;

      const capServer = createSdkMcpServer({
        name: serverName,
        version: cap.version,
        tools: mcpTools,
      });

      // Track loaded servers for this session
      if (!loadedServers.has(sessionId)) {
        loadedServers.set(sessionId, new Map());
      }
      const sessionServers = loadedServers.get(sessionId)!;

      // If already loaded, return existing info
      if (sessionServers.has(cap.id)) {
        return textResult(`Capability "${cap.name}" is already loaded and active.`);
      }

      sessionServers.set(cap.id, capServer);

      // Inject all loaded capability servers via setMcpServers
      const allServers: Record<string, McpSdkServerConfigWithInstance> = {};
      for (const [id, srv] of sessionServers) {
        allServers[`capability-${id}`] = srv;
      }

      const result = await session.setMcpServers(allServers);

      const toolNames = mcpTools.map((t: any) => t.name).join(', ');
      return textResult(
        `Capability "${cap.name}" loaded successfully.\n`
        + `Available tools: ${toolNames}\n`
        + `Servers added: ${result.added.join(', ') || '(already present)'}\n`
        + (Object.keys(result.errors).length > 0
          ? `Errors: ${JSON.stringify(result.errors)}\n`
          : '')
        + `You can now use these tools to complete the task.`,
      );
    } catch (e: any) {
      return errorResult(`Failed to load capability "${cap.name}": ${e.message}`);
    }
  }

  function handleInstructionInject(cap: CapabilityEntry): ToolResult {
    try {
      const instructions = createRunnerInstructions(cap, pluginsDir);
      if (!instructions) {
        return errorResult(`No instructions available for capability "${cap.name}".`);
      }
      return textResult(
        `Capability "${cap.name}" activated. Follow the instructions below:\n\n---\n${instructions}\n---\n`
        + `\nUse your existing tools (Bash, Write, Edit, etc.) to execute the workflow described above.`,
      );
    } catch (e: any) {
      return errorResult(`Failed to load instructions for "${cap.name}": ${e.message}`);
    }
  }

  return createSdkMcpServer({
    name: 'capability-gateway',
    version: '1.0.0',
    tools: [listTool, loadTool, runTool],
  });
}

/** Clean up loaded servers for a session (call on session close) */
export function cleanupLoadedCapabilities(sessionId: string): void {
  loadedServers.delete(sessionId);
}

// --- Runner loading helpers ---

function createRunnerMcpTools(cap: CapabilityEntry, pluginsDir: string): any[] {
  switch (cap.id) {
    case 'office-skills':
      return createOfficeSkillsMcpTools(pluginsDir);
    default:
      throw new Error(`No MCP tool factory for capability: ${cap.id}`);
  }
}

function createRunnerInstructions(cap: CapabilityEntry, pluginsDir: string): string | null {
  switch (cap.id) {
    case 'frontend-slides':
      return createFrontendSlidesInstructions(pluginsDir);
    case 'internal-comms':
    case 'theme-factory':
    case 'skill-creator':
      return createGenericInstructions(pluginsDir, cap.pluginPath);
    default:
      // Generic: try to read SKILL.md from plugin path
      return readPluginSkillMd(cap, pluginsDir);
  }
}

function readPluginSkillMd(cap: CapabilityEntry, pluginsDir: string): string | null {
  const fs = require('node:fs');
  const path = require('node:path');

  // Try common skill file locations
  const candidates = [
    path.join(pluginsDir, cap.pluginPath, 'SKILL.md'),
    path.join(pluginsDir, cap.pluginPath, 'skills', cap.id, 'SKILL.md'),
    path.join(pluginsDir, cap.pluginPath, 'skills', cap.id, 'skill.md'),
  ];

  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return fs.readFileSync(candidate, 'utf-8');
    }
  }
  return null;
}

function loadRunner(cap: CapabilityEntry, pluginsDir: string): CapabilityRunner {
  // For CLI-subprocess mode, return a simple runner
  return {
    execute: async (command: string, args: Record<string, string>) => {
      return `CLI execution not yet implemented for ${cap.id}: ${command}`;
    },
  };
}
