/**
 * Content Block Builder for Claude SDK multimodal messages.
 *
 * Constructs the content array for `SDKUserMessage` when media is present.
 * Falls back to plain string when no media or model doesn't support images.
 */
import type { MediaAttachment } from '../chatbot/types.js';
import type { DetectedCapabilities } from '../types.js';

export interface ContentBlock {
  type: string;
  text?: string;
  source?: {
    type: string;
    media_type: string;
    data: string;
  };
}

/**
 * Build content blocks for Claude SDK send().
 *
 * Returns:
 * - `string` when no media (backward compatible)
 * - `ContentBlock[]` when media is present and model supports images
 *
 * When media exists but model doesn't support images, attaches a degrade notice
 * to the text and returns string.
 */
export function buildContentBlocks(
  text: string,
  media?: MediaAttachment[],
  capabilities?: DetectedCapabilities,
): string | ContentBlock[] {
  if (!media || media.length === 0) {
    return text;
  }

  const imageSupported = capabilities?.image ?? false;

  if (!imageSupported) {
    // Degrade: process text only, append notice
    const hasText = text.trim().length > 0;
    if (hasText) {
      return text + '\n\n[系统提示：用户发送了图片，但当前模型不支持图片处理，已忽略]';
    }
    return '当前模型不支持图片理解。请切换到支持图片的模型（如 claude-sonnet-4-6），或以文字描述需求。';
  }

  // Model supports images — build content block array
  const blocks: ContentBlock[] = [];

  // Add text first if present
  if (text.trim()) {
    blocks.push({ type: 'text', text });
  }

  // Add image blocks
  for (const attachment of media) {
    if (isImageMimeType(attachment.mimeType)) {
      // Both explicit type='image' and StagedMedia (no type field, has image/* mimeType)
      blocks.push({
        type: 'image',
        source: {
          type: 'base64',
          media_type: attachment.mimeType,
          data: attachment.base64Data,
        },
      });
    } else if (attachment.type === 'document' && isPdfMimeType(attachment.mimeType) && capabilities?.pdf) {
      blocks.push({
        type: 'document',
        source: {
          type: 'base64',
          media_type: attachment.mimeType,
          data: attachment.base64Data,
        },
      });
    } else if (attachment.transcription) {
      // Voice with auto-transcription — treat as text
      blocks.push({ type: 'text', text: `[语音转文字] ${attachment.transcription}` });
    }
    // Unsupported types silently skipped
  }

  return blocks.length > 0 ? blocks : text;
}

function isImageMimeType(mime: string): boolean {
  return mime.startsWith('image/');
}

function isPdfMimeType(mime: string): boolean {
  return mime === 'application/pdf';
}
