import { useMemo } from 'react';
import { t } from '@/locales';
import type { IMRoom } from '@/schemas/im';
import { getAgentColor } from './utils';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface MemberPanelProps {
  room: IMRoom | null;
  onlineUsers: Set<string>;
  clientId: string;
  onSelectDM?: (agentId: string) => void;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Extract username from "username@host" format */
function extractUsername(member: string): string {
  return member.split('@')[0];
}

/** Check if a member looks like an agent: "username/workspaceName" */
function isAgentMember(member: string): boolean {
  // Agent IDs use "username/workspaceName" — they contain a slash but no @
  return member.includes('/') && !member.includes('@');
}

/** Get the owner of an agent from "username/workspaceName" */
function getAgentOwner(agentId: string): string {
  return agentId.split('/')[0];
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function MemberPanel({ room, onlineUsers, clientId, onSelectDM }: MemberPanelProps) {
  // Derive people and agents from room.members
  const { people, agentsByOwner } = useMemo(() => {
    if (!room || !room.members.length) {
      return { people: [] as string[], agentsByOwner: new Map<string, string[]>() };
    }

    const peopleSet = new Set<string>();
    const agentsByOwnerMap = new Map<string, string[]>();

    for (const member of room.members) {
      if (isAgentMember(member)) {
        const owner = getAgentOwner(member);
        if (!agentsByOwnerMap.has(owner)) {
          agentsByOwnerMap.set(owner, []);
        }
        agentsByOwnerMap.get(owner)!.push(member);
      } else {
        peopleSet.add(member);
      }
    }

    return { people: [...peopleSet], agentsByOwner: agentsByOwnerMap };
  }, [room]);

  // Don't render anything if no room selected
  if (!room) return null;

  const currentUsername = clientId ? extractUsername(clientId) : '';

  return (
    <div className="w-[240px] bg-[#111118] border-l border-[#2a2a38] flex flex-col flex-shrink-0">
      {/* Header */}
      <div className="px-4 py-3.5 border-b border-[#2a2a38] text-[13px] font-semibold text-[#8888a0]">
        {t('im.memberPanel.title')}
      </div>

      {/* Scrollable list */}
      <div className="flex-1 overflow-y-auto py-2">
        {/* ---- People section ---- */}
        <div className="px-4 pt-2 pb-1 text-[11px] text-[#555568]">
          {t('im.memberPanel.people')}
        </div>
        {people.map((member) => {
          const username = extractUsername(member);
          const isOnline = onlineUsers.has(member);
          return (
            <div
              key={member}
              className="flex items-center gap-2.5 px-4 py-1.5 hover:bg-[#22222e] transition-colors cursor-default"
            >
              <div className="relative flex-shrink-0">
                <div className="w-8 h-8 rounded-lg bg-[#2a2a38] flex items-center justify-center text-[11px] font-bold">
                  {username.charAt(0).toUpperCase()}
                </div>
                {/* Online/offline dot */}
                <div
                  className={`absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full border-2 border-[#111118] ${
                    isOnline ? 'bg-[#00b894]' : 'bg-[#636e72]'
                  }`}
                />
              </div>
              <div className="min-w-0">
                <div className={`text-[13px] font-medium truncate ${isOnline ? 'text-[#00b894]' : 'text-[#e8e8ed]'}`}>
                  {username}
                </div>
                <div className="text-[11px] text-[#555568] font-mono truncate">
                  {member}
                </div>
              </div>
            </div>
          );
        })}

        {/* ---- Agent sections (grouped by owner) ---- */}
        {[...agentsByOwner.entries()].map(([owner, agents]) => (
          <div key={owner}>
            {/* Group label */}
            <div className="px-4 pt-3 pb-1 text-[11px] text-[#555568]">
              {t('im.memberPanel.agentsOf', { name: owner })}
            </div>
            {agents.map((agentId) => {
              const color = getAgentColor(agentId);
              const isOwn = owner === currentUsername;
              return (
                <div
                  key={agentId}
                  onClick={isOwn && onSelectDM ? () => onSelectDM(agentId) : undefined}
                  className={`flex items-center gap-2.5 px-4 py-1.5 transition-colors ${
                    isOwn
                      ? 'cursor-pointer hover:bg-[#22222e]'
                      : 'cursor-default opacity-60'
                  }`}
                >
                  <div
                    className="w-8 h-8 rounded-lg flex items-center justify-center text-sm flex-shrink-0"
                    style={{
                      backgroundColor: `${color}1f`,
                      border: `1.5px dashed ${isOwn ? color : '#555568'}`,
                    }}
                  >
                    🤖
                  </div>
                  <div className="min-w-0">
                    <div
                      className="text-[13px] font-medium truncate font-mono"
                      style={{ color: isOwn ? color : '#8888a0' }}
                    >
                      {agentId}
                    </div>
                    {!isOwn && (
                      <div className="text-[11px] text-[#555568]">
                        {t('im.offline')}
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
