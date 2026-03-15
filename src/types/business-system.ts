/**
 * 业务系统相关类型定义
 */

export interface BusinessSystem {
  id: string;
  name: string;
  description?: string;
  path: string;
  techStack?: string[];
}

export interface SystemSession {
  /** 会话唯一标识 */
  id: string;
  /** 关联的业务系统 ID */
  systemId: string;
  /** 会话名称 ("新会话" 或用户消息前6字) */
  label: string;
  /** 创建时间戳 */
  createdAt: number;
  /** 更新时间戳 */
  updatedAt?: number;
}

export interface SystemsListResponse {
  systems: BusinessSystem[];
}
