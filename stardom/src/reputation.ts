// stardom/src/reputation.ts
import { createLogger, type Logger } from './utils/logger.js';
import type { AgentStore } from './agent-store.js';

const DAILY_CAP_PER_PAIR = 3;

export interface ReputationResult {
  helperDelta: number;
  requesterDelta: number;
}

export class ReputationEngine {
  private log: Logger;
  private store: AgentStore;

  constructor(store: AgentStore) {
    this.store = store;
    this.log = createLogger('ReputationEngine');
  }

  /**
   * 任务完成时计算声望变化
   *
   * 公式（简单版）：
   * - 协助方：基础分(1.0) + 评分加成(rating × 0.5)
   * - 请求方：0.3（鼓励提问）
   * - 防刷：同一对请求方-协助方每天最多计 3 次声望
   */
  onTaskComplete(
    taskId: string,
    requesterId: string,
    helperId: string,
    rating: number,
  ): ReputationResult {
    // 防刷检查
    const countToday = this.store.getReputationCountToday(helperId, requesterId);
    if (countToday >= DAILY_CAP_PER_PAIR) {
      this.log.info(`Daily reputation cap reached for pair ${requesterId}→${helperId}`);
      return { helperDelta: 0, requesterDelta: 0 };
    }

    // 协助方得分：基础分 + 评分加成
    const baseScore = 1.0;
    const qualityBonus = rating * 0.5;
    const helperDelta = baseScore + qualityBonus;

    // 请求方得分
    const requesterDelta = 0.3;

    // 更新声望
    this.store.updateReputation(helperId, helperDelta);
    this.store.updateReputation(requesterId, requesterDelta);

    // 记录日志
    this.store.logReputation(helperId, taskId, baseScore, 'base', requesterId);
    this.store.logReputation(helperId, taskId, qualityBonus, 'quality', requesterId);
    this.store.logReputation(requesterId, taskId, requesterDelta, 'question_bonus', helperId);

    this.log.info(`Reputation updated: helper=${helperId} +${helperDelta}, requester=${requesterId} +${requesterDelta}`);

    return { helperDelta, requesterDelta };
  }
}
