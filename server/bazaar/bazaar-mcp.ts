// server/bazaar/bazaar-mcp.ts
/**
 * MCP Server — registers bazaar_search and bazaar_collaborate tools.
 *
 * Uses createSdkMcpServer + tool API for in-process MCP server.
 * Pattern follows server/web-access/mcp-server.ts.
 */
import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import type { BazaarMcpDeps } from './types.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

/**
 * 临时拦截 BazaarClient.onMessage 回调，等待特定消息类型。
 * BazaarClient 使用 onMessage 回调而非 EventEmitter，所以需要保存/恢复原始 handler。
 */
function waitForMessage(
  client: BazaarMcpDeps['client'],
  type: string,
  timeoutMs: number,
): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const originalHandler = client.onMessage;
    const timer = setTimeout(() => {
      client.onMessage = originalHandler;
      reject(new Error(`等待 ${type} 消息超时 (${timeoutMs}ms)`));
    }, timeoutMs);

    client.onMessage = (msg: { type: string; payload: Record<string, unknown> }) => {
      if (msg.type === type) {
        clearTimeout(timer);
        client.onMessage = originalHandler;
        resolve(msg.payload);
      } else {
        // 非目标消息，传递给原始 handler
        if (originalHandler) originalHandler(msg);
      }
    };
  });
}

export function createBazaarMcpServer(deps: BazaarMcpDeps): McpSdkServerConfigWithInstance {
  const searchTool = tool(
    'bazaar_search',
    '搜索集市上其他 Agent 的能力。当你无法完成某个任务时（比如缺少某个项目的代码访问权限、不了解特定业务逻辑），'
      + '用此工具搜索能帮你的人。返回匹配的 Agent 列表（名称、能力、在线状态、声望），然后用 bazaar_collaborate 发起协作。',
    {
      query: z.string().describe('搜索关键词，描述你需要的能力，如 "支付查询" 或 "风控规则"'),
    },
    async (args: any) => {
      try {
        const query = args.query as string;

        // 1. 先查本地经验路由
        const localRoutes = deps.store.findLearnedRoutes(query);

        // 2. 通过 client 发送 task.create 到集市服务器获取远程搜索结果
        const remoteMatches = await searchRemoteAgents(deps, query);

        // 3. 合并结果：本地已知能人排在前面，远程结果去重
        const localAgentIds = new Set(localRoutes.map(r => r.agentId));
        const filteredRemote = remoteMatches.filter(m => !localAgentIds.has(m.agentId));

        const allResults = [
          ...localRoutes.map(r => ({
            source: 'local' as const,
            agentId: r.agentId,
            name: r.agentName,
            capability: r.capability,
          })),
          ...filteredRemote.map(m => ({
            source: 'remote' as const,
            agentId: m.agentId,
            name: m.name,
          })),
        ];

        if (allResults.length === 0) {
          return textResult(`没有找到拥有 "${query}" 能力的 Agent。你可以尝试换个关键词搜索，或者稍后再试。`);
        }

        const lines = allResults.map((r, i) => {
          const local = r.source === 'local' ? ' [历史协作]' : '';
          return `${i + 1}. ${r.name} (${r.agentId})${local}`;
        });

        return textResult(
          `找到 ${allResults.length} 个匹配的 Agent：\n${lines.join('\n')}\n\n`
          + `用 bazaar_collaborate 向其中任何一个发起协作。`,
        );
      } catch (e: any) {
        return errorResult(`搜索失败: ${e.message}`);
      }
    },
  );

  const collaborateTool = tool(
    'bazaar_collaborate',
    '向指定 Agent 发起协作请求。先用 bazaar_search 找到合适的 Agent，然后用此工具请求对方协助。'
      + '对方接受后，你们可以实时对话解决问题。协作过程会在前端传送门页面实时显示。',
    {
      targetAgentId: z.string().describe('目标 Agent 的 ID（从 bazaar_search 结果中获取）'),
      question: z.string().describe('你需要对方帮助解决的问题'),
    },
    async (args: any) => {
      try {
        const { targetAgentId, question } = args;

        // 发送 task.create 获取搜索结果（含 taskId）
        const searchPayload = await searchRemoteWithId(deps, question);
        const taskId = searchPayload.taskId as string;

        // 发送 task.offer 给目标 Agent
        deps.client.send({
          id: `mcp-offer-${Date.now()}`,
          type: 'task.offer',
          payload: { taskId, targetAgent: targetAgentId },
        });

        return textResult(
          `协作请求已发送！任务 ID: ${taskId}\n`
          + `正在等待 ${targetAgentId} 接受。对方接受后，你可以直接在这里继续对话。`,
        );
      } catch (e: any) {
        return errorResult(`发起协作失败: ${e.message}`);
      }
    },
  );

  return createSdkMcpServer({
    name: 'bazaar',
    version: '1.0.0',
    tools: [searchTool, collaborateTool],
  });
}

// ── Helper：远程搜索（返回 matches 列表）──

async function searchRemoteAgents(
  deps: BazaarMcpDeps,
  query: string,
): Promise<Array<{ agentId: string; name: string; repo: string }>> {
  deps.client.send({
    id: `mcp-search-${Date.now()}`,
    type: 'task.create',
    payload: { question: query, capabilityQuery: query },
  });

  try {
    const payload = await waitForMessage(deps.client, 'task.search_result', 5000);
    const matches = (payload as any).matches ?? [];
    return matches.map((m: any) => ({
      agentId: m.agentId,
      name: m.name,
      repo: m.repo,
    }));
  } catch {
    return []; // 搜索超时，返回空
  }
}

// ── Helper：远程搜索并返回含 taskId 的 payload ──

async function searchRemoteWithId(
  deps: BazaarMcpDeps,
  query: string,
): Promise<Record<string, unknown>> {
  deps.client.send({
    id: `mcp-create-${Date.now()}`,
    type: 'task.create',
    payload: { question: query, capabilityQuery: query },
  });

  return waitForMessage(deps.client, 'task.search_result', 5000);
}
