import { describe, it, expect, vi, beforeEach } from 'vitest';
import { WebAccessService } from '../../../server/web-access/web-access-service.js';
import { BrowserConnectionError } from '../../../server/web-access/browser-engine.js';

function createMockEngine() {
  return {
    isAvailable: vi.fn().mockResolvedValue(true),
    newTab: vi.fn().mockResolvedValue({ tabId: 'tab-1', url: 'https://jira.example.com', title: 'Jira', createdAt: Date.now(), lastUsedAt: Date.now() }),
    navigate: vi.fn().mockResolvedValue({ title: 'Page', url: 'https://example.com', accessibilityTree: '', isLoginPage: false }),
    snapshot: vi.fn().mockResolvedValue({ title: 'Page', url: 'https://example.com', accessibilityTree: '', isLoginPage: false }),
    screenshot: vi.fn().mockResolvedValue(Buffer.from('test')),
    click: vi.fn().mockResolvedValue({ title: 'Clicked', url: 'https://example.com', accessibilityTree: '', isLoginPage: false }),
    fill: vi.fn().mockResolvedValue({ title: 'Filled', url: 'https://example.com', accessibilityTree: '', isLoginPage: false }),
    pressKey: vi.fn().mockResolvedValue({ title: 'Page', url: 'https://example.com', accessibilityTree: '', isLoginPage: false }),
    evaluate: vi.fn().mockResolvedValue({ success: true, result: 42 }),
    waitFor: vi.fn().mockResolvedValue(undefined),
    listTabs: vi.fn().mockResolvedValue([]),
    closeTab: vi.fn().mockResolvedValue(undefined),
    dispose: vi.fn().mockResolvedValue(undefined),
  };
}

describe('WebAccessService', () => {
  let service: WebAccessService;

  beforeEach(() => {
    service = new WebAccessService();
  });

  describe('detectEngine', () => {
    it('should select CdpEngine when Chrome is available', async () => {
      const mockEngine = createMockEngine();
      vi.spyOn(service as any, 'createCdpEngine').mockReturnValue(mockEngine);

      await service.detectEngine();
      expect(service.getActiveEngineType()).toBe('cdp');
    });

    it('should have no engine when neither CDP nor Electron available', async () => {
      const mockEngine = createMockEngine();
      mockEngine.isAvailable.mockResolvedValue(false);
      vi.spyOn(service as any, 'createCdpEngine').mockReturnValue(mockEngine);

      await service.detectEngine();
      expect(service.getActiveEngineType()).toBeNull();
    });
  });

  describe('tab management', () => {
    it('should isolate tabs by session ID', async () => {
      const mockEngine = createMockEngine();
      mockEngine.newTab
        .mockResolvedValueOnce({ tabId: 'tab-1', url: 'https://jira.example.com', title: 'Jira', createdAt: Date.now(), lastUsedAt: Date.now() })
        .mockResolvedValueOnce({ tabId: 'tab-2', url: 'https://gitlab.example.com', title: 'GitLab', createdAt: Date.now(), lastUsedAt: Date.now() });
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      await service.createTab('session-a', 'https://jira.example.com');
      await service.createTab('session-a', 'https://gitlab.example.com');

      const tabsA = service.getSessionTabs('session-a');
      expect(tabsA).toHaveLength(2);

      const tabsB = service.getSessionTabs('session-b');
      expect(tabsB).toHaveLength(0);
    });

    it('should close all tabs for a session on closeSession', async () => {
      const mockEngine = createMockEngine();
      mockEngine.newTab
        .mockResolvedValueOnce({ tabId: 'tab-1', url: 'https://jira.example.com', title: 'Jira', createdAt: Date.now(), lastUsedAt: Date.now() })
        .mockResolvedValueOnce({ tabId: 'tab-2', url: 'https://gitlab.example.com', title: 'GitLab', createdAt: Date.now(), lastUsedAt: Date.now() });
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      await service.createTab('session-a', 'https://jira.example.com');
      await service.createTab('session-a', 'https://gitlab.example.com');

      await service.closeSession('session-a');
      expect(mockEngine.closeTab).toHaveBeenCalledTimes(2);
      expect(service.getSessionTabs('session-a')).toHaveLength(0);
    });
  });

  describe('max tabs limit', () => {
    it('should reject when session exceeds max tabs', async () => {
      const mockEngine = createMockEngine();
      mockEngine.newTab.mockImplementation(async (url: string) => ({
        tabId: `tab-${Math.random()}`, url, title: 'Page', createdAt: Date.now(), lastUsedAt: Date.now(),
      }));
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      // Create 5 tabs (max per session)
      for (let i = 0; i < 5; i++) {
        await service.createTab('session-a', `https://example.com/${i}`);
      }

      // 6th should fail
      await expect(service.createTab('session-a', 'https://example.com/6')).rejects.toThrow();
    });
  });

  describe('no engine available', () => {
    it('should throw BrowserConnectionError when engine is null', async () => {
      (service as any).engine = null;
      (service as any).engineType = null;

      await expect(service.createTab('session-a', 'https://example.com')).rejects.toThrow(BrowserConnectionError);
    });
  });

  describe('proxy operations', () => {
    it('should proxy navigate to engine', async () => {
      const mockEngine = createMockEngine();
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';
      (service as any).sessionTabs.set('tab-1', 'session-a');

      await service.navigate('tab-1', 'https://example.com');
      expect(mockEngine.navigate).toHaveBeenCalledWith('tab-1', 'https://example.com');
    });

    it('should proxy click to engine', async () => {
      const mockEngine = createMockEngine();
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      await service.click('tab-1', '#btn');
      expect(mockEngine.click).toHaveBeenCalledWith('tab-1', '#btn');
    });

    it('should proxy fill to engine', async () => {
      const mockEngine = createMockEngine();
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      await service.fill('tab-1', '#input', 'value');
      expect(mockEngine.fill).toHaveBeenCalledWith('tab-1', '#input', 'value');
    });

    it('should proxy screenshot to engine', async () => {
      const mockEngine = createMockEngine();
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      const result = await service.screenshot('tab-1');
      expect(mockEngine.screenshot).toHaveBeenCalledWith('tab-1');
      expect(Buffer.isBuffer(result)).toBe(true);
    });

    it('should proxy evaluate to engine', async () => {
      const mockEngine = createMockEngine();
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      const result = await service.evaluate('tab-1', '1+1');
      expect(mockEngine.evaluate).toHaveBeenCalledWith('tab-1', '1+1');
      expect(result.success).toBe(true);
    });
  });

  describe('dispose', () => {
    it('should dispose engine and clear all state', async () => {
      const mockEngine = createMockEngine();
      (service as any).engine = mockEngine;
      (service as any).engineType = 'cdp';

      await service.dispose();
      expect(mockEngine.dispose).toHaveBeenCalled();
      expect(service.getActiveEngineType()).toBeNull();
    });
  });
});
