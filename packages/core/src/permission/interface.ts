/**
 * SmanCode Core - Permission 权限系统接口定义
 *
 * 权限系统控制工具执行的访问控制
 */

import type { PermissionAction, PermissionRule, PermissionRequest } from "../types"

// ============================================================================
// 权限规则
// ============================================================================

/** 规则求值结果 */
export interface RuleEvaluation {
  /** 匹配的规则 */
  rule: PermissionRule
  /** 是否匹配 */
  matched: boolean
  /** 动作 */
  action: PermissionAction
}

/** 权限规则管理器接口 */
export interface PermissionRuleManager {
  /** 添加规则 */
  addRule(rule: PermissionRule): Promise<void>

  /** 添加规则集 */
  addRuleset(rules: PermissionRule[]): Promise<void>

  /** 获取规则 */
  getRules(permission?: string): Promise<PermissionRule[]>

  /** 更新规则 */
  updateRule(index: number, rule: PermissionRule): Promise<void>

  /** 删除规则 */
  removeRule(index: number): Promise<void>

  /** 清空规则 */
  clearRules(): Promise<void>

  /** 求值规则 */
  evaluate(request: PermissionRequest): Promise<RuleEvaluation[]>
}

// ============================================================================
// 权限检查器
// ============================================================================

/** 权限检查结果 */
export interface PermissionCheckResult {
  /** 是否允许 */
  allowed: boolean
  /** 动作 */
  action: PermissionAction
  /** 匹配的规则 */
  matchedRule?: PermissionRule
  /** 拒绝原因 */
  reason?: string
}

/** 权限检查器接口 */
export interface PermissionChecker {
  /** 检查权限 */
  check(request: PermissionRequest): Promise<PermissionCheckResult>

  /** 批量检查 */
  checkBatch(requests: PermissionRequest[]): Promise<PermissionCheckResult[]>

  /** 请求用户确认 */
  askUser(request: PermissionRequest): Promise<boolean>
}

// ============================================================================
// 权限会话管理
// ============================================================================

/** 临时授权 */
export interface TemporaryGrant {
  id: string
  permission: string
  pattern: string
  sessionId: string
  expiresAt?: number
  createdAt: number
}

/** 权限会话管理器接口 */
export interface PermissionSessionManager {
  /** 添加临时授权 */
  grant(grant: Omit<TemporaryGrant, "id" | "createdAt">): Promise<TemporaryGrant>

  /** 获取会话授权 */
  getGrants(sessionId: string): Promise<TemporaryGrant[]>

  /** 检查是否已授权 */
  isGranted(sessionId: string, permission: string, pattern: string): Promise<boolean>

  /** 撤销授权 */
  revoke(grantId: string): Promise<void>

  /** 清理会话授权 */
  clearSession(sessionId: string): Promise<void>

  /** 清理过期授权 */
  cleanupExpired(): Promise<number>
}

// ============================================================================
// 权限管理器（聚合接口）
// ============================================================================

/** 权限管理器接口 */
export interface PermissionManager {
  /** 规则管理器 */
  rules: PermissionRuleManager

  /** 检查器 */
  checker: PermissionChecker

  /** 会话管理器 */
  session: PermissionSessionManager

  /** 初始化 */
  init(): Promise<void>

  /** 请求权限（完整流程） */
  request(request: PermissionRequest, sessionId: string): Promise<PermissionCheckResult>

  /** 关闭 */
  close(): Promise<void>
}

// ============================================================================
// 预定义权限类型
// ============================================================================

/** 系统权限类型 */
export const SystemPermissions = {
  /** 文件读取 */
  FILE_READ: "file.read",
  /** 文件写入 */
  FILE_WRITE: "file.write",
  /** 文件删除 */
  FILE_DELETE: "file.delete",
  /** 命令执行 */
  SHELL_EXEC: "shell.exec",
  /** 网络访问 */
  NETWORK_ACCESS: "network.access",
  /** 子任务创建 */
  TASK_CREATE: "task.create",
  /** 修改系统设置 */
  SYSTEM_CONFIG: "system.config",
} as const

/** 预定义规则模板 */
export const DefaultRulesets = {
  /** 只读模式 */
  readonly: [
    { permission: "*", pattern: "*.read", action: "allow" as PermissionAction },
    { permission: "*", pattern: "*", action: "deny" as PermissionAction },
  ],

  /** 标准模式 */
  standard: [
    { permission: "file.read", pattern: "*", action: "allow" as PermissionAction },
    { permission: "file.write", pattern: "*.md", action: "allow" as PermissionAction },
    { permission: "file.write", pattern: "*.txt", action: "allow" as PermissionAction },
    { permission: "shell.exec", pattern: "git *", action: "ask" as PermissionAction },
    { permission: "shell.exec", pattern: "npm *", action: "ask" as PermissionAction },
    { permission: "*", pattern: "*.env", action: "deny" as PermissionAction },
  ],

  /** 完全信任 */
  fullTrust: [
    { permission: "*", pattern: "*", action: "allow" as PermissionAction },
  ],
}
