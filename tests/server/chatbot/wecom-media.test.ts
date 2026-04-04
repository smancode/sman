import { describe, it, expect } from 'vitest';
import crypto from 'crypto';
import { decryptAes256Cbc, detectMimeType, wecomMsgtypeToMediaType } from '../../../server/chatbot/wecom-media.js';

// We test the pure functions directly. downloadAndDecrypt requires network
// so it's tested via integration tests only.

describe('wecom-media helpers', () => {
  describe('detectMimeType', () => {
    it('detects PNG', () => {
      const buf = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]);
      expect(detectMimeType(buf)).toBe('image/png');
    });

    it('detects JPEG', () => {
      const buf = Buffer.from([0xff, 0xd8, 0xff, 0xe0]);
      expect(detectMimeType(buf)).toBe('image/jpeg');
    });

    it('detects GIF', () => {
      const buf = Buffer.from([0x47, 0x49, 0x46, 0x38, 0x39, 0x61]);
      expect(detectMimeType(buf)).toBe('image/gif');
    });

    it('detects WebP', () => {
      const buf = Buffer.from([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50]);
      expect(detectMimeType(buf)).toBe('image/webp');
    });

    it('detects PDF', () => {
      const buf = Buffer.from([0x25, 0x50, 0x44, 0x46, 0x2d]);
      expect(detectMimeType(buf)).toBe('application/pdf');
    });

    it('detects AMR audio', () => {
      const buf = Buffer.from([0x23, 0x21, 0x41, 0x4d, 0x52, 0x0a]);
      expect(detectMimeType(buf)).toBe('audio/amr');
    });

    it('detects SILK audio', () => {
      const buf = Buffer.from('#!SILK_V3');
      expect(detectMimeType(buf)).toBe('audio/silk');
    });

    it('detects MP4 video', () => {
      const buf = Buffer.from([0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70]);
      expect(detectMimeType(buf)).toBe('video/mp4');
    });

    it('returns octet-stream for unknown', () => {
      const buf = Buffer.from([0x00, 0x01, 0x02, 0x03]);
      expect(detectMimeType(buf)).toBe('application/octet-stream');
    });

    it('returns octet-stream for short buffers', () => {
      const buf = Buffer.from([0x00, 0x01]);
      expect(detectMimeType(buf)).toBe('application/octet-stream');
    });
  });

  describe('decryptAes256Cbc', () => {
    it('round-trips encrypt/decrypt', () => {
      const key = crypto.randomBytes(32);
      const iv = key.subarray(0, 16);
      const plaintext = Buffer.from('Hello WeCom media decryption!');

      const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
      const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);

      const aesKeyBase64 = key.toString('base64');
      const decrypted = decryptAes256Cbc(encrypted, aesKeyBase64);

      expect(decrypted.toString()).toBe('Hello WeCom media decryption!');
    });

    it('rejects short keys', () => {
      const shortKey = Buffer.alloc(8).toString('base64');
      expect(() => decryptAes256Cbc(Buffer.alloc(32), shortKey)).toThrow('Invalid aeskey length');
    });
  });

  describe('wecomMsgtypeToMediaType', () => {
    it('maps known types', () => {
      expect(wecomMsgtypeToMediaType('image')).toBe('image');
      expect(wecomMsgtypeToMediaType('voice')).toBe('audio');
      expect(wecomMsgtypeToMediaType('video')).toBe('video');
      expect(wecomMsgtypeToMediaType('file')).toBe('document');
    });

    it('maps unknown to document', () => {
      expect(wecomMsgtypeToMediaType('location')).toBe('document');
    });
  });
});
