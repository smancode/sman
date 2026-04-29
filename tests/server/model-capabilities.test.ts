import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  queryModelCapabilities,
  detectCapabilities,
  testAnthropicCompat,
  MODEL_CAPABILITIES_MAP,
} from '../../server/model-capabilities.js';

// Mock logger
vi.mock('../../server/utils/logger.js', () => ({
  createLogger: () => ({ info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }),
}));

// Mock global fetch using vi.fn with controlled implementation
const originalFetch = globalThis.fetch;
beforeEach(() => {
  globalThis.fetch = vi.fn();
});
afterEach(() => {
  globalThis.fetch= originalFetch;
});

function mockFetchResponse(status: number, body: any) {
  (globalThis.fetch as any).mockResolvedValueOnce({
    status,
    ok: status >= 200 && status < 300,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  });
}

describe('MODEL_CAPABILITIES_MAP', () => {
  it('should have known Claude models with full vision support', () => {
    const caps = MODEL_CAPABILITIES_MAP['claude-sonnet-4-6'];
    expect(caps).toBeDefined();
    expect(caps.image).toBe(true);
    expect(caps.pdf).toBe(true);
  });

  it('should have vision models like llava', () => {
    const llava = MODEL_CAPABILITIES_MAP['llava'];
    expect(llava).toBeDefined();
    expect(llava.image).toBe(true);
    expect(llava.pdf).toBe(false);
  });
});

describe('queryModelCapabilities', () => {
  it('should return capabilities from API when model found', async () => {
    mockFetchResponse(200, {
      id: 'claude-sonnet-4-6',
      display_name: 'Claude Sonnet 4.6',
      max_input_tokens: 200000,
      capabilities: {
        image_input: { supported: true },
        pdf_input: { supported: true },
      },
    });

    const result = await queryModelCapabilities('sk-test', 'claude-sonnet-4-6');
    expect(result).toBeDefined();
    expect(result!.image).toBe(true);
    expect(result!.pdf).toBe(true);
    expect(result!.source).toBe('api');
  });

  it('should return null on 404', async () => {
    mockFetchResponse(404, { error: 'not found' });
    const result = await queryModelCapabilities('sk-test', 'nonexistent');
    expect(result).toBeNull();
  });

  it('should return null on auth failure', async () => {
    mockFetchResponse(401, { error: 'unauthorized' });
    const result = await queryModelCapabilities('sk-test', 'claude-sonnet-4-6');
    expect(result).toBeNull();
  });

  it('should return null on network error', async () => {
    (globalThis.fetch as any).mockRejectedValueOnce(new Error('network error'));
    const result = await queryModelCapabilities('sk-test', 'claude-sonnet-4-6');
    expect(result).toBeNull();
  });
});

describe('testAnthropicCompat', () => {

  it('should return success on valid response', async () => {
    // First call: testAnthropicCompat sends "hi" message
    mockFetchResponse(200, { content: [{ type: 'text', text: 'hello' }] });
    // Second call: queryModelCapabilities inside testAnthropicCompat
    mockFetchResponse(200, {
      id: 'claude-sonnet-4-6',
      capabilities: {
        image_input: { supported: true },
        pdf_input: { supported: true },
      },
    });

    const result = await testAnthropicCompat('sk-test', 'claude-sonnet-4-6');
    expect(result.success).toBe(true);
    expect(result.capabilities).toBeDefined();
    expect(result.capabilities!.image).toBe(true);
  });

  it('should return error on 401', async () => {
    mockFetchResponse(401, { error: 'unauthorized' });
    const result = await testAnthropicCompat('sk-test', 'claude-sonnet-4-6');
    expect(result.success).toBe(false);
    expect(result.error).toContain('无效');
  });

  it('should return error on 404', async () => {
    mockFetchResponse(404, { error: 'not found' });
    const result = await testAnthropicCompat('sk-test', 'nonexistent-model');
    expect(result.success).toBe(false);
    expect(result.error).toContain('不存在');
  });

  it('should return error on connection failure', async () => {
    (globalThis.fetch as any).mockRejectedValueOnce(new Error('ECONNREFUSED'));
    const result = await testAnthropicCompat('sk-test', 'claude-sonnet-4-6');
    expect(result.success).toBe(false);
    expect(result.error).toContain('无法连接');
  });

  it('should return error on 400 (incompatible API)', async () => {
    mockFetchResponse(400, { error: 'bad request' });
    const result = await testAnthropicCompat('sk-test', 'claude-sonnet-4-6');
    expect(result.success).toBe(false);
    expect(result.error).toContain('不兼容');
  });
});

describe('detectCapabilities', () => {

  it('should use API capabilities when available', async () => {
    // Layer 1: queryModelCapabilities succeeds
    mockFetchResponse(200, {
      id: 'claude-sonnet-4-6',
      display_name: 'Claude Sonnet 4.6',
      max_input_tokens: 200000,
      capabilities: {
        image_input: { supported: true },
        pdf_input: { supported: true },
      },
    });
    // Layer 3: probe — model describes the red image correctly
    mockFetchResponse(200, { content: [{ type: 'text', text: 'red' }] });

    const result = await detectCapabilities('sk-test', 'claude-sonnet-4-6');
    expect(result.success).toBe(true);
    expect(result.capabilities).toBeDefined();
    expect(result.capabilities!.source).toBe('probe');
    expect(result.capabilities!.image).toBe(true);
  });

  it('should fall back to mapping table when API fails', async () => {
    // Layer 1: queryModelCapabilities returns null (404)
    mockFetchResponse(404, { error: 'not found' });
    // Layer 3: probe — model cannot handle image
    mockFetchResponse(200, { content: [{ type: 'text', text: 'I cannot see images' }] });

    const result = await detectCapabilities('sk-test', 'deepseek-chat');
    expect(result.success).toBe(true);
    expect(result.capabilities).toBeDefined();
    expect(result.capabilities!.source).toBe('probe');
    expect(result.capabilities!.image).toBe(false);
  });

  it('should fall back to fuzzy matching for llava', async () => {
    // Layer 1 fails
    mockFetchResponse(404, { error: 'not found' });
    // Layer 3: probe — vision model describes the red image
    mockFetchResponse(200, { content: [{ type: 'text', text: 'red' }] });

    const result = await detectCapabilities('sk-test', 'llava:13b');
    expect(result.success).toBe(true);
    expect(result.capabilities).toBeDefined();
    expect(result.capabilities!.source).toBe('probe');
    expect(result.capabilities!.image).toBe(true);
  });

  it('should probe unknown models', async () => {
    // Layer 1: 404
    mockFetchResponse(404, { error: 'not found' });
    // Layer 3: probe (POST /v1/messages with image)
    mockFetchResponse(200, { content: [{ type: 'text', text: 'ok' }] });

    const result = await detectCapabilities('sk-test', 'my-custom-vision-model');
    expect(result.success).toBe(true);
    expect(result.capabilities).toBeDefined();
    expect(result.capabilities!.source).toBe('probe');
  });
});
