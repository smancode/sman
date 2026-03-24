import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from './utils/logger.js';
import type { SessionStore, Message } from './session-store.js';
import type { SkillsRegistry } from './skills-registry.js';
import type { ProfileManager } from './profile-manager.js';

export interface ActiveSession {
  id: string;
  systemId: string;
  workspace: string;
  createdAt: string;
  lastActiveAt: string;
}

type WsSend = (data: string) => void;

export class ClaudeSessionManager {
  private sessions = new Map<string, ActiveSession>();
  private abortControllers = new Map<string, AbortController>();
  private log: Logger;

  constructor(
    private store: SessionStore,
    private skillsRegistry: SkillsRegistry,
    private profileManager: ProfileManager,
  ) {
    this.log = createLogger('ClaudeSessionManager');
  }

  createSession(systemId: string): string {
    const profile = this.profileManager.getProfile(systemId);
    if (!profile) {
      throw new Error(`Profile not found: ${systemId}`);
    }

    const id = uuidv4();
    this.store.createSession({
      id,
      systemId,
      workspace: profile.workspace,
    });

    const session: ActiveSession = {
      id,
      systemId,
      workspace: profile.workspace,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
    };

    this.sessions.set(id, session);
    this.log.info(`Session created: ${id} for system ${systemId}`);
    return id;
  }

  async sendMessage(sessionId: string, content: string, wsSend: WsSend): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    this.store.addMessage(sessionId, { role: 'user', content });

    const profile = this.profileManager.getProfile(session.systemId);
    if (!profile) throw new Error(`Profile not found: ${session.systemId}`);

    const skillDirs = this.skillsRegistry.getSkillDirs(profile.skills);

    // TODO: Replace with actual Claude Agent SDK call in Chunk 2
    const response = `[Mock] received: ${content}. Skills: ${skillDirs.join(', ') || 'none'}`;

    wsSend(JSON.stringify({
      type: 'chat.delta',
      sessionId,
      content: response,
    }));

    this.store.addMessage(sessionId, { role: 'assistant', content: response });

    wsSend(JSON.stringify({
      type: 'chat.done',
      sessionId,
      cost: 0,
    }));

    this.log.info(`Message processed for session ${sessionId}`);
  }

  abort(sessionId: string): void {
    const controller = this.abortControllers.get(sessionId);
    if (controller) {
      controller.abort();
      this.abortControllers.delete(sessionId);
      this.log.info(`Session aborted: ${sessionId}`);
    }
  }

  listSessions(systemId?: string): ActiveSession[] {
    const allSessions = this.store.listSessions(systemId);
    return allSessions.map(s => {
      let active = this.sessions.get(s.id);
      if (!active) {
        active = {
          id: s.id,
          systemId: s.systemId,
          workspace: s.workspace,
          createdAt: s.createdAt,
          lastActiveAt: s.lastActiveAt,
        };
        this.sessions.set(s.id, active);
      }
      return active;
    });
  }

  getHistory(sessionId: string): Message[] {
    return this.store.getMessages(sessionId);
  }

  close(): void {
    for (const controller of this.abortControllers.values()) {
      controller.abort();
    }
    this.abortControllers.clear();
    this.sessions.clear();
  }
}
