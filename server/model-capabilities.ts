/**
 * Model Capability Detection Module
 *
 * Three-layer detection: Anthropic API → mapping table → probe.
 * Persists detected capabilities alongside LLM config.
 */
import { createLogger } from './utils/logger.js';

const log = createLogger('ModelCapabilities');

export interface DetectedCapabilities {
  text: boolean;
  image: boolean;
  pdf: boolean;
  audio: boolean;
  video: boolean;
  maxInputTokens?: number;
  displayName?: string;
  source: 'api' | 'mapping' | 'probe';
}

export interface ModelTestResult {
  success: boolean;
  error?: string;
  capabilities?: DetectedCapabilities;
}

/**
 * Known model capabilities mapping table.
 * Used as Layer 2 fallback when API query fails.
 */
export const MODEL_CAPABILITIES_MAP: Record<string, DetectedCapabilities> = {
  // Anthropic Claude — all support vision + PDF
  'claude-sonnet-4-6':        { text: true, image: true,  pdf: true,  audio: false, video: false, displayName: 'Claude Sonnet 4.6', source: 'mapping' },
  'claude-opus-4-6':           { text: true, image: true,  pdf: true,  audio: false, video: false, displayName: 'Claude Opus 4.6', source: 'mapping' },
  'claude-haiku-4-5':          { text: true, image: true,  pdf: true,  audio: false, video: false, displayName: 'Claude Haiku 4.5', source: 'mapping' },
  'claude-3-5-sonnet':        { text: true, image: true,  pdf: true,  audio: false, video: false, displayName: 'Claude 3.5 Sonnet', source: 'mapping' },
  'claude-3-opus':             { text: true, image: true,  pdf: true,  audio: false, video: false, displayName: 'Claude 3 Opus', source: 'mapping' },
  'claude-3-haiku':           { text: true, image: true,  pdf: true,  audio: false, video: false, displayName: 'Claude 3 Haiku', source: 'mapping' },

  // OpenAI — vision, no native PDF
  'gpt-4o':                  { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'GPT-4o', source: 'mapping' },
  'gpt-4o-mini':             { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'GPT-4o mini', source: 'mapping' },
  'gpt-4-turbo':             { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'GPT-4 Turbo', source: 'mapping' },
  'gpt-4':                   { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'GPT-4', source: 'mapping' },

  // DeepSeek — text only
  'deepseek-chat':             { text: true, image: false, pdf: false, audio: false, video: false, displayName: 'DeepSeek Chat', source: 'mapping' },
  'deepseek-reasoner':         { text: true, image: false, pdf: false, audio: false, video: false, displayName: 'DeepSeek Reasoner', source: 'mapping' },

  // Qwen VL — vision
  'qwen-vl-plus':             { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'Qwen VL Plus', source: 'mapping' },
  'qwen-vl-max':              { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'Qwen VL Max', source: 'mapping' },

  // Ollama vision models
  'llava':                   { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'LLaVA', source: 'mapping' },
  'llava:13b':               { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'LLaVA 13B', source: 'mapping' },
  'bakllava':                { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'BakLLaVA', source: 'mapping' },
  'qwen2-vl':                { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'Qwen2-VL', source: 'mapping' },
  'llama3.2-vision':            { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'Llama 3.2 Vision', source: 'mapping' },
  'minicpm-v':                { text: true, image: true,  pdf: false, audio: false, video: false, displayName: 'MiniCPM-V', source: 'mapping' },
};

/**
 * Look up model capabilities by fuzzy name matching.
 */
function lookupByFuzzyName(model: string): DetectedCapabilities | null {
  const lower = model.toLowerCase();

  // Anthropic Claude
  if (lower.includes('claude')) {
    return { text: true, image: true, pdf: true, audio: false, video: false, displayName: `Claude (${model})`, source: 'mapping' };
  }

  // OpenAI GPT-4 variants
  if (lower.includes('gpt-4') && !lower.includes('gpt-3')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, displayName: model, source: 'mapping' };
  }
  if (lower.includes('gpt-3')) {
    return { text: true, image: false, pdf: false, audio: false, video: false, displayName: model, source: 'mapping' };
  }

  // DeepSeek
  if (lower.includes('deepseek')) {
    return { text: true, image: false, pdf: false, audio: false, video: false, displayName: model, source: 'mapping' };
  }

  // Qwen VL variants
  if (lower.includes('qwen-vl') || lower.includes('qwen-vision')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, displayName: model, source: 'mapping' };
  }

  // GLM vision variants
  if (lower.includes('glm') && lower.includes('vision')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, displayName: model, source: 'mapping' };
  }

  // Ollama vision models
  if (lower.includes('llava') || lower.includes('bakllava') || lower.includes('minicpm-v')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, displayName: model, source: 'mapping' };
  }

  // Llama vision
  if (lower.includes('llama') && lower.includes('vision')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, displayName: model, source: 'mapping' };
  }

  return null;
}

/**
 * Layer 1: Query Anthropic /v1/models/{model} to get capabilities.
 * Falls back to mapping table on failure.
 */
export async function queryModelCapabilities(
  apiKey: string,
  model: string,
  baseUrl?: string,
): Promise<DetectedCapabilities | null> {
  const url = baseUrl
    ? `${baseUrl.replace(/\/+$/, '')}/v1/models/${model}`
    : `https://api.anthropic.com/v1/models/${model}`;

  try {
    const resp = await fetch(url, {
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      },
    });

    if (resp.status === 404) {
      log.info(`Model not found: ${model}`);
      return null;
    }
    if (resp.status === 401 || resp.status === 403) {
      return null;
    }
    if (resp.status >= 500) {
      log.warn(`Models API error ${resp.status} for ${model}`);
      return null;
    }
    if (!resp.ok) {
      return null;
    }

    const data = await resp.json() as any;
    const caps = data.capabilities;

    if (caps) {
      return {
        text: true,
        image: caps.image_input?.supported ?? false,
        pdf: caps.pdf_input?.supported ?? false,
        audio: false,
        video: false,
        maxInputTokens: data.max_input_tokens,
        displayName: data.display_name,
        source: 'api',
      };
    }

    return null;
  } catch (err) {
    log.warn(`Failed to query model capabilities: ${err instanceof Error ? err.message : String(err)}`);
    return null;
  }
}

/**
 * Layer 2: Mapping table lookup (exact match then fuzzy).
 */
function lookupMappingTable(model: string): DetectedCapabilities | null {
  if (MODEL_CAPABILITIES_MAP[model]) {
    return MODEL_CAPABILITIES_MAP[model];
  }
  return lookupByFuzzyName(model);
}

/**
 * Layer 3: Probe — send minimal image request to test vision support.
 * Conservative: don't assume PDF from probe.
 */
async function probeVisionCapability(
  apiKey: string,
  model: string,
  baseUrl?: string,
): Promise<DetectedCapabilities> {
  const url = baseUrl
    ? `${baseUrl.replace(/\/+$/, '')}/v1/messages`
    : `https://api.anthropic.com/v1/messages`;

  // Minimal valid 1x1 PNG
  const MINIMAL_PNG_BASE64 = 'iVBORw0KGgoAAAANS';

  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'content-type': 'application/json',
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model,
        max_tokens: 1,
        messages: [{
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: 'image/png', data: MINIMAL_PNG_BASE64 } },
            { type: 'text', text: 'ok' },
          ],
        }],
      }),
    });

    if (resp.ok) {
      return {
        text: true,
        image: true,
        pdf: false, // conservative
        audio: false,
        video: false,
        source: 'probe',
      };
    }

    return {
      text: true,
      image: false,
      pdf: false,
      audio: false,
      video: false,
      source: 'probe',
    };
  } catch {
    return {
      text: true,
      image: false,
      pdf: false,
      audio: false,
      video: false,
      source: 'probe',
    };
  }
}

/**
 * Test Anthropic API compatibility: send a minimal message to verify the endpoint.
 */
export async function testAnthropicCompat(
  apiKey: string,
  model: string,
  baseUrl?: string,
): Promise<ModelTestResult> {
  const apiUrl = baseUrl
    ? `${baseUrl.replace(/\/+$/, '')}/v1/messages`
    : 'https://api.anthropic.com/v1/messages';

  try {
    const resp = await fetch(apiUrl, {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'content-type': 'application/json',
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model,
        max_tokens: 1,
        messages: [{ role: 'user', content: 'hi' }],
      }),
    });

    if (resp.ok) {
      // Test passed — detect capabilities (saves config after this step)
      const caps = await queryModelCapabilities(apiKey, model, baseUrl) ?? undefined;
      return {
        success: true,
        capabilities: caps ?? {
          text: true,
          image: false,
          pdf: false,
          audio: false,
          video: false,
          source: 'probe',
        },
      };
    }

    if (resp.status === 401 || resp.status === 403) {
      return { success: false, error: 'API Key 无效或无权限' };
    }
    if (resp.status === 404) {
      return { success: false, error: `模型不存在: ${model}` };
    }
    if (resp.status === 400) {
      return { success: false, error: '该 API 不兼容 Anthropic 格式' };
    }

    return { success: false, error: `API 返回错误 (${resp.status})` };
  } catch {
    return { success: false, error: `无法连接到 ${baseUrl ?? 'Anthropic API'}` };
  }
}

/**
 * Three-layer capability detection: API → mapping → probe.
 */
export async function detectCapabilities(
  apiKey: string,
  model: string,
  baseUrl?: string,
): Promise<ModelTestResult> {
  // Layer 1: Try Anthropic API
  const apiCaps = await queryModelCapabilities(apiKey, model, baseUrl);
  if (apiCaps) {
    return { success: true, capabilities: apiCaps };
  }

  // Layer 2: Mapping table
  const mappingCaps = lookupMappingTable(model);
  if (mappingCaps) {
    return { success: true, capabilities: mappingCaps };
  }

  // Layer 3: Probe
  const probeCaps = await probeVisionCapability(apiKey, model, baseUrl);
  return { success: true, capabilities: probeCaps };
}
