import { t } from '@/locales';
import { useRoomList } from '@/queries/use-im';
import type { IMRoom } from '@/schemas/im';
import { getAgentColor } from './utils';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface SessionListProps {
  selectedRoomId: string | null;
  onSelect: (roomId: string) => void;
}

// ---------------------------------------------------------------------------
// Helper: extract agent display name from room name
// ---------------------------------------------------------------------------

function getAgentDisplayName(room: IMRoom): string {
  // DM rooms store agent identifier in the name
  return room.name || room.members[0] || 'Agent';
}

// ---------------------------------------------------------------------------
// Helper: relative time
// ---------------------------------------------------------------------------

function formatRelativeTime(timestamp: number | undefined): string {
  if (!timestamp) return '';
  const now = Date.now();
  const diff = now - timestamp;
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return t('im.online');
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d`;
  return new Date(timestamp).toLocaleDateString();
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function SessionList({ selectedRoomId, onSelect }: SessionListProps) {
  const { data: rooms = [], isLoading } = useRoomList();

  // Filter to dm rooms (agent sessions)
  const sessions = rooms.filter((r: IMRoom) => r.type === 'dm');

  return (
    <div className="flex flex-col h-full">
      {/* Session list */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="px-4 py-8 text-center text-xs text-[#555568]">Loading...</div>
        )}

        {!isLoading && sessions.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-[#555568]">
            {t('im.empty.noSessions')}
          </div>
        )}

        {sessions.map((session: IMRoom) => {
          const isActive = selectedRoomId === session.id;
          const agentName = getAgentDisplayName(session);
          const color = getAgentColor(session.members[0] || session.id);

          return (
            <div
              key={session.id}
              onClick={() => onSelect(session.id)}
              className={`flex items-center gap-2.5 px-3 py-2.5 cursor-pointer transition-colors ${
                isActive ? 'bg-[rgba(108,92,231,0.12)]' : 'hover:bg-[#22222e]'
              }`}
            >
              {/* Agent avatar with colored border */}
              <div
                className="w-10 h-10 rounded-xl flex items-center justify-center text-xs font-semibold flex-shrink-0 border-2"
                style={{
                  borderColor: color,
                  backgroundColor: `${color}1a`,
                }}
              >
                {'🤖'}
              </div>

              {/* Info */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-0.5">
                  <span className="text-sm font-medium truncate" style={{ color }}>
                    {agentName}
                  </span>
                  <span className="text-[11px] text-[#555568] flex-shrink-0 ml-2">
                    {formatRelativeTime(session.lastMessageTime)}
                  </span>
                </div>
                <div className="text-xs text-[#8888a0] truncate">
                  {session.lastMessage || ''}
                </div>
              </div>
            </div>
          );
        })}

        {/* Bottom hint */}
        {!isLoading && sessions.length > 0 && (
          <div className="px-4 py-4 text-center">
            <div className="text-xs text-[#555568] mb-2">
              {t('im.session.autoCreateHint')}
            </div>
            <div className="text-[11px] text-[#555568] leading-relaxed">
              {t('im.session.dmFromCard')}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
