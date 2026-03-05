/**
 * Unit tests for message update logic
 * Tests the core logic that updates message content when tasks complete
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Message, Task } from '../types';

// Helper function that mirrors the logic in ChatWindow.svelte
function updateMessagesWithTaskResults(
  messages: Message[],
  tasks: Task[]
): { updated: boolean; messages: Message[] } {
  let updated = false;
  
  const updatedMessages = messages.map(msg => {
    if (!msg.taskId) return msg;
    if (msg.content !== 'Thinking...') return msg; // Already updated
    
    const task = tasks.find((t: Task) => t.id === msg.taskId);
    if (!task) return msg;
    
    // Update if task is completed with output
    if (task.status === 'completed' && task.output) {
      updated = true;
      return { ...msg, content: task.output };
    }
    
    // Update if task failed with error
    if (task.status === 'failed') {
      const errorMsg = task.error || task.output || 'Task failed';
      updated = true;
      return { ...msg, content: `Error: ${errorMsg}` };
    }
    
    return msg;
  });

  return { updated, messages: updatedMessages };
}

describe('Message Update Logic', () => {
  const createMessage = (id: string, content: string, taskId?: string): Message => ({
    id,
    role: 'assistant',
    content,
    timestamp: Date.now(),
    taskId
  });

  const createTask = (id: string, status: string, output?: string, error?: string): Task => ({
    id,
    projectId: 'proj-1',
    input: 'test',
    status: status as any,
    output,
    error,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  });

  describe('when task is completed', () => {
    it('should update Thinking... message with task output', () => {
      const taskId = 'task-123';
      const messages = [createMessage('msg-1', 'Thinking...', taskId)];
      const tasks = [createTask(taskId, 'completed', 'Hello! How can I help?')];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(true);
      expect(result.messages[0].content).toBe('Hello! How can I help?');
    });

    it('should not update message if task has no output', () => {
      const taskId = 'task-123';
      const messages = [createMessage('msg-1', 'Thinking...', taskId)];
      const tasks = [createTask(taskId, 'completed', undefined)];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(false);
      expect(result.messages[0].content).toBe('Thinking...');
    });

    it('should not update already updated messages', () => {
      const taskId = 'task-123';
      const messages = [createMessage('msg-1', 'Already updated content', taskId)];
      const tasks = [createTask(taskId, 'completed', 'New output')];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(false);
      expect(result.messages[0].content).toBe('Already updated content');
    });
  });

  describe('when task is failed', () => {
    it('should update Thinking... message with error', () => {
      const taskId = 'task-123';
      const messages = [createMessage('msg-1', 'Thinking...', taskId)];
      const tasks = [createTask(taskId, 'failed', undefined, 'Network error')];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(true);
      expect(result.messages[0].content).toBe('Error: Network error');
    });

    it('should use output as fallback if no error field', () => {
      const taskId = 'task-123';
      const messages = [createMessage('msg-1', 'Thinking...', taskId)];
      const tasks = [createTask(taskId, 'failed', 'Error in output', undefined)];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(true);
      expect(result.messages[0].content).toBe('Error: Error in output');
    });

    it('should use default message if no error or output', () => {
      const taskId = 'task-123';
      const messages = [createMessage('msg-1', 'Thinking...', taskId)];
      const tasks = [createTask(taskId, 'failed', undefined, undefined)];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(true);
      expect(result.messages[0].content).toBe('Error: Task failed');
    });
  });

  describe('when task is still running', () => {
    it('should not update message', () => {
      const taskId = 'task-123';
      const messages = [createMessage('msg-1', 'Thinking...', taskId)];
      const tasks = [createTask(taskId, 'running', undefined)];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(false);
      expect(result.messages[0].content).toBe('Thinking...');
    });
  });

  describe('multiple messages', () => {
    it('should update only the matching task message', () => {
      const messages = [
        createMessage('msg-1', 'Previous response', 'task-001'),
        createMessage('msg-2', 'Thinking...', 'task-002'),
        createMessage('msg-3', 'User question') // no taskId
      ];
      const tasks = [
        createTask('task-001', 'completed', 'Old output'),
        createTask('task-002', 'completed', 'New output')
      ];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(true);
      expect(result.messages[0].content).toBe('Previous response'); // unchanged
      expect(result.messages[1].content).toBe('New output'); // updated
      expect(result.messages[2].content).toBe('User question'); // unchanged
    });

    it('should handle multiple tasks completing at once', () => {
      const messages = [
        createMessage('msg-1', 'Thinking...', 'task-001'),
        createMessage('msg-2', 'Thinking...', 'task-002')
      ];
      const tasks = [
        createTask('task-001', 'completed', 'Response 1'),
        createTask('task-002', 'completed', 'Response 2')
      ];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(true);
      expect(result.messages[0].content).toBe('Response 1');
      expect(result.messages[1].content).toBe('Response 2');
    });
  });

  describe('edge cases', () => {
    it('should handle task not found', () => {
      const messages = [createMessage('msg-1', 'Thinking...', 'non-existent-task')];
      const tasks = [createTask('other-task', 'completed', 'Output')];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(false);
      expect(result.messages[0].content).toBe('Thinking...');
    });

    it('should handle empty messages array', () => {
      const messages: Message[] = [];
      const tasks = [createTask('task-1', 'completed', 'Output')];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(false);
      expect(result.messages).toEqual([]);
    });

    it('should handle empty tasks array', () => {
      const messages = [createMessage('msg-1', 'Thinking...', 'task-1')];
      const tasks: Task[] = [];

      const result = updateMessagesWithTaskResults(messages, tasks);

      expect(result.updated).toBe(false);
      expect(result.messages[0].content).toBe('Thinking...');
    });
  });
});

// Export for use in component tests
export { updateMessagesWithTaskResults };
