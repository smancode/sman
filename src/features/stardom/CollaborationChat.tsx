// src/features/stardom/CollaborationChat.tsx
import { useEffect, useRef } from 'react';
import { useStardomStore } from '@/stores/stardom';
import { Button } from '@/components/ui/button';
import { MessageSquare, X, User } from 'lucide-react';
import { t } from '@/locales';

export function CollaborationChat() {
  const { activeChat, connection, clearActiveChat, cancelTask } = useStardomStore();
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [activeChat?.messages.length]);

  if (!activeChat) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-3 p-4">
        <MessageSquare className="h-10 w-10 text-muted-foreground/40" />
        <p className="text-sm">{t("stardom.chat.clickToView")}</p>
        <p className="text-xs text-muted-foreground/60">{t("stardom.chat.selectTask")}</p>
      </div>
    );
  }

  const myAgentName = connection.agentName ?? '';
  const messages = activeChat.messages;

  return (
    <div className="flex flex-col h-full">
      {/* Header: task info */}
      <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/20">
        <div className="flex items-center gap-2 min-w-0">
          <MessageSquare className="h-4 w-4 text-primary shrink-0" />
          <span className="text-sm font-medium truncate">
            {t("stardom.chat.task")} {activeChat.taskId.slice(0, 8)}
          </span>
          <span className="text-xs text-muted-foreground">
            {messages.length} {t("stardom.chat.messages")}
          </span>
        </div>
        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            className="h-6 text-xs text-red-500 hover:text-red-600"
            onClick={() => cancelTask(activeChat.taskId)}
          >
            {t("stardom.chat.terminate")}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="h-6 w-6 p-0"
            onClick={clearActiveChat}
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {/* 消息列表 */}
      <div className="flex-1 overflow-y-auto p-3 space-y-3">
        {messages.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-2">
            <p className="text-sm">{t("stardom.chat.waiting")}</p>
            <p className="text-xs">{t("stardom.chat.realtimeHint")}</p>
          </div>
        ) : (
          messages.map((msg, idx) => {
            const isMine = msg.from === myAgentName;
            return (
              <div
                key={idx}
                className={`flex ${isMine ? 'justify-end' : 'justify-start'}`}
              >
                <div className="max-w-[80%]">
                  {/* 发送者名称 */}
                  <div className={`flex items-center gap-1 mb-0.5 ${isMine ? 'justify-end' : 'justify-start'}`}>
                    <span className="text-xs text-muted-foreground flex items-center gap-0.5">
                      {isMine && <User className="h-3 w-3" />}
                      {msg.from}
                      {!isMine && <User className="h-3 w-3" />}
                    </span>
                    <span className="text-xs text-muted-foreground/60">
                      {new Date(msg.timestamp).toLocaleTimeString('zh-CN', {
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                      })}
                    </span>
                  </div>
                  {/* 消息气泡 */}
                  <div
                    className={`rounded-lg px-3 py-2 text-sm ${
                      isMine
                        ? 'bg-primary text-primary-foreground'
                        : 'bg-muted text-foreground'
                    }`}
                  >
                    <p className="whitespace-pre-wrap break-words">{msg.text}</p>
                  </div>
                </div>
              </div>
            );
          })
        )}
        <div ref={messagesEndRef} />
      </div>
    </div>
  );
}
