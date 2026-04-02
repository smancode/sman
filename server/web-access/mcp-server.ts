/**
 * MCP Server — registers web_access_* tools for Claude Agent SDK.
 *
 * Uses createSdkMcpServer + tool API for in-process MCP server.
 */

import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import type { WebAccessService } from './web-access-service.js';
import { BrowserConnectionError } from './browser-engine.js';
import { loadExperiences, addExperience } from './url-experience-store.js';
import { readAllHistory } from './chrome-sites.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

/** Wrap a handler to catch engine-unavailable errors */
function withEngineCheck(
  service: WebAccessService,
  handler: (args: any, extra: any) => Promise<any>,
): (args: any, extra: any) => Promise<any> {
  return async (args: any, extra: any) => {
    if (!service.getActiveEngineType()) {
      return errorResult('浏览器不可用。Chrome 自动启动失败，请确认已安装 Google Chrome');
    }
    try {
      return await handler(args, extra);
    } catch (e: any) {
      if (e instanceof BrowserConnectionError) {
        return errorResult(`浏览器连接错误: ${e.message}`);
      }
      return errorResult(`操作失败: ${e.message}`);
    }
  };
}

export function createWebAccessMcpServer(service: WebAccessService): McpSdkServerConfigWithInstance {
  const findUrlTool = tool(
    'web_access_find_url',
    'Find the best matching URL from user\'s Chrome browsing history and learned experiences. '
    + 'Use this when you need to navigate to a website but are unsure of the exact URL. '
    + 'You will receive a list of candidate URLs from both saved experiences and Chrome history — '
    + 'pick the one that best matches the user\'s intent. '
    + 'If none matches, ask the user for the URL and save it with web_access_remember_url.',
    {
      query: z.string().describe('User\'s original question or intent, e.g., "智谱MCP用量" or "ITSM待办"'),
    },
    async (args: any) => {
      const experiences = loadExperiences();
      const history = readAllHistory(200);

      return textResult(JSON.stringify({
        query: args.query,
        experiences: experiences.entries,
        chromeHistory: history,
      }, null, 2));
    },
  );

  const rememberUrlTool = tool(
    'web_access_remember_url',
    'Save a URL mapping for future smart matching. '
    + 'Call this after the user provides a URL that was not found by web_access_find_url, '
    + 'so next time it will be matched automatically.',
    {
      description: z.string().describe('What this URL is for, in the user\'s own words, e.g., "智谱MCP用量页面"'),
      url: z.string().describe('The exact URL to remember'),
    },
    async (args: any) => {
      addExperience({
        description: args.description,
        url: args.url,
        createdAt: new Date().toISOString(),
      });
      return textResult(`已记录: ${args.description} -> ${args.url}`);
    },
  );

  const navigateTool = tool(
    'web_access_navigate',
    'Navigate to a URL in the browser. Creates a new tab for the session if needed.',
    {
      session_id: z.string().describe('Session ID for tab isolation'),
      url: z.string().describe('URL to navigate to'),
    },
    withEngineCheck(service, async (args: any) => {
      const { tabId, snapshot } = await service.createTab(args.session_id, args.url);
      const result: Record<string, any> = {
        tabId,
        title: snapshot.title,
        url: snapshot.url,
        isLoginPage: snapshot.isLoginPage,
      };
      if (snapshot.isLoginPage && snapshot.loginUrl) {
        result.loginUrl = snapshot.loginUrl;
        result.message = '检测到登录页面，请先登录后再操作';
      }
      return textResult(JSON.stringify(result, null, 2));
    }),
  );

  const snapshotTool = tool(
    'web_access_snapshot',
    'Get an accessibility snapshot of the current page. Returns text content, buttons, links, etc.',
    {
      tab_id: z.string().describe('Tab ID to snapshot'),
    },
    withEngineCheck(service, async (args: any) => {
      const snapshot = await service.snapshot(args.tab_id);
      return textResult(JSON.stringify({
        title: snapshot.title,
        url: snapshot.url,
        accessibilityTree: snapshot.accessibilityTree,
        isLoginPage: snapshot.isLoginPage,
      }, null, 2));
    }),
  );

  const screenshotTool = tool(
    'web_access_screenshot',
    'Take a screenshot of the current page.',
    {
      tab_id: z.string().describe('Tab ID to screenshot'),
    },
    withEngineCheck(service, async (args: any) => {
      const buffer = await service.screenshot(args.tab_id);
      return {
        content: [
          { type: 'image', data: buffer.toString('base64'), mimeType: 'image/png' },
        ],
      };
    }),
  );

  const clickTool = tool(
    'web_access_click',
    'Click an element on the page by CSS selector.',
    {
      tab_id: z.string().describe('Tab ID'),
      selector: z.string().describe('CSS selector of the element to click'),
    },
    withEngineCheck(service, async (args: any) => {
      const snapshot = await service.click(args.tab_id, args.selector);
      return textResult(JSON.stringify({
        title: snapshot.title,
        url: snapshot.url,
        isLoginPage: snapshot.isLoginPage,
      }, null, 2));
    }),
  );

  const fillTool = tool(
    'web_access_fill',
    'Fill a form field with a value.',
    {
      tab_id: z.string().describe('Tab ID'),
      selector: z.string().describe('CSS selector of the input field'),
      value: z.string().describe('Value to fill in'),
    },
    withEngineCheck(service, async (args: any) => {
      const snapshot = await service.fill(args.tab_id, args.selector, args.value);
      return textResult(JSON.stringify({
        title: snapshot.title,
        url: snapshot.url,
        isLoginPage: snapshot.isLoginPage,
      }, null, 2));
    }),
  );

  const pressKeyTool = tool(
    'web_access_press_key',
    'Press a key on the keyboard (e.g., Enter, Tab, Escape).',
    {
      tab_id: z.string().describe('Tab ID'),
      key: z.string().describe('Key to press (e.g., Enter, Tab, Escape)'),
    },
    withEngineCheck(service, async (args: any) => {
      const snapshot = await service.pressKey(args.tab_id, args.key);
      return textResult(JSON.stringify({
        title: snapshot.title,
        url: snapshot.url,
        isLoginPage: snapshot.isLoginPage,
      }, null, 2));
    }),
  );

  const evaluateTool = tool(
    'web_access_evaluate',
    'Execute JavaScript in the page and return the result.',
    {
      tab_id: z.string().describe('Tab ID'),
      expression: z.string().describe('JavaScript expression to evaluate'),
    },
    withEngineCheck(service, async (args: any) => {
      const result = await service.evaluate(args.tab_id, args.expression);
      return textResult(JSON.stringify(result, null, 2));
    }),
  );

  const listTabsTool = tool(
    'web_access_list_tabs',
    'List all open browser tabs.',
    {},
    withEngineCheck(service, async () => {
      const tabs = await service.listTabs();
      return textResult(JSON.stringify(tabs, null, 2));
    }),
  );

  const closeTabTool = tool(
    'web_access_close_tab',
    'Close a browser tab.',
    {
      tab_id: z.string().describe('Tab ID to close'),
    },
    withEngineCheck(service, async (args: any) => {
      await service.closeTab(args.tab_id);
      return textResult(`Tab ${args.tab_id} closed`);
    }),
  );

  return createSdkMcpServer({
    name: 'web-access',
    version: '1.0.0',
    tools: [
      findUrlTool,
      rememberUrlTool,
      navigateTool,
      snapshotTool,
      screenshotTool,
      clickTool,
      fillTool,
      pressKeyTool,
      evaluateTool,
      listTabsTool,
      closeTabTool,
    ],
  });
}
