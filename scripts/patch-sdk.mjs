#!/usr/bin/env node

/**
 * Patch @anthropic-ai/claude-agent-sdk to support full V2 session configuration.
 *
 * The native V2 SDK only passes model/env/pathToClaudeCodeExecutable to SessionImpl,
 * ignoring systemPrompt, mcpServers, plugins, permissionMode, cwd, etc.
 * This patch mirrors what hello-halo does to make V2 sessions fully configurable.
 *
 * Also removes CLAUDE_CODE_ENTRYPOINT/CLAUDE_AGENT_SDK_VERSION markers so the CLI
 * subprocess appears as a native invocation (enabling /skill commands natively).
 */

import { readFileSync, writeFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Resolve SDK file path
const sdkPaths = [
  // Local dev: scripts/patch-sdk.mjs → node_modules/ is at project root
  join(__dirname, '..', 'node_modules', '@anthropic-ai', 'claude-agent-sdk', 'sdk.mjs'),
  // pnpm monorepo
  join(__dirname, '..', 'node_modules', '.pnpm', 'node_modules', '@anthropic-ai', 'claude-agent-sdk', 'sdk.mjs'),
  // Remote deploy: patch-sdk.mjs is in /root/sman/app/ alongside node_modules/
  join(__dirname, 'node_modules', '@anthropic-ai', 'claude-agent-sdk', 'sdk.mjs'),
];

let sdkPath = null;
for (const p of sdkPaths) {
  try {
    readFileSync(p, 'utf-8');
    sdkPath = p;
    break;
  } catch {}
}

if (!sdkPath) {
  console.log('[patch-sdk] SDK file not found, skipping patch');
  process.exit(0);
}

let content = readFileSync(sdkPath, 'utf-8');

// Check if already patched
if (content.includes('[PATCHED_BY_SMAN]')) {
  console.log('[patch-sdk] Already patched, skipping');
  process.exit(0);
}

const patches = [
  // ── Patch 1: ProcessTransport.mappedProcess - expose pid ──
  {
    name: 'Expose child process pid in mappedProcess',
    find: `    const mappedProcess = {
      stdin: childProcess.stdin,
      stdout: childProcess.stdout,
      get killed() {`,
    replace: `    const mappedProcess = {
      stdin: childProcess.stdin,
      stdout: childProcess.stdout,
      // [PATCHED_BY_SMAN] Expose pid for health system process tracking
      get pid() {
        return childProcess.pid;
      },
      get killed() {`,
  },

  // ── Patch 2: ProcessTransport.initialize - remove CLAUDE_CODE_ENTRYPOINT ──
  {
    name: 'Remove CLAUDE_CODE_ENTRYPOINT in ProcessTransport.initialize',
    find: `      if (!env.CLAUDE_CODE_ENTRYPOINT) {
        env.CLAUDE_CODE_ENTRYPOINT = "sdk-ts";
      }
      delete env.NODE_OPTIONS;`,
    replace: `      // [PATCHED_BY_SMAN] Removed CLAUDE_CODE_ENTRYPOINT to appear as native CLI
      delete env.NODE_OPTIONS;`,
  },

  // ── Patch 3: SessionImpl constructor - remove CLAUDE_CODE_ENTRYPOINT ──
  {
    name: 'Remove CLAUDE_CODE_ENTRYPOINT in SessionImpl constructor',
    find: `    const processEnv = { ...options.env ?? process.env };
    if (!processEnv.CLAUDE_CODE_ENTRYPOINT) {
      processEnv.CLAUDE_CODE_ENTRYPOINT = "sdk-ts";
    }
    this.abortController = createAbortController();`,
    replace: `    // [PATCHED_BY_SMAN] Removed CLAUDE_CODE_ENTRYPOINT to appear as native CLI
    const processEnv = { ...options.env ?? process.env };
    this.abortController = createAbortController();`,
  },

  // ── Patch 4: SessionImpl constructor - forward all options to ProcessTransport ──
  {
    name: 'Forward all options in SessionImpl constructor',
    find: `    const transport = new ProcessTransport({
      abortController: this.abortController,
      pathToClaudeCodeExecutable,
      env: processEnv,
      executable: options.executable ?? (isRunningWithBun() ? "bun" : "node"),
      executableArgs: options.executableArgs ?? [],
      extraArgs: {},
      maxThinkingTokens: undefined,
      maxTurns: undefined,
      maxBudgetUsd: undefined,
      model: options.model,
      fallbackModel: undefined,
      permissionMode: "default",
      allowDangerouslySkipPermissions: false,
      continueConversation: false,
      resume: options.resume,
      settingSources: [],
      allowedTools: [],
      disallowedTools: [],
      mcpServers: {},
      strictMcpConfig: false,
      canUseTool: false,
      hooks: false,
      includePartialMessages: false,
      forkSession: false,
      resumeSessionAt: undefined
    });
    this.query = new Query(transport, false, undefined, undefined, this.abortController, new Map);
    this.query.streamInput(this.inputStream);`,
    replace: `    // [PATCHED_BY_SMAN] Process SDK-type MCP servers
    const sdkMcpServers = new Map;
    const processedMcpServers = {};
    if (options.mcpServers) {
      for (const [name, config] of Object.entries(options.mcpServers)) {
        if (config.type === "sdk" && "instance" in config) {
          sdkMcpServers.set(name, config.instance);
          processedMcpServers[name] = { type: "sdk", name };
        } else {
          processedMcpServers[name] = config;
        }
      }
    }
    const transport = new ProcessTransport({
      abortController: this.abortController,
      pathToClaudeCodeExecutable,
      cwd: options.cwd,
      stderr: options.stderr,
      env: processEnv,
      executable: options.executable ?? (isRunningWithBun() ? "bun" : "node"),
      executableArgs: options.executableArgs ?? [],
      extraArgs: options.extraArgs ?? {},
      customSystemPrompt: typeof options.systemPrompt === 'string' ? options.systemPrompt : (options.systemPrompt?.append ?? ""),
      maxThinkingTokens: options.maxThinkingTokens ?? undefined,
      maxTurns: options.maxTurns ?? undefined,
      maxBudgetUsd: options.maxBudgetUsd ?? undefined,
      model: options.model,
      fallbackModel: options.fallbackModel ?? undefined,
      permissionMode: options.permissionMode ?? "default",
      allowDangerouslySkipPermissions: options.allowDangerouslySkipPermissions ?? false,
      continueConversation: options.continueConversation ?? false,
      resume: options.resume,
      settingSources: options.settingSources ?? [],
      allowedTools: options.allowedTools ?? [],
      disallowedTools: options.disallowedTools ?? [],
      mcpServers: processedMcpServers,
      strictMcpConfig: options.strictMcpConfig ?? false,
      canUseTool: options.canUseTool ?? false,
      hooks: options.hooks ?? false,
      includePartialMessages: options.includePartialMessages ?? true,
      forkSession: options.forkSession ?? false,
      resumeSessionAt: options.resumeSessionAt ?? undefined,
      plugins: options.plugins
    });
    // [PATCHED_BY_SMAN] Build initConfig (mirrors query() behavior)
    const customSystemPrompt = typeof options.systemPrompt === 'string' ? options.systemPrompt : (options.systemPrompt?.append ?? "");
    const initConfig = {
      systemPrompt: customSystemPrompt,
      appendSystemPrompt: options.systemPrompt?.type === 'preset' ? options.systemPrompt.append : undefined,
      agents: options.agents
    };
    this.query = new Query(transport, false, options.canUseTool, options.hooks, this.abortController, sdkMcpServers, undefined, initConfig);
    this.query.streamInput(this.inputStream);`,
  },

  // ── Patch 5: SessionImpl - add pid/interrupt/setModel/setPermissionMode ──
  {
    name: 'Add pid/interrupt/setModel/setPermissionMode to SessionImpl',
    find: `      if (value.type === "result") {
        return;
      }
    }
  }
  close() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.inputStream.done();
    this.abortController.abort();
  }`,
    replace: `      if (value.type === "result") {
        return;
      }
    }
  }
  // [PATCHED_BY_SMAN] Expose subprocess PID
  get pid() {
    return this.query?.transport?.process?.pid;
  }
  // [PATCHED_BY_SMAN] Expose interrupt
  async interrupt() {
    return this.query.interrupt();
  }
  // [PATCHED_BY_SMAN] Expose setModel
  async setModel(model) {
    return this.query.setModel(model);
  }
  // [PATCHED_BY_SMAN] Expose setPermissionMode
  async setPermissionMode(mode) {
    return this.query.setPermissionMode(mode);
  }
  close() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.inputStream.done();
    this.abortController.abort();
  }`,
  },

  // ── Patch 6: query() function - remove CLAUDE_AGENT_SDK_VERSION ──
  {
    name: 'Remove CLAUDE_AGENT_SDK_VERSION in query()',
    find: `  process.env.CLAUDE_AGENT_SDK_VERSION = "0.1.77";`,
    replace: `  // [PATCHED_BY_SMAN] Removed CLAUDE_AGENT_SDK_VERSION to appear as native CLI`,
  },

  // ── Patch 7: query() function - remove CLAUDE_CODE_ENTRYPOINT ──
  {
    name: 'Remove CLAUDE_CODE_ENTRYPOINT in query()',
    find: `  if (!processEnv.CLAUDE_CODE_ENTRYPOINT) {
    processEnv.CLAUDE_CODE_ENTRYPOINT = "sdk-ts";
  }
  if (enableFileCheckpointing) {`,
    replace: `  // [PATCHED_BY_SMAN] Removed CLAUDE_CODE_ENTRYPOINT to appear as native CLI
  if (enableFileCheckpointing) {`,
  },
];

let patched = 0;
for (const patch of patches) {
  if (content.includes(patch.find)) {
    content = content.replace(patch.find, patch.replace);
    patched++;
    console.log(`[patch-sdk] Applied: ${patch.name}`);
  } else {
    console.warn(`[patch-sdk] WARNING: Could not find patch target: ${patch.name}`);
  }
}

writeFileSync(sdkPath, content, 'utf-8');
console.log(`[patch-sdk] Applied ${patched}/${patches.length} patches to ${sdkPath}`);
