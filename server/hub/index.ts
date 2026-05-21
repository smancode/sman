import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import type { SessionStore } from '../session-store.js';
import type { SettingsManager } from '../settings-manager.js';
import type { BroadcastStore } from '../broadcast-store.js';
import type { ClaudeSessionManager } from '../claude-session.js';
import { HubClient } from './client.js';
import { HubWsClient } from './hub-ws-client.js';
import { EvaluationHandler } from './evaluation-handler.js';
import { TaskWorker } from './task-worker.js';
import { readInitMd } from './init-reader.js';
import { loadPsk } from './crypto.js';
import { createLogger } from '../utils/logger.js';
import { getClientId } from '../utils/network.js';
import { getServerBaseUrl, resolveServerBaseUrl, invalidateServerBaseUrlCache } from '../server-url.js';

/** Get the actual port the HTTP server is listening on. Set by server/index.ts after listen(). */
let _actualPort = 5880;
export function setActualPort(port: number): void { _actualPort = port; }
export function getActualPort(): number { return _actualPort; }

const log = createLogger('Hub');

let hubClient: HubClient | null = null;
let hubWsClient: HubWsClient | null = null;
let evaluationHandler: EvaluationHandler | null = null;
let taskWorker: TaskWorker | null = null;

function getVersion(): string {
  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  for (const rel of ['../../package.json', '../../../package.json', '../../../../package.json']) {
    try {
      const pkgPath = path.resolve(__dirname, rel);
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
      if (pkg.version) return pkg.version;
    } catch { /* continue */ }
  }
  return 'unknown';
}

function getPsk(): string {
  return loadPsk();
}

function isHubEnabled(sm: SettingsManager): boolean {
  const url = getServerBaseUrl(sm);
  if (url) return true;
  if (process.env.SMAN_HUB_URL) return true;
  return sm.getConfig().hub?.enabled ?? false;
}

function buildAgentId(clientId: string, workspace: string): string {
  const hash = crypto.createHash('sha256').update(`${clientId}:${workspace}`).digest('hex').slice(0, 12);
  const hostname = clientId.split('@')[0] || clientId;
  return `${hostname}:${hash}`;
}

interface ImModuleLike {
  getHandler: () => { handleHubMessage: (msg: any) => void } | null;
}

export async function initHub(
  settingsManager: SettingsManager,
  sessionStore: SessionStore,
  broadcastStore: BroadcastStore,
  sessionManager?: ClaudeSessionManager,
  imModule?: ImModuleLike,
): Promise<void> {
  // Resolve serverBaseUrl: read config → probe if missing
  const serverUrl = await resolveServerBaseUrl(settingsManager);
  const enabled = isHubEnabled(settingsManager);
  log.info(`initHub: enabled=${enabled}, serverUrl=${serverUrl || '(empty)'}`);

  if (!enabled || !serverUrl) {
    log.info(`Hub disabled (enabled=${enabled}, serverUrl='${serverUrl}')`);
    return;
  }

  // HTTP client for heartbeat/broadcast
  hubClient = new HubClient({
    getServerUrl: () => getServerBaseUrl(settingsManager),
    getEnabled: () => isHubEnabled(settingsManager),
    getVersion,
    getPort: () => getActualPort(),
    sessionStore,
    broadcastStore,
  });
  hubClient.start();

  // WebSocket client for task collaboration
  if (!sessionManager) {
    log.info('No sessionManager provided, skipping WS connection');
    return;
  }

  const psk = getPsk();
  if (!psk) {
    log.warn('No PSK key available, skipping WS connection');
    return;
  }

  let joinedRoomId: string | undefined;

  hubWsClient = new HubWsClient({
    getServerUrl: () => getServerBaseUrl(settingsManager),
    getPsk: () => getPsk(),
    getClientId: () => getClientId(),
    sessionStore,
    settingsManager,
    onMessage: (msg) => {
      // Track joined room
      if (msg.type === 'room.joined') {
        joinedRoomId = msg.roomId;
        registerAgentsForWorkspaces(sessionStore, hubWsClient!, joinedRoomId, getClientId());
      }
      if (msg.type === 'room.created') {
        const room = msg.room as { id: string } | undefined;
        if (room) {
          joinedRoomId = room.id;
          registerAgentsForWorkspaces(sessionStore, hubWsClient!, joinedRoomId, getClientId());
        }
      }
      // Auto-join rooms after receiving room list
      if (msg.type === 'room.list.update' && !joinedRoomId) {
        const rooms = msg.rooms as { id: string }[] | undefined;
        if (rooms && rooms.length > 0) {
          const clientId = getClientId();
          hubWsClient!.send({ type: 'room.join', roomId: rooms[0].id, clientId, displayName: clientId });
        }
      }
      // On auth success, register agents if we already have a room
      if (msg.type === 'auth.ok' && joinedRoomId) {
        registerAgentsForWorkspaces(sessionStore, hubWsClient!, joinedRoomId, getClientId());
      }

      // IM messages from Hub (broadcasted from other clients)
      if (msg.type?.startsWith('im.')) {
        const handler = imModule?.getHandler();
        if (handler) {
          handler.handleHubMessage(msg);
        }
        return;
      }

      evaluationHandler?.handleMessage(msg);
      taskWorker?.handleMessage(msg);
    },
  });

  evaluationHandler = new EvaluationHandler({
    hubWsClient,
    sessionManager,
    sessionStore,
    getAgentId: (workspace) => buildAgentId(getClientId(), workspace),
    getRoomId: () => joinedRoomId,
  });

  taskWorker = new TaskWorker({
    hubWsClient,
    sessionManager,
    getAgentId: (workspace) => buildAgentId(getClientId(), workspace),
  });

  hubWsClient.start();
  evaluationHandler.start();
  taskWorker.start();
}

function registerAgentsForWorkspaces(
  sessionStore: SessionStore,
  wsClient: HubWsClient,
  roomId: string | undefined,
  clientId: string,
): void {
  if (!roomId) return;
  const workspaces = sessionStore.getActiveWorkspaces();
  for (const workspace of workspaces) {
    const agentId = buildAgentId(clientId, workspace);
    const initInfo = readInitMd(workspace);
    wsClient.registerAgent(agentId, roomId, workspace, {
      skills: initInfo?.skills ?? [],
      techStack: initInfo?.techStack ?? [],
      projectType: initInfo?.projectType ?? '',
      summary: initInfo?.summary ?? '',
      description: initInfo?.description ?? '',
    });
  }
}

export function stopHub(): void {
  evaluationHandler?.stop();
  taskWorker?.stop();
  hubWsClient?.stop();
  hubClient?.stop();

  evaluationHandler = null;
  taskWorker = null;
  hubWsClient = null;
  hubClient = null;
}

export function getHubClient(): HubClient | null {
  return hubClient;
}

export function getHubWsClient(): HubWsClient | null {
  return hubWsClient;
}

export function getEvaluationHandler(): EvaluationHandler | null {
  return evaluationHandler;
}

export function getHubStatus(sm: SettingsManager): Record<string, unknown> {
  return {
    initialized: hubClient !== null,
    wsConnected: hubWsClient?.isConnected() ?? false,
    serverBaseUrl: getServerBaseUrl(sm) || '(not resolved)',
    SMAN_PSK: process.env.SMAN_PSK ? '(set, ' + process.env.SMAN_PSK.length + ' chars)' : '(not set, will use bundled hub.key)',
  };
}
