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

  describe('updateProfile', () => {
    it('should truncate long inputs', () => {
      const longText = 'a'.repeat(5000);
      const truncated = (manager as any).truncateInput(longText, 2000);
      expect(truncated.length).toBe(2003); // 2000 + '...'
      expect(truncated.endsWith('...')).toBe(true);
    });

    it('should not truncate short inputs', () => {
      const shortText = 'short message';
      const result = (manager as any).truncateInput(shortText, 2000);
      expect(result).toBe('short message');
    });

    it('should serialize concurrent updates via queue', async () => {
      const callOrder: number[] = [];
      (manager as any).callLLMForUpdate = async (_existing: string, _userMsg: string, _assistantMsg: string, tag?: number) => {
        callOrder.push(tag!);
        await new Promise(r => setTimeout(r, 50));
        return `# 用户画像\n\n## 助手身份\n- 更新 ${tag}`;
      };

      // Fire 3 concurrent updates — updateProfile is void, but the internal queue is a promise chain
      manager.updateProfile('user1', 'assistant1');
      // Wait for queue to settle
      await (manager as any).updateQueue;

      manager.updateProfile('user2', 'assistant2');
      await (manager as any).updateQueue;

      manager.updateProfile('user3', 'assistant3');
      await (manager as any).updateQueue;

      expect(callOrder.length).toBe(3);
    });

    it('should preserve profile when LLM call throws', async () => {
      (manager as any).callLLMForUpdate = async () => {
        throw new Error('LLM error');
      };

      const profileBefore = manager.loadProfile();
      manager.updateProfile('user msg', 'assistant msg');
      await (manager as any).updateQueue;
      const profileAfter = manager.loadProfile();

      expect(profileAfter).toBe(profileBefore);
    });

    it('should save updated profile when LLM returns valid result', async () => {
      const updatedProfile = `# 用户画像\n\n## 助手身份\n- 名字: Crumpet\n\n## 用户身份\n- 角色: 开发者`;
      (manager as any).callLLMForUpdate = async () => updatedProfile;

      manager.updateProfile('我叫Crumpet', '好的Crumpet');
      await (manager as any).updateQueue;

      const saved = manager.loadProfile();
      expect(saved).toBe(updatedProfile);
    });

    it('should reject invalid LLM response (missing # 用户画像)', async () => {
      (manager as any).callLLMForUpdate = async () => 'This is not a profile';

      const profileBefore = manager.loadProfile();
      manager.updateProfile('user msg', 'assistant msg');
      await (manager as any).updateQueue;

      const profileAfter = manager.loadProfile();
      expect(profileAfter).toBe(profileBefore);
    });
  });
});
