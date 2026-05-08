import { describe, it, expect } from 'vitest';
import { encrypt, decrypt, buildEncryptedRequest } from '../crypto.js';

describe('hub crypto', () => {
  it('should encrypt and decrypt round-trip', () => {
    const data = { clientId: 'test@1.2.3.4', version: '1.0' };
    const encrypted = encrypt(data);
    const decrypted = decrypt(encrypted);
    expect(decrypted).toEqual(data);
  });

  it('buildEncryptedRequest should produce valid structure', () => {
    const data = { test: true };
    const req = buildEncryptedRequest(data);
    expect(req).toHaveProperty('payload');
    expect(req).toHaveProperty('timestamp');
    expect(req).toHaveProperty('pskVersion', 1);
    expect(typeof req.payload).toBe('string');
  });
});
