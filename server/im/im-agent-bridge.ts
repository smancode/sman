import crypto from 'crypto';
import { IMStore, type IMMessage } from './im-store.js';
import { createLogger, type Logger } from '../utils/logger.js';

/**
 * IMAgentBridge — activates Claude Agent sessions when @mentioned in IM group chat.
 *
 * Architecture: fully decoupled from ClaudeSessionManager via injected callbacks.
 * The bridge only knows about IMStore and the callback signatures.
 *
 * Lifecycle per @mention:
 *   1. handleMention() filters mentioned agents to those owned by this client
 *   2. For each owned agent, activateAgent() runs:
 *      a. Insert agent_output message (status=running) → broadcast
 *      b. Create/reuse Claude session via injected callback
 *      c. Stream response via injected callback, broadcasting deltas
 *      d. On completion: update message (status=completed, final content) → broadcast
 *      e. On error: update message (status=failed, error message) → broadcast
 */
export class IMAgentBridge {
  private log: Logger;

  constructor(
    private imStore: IMStore,
    private broadcastToRoom: (roomId: string, msg: any) => void,
    private clientId: string,
    /** Get workspace path for a locally-open agent display ID. Returns undefined if agent not found locally. */
    private getWorkspaceForAgent: (agentDisplayId: string) => string | undefined,
    /** Create a new or reuse an existing Claude session for the given workspace. */
    private createOrGetSession: (workspace: string) => Promise<{ sessionId: string; isNew: boolean }>,
    /** Stream a message through the Claude session. Calls onDelta for each text chunk. Returns full response text. */
    private streamSessionMessage: (sessionId: string, content: string, onDelta: (delta: string) => void) => Promise<string>,
  ) {
    this.log = createLogger('IMAgentBridge');
  }

  /**
   * Handle a message that contains @mentions.
   * Filters to only agents owned by this client, then activates each one.
   * Errors are caught per-agent so one failure doesn't block others.
   */
  async handleMention(message: IMMessage): Promise<void> {
    const mentionedAgents = message.mentionedAgents;
    if (!mentionedAgents || mentionedAgents.length === 0) return;

    // Activate each mentioned agent that belongs to this client.
    // Run in parallel — agents are independent.
    const activations = mentionedAgents.map(async (agentDisplayId) => {
      try {
        await this.activateAgent(agentDisplayId, message);
      } catch (err) {
        this.log.error(`Failed to activate agent ${agentDisplayId}`, { error: String(err) });
      }
    });

    await Promise.allSettled(activations);
  }

  /**
   * Activate a single agent for a mentioned message.
   * Creates an agent_output message, streams the response, and updates status.
   */
  private async activateAgent(agentDisplayId: string, message: IMMessage): Promise<void> {
    const workspace = this.getWorkspaceForAgent(agentDisplayId);
    if (!workspace) {
      this.log.info(`Agent ${agentDisplayId} not found locally, skipping`);
      return;
    }

    // 1. Create agent_output message with status=running
    const agentMsgId = crypto.randomUUID();
    const seq = this.imStore.getNextSeq(message.roomId);
    const agentMsg: IMMessage = {
      id: agentMsgId,
      roomId: message.roomId,
      sender: `${this.clientId}/${agentDisplayId}`,
      content: '',
      mentionedAgents: [],
      type: 'agent_output',
      status: 'running',
      timestamp: Date.now(),
      seq,
    };
    this.imStore.insertMessage(agentMsg);
    this.broadcastToRoom(message.roomId, { type: 'im.message', data: agentMsg });

    try {
      // 2. Create or get Claude session for this workspace
      const { sessionId, isNew } = await this.createOrGetSession(workspace);
      this.log.info(`Agent ${agentDisplayId}: ${isNew ? 'created' : 'reusing'} session ${sessionId}`);

      // 3. Build prompt from message content + quote context
      let prompt = message.content;
      if (message.quoteId) {
        const quoted = this.imStore.getMessage(message.quoteId);
        if (quoted) {
          prompt = `[引用 ${quoted.sender} 的消息]\n${quoted.content}\n\n---\n${prompt}`;
        }
      }

      // 4. Stream response, broadcast deltas
      let fullContent = '';
      const finalContent = await this.streamSessionMessage(
        sessionId,
        prompt,
        (delta: string) => {
          fullContent += delta;
          this.broadcastToRoom(message.roomId, {
            type: 'im.agent_delta',
            data: { messageId: agentMsgId, agentId: agentDisplayId, content: fullContent },
          });
        },
      );

      // 5. Update message with final content + status=completed
      const resolvedContent = finalContent || fullContent;
      this.imStore.updateMessageContent(agentMsgId, resolvedContent);
      this.imStore.updateMessageStatus(agentMsgId, 'completed');
      this.broadcastToRoom(message.roomId, {
        type: 'im.message',
        data: { ...agentMsg, content: resolvedContent, status: 'completed' },
      });

      this.log.info(`Agent ${agentDisplayId} completed (${resolvedContent.length} chars)`);
    } catch (err) {
      // 6. On error, mark as failed
      const errorMsg = err instanceof Error ? err.message : String(err);
      this.imStore.updateMessageContent(agentMsgId, `执行失败: ${errorMsg}`);
      this.imStore.updateMessageStatus(agentMsgId, 'failed');
      this.broadcastToRoom(message.roomId, {
        type: 'im.message',
        data: { ...agentMsg, content: `执行失败: ${errorMsg}`, status: 'failed' },
      });

      this.log.error(`Agent ${agentDisplayId} failed`, { error: errorMsg });
    }
  }
}
