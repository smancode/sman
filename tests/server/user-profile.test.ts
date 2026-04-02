import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { UserProfileManager } from '../../server/user-profile.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('UserProfileManager', () => {
  let homeDir: string;
  let manager: UserProfileManager;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `sman-user-profile-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    manager = new UserProfileManager(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  describe('loadProfile', () => {
    it('should create empty template when profile file does not exist', () => {
      const profile = manager.loadProfile();
      expect(profile).toContain('# 用户画像');
      expect(profile).toContain('## 助手身份');
      expect(profile).toContain('## 用户身份');
      expect(profile).toContain('## 技术偏好');
      expect(profile).toContain('## 常用任务');
      expect(profile).toContain('## 失败任务');
      expect(profile).toContain('## 沟通风格');
      expect(profile).toContain('## 回复策略');
      const filePath = path.join(homeDir, 'user-profile.md');
      expect(fs.existsSync(filePath)).toBe(true);
    });

    it('should load existing profile from file', () => {
      const customProfile = `# 用户画像\n\n## 助手身份\n- 名字: Crumpet`;
      fs.writeFileSync(path.join(homeDir, 'user-profile.md'), customProfile, 'utf-8');
      const profile = manager.loadProfile();
      expect(profile).toBe(customProfile);
    });

    it('should recreate template when file is corrupted (empty)', () => {
      fs.writeFileSync(path.join(homeDir, 'user-profile.md'), '', 'utf-8');
      const profile = manager.loadProfile();
      expect(profile).toContain('# 用户画像');
    });
  });
});
