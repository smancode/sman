// stardom/src/protocol.ts
import type {
  AgentMessageType,
  TaskMessageType,
  WorldMessageType,
  ServerMessageType,
} from '../../shared/stardom-types.js';

type AllMessageTypes = AgentMessageType | TaskMessageType | WorldMessageType | ServerMessageType;

const VALID_TYPES: Set<string> = new Set([
  // Agent
  'agent.register', 'agent.registered', 'agent.heartbeat', 'agent.update', 'agent.offline',
  'agent.kicked', 'agent.resume_tasks',
  // Task
  'task.create', 'task.search_result', 'task.offer', 'task.incoming',
  'task.accept', 'task.reject', 'task.matched', 'task.chat',
  'task.progress', 'task.complete', 'task.result', 'task.timeout',
  'task.cancel', 'task.cancelled', 'task.escalate', 'task.sync',
  // World
  'world.move', 'world.agent_update', 'world.enter_zone', 'world.leave_zone',
  'world.zone_snapshot', 'world.agent_enter', 'world.agent_leave', 'world.event',
  'world.resync',
  // Server
  'ack', 'error', 'server.maintenance', 'reputation.update',
  // Capabilities
  'capabilities.search', 'capabilities.list', 'capabilities.publish', 'capabilities.remove',
]);

// 各消息类型的必填 payload 字段
const REQUIRED_FIELDS: Record<string, string[]> = {
  'agent.register': ['agentId', 'username', 'hostname', 'name'],
  'agent.heartbeat': ['agentId', 'status'],
  'agent.update': ['agentId'],
  'task.create': ['question', 'capabilityQuery'],
  'task.offer': ['taskId', 'targetAgent'],
  'task.accept': ['taskId'],
  'task.reject': ['taskId'],
  'task.chat': ['taskId', 'text'],
  'task.complete': ['taskId', 'rating'],
  'task.cancel': ['taskId', 'reason'],
  'task.sync': ['taskId'],
  'capabilities.search': ['query'],
  'capabilities.list': [],
  'capabilities.publish': ['name', 'description', 'version', 'category', 'packageUrl'],
  'capabilities.remove': ['name'],
};

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export function isValidMessageType(type: string): boolean {
  return VALID_TYPES.has(type);
}

export function validateMessage(raw: unknown): ValidationResult {
  const errors: string[] = [];

  if (!raw || typeof raw !== 'object') {
    return { valid: false, errors: ['Message must be an object'] };
  }

  const msg = raw as Record<string, unknown>;

  // 必填字段检查
  if (!msg.id || typeof msg.id !== 'string') {
    errors.push('Missing required field: id');
  }
  if (!msg.type || typeof msg.type !== 'string') {
    errors.push('Missing required field: type');
  }

  if (errors.length > 0) {
    return { valid: false, errors };
  }

  // 消息类型检查
  if (!isValidMessageType(msg.type as string)) {
    errors.push(`Unknown message type: ${msg.type}`);
    return { valid: false, errors };
  }

  // 特定消息类型的 payload 校验
  const required = REQUIRED_FIELDS[msg.type as string];
  if (required) {
    const payload = (msg.payload as Record<string, unknown>) ?? {};
    for (const field of required) {
      if (payload[field] === undefined || payload[field] === null) {
        errors.push(`Missing required payload field: ${field} for ${msg.type}`);
      }
    }
  }

  return { valid: errors.length === 0, errors };
}
