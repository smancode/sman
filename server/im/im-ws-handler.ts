import { WebSocket } from 'ws';
import { IMStore, IMMessage } from './im-store.js';
import { IMAgentBridge } from './im-agent-bridge.js';
import { getHubWsClient } from '../hub/index.js';
import { encryptIMMessage, decryptIMMessage } from './im-crypto.js';

export class IMWsHandler {
  private agentBridge?: IMAgentBridge;

  constructor(
    private imStore: IMStore,
    private broadcastToRoom: (roomId: string, msg: any, excludeWs?: WebSocket) => void,
    private sendToWs: (ws: WebSocket, msg: any) => void,
  ) {}

  /** Inject the Agent Bridge (called after initialization when deps are ready) */
  setAgentBridge(bridge: IMAgentBridge): void {
    this.agentBridge = bridge;
  }

  handleLocalMessage(msg: any, ws: WebSocket, clientInfo: { clientId: string }): void {
    switch (msg.type) {
      case 'im.send':      return this.handleSend(msg, ws, clientInfo);
      case 'im.history':   return this.handleHistory(msg, ws, clientInfo);
      case 'im.sync':      return this.handleSync(msg, ws);
      case 'im.typing':    return this.handleTyping(msg, ws, clientInfo);
      case 'im.whoami':    return this.sendToWs(ws, { type: 'im.whoami', data: { clientId: clientInfo.clientId } });
      case 'im.read':      return this.handleRead(msg, clientInfo);
      case 'im.unread':    return this.handleUnread(msg, ws, clientInfo);
      case 'im.search':    return this.handleSearch(msg, ws);
      default: break;
    }
  }

  handleHubMessage(msg: any): void {
    switch (msg.type) {
      case 'im.message':
        if (msg.data) {
          const decrypted = decryptIMMessage(msg.data);
          this.imStore.insertMessage(decrypted as any);
        }
        break;
    }
  }

  private handleSend(msg: any, ws: WebSocket, clientInfo: { clientId: string }): void {
    const { roomId, content, mentionedAgents, quoteId } = msg;
    if (!roomId || !content) {
      this.sendToWs(ws, { type: 'im.error', error: 'Missing roomId or content' });
      return;
    }

    const seq = this.imStore.getNextSeq(roomId);
    const imMsg: IMMessage = {
      id: crypto.randomUUID(),
      roomId,
      sender: clientInfo.clientId,
      content,
      mentionedAgents: mentionedAgents || [],
      quoteId,
      type: 'text',
      timestamp: Date.now(),
      seq,
    };

    this.imStore.insertMessage(imMsg);
    this.imStore.updateRoomLastMessage(roomId, content.slice(0, 100), imMsg.timestamp);

    // Broadcast to local WS clients
    this.broadcastToRoom(roomId, { type: 'im.message', data: imMsg });
    this.sendToWs(ws, { type: 'im.sent', data: imMsg });

    // Forward to Hub WS so other connected devices receive it (encrypted)
    this.sendToHub({ type: 'im.message', data: encryptIMMessage(imMsg as unknown as Record<string, unknown>) });

    // Fire-and-forget: activate mentioned agents (if any)
    if (imMsg.mentionedAgents.length > 0 && this.agentBridge) {
      this.agentBridge.handleMention(imMsg).catch(() => {
        // Errors are already handled per-agent inside handleMention
      });
    }
  }

  private handleHistory(msg: any, ws: WebSocket, clientInfo: { clientId: string }): void {
    const { roomId, before, limit = 50 } = msg;
    if (!roomId) {
      this.sendToWs(ws, { type: 'im.error', error: 'Missing roomId' });
      return;
    }
    const messages = before
      ? this.imStore.getMessagesBefore(roomId, before, limit)
      : this.imStore.getMessagesByRoom(roomId, { limit });
    this.sendToWs(ws, { type: 'im.history', data: { roomId, messages, clientId: clientInfo.clientId } });
  }

  private handleSync(msg: any, ws: WebSocket): void {
    const { roomId, afterTimestamp } = msg;
    if (!roomId || afterTimestamp == null) {
      this.sendToWs(ws, { type: 'im.error', error: 'Missing roomId or afterTimestamp' });
      return;
    }
    const messages = this.imStore.getMessagesByRoom(roomId, { before: undefined, limit: 500 })
      .filter(m => m.timestamp > afterTimestamp);
    this.sendToWs(ws, { type: 'im.sync', data: { roomId, messages } });
  }

  private handleTyping(msg: any, ws: WebSocket, clientInfo: { clientId: string }): void {
    const { roomId } = msg;
    if (!roomId) return;
    this.broadcastToRoom(roomId, { type: 'im.typing', data: { roomId, sender: clientInfo.clientId } }, ws);
  }

  private handleRead(msg: any, clientInfo: { clientId: string }): void {
    const { roomId, timestamp } = msg;
    if (!roomId || !timestamp) return;
    this.imStore.updateLastRead(roomId, clientInfo.clientId, timestamp);
  }

  private handleUnread(msg: any, ws: WebSocket, clientInfo: { clientId: string }): void {
    const unreadCounts: Record<string, number> = {};
    const counts = this.imStore.getAllUnreadCounts(clientInfo.clientId);
    counts.forEach((count, roomId) => { unreadCounts[roomId] = count; });
    this.sendToWs(ws, { type: 'im.unread', data: { counts: unreadCounts } });
  }

  private handleSearch(msg: any, ws: WebSocket): void {
    const { query } = msg;
    if (!query || typeof query !== 'string') {
      this.sendToWs(ws, { type: 'im.search', data: { rooms: [], messages: [] } });
      return;
    }
    const rooms = this.imStore.searchRooms(query);
    const messages = this.imStore.searchMessages(query);
    this.sendToWs(ws, { type: 'im.search', data: { rooms, messages } });
  }

  private sendToHub(msg: Record<string, unknown>): void {
    const hubWs = getHubWsClient();
    if (hubWs?.isConnected()) {
      hubWs.send(msg);
    }
  }
}
