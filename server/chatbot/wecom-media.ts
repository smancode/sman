/**
 * WeCom AI Bot media download and decryption.
 *
 * WeCom encrypts media files with AES-256-CBC:
 * - Key = aeskey (Base64-decoded)
 * - IV  = first 16 bytes of the key
 * - Padding = PKCS#7
 * - URLs are valid for ~5 minutes
 */

import https from 'https';
import http from 'http';
import crypto from 'crypto';
import { createLogger, type Logger } from '../utils/logger.js';

const log = createLogger('WeComMedia');

/**
 * Download a URL and return its body as a Buffer.
 */
function downloadBuffer(url: string): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const client = url.startsWith('https') ? https : http;
    client.get(url, (res) => {
      if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        // Follow redirect
        downloadBuffer(res.headers.location).then(resolve).catch(reject);
        return;
      }
      if (res.statusCode !== 200) {
        reject(new Error(`Download failed: HTTP ${res.statusCode}`));
        res.resume();
        return;
      }
      const chunks: Buffer[] = [];
      res.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('end', () => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    }).on('error', reject);
  });
}

/**
 * Decrypt WeCom media using AES-256-CBC.
 * - aesKey is Base64-encoded
 * - IV = first 16 bytes of the decoded key
 * - PKCS#7 padding removed automatically by crypto.createDecipheriv
 */
export function decryptAes256Cbc(encrypted: Buffer, aesKeyBase64: string): Buffer {
  const key = Buffer.from(aesKeyBase64, 'base64');
  if (key.length < 16) {
    throw new Error(`Invalid aeskey length: ${key.length} bytes, expected >= 16`);
  }
  const iv = key.subarray(0, 16);
  const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv);
  return Buffer.concat([decipher.update(encrypted), decipher.final()]);
}

/**
 * Download and decrypt a WeCom media file.
 * Returns the decrypted Buffer and detected MIME type from response headers.
 */
export async function downloadAndDecrypt(
  url: string,
  aesKeyBase64: string,
): Promise<{ buffer: Buffer; mimeType: string }> {
  log.info(`Downloading media from WeCom (url length=${url.length})`);
  const encrypted = await downloadBuffer(url);
  log.info(`Downloaded ${encrypted.length} bytes, decrypting...`);
  const buffer = decryptAes256Cbc(encrypted, aesKeyBase64);
  const mimeType = detectMimeType(buffer);
  log.info(`Decrypted ${buffer.length} bytes, mime=${mimeType}`);
  return { buffer, mimeType };
}

/**
 * Detect MIME type from file header magic bytes.
 */
export function detectMimeType(buf: Buffer): string {
  if (buf.length < 4) return 'application/octet-stream';

  // PNG: 89 50 4E 47
  if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) {
    return 'image/png';
  }
  // JPEG: FF D8 FF
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) {
    return 'image/jpeg';
  }
  // GIF: 47 49 46 38
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46 && buf[3] === 0x38) {
    return 'image/gif';
  }
  // WebP: 52 49 46 46 ... 57 45 42 50
  if (buf[0] === 0x52 && buf[1] === 0x49 && buf[2] === 0x46 && buf[3] === 0x46
    && buf.length >= 12 && buf[8] === 0x57 && buf[9] === 0x45 && buf[10] === 0x42 && buf[11] === 0x50) {
    return 'image/webp';
  }
  // PDF: 25 50 44 46
  if (buf[0] === 0x25 && buf[1] === 0x50 && buf[2] === 0x44 && buf[3] === 0x46) {
    return 'application/pdf';
  }
  // AMR (voice): 23 21 41 4D 52
  if (buf.length >= 5 && buf[0] === 0x23 && buf[1] === 0x21 && buf[2] === 0x41 && buf[3] === 0x4D && buf[4] === 0x52) {
    return 'audio/amr';
  }
  // SILK (voice): 23 21 53 49 4C 4B
  if (buf.length >= 6 && buf.toString('ascii', 0, 6) === '#!SILK') {
    return 'audio/silk';
  }
  // MP4: various ftyp boxes
  if (buf.length >= 8 && buf[4] === 0x66 && buf[5] === 0x74 && buf[6] === 0x79 && buf[7] === 0x70) {
    return 'video/mp4';
  }

  return 'application/octet-stream';
}

/**
 * Map WeCom msgtype to MediaAttachment type.
 */
export function wecomMsgtypeToMediaType(msgtype: string): 'image' | 'audio' | 'video' | 'document' {
  switch (msgtype) {
    case 'image': return 'image';
    case 'voice': return 'audio';
    case 'video': return 'video';
    case 'file': return 'document';
    default: return 'document';
  }
}
