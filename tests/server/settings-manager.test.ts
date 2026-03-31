import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SettingsManager } from '../../server/settings-manager.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SettingsManager', () => {
  let homeDir: string;
  let manager: SettingsManager;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `sman-test-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    manager = new SettingsManager(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  describe('ensureAuthToken', () => {
    it('should generate a token on first call', () => {
      const token = manager.ensureAuthToken();
      expect(token).toBeDefined();
      expect(token.length).toBe(64); // 32 bytes hex = 64 chars
    });

    it('should return the same token on subsequent calls', () => {
      const token1 = manager.ensureAuthToken();
      const token2 = manager.ensureAuthToken();
      expect(token1).toBe(token2);
    });

    it('should persist token to config.json', () => {
      const token = manager.ensureAuthToken();
      const config = JSON.parse(fs.readFileSync(path.join(homeDir, 'config.json'), 'utf-8'));
      expect(config.auth.token).toBe(token);
    });

    it('should use existing token from config.json', () => {
      const existingToken = 'abcdef1234567890'.repeat(4);
      fs.writeFileSync(
        path.join(homeDir, 'config.json'),
        JSON.stringify({ auth: { token: existingToken } }, null, 2),
        'utf-8'
      );
      const token = manager.ensureAuthToken();
      expect(token).toBe(existingToken);
    });

    it('should generate token and preserve existing config fields', () => {
      // Pre-write a config without auth field
      fs.writeFileSync(
        path.join(homeDir, 'config.json'),
        JSON.stringify({
          port: 5880,
          llm: { apiKey: 'test-key', model: 'test-model' },
          webSearch: { provider: 'builtin', braveApiKey: '', tavilyApiKey: '', bingApiKey: '', maxUsesPerSession: 50 },
        }, null, 2),
        'utf-8'
      );
      const token = manager.ensureAuthToken();
      expect(token).toBeDefined();
      expect(token.length).toBe(64);

      const config = JSON.parse(fs.readFileSync(path.join(homeDir, 'config.json'), 'utf-8'));
      expect(config.llm.apiKey).toBe('test-key');
      expect(config.llm.model).toBe('test-model');
      expect(config.auth.token).toBe(token);
    });
  });
});
