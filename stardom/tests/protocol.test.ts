// stardom/tests/protocol.test.ts
import { describe, it, expect } from 'vitest';
import { validateMessage, isValidMessageType } from '../src/protocol.js';

describe('validateMessage', () => {
  it('should validate a well-formed agent.register message', () => {
    const msg = {
      id: 'msg-001',
      type: 'agent.register',
      payload: {
        agentId: 'agent-abc',
        username: 'zhangsan',
        hostname: 'VDI-ZHANGSAN-01',
        name: '张三',
        projects: [],
        privateCapabilities: [],
      },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('should reject message without id', () => {
    const msg = {
      type: 'agent.register',
      payload: { agentId: 'abc' },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain('Missing required field: id');
  });

  it('should reject message without type', () => {
    const msg = {
      id: 'msg-002',
      payload: {},
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain('Missing required field: type');
  });

  it('should reject unknown message type', () => {
    const msg = {
      id: 'msg-003',
      type: 'unknown.type',
      payload: {},
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors[0]).toMatch(/Unknown message type/);
  });

  it('should reject agent.register with missing required fields', () => {
    const msg = {
      id: 'msg-004',
      type: 'agent.register',
      payload: {
        agentId: 'agent-abc',
        // missing username, hostname, name
      },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors.length).toBeGreaterThan(0);
  });

  it('should accept agent.heartbeat message', () => {
    const msg = {
      id: 'msg-005',
      type: 'agent.heartbeat',
      payload: {
        agentId: 'agent-abc',
        status: 'idle',
        activeTaskCount: 0,
      },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(true);
  });

  it('should validate task.sync message', () => {
    const msg = {
      id: 'msg-010',
      type: 'task.sync',
      payload: { taskId: 'task-123' },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(true);
  });

  it('should reject task.sync without taskId', () => {
    const msg = {
      id: 'msg-011',
      type: 'task.sync',
      payload: {},
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('taskId'))).toBe(true);
  });

  it('should accept ack message', () => {
    const msg = {
      id: 'msg-006',
      type: 'ack',
      payload: { id: 'msg-001' },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(true);
  });
});

describe('isValidMessageType', () => {
  it('should return true for known types', () => {
    expect(isValidMessageType('agent.register')).toBe(true);
    expect(isValidMessageType('task.create')).toBe(true);
    expect(isValidMessageType('ack')).toBe(true);
  });

  it('should return false for unknown types', () => {
    expect(isValidMessageType('foo.bar')).toBe(false);
    expect(isValidMessageType('')).toBe(false);
  });
});
