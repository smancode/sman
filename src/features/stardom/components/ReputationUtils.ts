// src/features/bazaar/components/ReputationUtils.ts
// 声望等级系统

export interface ReputationLevel {
  level: number;
  title: string;
  minRep: number;
  color: string;
  glow: string;
  icon: string;
}

export const REPUTATION_LEVELS: ReputationLevel[] = [
  { level: 1, title: '新晋节点', minRep: 0, color: 'var(--bz-text-dim)', glow: 'transparent', icon: '◉' },
  { level: 2, title: '活跃终端', minRep: 10, color: 'var(--bz-green)', glow: 'var(--bz-green-glow)', icon: '◈' },
  { level: 3, title: '协作枢纽', minRep: 50, color: 'var(--bz-cyan)', glow: 'var(--bz-cyan-glow)', icon: '◆' },
  { level: 4, title: '网络核心', minRep: 150, color: 'var(--bz-purple)', glow: 'var(--bz-purple)', icon: '⬡' },
  { level: 5, title: '战略中枢', minRep: 500, color: 'var(--bz-amber)', glow: 'var(--bz-amber-glow)', icon: '★' },
];

export function getReputationLevel(reputation: number): ReputationLevel {
  for (let i = REPUTATION_LEVELS.length - 1; i >= 0; i--) {
    if (reputation >= REPUTATION_LEVELS[i].minRep) return REPUTATION_LEVELS[i];
  }
  return REPUTATION_LEVELS[0];
}

export function getNextLevel(reputation: number): ReputationLevel | null {
  const current = getReputationLevel(reputation);
  const nextIdx = REPUTATION_LEVELS.findIndex(l => l.level === current.level) + 1;
  return nextIdx < REPUTATION_LEVELS.length ? REPUTATION_LEVELS[nextIdx] : null;
}

export function getReputationProgress(reputation: number): number {
  const current = getReputationLevel(reputation);
  const next = getNextLevel(reputation);
  if (!next) return 100;
  const range = next.minRep - current.minRep;
  const progress = reputation - current.minRep;
  return Math.round((progress / range) * 100);
}
