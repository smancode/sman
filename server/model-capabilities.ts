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
  'claude-sonnet-4-6':        { text: true, image: true,  pdf: true,  audio: false, video: false, maxInputTokens: 200000, displayName: 'Claude Sonnet 4.6', source: 'mapping' },
  'claude-opus-4-6':           { text: true, image: true,  pdf: true,  audio: false, video: false, maxInputTokens: 200000, displayName: 'Claude Opus 4.6', source: 'mapping' },
  'claude-haiku-4-5':          { text: true, image: true,  pdf: true,  audio: false, video: false, maxInputTokens: 200000, displayName: 'Claude Haiku 4.5', source: 'mapping' },
  'claude-3-5-sonnet':        { text: true, image: true,  pdf: true,  audio: false, video: false, maxInputTokens: 200000, displayName: 'Claude 3.5 Sonnet', source: 'mapping' },
  'claude-3-opus':             { text: true, image: true,  pdf: true,  audio: false, video: false, maxInputTokens: 200000, displayName: 'Claude 3 Opus', source: 'mapping' },
  'claude-3-haiku':           { text: true, image: true,  pdf: true,  audio: false, video: false, maxInputTokens: 200000, displayName: 'Claude 3 Haiku', source: 'mapping' },

  // OpenAI — vision, no native PDF
  'gpt-4o':                  { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'GPT-4o', source: 'mapping' },
  'gpt-4o-mini':             { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'GPT-4o mini', source: 'mapping' },
  'gpt-4-turbo':             { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'GPT-4 Turbo', source: 'mapping' },
  'gpt-4':                   { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 8192, displayName: 'GPT-4', source: 'mapping' },

  // DeepSeek — text only
  'deepseek-chat':             { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 64000, displayName: 'DeepSeek Chat', source: 'mapping' },
  'deepseek-reasoner':         { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 64000, displayName: 'DeepSeek Reasoner', source: 'mapping' },

  // Qwen — text + vision variants
  'qwen-vl-plus':             { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: 'Qwen VL Plus', source: 'mapping' },
  'qwen-vl-max':              { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: 'Qwen VL Max', source: 'mapping' },
  'qwen2.5-0.5b':             { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: 'Qwen2.5 0.5B', source: 'mapping' },
  'qwen2.5-1.5b':             { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: 'Qwen2.5 1.5B', source: 'mapping' },
  'qwen2.5-3b':               { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: 'Qwen2.5 3B', source: 'mapping' },
  'qwen2.5-7b':               { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'Qwen2.5 7B', source: 'mapping' },
  'qwen2.5-14b':              { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'Qwen2.5 14B', source: 'mapping' },
  'qwen2.5-32b':              { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'Qwen2.5 32B', source: 'mapping' },
  'qwen2.5-72b':              { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'Qwen2.5 72B', source: 'mapping' },
  'qwen2.5-coder':            { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'Qwen2.5 Coder', source: 'mapping' },
  'qwen2.5-math':             { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'Qwen2.5 Math', source: 'mapping' },
  'qwen2.5-turbo':            { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 1000000, displayName: 'Qwen2.5 Turbo', source: 'mapping' },

  // GLM — text + vision
  'glm-4':                    { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'GLM-4', source: 'mapping' },
  'glm-4.5':                  { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'GLM-4.5', source: 'mapping' },
  'glm-4.6':                  { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: 'GLM-4.6', source: 'mapping' },
  'glm-4.7':                  { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: 'GLM-4.7', source: 'mapping' },
  'glm-5':                    { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: 'GLM-5', source: 'mapping' },
  'glm-5.1':                  { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: 'GLM-5.1', source: 'mapping' },

  // MiniMax — text
  'minimax-text-01':          { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 4000000, displayName: 'MiniMax-Text-01', source: 'mapping' },
  'minimax-m1':               { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 1000000, displayName: 'MiniMax-M1', source: 'mapping' },
  'minimax-m2':               { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 204800, displayName: 'MiniMax-M2', source: 'mapping' },
  'minimax-m2.1':             { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 204800, displayName: 'MiniMax-M2.1', source: 'mapping' },
  'minimax-m2.5':             { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 204800, displayName: 'MiniMax-M2.5', source: 'mapping' },
  'minimax-m2.7':             { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 204800, displayName: 'MiniMax-M2.7', source: 'mapping' },

  // Kimi (Moonshot) — text + vision
  'moonshot-v1-8k':           { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 8192, displayName: 'Moonshot V1 8K', source: 'mapping' },
  'moonshot-v1-32k':          { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 32768, displayName: 'Moonshot V1 32K', source: 'mapping' },
  'moonshot-v1-128k':         { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 131072, displayName: 'Moonshot V1 128K', source: 'mapping' },
  'kimi-k2.5':                { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 256000, displayName: 'Kimi K2.5', source: 'mapping' },
  'kimi-k2':                  { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 256000, displayName: 'Kimi K2', source: 'mapping' },

  // Ollama vision models
  'llava':                   { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 4096, displayName: 'LLaVA', source: 'mapping' },
  'llava:13b':               { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 4096, displayName: 'LLaVA 13B', source: 'mapping' },
  'bakllava':                { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 4096, displayName: 'BakLLaVA', source: 'mapping' },
  'qwen2-vl':                { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: 'Qwen2-VL', source: 'mapping' },
  'llama3.2-vision':            { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: 'Llama 3.2 Vision', source: 'mapping' },
  'minicpm-v':                { text: true, image: true,  pdf: false, audio: false, video: false, maxInputTokens: 4096, displayName: 'MiniCPM-V', source: 'mapping' },
};

/**
 * Look up model capabilities by fuzzy name matching.
 */
function lookupByFuzzyName(model: string): DetectedCapabilities | null {
  const lower = model.toLowerCase();

  // Anthropic Claude
  if (lower.includes('claude')) {
    return { text: true, image: true, pdf: true, audio: false, video: false, maxInputTokens: 200000, displayName: `Claude (${model})`, source: 'mapping' };
  }

  // OpenAI GPT-4 variants
  if (lower.includes('gpt-4') && !lower.includes('gpt-3')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: model, source: 'mapping' };
  }
  if (lower.includes('gpt-3')) {
    return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 4096, displayName: model, source: 'mapping' };
  }

  // DeepSeek — official API supports 128K
  if (lower.includes('deepseek')) {
    return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: model, source: 'mapping' };
  }

  // Qwen variants
  if (lower.includes('qwen')) {
    if (lower.includes('qwen-vl')) return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: model, source: 'mapping' };
    if (lower.includes('qwen2.5-0.5b') || lower.includes('qwen2.5-1.5b') || lower.includes('qwen2.5-3b')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 32000, displayName: model, source: 'mapping' };
    if (lower.includes('qwen2.5-turbo')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 1000000, displayName: model, source: 'mapping' };
    if (lower.includes('qwen2.5')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: model, source: 'mapping' };
    if (lower.includes('qwen3')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: model, source: 'mapping' };
    return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: model, source: 'mapping' };
  }

  // GLM variants
  if (lower.includes('glm')) {
    if (lower.includes('glm-4.5')) return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: model, source: 'mapping' };
    if (lower.includes('glm-4')) return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: model, source: 'mapping' };
    if (lower.includes('glm-5')) return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: model, source: 'mapping' };
    return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: model, source: 'mapping' };
  }

  // MiniMax variants
  if (lower.includes('minimax')) {
    if (lower.includes('text-01')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 4000000, displayName: model, source: 'mapping' };
    if (lower.includes('m1')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 1000000, displayName: model, source: 'mapping' };
    if (lower.includes('m2')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 204800, displayName: model, source: 'mapping' };
    return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 204800, displayName: model, source: 'mapping' };
  }

  // Kimi (Moonshot) variants
  if (lower.includes('kimi') || lower.includes('moonshot')) {
    if (lower.includes('8k')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 8192, displayName: model, source: 'mapping' };
    if (lower.includes('32k')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 32768, displayName: model, source: 'mapping' };
    if (lower.includes('128k')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 131072, displayName: model, source: 'mapping' };
    if (lower.includes('k2')) return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 256000, displayName: model, source: 'mapping' };
    return { text: true, image: false, pdf: false, audio: false, video: false, maxInputTokens: 200000, displayName: model, source: 'mapping' };
  }

  // Ollama vision models
  if (lower.includes('llava') || lower.includes('bakllava') || lower.includes('minicpm-v')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 4096, displayName: model, source: 'mapping' };
  }

  // Llama vision
  if (lower.includes('llama') && lower.includes('vision')) {
    return { text: true, image: true, pdf: false, audio: false, video: false, maxInputTokens: 128000, displayName: model, source: 'mapping' };
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
 * Layer 3: Probe — send an image with a question that requires processing it.
 * We ask the model to describe the image content. If it correctly identifies
 * something in the image, we know it processed it. If it gives a generic
 * response unrelated to the image, it likely ignored the image content.
 */
async function probeVisionCapability(
  apiKey: string,
  model: string,
  baseUrl?: string,
): Promise<DetectedCapabilities> {
  const url = baseUrl
    ? `${baseUrl.replace(/\/+$/, '')}/v1/messages`
    : `https://api.anthropic.com/v1/messages`;

  // 2x2 red PNG — distinct color that a vision model should describe
  // Created from: a 2x2 pixel image with all pixels set to red (255,0,0)
  const RED_PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAAByss0lAAAAFUlEQVQI12P8z8BQz0BFwMgwww0oAHYPCspjiWkoAAAAAElFTkSuQmCC';

  const TEXT_ONLY_CAPS: DetectedCapabilities = {
    text: true,
    image: false,
    pdf: false,
    audio: false,
    video: false,
    source: 'probe',
  };

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
        max_tokens: 50,
        messages: [{
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: 'image/png', data: RED_PNG_BASE64 } },
            { type: 'text', text: 'What color is this image? Reply with just the color name in English.' },
          ],
        }],
      }),
    });

    if (!resp.ok) {
      // If the API rejects image content entirely (e.g. 400 error), it doesn't support images
      log.info(`Probe: API returned ${resp.status}, model does not support images`);
      return TEXT_ONLY_CAPS;
    }

    const data = await resp.json() as any;

    // Extract text from response
    let responseText = '';
    if (data.content) {
      for (const block of data.content) {
        if (block.type === 'text' && block.text) {
          responseText += block.text;
        }
      }
    }

    const lowerResponse = responseText.toLowerCase().trim();

    // The image is solid red. A model that processed the image should mention "red" (or "红色").
    // Models that ignore the image will give generic responses like "I can help with..."
    // or claim they cannot see images.
    const mentionsRed = lowerResponse.includes('red') || lowerResponse.includes('红色') || lowerResponse.includes('紅色');
    if (mentionsRed) {
      log.info(`Probe: model described image color correctly → image supported`);
      return {
        text: true,
        image: true,
        pdf: false,
        audio: false,
        video: false,
        source: 'probe',
      };
    }

    log.info(`Probe: model response "${lowerResponse.substring(0, 100)}" does not mention red → image NOT supported`);
    return TEXT_ONLY_CAPS;
  } catch (err) {
    log.info(`Probe: failed with error ${err instanceof Error ? err.message : String(err)}`);
    return TEXT_ONLY_CAPS;
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
        max_tokens: 5,
        messages: [{ role: 'user', content: 'hi' }],
        stream: true,
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
  return {
    success: true,
    capabilities: {
      ...probeCaps,
      maxInputTokens: probeCaps.maxInputTokens ?? 200000,
    },
  };
}
