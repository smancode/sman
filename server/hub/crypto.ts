import crypto from 'node:crypto';
import type { EncryptedRequest } from './types.js';

const ALGORITHM = 'aes-256-gcm';
const IV_LENGTH = 12;
const AUTH_TAG_LENGTH = 16;

const PSK = 'sman-hub-aes256-key!!2026-32b!!!';
const PSK_VERSION = 1;

export function encrypt(data: unknown): string {
  const iv = crypto.randomBytes(IV_LENGTH);
  const key = Buffer.from(PSK, 'utf-8');
  const cipher = crypto.createCipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
  const plaintext = JSON.stringify(data);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf-8'), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return Buffer.concat([iv, encrypted, authTag]).toString('base64');
}

export function decrypt(encoded: string): unknown {
  const buf = Buffer.from(encoded, 'base64');
  const key = Buffer.from(PSK, 'utf-8');
  const iv = buf.subarray(0, IV_LENGTH);
  const authTag = buf.subarray(buf.length - AUTH_TAG_LENGTH);
  const ciphertext = buf.subarray(IV_LENGTH, buf.length - AUTH_TAG_LENGTH);
  const decipher = crypto.createDecipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
  decipher.setAuthTag(authTag);
  const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  return JSON.parse(decrypted.toString('utf-8'));
}

export function buildEncryptedRequest(payload: unknown): EncryptedRequest {
  return {
    payload: encrypt(payload),
    timestamp: Math.floor(Date.now() / 1000),
    pskVersion: PSK_VERSION,
  };
}
