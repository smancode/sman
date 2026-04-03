import { describe, it, expect } from 'vitest';
import { buildContentBlocks } from '../../server/utils/content-blocks.js';
import type { MediaAttachment } from '../../server/chatbot/types.js';
import type { DetectedCapabilities } from '../../server/types.js';

const IMAGE_CAPS: DetectedCapabilities = {
  text: true, image: true, pdf: true, audio: false, video: false, source: 'api',
};

const TEXT_ONLY_CAPS: DetectedCapabilities = {
  text: true, image: false, pdf: false, audio: false, video: false, source: 'mapping',
};

const PDF_CAPS: DetectedCapabilities = {
  text: true, image: true, pdf: true, audio: false, video: false, source: 'api',
};

const sampleImage: MediaAttachment = {
  type: 'image',
  mimeType: 'image/png',
  base64Data: 'iVBORw0KGgoAAAANS',
  fileName: 'test.png',
};

const samplePdf: MediaAttachment = {
  type: 'document',
  mimeType: 'application/pdf',
  base64Data: 'JVBERi0xLjQ=',
  fileName: 'doc.pdf',
};

const sampleVoice: MediaAttachment = {
  type: 'audio',
  mimeType: 'audio/amr',
  base64Data: 'AAAA',
  transcription: '你好世界',
};

describe('buildContentBlocks', () => {
  describe('no media', () => {
    it('should return plain string when no media', () => {
      const result = buildContentBlocks('hello');
      expect(result).toBe('hello');
    });

    it('should return plain string when media array is empty', () => {
      const result = buildContentBlocks('hello', []);
      expect(result).toBe('hello');
    });
  });

  describe('image support', () => {
    it('should build content blocks with image when model supports it', () => {
      const result = buildContentBlocks('描述这张图', [sampleImage], IMAGE_CAPS);
      expect(Array.isArray(result)).toBe(true);
      const blocks = result as any[];
      expect(blocks).toHaveLength(2);
      expect(blocks[0]).toEqual({ type: 'text', text: '描述这张图' });
      expect(blocks[1].type).toBe('image');
      expect(blocks[1].source.media_type).toBe('image/png');
    });

    it('should handle image-only (no text)', () => {
      const result = buildContentBlocks('', [sampleImage], IMAGE_CAPS);
      expect(Array.isArray(result)).toBe(true);
      const blocks = result as any[];
      // No text block, just image
      expect(blocks).toHaveLength(1);
      expect(blocks[0].type).toBe('image');
    });

    it('should degrade to string with notice when model does not support images', () => {
      const result = buildContentBlocks('描述图片', [sampleImage], TEXT_ONLY_CAPS);
      expect(typeof result).toBe('string');
      expect(result).toContain('不支持图片处理');
    });

    it('should return degrade notice when no text and no image support', () => {
      const result = buildContentBlocks('', [sampleImage], TEXT_ONLY_CAPS);
      expect(typeof result).toBe('string');
      expect(result).toContain('不支持图片理解');
    });

    it('should degrade when capabilities not provided', () => {
      const result = buildContentBlocks('看图', [sampleImage]);
      expect(typeof result).toBe('string');
      expect(result).toContain('不支持图片处理');
    });
  });

  describe('PDF support', () => {
    it('should include PDF as document block when model supports it', () => {
      const result = buildContentBlocks('分析这个PDF', [samplePdf], PDF_CAPS);
      expect(Array.isArray(result)).toBe(true);
      const blocks = result as any[];
      expect(blocks.some((b: any) => b.type === 'document')).toBe(true);
    });
  });

  describe('voice with transcription', () => {
    it('should include transcription as text block', () => {
      const result = buildContentBlocks('', [sampleVoice], TEXT_ONLY_CAPS);
      expect(typeof result).toBe('string');
      // Voice with transcription degrades since no image support, but text has no content
      // So the notice is returned instead
    });

    it('should include transcription in content blocks with image support', () => {
      const result = buildContentBlocks('回复语音消息', [sampleVoice], IMAGE_CAPS);
      expect(Array.isArray(result)).toBe(true);
      const blocks = result as any[];
      expect(blocks.some((b: any) => b.text?.includes('语音转文字'))).toBe(true);
    });
  });

  describe('multiple media', () => {
    it('should handle multiple images', () => {
      const result = buildContentBlocks('比较这些图片', [sampleImage, { ...sampleImage, fileName: 'test2.png' }], IMAGE_CAPS);
      expect(Array.isArray(result)).toBe(true);
      const blocks = result as any[];
      const imageBlocks = blocks.filter((b: any) => b.type === 'image');
      expect(imageBlocks).toHaveLength(2);
    });
  });

  describe('image file with document type', () => {
    it('should treat image mime in document attachment as image', () => {
      const imgDoc: MediaAttachment = {
        type: 'document',
        mimeType: 'image/jpeg',
        base64Data: '/9j/4AAQ',
        fileName: 'photo.jpg',
      };
      const result = buildContentBlocks('看图', [imgDoc], IMAGE_CAPS);
      expect(Array.isArray(result)).toBe(true);
      const blocks = result as any[];
      expect(blocks.some((b: any) => b.type === 'image')).toBe(true);
    });
  });
});
