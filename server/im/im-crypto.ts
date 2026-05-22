import { encrypt, decrypt, loadPsk } from '../hub/crypto.js';

const IM_ENCRYPTED_PREFIX = 'enc:';

/** Encrypt a string value for IM transmission */
export function encryptField(plaintext: string): string {
  return IM_ENCRYPTED_PREFIX + encrypt(plaintext);
}

/** Decrypt an IM encrypted field, returns original if not encrypted */
export function decryptField(ciphertext: string): string {
  if (!ciphertext.startsWith(IM_ENCRYPTED_PREFIX)) return ciphertext;
  try {
    return decrypt(ciphertext.slice(IM_ENCRYPTED_PREFIX.length)) as string;
  } catch {
    return ciphertext;
  }
}

/** Encrypt IM message content fields for transmission to Hub */
export function encryptIMMessage(msg: Record<string, unknown>): Record<string, unknown> {
  const result = { ...msg };
  if (typeof result.content === 'string' && result.content) {
    result.content = encryptField(result.content);
  }
  if (typeof result.attachments === 'string' && result.attachments) {
    result.attachments = encryptField(result.attachments);
  }
  return result;
}

/** Decrypt IM message content fields received from Hub */
export function decryptIMMessage(msg: Record<string, unknown>): Record<string, unknown> {
  const result = { ...msg };
  if (typeof result.content === 'string' && result.content) {
    result.content = decryptField(result.content);
  }
  if (typeof result.attachments === 'string' && result.attachments) {
    result.attachments = decryptField(result.attachments);
  }
  return result;
}
