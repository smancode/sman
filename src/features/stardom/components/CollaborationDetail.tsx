// src/features/stardom/components/CollaborationDetail.tsx
// 展开的协作对话详情面板

import { useStardomStore } from '@/stores/stardom';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import { X, AlertTriangle } from 'lucide-react';
import { useState, useEffect } from 'react';
import { t } from '@/locales';

interface CollaborationDetailProps {
  taskId: string;
  onClose: () => void;
}

export function CollaborationDetail({ taskId, onClose }: CollaborationDetailProps) {
  const { activeChat, getTaskChat, acceptTask, rejectTask, notifications } = useStardomStore();
  const messages = activeChat?.taskId === taskId ? activeChat.messages : getTaskChat(taskId);
  const notification = notifications.find((n) => n.taskId === taskId);

  // Countdown for notify mode
  const [remaining, setRemaining] = useState<number | null>(null);
  useEffect(() => {
    if (!notification?.countdownEndsAt) return;
    const end = new Date(notification.countdownEndsAt).getTime();
    const interval = setInterval(() => {
      const left = Math.max(0, end - Date.now());
      setRemaining(left);
      if (left <= 0) clearInterval(interval);
    }, 200);
    return () => clearInterval(interval);
  }, [notification?.countdownEndsAt]);

  return (
    <div className="border-t border-border bg-muted/20">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-border/50">
        <span className="text-sm font-medium">协作详情</span>
        <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={onClose}>
          <X className="h-3 w-3" />
        </Button>
      </div>

      {/* Messages */}
      <ScrollArea className="max-h-48 px-4 py-2">
        {messages.length === 0 ? (
          <p className="text-xs text-muted-foreground py-2">暂无对话消息</p>
        ) : (
          <div className="space-y-1.5">
            {messages.map((msg, i) => (
              <div key={i} className="text-sm">
                <span className="font-medium text-primary">{msg.from}:</span>{' '}
                <span className="text-muted-foreground">{msg.text}</span>
              </div>
            ))}
          </div>
        )}
      </ScrollArea>

      {/* Actions */}
      {notification && (
        <div className="flex items-center gap-2 px-4 py-2 border-t border-border/50">
          {remaining !== null && remaining > 0 && (
            <span className="text-xs text-yellow-500 flex items-center gap-1">
              <AlertTriangle className="h-3 w-3" />
              {Math.ceil(remaining / 1000)}s 后自动接受
            </span>
          )}
          <div className="flex-1" />
          <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => { rejectTask(taskId); onClose(); }}>
            拒绝
          </Button>
          <Button size="sm" className="h-7 text-xs" onClick={() => { acceptTask(taskId); onClose(); }}>
            接受
          </Button>
        </div>
      )}
    </div>
  );
}
