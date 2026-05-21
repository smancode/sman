import Database from 'better-sqlite3';
import { IMStore } from './im-store.js';
import { IMWsHandler } from './im-ws-handler.js';
import { IMAgentBridge } from './im-agent-bridge.js';

export function initIM(db: Database.Database) {
  const imStore = new IMStore(db);

  let handler: IMWsHandler | null = null;

  function setBroadcastFn(
    fn: (roomId: string, msg: any, excludeWs?: any) => void,
    sendFn: (ws: any, msg: any) => void,
  ) {
    handler = new IMWsHandler(imStore, fn, sendFn);
  }

  /**
   * Create and wire up an IMAgentBridge.
   * The caller provides all Claude-session-related callbacks for loose coupling.
   * Returns the bridge instance (caller may also retrieve it via getHandler()).
   */
  function createAgentBridge(deps: {
    clientId: string;
    getWorkspaceForAgent: (agentDisplayId: string) => string | undefined;
    createOrGetSession: (workspace: string) => Promise<{ sessionId: string; isNew: boolean }>;
    streamSessionMessage: (sessionId: string, content: string, onDelta: (delta: string) => void) => Promise<string>;
  }): IMAgentBridge {
    const broadcastFn = handler
      ? (roomId: string, msg: any) => { handler!['broadcastToRoom'](roomId, msg); }
      : (_roomId: string, _msg: any) => {};

    const bridge = new IMAgentBridge(
      imStore,
      broadcastFn,
      deps.clientId,
      deps.getWorkspaceForAgent,
      deps.createOrGetSession,
      deps.streamSessionMessage,
    );
    if (handler) {
      handler.setAgentBridge(bridge);
    }
    return bridge;
  }

  return {
    imStore,
    getHandler: () => handler,
    setBroadcastFn,
    createAgentBridge,
  };
}

export { IMStore } from './im-store.js';
export { IMWsHandler } from './im-ws-handler.js';
export { IMAgentBridge } from './im-agent-bridge.js';
