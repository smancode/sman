#!/usr/bin/env node

/**
 * Patch @anthropic-ai/claude-agent-sdk to support full V2 session configuration.
 *
 * Targets SDK v0.2.110+ / claude-code v2.1.110+.
 *
 * The native V2 SDK SessionImpl only passes a subset of options to ProcessTransport,
 * ignoring systemPrompt, mcpServers, plugins, permissionMode defaults, etc.
 * This patch forwards all options so V2 sessions are fully configurable.
 *
 * Also removes CLAUDE_CODE_ENTRYPOINT/CLAUDE_AGENT_SDK_VERSION markers so the CLI
 * subprocess appears as a native invocation (enabling /skill commands natively).
 *
 * Patch summary (SDK 0.2.110):
 *   1. [SKIPPED] pid exposure — already built into SDK 0.2.110+
 *   2. ProcessTransport.initialize: remove CLAUDE_CODE_ENTRYPOINT
 *   3. SessionImpl constructor: remove CLAUDE_CODE_ENTRYPOINT
 *   4. SessionImpl constructor: forward all options to ProcessTransport + Query
 *   5. SessionImpl: add interrupt/setModel/setPermissionMode methods (pid built-in)
 *   6. query() function: remove CLAUDE_AGENT_SDK_VERSION
 *   7. query() function: remove CLAUDE_CODE_ENTRYPOINT
 */

import { readFileSync, writeFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Resolve SDK file path
const sdkPaths = [
  join(__dirname, '..', 'node_modules', '@anthropic-ai', 'claude-agent-sdk', 'sdk.mjs'),
  join(__dirname, '..', 'node_modules', '.pnpm', 'node_modules', '@anthropic-ai', 'claude-agent-sdk', 'sdk.mjs'),
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

if (sdkPath) {
  let content = readFileSync(sdkPath, 'utf-8');

  // Check if already patched
  if (content.includes('[PATCHED_BY_SMAN]')) {
    console.log('[patch-sdk] SDK already patched, skipping');
  } else {
    const patches = [
      // ── Patch 1: SKIPPED — pid already built into SDK 0.2.110+ ──

      // ── Patch 2: ProcessTransport.initialize - remove CLAUDE_CODE_ENTRYPOINT ──
      {
        name: 'Remove CLAUDE_CODE_ENTRYPOINT in ProcessTransport.initialize',
        find: `if(!U.CLAUDE_CODE_ENTRYPOINT)U.CLAUDE_CODE_ENTRYPOINT="sdk-ts";if(delete U.NODE_OPTIONS`,
        replace: `if(delete U.NODE_OPTIONS`,
      },

      // ── Patch 3: SessionImpl constructor - remove CLAUDE_CODE_ENTRYPOINT ──
      {
        name: 'Remove CLAUDE_CODE_ENTRYPOINT in SessionImpl constructor',
        find: `if(!Y.CLAUDE_CODE_ENTRYPOINT)Y.CLAUDE_CODE_ENTRYPOINT="sdk-ts";this.abortController=c1()`,
        replace: `this.abortController=c1()`,
      },

      // ── Patch 4: SessionImpl constructor - forward all options to ProcessTransport + Query ──
      {
        name: 'Forward ProcessTransport options + Query initConfig',
        find: `let Q=new $8({abortController:this.abortController,pathToClaudeCodeExecutable:X,cwd:$.cwd,env:Y,executable:$.executable??(p1()?"bun":"node"),executableArgs:$.executableArgs??[],extraArgs:{},thinkingConfig:void 0,maxTurns:void 0,maxBudgetUsd:void 0,model:$.model,fallbackModel:void 0,permissionMode:$.permissionMode??"default",allowDangerouslySkipPermissions:$.allowDangerouslySkipPermissions??!1,continueConversation:!1,resume:$.resume,settingSources:$.settingSources??[],allowedTools:$.allowedTools??[],disallowedTools:$.disallowedTools??[],mcpServers:{},strictMcpConfig:!1,canUseTool:!!$.canUseTool,hooks:!!$.hooks,includePartialMessages:!1,forkSession:!1,resumeSessionAt:void 0});this.query=new X8(Q,!1,$.canUseTool,$.hooks,this.abortController,new Map),this.query.streamInput(this.inputStream)`,
        replace: `let smanMcpServers=new Map;let smanProcessedMcp={};if($.mcpServers)for(let[k,v]of Object.entries($.mcpServers))if(v.type==="sdk"&&"instance" in v)smanMcpServers.set(k,v.instance),smanProcessedMcp[k]={type:"sdk",name:k};else smanProcessedMcp[k]=v;let Q=new $8({abortController:this.abortController,pathToClaudeCodeExecutable:X,cwd:$.cwd,stderr:$.stderr,env:Y,executable:$.executable??(p1()?"bun":"node"),executableArgs:$.executableArgs??[],extraArgs:$.extraArgs??{},thinkingConfig:$.thinkingConfig,maxTurns:$.maxTurns,maxBudgetUsd:$.maxBudgetUsd,model:$.model,fallbackModel:$.fallbackModel,permissionMode:$.permissionMode,allowDangerouslySkipPermissions:$.allowDangerouslySkipPermissions,continueConversation:$.continueConversation,resume:$.resume,settingSources:$.settingSources??[],allowedTools:$.allowedTools??[],disallowedTools:$.disallowedTools??[],mcpServers:smanProcessedMcp,strictMcpConfig:$.strictMcpConfig,canUseTool:!!$.canUseTool,hooks:!!$.hooks,includePartialMessages:$.includePartialMessages??!0,forkSession:$.forkSession,resumeSessionAt:$.resumeSessionAt,plugins:$.plugins});let smanInitCfg={systemPrompt:typeof $.systemPrompt==="string"?$.systemPrompt:($.systemPrompt?.append??"")};this.query=new X8(Q,!1,$.canUseTool,$.hooks,this.abortController,smanMcpServers,void 0,smanInitCfg),this.query.streamInput(this.inputStream)`,
      },

      // ── Patch 5: SessionImpl - add interrupt/setModel/setPermissionMode methods ──
      {
        name: 'Add interrupt/setModel/setPermissionMode to SessionImpl',
        find: `close(){if(this.closed)return;this.closed=!0,this.inputStream.done(),setTimeout`,
        replace: `async interrupt(){return this.query.interrupt()}async setModel($){return this.query.setModel($)}async setPermissionMode($){return this.query.setPermissionMode($)}close(){if(this.closed)return;this.closed=!0,this.inputStream.done(),setTimeout`,
      },

      // ── Patch 6: query() function - remove CLAUDE_AGENT_SDK_VERSION ──
      {
        name: 'Remove CLAUDE_AGENT_SDK_VERSION in query()',
        find: `process.env.CLAUDE_AGENT_SDK_VERSION="0.2.110"`,
        replace: `/* [PATCHED_BY_SMAN] removed CLAUDE_AGENT_SDK_VERSION */`,
      },

      // ── Patch 7: query() function - remove CLAUDE_CODE_ENTRYPOINT ──
      {
        name: 'Remove CLAUDE_CODE_ENTRYPOINT in query()',
        find: `if(!Y4.CLAUDE_CODE_ENTRYPOINT)Y4.CLAUDE_CODE_ENTRYPOINT="sdk-ts";if(p)Y4.CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING="true"`,
        replace: `if(p)Y4.CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING="true"`,
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
    console.log(`[patch-sdk] Applied ${patched}/${patches.length} SDK patches to ${sdkPath}`);
  }
} else {
  console.log('[patch-sdk] SDK file not found, skipping SDK patch');
}

// ── Patch claude-code CLI: remove ?beta=true from API URLs ──
// Zhipu proxy (open.bigmodel.cn/api/anthropic) rejects requests with ?beta=true.
// The CLI hardcodes beta endpoints (e.g. this.client.beta.messages.create() → /v1/messages?beta=true).
// This patch strips ?beta=true from all relevant API paths for proxy compatibility.
const cliPaths = [
  join(__dirname, '..', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js'),
  join(__dirname, '..', 'node_modules', '.pnpm', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js'),
];

let cliPath = null;
for (const p of cliPaths) {
  try {
    readFileSync(p, 'utf-8');
    cliPath = p;
    break;
  } catch {}
}

if (cliPath) {
  let cliContent = readFileSync(cliPath, 'utf-8');

  if (cliContent.includes('[CLI_PATCHED_BY_SMAN]')) {
    console.log('[patch-sdk] CLI already patched, skipping');
  } else {
    // Simple global replacement: strip all ?beta=true from API URLs.
    // This covers /v1/messages, /v1/messages/count_tokens, /v1/models,
    // /v1/messages/batches, /v1/skills — everything that uses beta endpoints.
    const before = cliContent;
    cliContent = cliContent.replaceAll('?beta=true', '');

    const changeCount = before.split('?beta=true').length - 1;
    if (changeCount > 0) {
      console.log(`[patch-sdk] CLI: removed ?beta=true from ${changeCount} locations`);
    } else {
      console.warn('[patch-sdk] WARNING: no ?beta=true found in CLI (already stripped?)');
    }

    // Add marker after shebang (line 1) so we don't break #!/usr/bin/env node
    const lines = cliContent.split('\n');
    // Insert marker comment after the shebang line
    const insertAt = lines[0]?.startsWith('#!') ? 1 : 0;
    lines.splice(insertAt, 0, '// [CLI_PATCHED_BY_SMAN] removed ?beta=true for proxy compat');
    cliContent = lines.join('\n');
    writeFileSync(cliPath, cliContent, 'utf-8');
    console.log(`[patch-sdk] CLI patched: ${cliPath}`);
  }
} else {
  console.log('[patch-sdk] CLI file not found, skipping CLI patch');
}
