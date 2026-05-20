import { EventEmitter } from 'events';

const achievementEmitter = new EventEmitter();

export type AchievementEventType =
  | 'session_created' | 'message_sent' | 'message_done'
  | 'cron_executed' | 'batch_item_completed' | 'batch_completed'
  | 'smartpath_run' | 'stardom_collab'
  | 'token_used' | 'workspace_added' | 'skill_used'
  | 'code_viewed' | 'git_operation'
  | 'error_occurred' | 'day_active'
  | 'bot_session_created' | 'bot_message_sent';

export interface AchievementEvent {
  type: AchievementEventType;
  data: Record<string, any>;
}

export function emitAchievementEvent(event: AchievementEvent): void {
  achievementEmitter.emit('achievement', event);
}

export function onAchievementEvent(handler: (event: AchievementEvent) => void): void {
  achievementEmitter.on('achievement', handler);
}

export function removeAllAchievementListeners(): void {
  achievementEmitter.removeAllListeners('achievement');
}
