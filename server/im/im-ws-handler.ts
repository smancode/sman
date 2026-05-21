import { WebSocket } from 'ws';
import { IMStore, IMMessage } from './im-store.js';
import { getHubWsClient } from '../hub/index.js';

export class IMWsHandler {
  constructor(
    private imStore: IMStore,
    private broadcastToRoom: (roomId: string, msg: any, excludeWs?: WebSocket) => void,
    private sendToWs: (ws: WebSocket, msg: any) => void,
  ) {}

  handleLocalMessage(msg: any, ws: WebSocket, clientInfo: { clientId: string }): void {
    switch (msg.type) {
      case 'im.send':      return this.handleSend(msg, ws, clientInfo);
      case 'im.history':   return this.handleHistory(msg, ws);
      case 'im.sync':      return this.handleSync(msg, ws);
      case 'im.typing':    return this.handleTyping(msg, ws, clientInfo);
      default: break;
    }
  }

  handleHubMessage(msg: any): void {
    switch (msg.type) {
      case 'im.message':
        if (msg.data) {
          this.imStore.insertMessage(msg.data);
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

    const imMsg: IMMessage = {
      id: crypto.randomUUID(),
      roomId,
      sender: clientInfo.clientId,
      content,
      mentionedAgents: mentionedAgents || [],
      quoteId,
      type: 'text',
      timestamp: Date.now(),
    };

    this.imStore.insertMessage(imMsg);
    this.imStore.updateRoomLastMessage(roomId, content.slice(0, 100), imMsg.timestamp);

    // Broadcast to local WS clients
    this.broadcastToRoom(roomId, { type: 'im.message', data: imMsg });
    this.sendToWs(ws, { type: 'im.sent', data: imMsg });

    // Forward to Hub WS so other connected devices receive it
    this.sendToHub({ type: 'im.message', data: imMsg });
  }

  private handleHistory(msg: any, ws: WebSocket): void {
    const { roomId, before, limit = 50 } = msg;
    if (!roomId) {
      this.sendToWs(ws, { type: 'im.error', error: 'Missing roomId' });
      return;
    }
    const messages = before
      ? this.imStore.getMessagesBefore(roomId, before, limit)
      : this.imStore.getMessagesByRoom(roomId, { limit });
    this.sendToWs(ws, { type: 'im.history', data: { roomId, messages } });
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

  private sendToHub(msg: Record<string, unknown>): void {
    const hubWs = getHubWsClient();
    if (hubWs?.isConnected()) {
      hubWs.send(msg);
    }
  }
}
