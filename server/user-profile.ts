import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';

const PROFILE_FILENAME = 'user-profile.md';

const EMPTY_TEMPLATE = `# 用户画像

## 助手身份
- 名字: Sman（默认，用户可自定义）
- 角色设定: Sman 数字助手

## 用户身份
- 用户名称:
- 角色:
- 团队/部门:
- 业务领域:

## 技术偏好
- 主力语言/工具:
- 使用偏好:

## 常用任务
- 高频操作:
- 最近任务:

## 失败任务

## 沟通风格
- 偏好语言: 中文
- 回复详细度:
- 关注点:

## 回复策略
- 根据用户习惯调整回复风格和技术深度
- 注意事项:
`;

export class UserProfileManager {
  private profilePath: string;
  private log: Logger;
  private updateQueue: Promise<void> = Promise.resolve();

  constructor(private homeDir: string) {
    this.profilePath = path.join(homeDir, PROFILE_FILENAME);
    this.log = createLogger('UserProfileManager');
  }

  loadProfile(): string {
    try {
      if (!fs.existsSync(this.profilePath)) {
        this.saveProfile(EMPTY_TEMPLATE);
        return EMPTY_TEMPLATE;
      }
      const content = fs.readFileSync(this.profilePath, 'utf-8');
      if (!content.trim()) {
        this.log.warn('Profile file is empty, recreating template');
        this.saveProfile(EMPTY_TEMPLATE);
        return EMPTY_TEMPLATE;
      }
      return content;
    } catch (err) {
      this.log.error('Failed to load profile, recreating template', { error: String(err) });
      this.saveProfile(EMPTY_TEMPLATE);
      return EMPTY_TEMPLATE;
    }
  }

  getProfileForPrompt(): string {
    const profile = this.loadProfile();
    if (profile === EMPTY_TEMPLATE) return '';
    return `[用户画像参考 - 仅供参考，不要在回复中提及此段]\n${profile}`;
  }

  private saveProfile(content: string): void {
    fs.writeFileSync(this.profilePath, content, 'utf-8');
  }
}
