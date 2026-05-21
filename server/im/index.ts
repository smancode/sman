import Database from 'better-sqlite3';
import { IMStore } from './im-store.js';
import { IMWsHandler } from './im-ws-handler.js';

export function initIM(db: Database.Database) {
  const imStore = new IMStore(db);

  let handler: IMWsHandler | null = null;

  function setBroadcastFn(
    fn: (roomId: string, msg: any, excludeWs?: any) => void,
    sendFn: (ws: any, msg: any) => void,
  ) {
    handler = new IMWsHandler(imStore, fn, sendFn);
  }

  return {
    imStore,
    getHandler: () => handler,
    setBroadcastFn,
  };
}

export { IMStore } from './im-store.js';
export { IMWsHandler } from './im-ws-handler.js';
