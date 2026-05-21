// ---------------------------------------------------------------------------
// Shared IM utilities
// ---------------------------------------------------------------------------

export const AGENT_COLORS = ['#6c5ce7', '#00cec9', '#e17055', '#fdcb6e', '#00b894', '#ff7675'];

export function getAgentColor(agentId: string): string {
  let hash = 0;
  for (let i = 0; i < agentId.length; i++) {
    hash = agentId.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AGENT_COLORS[Math.abs(hash) % AGENT_COLORS.length];
}

export function getDisplayName(sender: string, allSenders: string[]): string {
  const username = sender.split('@')[0];
  const sameName = allSenders.filter(s => s.split('@')[0] === username);
  return sameName.length > 1 ? sender : username;
}
