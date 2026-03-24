/**
 * 业务系统相关类型定义 (SmanBase)
 */

/** 业务系统 Profile — 与后端 Profile 一致 */
export interface BusinessSystem {
  systemId: string;
  name: string;
  workspace: string;
  description: string;
  skills: string[];
  autoTriggers: {
    onInit: string[];
    onConversationStart: string[];
  };
  claudeMdTemplate?: string;
}

/** 创建业务系统输入 */
export interface CreateBusinessSystemInput {
  systemId: string;
  name: string;
  workspace: string;
  description: string;
  skills: string[];
  autoTriggers?: {
    onInit?: string[];
    onConversationStart?: string[];
  };
  claudeMdTemplate?: string;
}

/** 更新业务系统输入 */
export type UpdateBusinessSystemInput = Partial<Omit<CreateBusinessSystemInput, 'systemId'>>;

/** Skill 条目（来自 registry） */
export interface SkillItem {
  id: string;
  name: string;
  description: string;
  version: string;
  path: string;
  triggers: string[];
  tags: string[];
}
