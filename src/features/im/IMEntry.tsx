import { useLocale, t } from '@/locales';
import { useIMStore } from '@/stores/im';
import { GroupChatList } from './GroupChatList';
import { SessionList } from './SessionList';

// ---------------------------------------------------------------------------
// Tab type
// ---------------------------------------------------------------------------

type TabType = 'sessions' | 'groups';

// ---------------------------------------------------------------------------
// IMEntry — three-column layout: left panel + center chat + right members
// ---------------------------------------------------------------------------

export default function IMEntry() {
  useLocale();

  const { activeTab, selectedRoomId, selectRoom, setActiveTab } = useIMStore();

  return (
    <div className="flex h-full w-full overflow-hidden">
      {/* ===== LEFT PANEL: 280px ===== */}
      <div className="w-[280px] flex-shrink-0 bg-[#111118] border-r border-[#2a2a38] flex flex-col">
        {/* Tab switcher */}
        <div className="flex border-b border-[#2a2a38]">
          <button
            onClick={() => setActiveTab('sessions')}
            className={`flex-1 py-2.5 text-center text-[13px] cursor-pointer border-b-2 transition-colors ${
              activeTab === 'sessions'
                ? 'text-[#a29bfe] border-b-[#6c5ce7]'
                : 'text-[#555568] border-b-transparent hover:text-[#8888a0]'
            }`}
          >
            {t('im.sessions')}
          </button>
          <button
            onClick={() => setActiveTab('groups')}
            className={`flex-1 py-2.5 text-center text-[13px] cursor-pointer border-b-2 transition-colors ${
              activeTab === 'groups'
                ? 'text-[#a29bfe] border-b-[#6c5ce7]'
                : 'text-[#555568] border-b-transparent hover:text-[#8888a0]'
            }`}
          >
            {t('im.groups')}
          </button>
        </div>

        {/* List content */}
        <div className="flex-1 overflow-hidden">
          {activeTab === 'groups' ? (
            <GroupChatList
              selectedRoomId={selectedRoomId}
              onSelect={selectRoom}
            />
          ) : (
            <SessionList
              selectedRoomId={selectedRoomId}
              onSelect={selectRoom}
            />
          )}
        </div>
      </div>

      {/* ===== CENTER: Chat Window placeholder ===== */}
      <div className="flex-1 flex flex-col bg-[#0a0a0f] min-w-0">
        {!selectedRoomId ? (
          <div className="flex-1 flex items-center justify-center">
            <p className="text-[#555568] text-sm">
              {t('im.empty.selectHint')}
            </p>
          </div>
        ) : (
          <div className="flex-1 flex items-center justify-center">
            {/* ChatWindow will be rendered here in Task 9 */}
            <p className="text-[#555568] text-sm">
              ChatWindow placeholder — room: {selectedRoomId}
            </p>
          </div>
        )}
      </div>

      {/* ===== RIGHT: Member Panel placeholder ===== */}
      {/* MemberPanel will be conditionally rendered here in Task 12 */}
    </div>
  );
}
