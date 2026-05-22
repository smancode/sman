import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import type { EncryptedRequest } from './types.js';

const ALGORITHM = 'aes-256-gcm';
const IV_LENGTH = 12;
const AUTH_TAG_LENGTH = 16;
const KEY_LENGTH = 32;
const PSK_VERSION = 1;

let cachedKey: string | null = null;

function getBundledKeyPath(): string {
  // extraResources puts hub.key next to the app executable
  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  // dev: project root; prod: resources/ alongside app
  return path.resolve(__dirname, '../../hub.key');
}

function readKeyFile(filePath: string): string | null {
  try {
    const key = fs.readFileSync(filePath, 'utf-8').trim();
    if (key.length === KEY_LENGTH) return key;
  } catch {}
  return null;
}

function generateRandomKey(): string {
  return crypto.randomBytes(KEY_LENGTH).toString('base64').slice(0, KEY_LENGTH);
}

function getOrCreateUserKey(): string {
  const keyPath = path.join(os.homedir(), '.sman', 'hub.key');
  const existing = readKeyFile(keyPath);
  if (existing) return existing;

  // First launch — generate a unique random key
  const key = generateRandomKey();
  fs.mkdirSync(path.dirname(keyPath), { recursive: true });
  fs.writeFileSync(keyPath, key, 'utf-8');
  return key;
}

export function loadPsk(): string {
  if (cachedKey) return cachedKey;

  // Priority: SMAN_PSK env > ~/.sman/hub.key (auto-generated on first launch) > bundled hub.key
  if (process.env.SMAN_PSK && process.env.SMAN_PSK.length === KEY_LENGTH) {
    cachedKey = process.env.SMAN_PSK;
    return cachedKey;
  }

  const userKey = getOrCreateUserKey();
  if (userKey) { cachedKey = userKey; return userKey; }

  const bundledKey = readKeyFile(getBundledKeyPath());
  if (bundledKey) { cachedKey = bundledKey; return bundledKey; }

  // Fallback: generate in-memory only (won't persist across restarts)
  cachedKey = generateRandomKey();
  return cachedKey;
}

export function encrypt(data: unknown): string {
  const key = Buffer.from(loadPsk(), 'utf-8');
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
  const plaintext = JSON.stringify(data);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf-8'), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return Buffer.concat([iv, encrypted, authTag]).toString('base64');
}

export function decrypt(encoded: string): unknown {
  const key = Buffer.from(loadPsk(), 'utf-8');
  const buf = Buffer.from(encoded, 'base64');
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
