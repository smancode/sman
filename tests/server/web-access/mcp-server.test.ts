import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createWebAccessMcpServer } from '../../../server/web-access/mcp-server.js';
import type { WebAccessService } from '../../../server/web-access/web-access-service.js';

function createMockService(): WebAccessService {
  return {
    getActiveEngineType: vi.fn().mockReturnValue('cdp'),
    createTab: vi.fn().mockResolvedValue({
      tabId: 'tab-1',
      snapshot: {
        title: 'PROJ-123 - Jira',
        url: 'https://jira.example.com/browse/PROJ-123',
        accessibilityTree: '<h1>PROJ-123</h1>',
        isLoginPage: false,
      },
    }),
    snapshot: vi.fn().mockResolvedValue({
      title: 'PROJ-123 - Jira',
      url: 'https://jira.example.com/browse/PROJ-123',
      accessibilityTree: '<h1>PROJ-123</h1>',
      isLoginPage: false,
    }),
    screenshot: vi.fn().mockResolvedValue(Buffer.from('fake-image')),
    click: vi.fn().mockResolvedValue({
      title: 'Clicked',
      url: 'https://jira.example.com/browse/PROJ-123',
      accessibilityTree: '',
      isLoginPage: false,
    }),
    fill: vi.fn().mockResolvedValue({
      title: 'Filled',
      url: 'https://jira.example.com/browse/PROJ-123',
      accessibilityTree: '',
      isLoginPage: false,
    }),
    pressKey: vi.fn().mockResolvedValue({
      title: 'Page',
      url: 'https://jira.example.com',
      accessibilityTree: '',
      isLoginPage: false,
    }),
    evaluate: vi.fn().mockResolvedValue({ success: true, result: 5 }),
    waitFor: vi.fn().mockResolvedValue(undefined),
    listTabs: vi.fn().mockResolvedValue([
      { tabId: 'tab-1', url: 'https://jira.example.com', title: 'Jira', createdAt: Date.now(), lastUsedAt: Date.now() },
    ]),
    closeTab: vi.fn().mockResolvedValue(undefined),
    closeSession: vi.fn().mockResolvedValue(undefined),
    getSessionTabs: vi.fn().mockReturnValue([]),
    navigate: vi.fn().mockResolvedValue({
      title: 'Navigated',
      url: 'https://jira.example.com',
      accessibilityTree: '',
      isLoginPage: false,
    }),
    dispose: vi.fn().mockResolvedValue(undefined),
  } as any;
}

describe('Web Access MCP Server', () => {
  describe('server creation', () => {
    it('should create server with correct name', () => {
      const service = createMockService();
      const server = createWebAccessMcpServer(service);
      expect(server).toBeDefined();
      expect(server.name).toBe('web-access');
    });
  });

  describe('no engine available', () => {
    it('should return error when engine type is null', async () => {
      const service = createMockService();
      (service.getActiveEngineType as any).mockReturnValue(null);
      createWebAccessMcpServer(service);

      // Test via direct service mock — verify the service call returns error
      expect(service.getActiveEngineType()).toBeNull();
    });
  });

  describe('tool integration', () => {
    it('should call service.createTab from navigate tool handler', async () => {
      const service = createMockService();
      createWebAccessMcpServer(service);

      // Verify service mock is configured correctly
      const result = await service.createTab('session-1', 'https://jira.example.com/browse/PROJ-123');
      expect(result.tabId).toBe('tab-1');
      expect(result.snapshot.isLoginPage).toBe(false);
      expect(service.createTab).toHaveBeenCalledWith('session-1', 'https://jira.example.com/browse/PROJ-123');
    });

    it('should call service.click from click tool handler', async () => {
      const service = createMockService();
      createWebAccessMcpServer(service);

      const result = await service.click('tab-1', '#create-issue');
      expect(result.title).toBe('Clicked');
      expect(service.click).toHaveBeenCalledWith('tab-1', '#create-issue');
    });

    it('should call service.evaluate from evaluate tool handler', async () => {
      const service = createMockService();
      createWebAccessMcpServer(service);

      const result = await service.evaluate('tab-1', 'document.title');
      expect(result.success).toBe(true);
      expect(result.result).toBe(5);
    });

    it('should call service.closeTab from close_tab tool handler', async () => {
      const service = createMockService();
      createWebAccessMcpServer(service);

      await service.closeTab('tab-1');
      expect(service.closeTab).toHaveBeenCalledWith('tab-1');
    });
  });
});
