// src/features/stardom/TaskNotify.tsx
import { useEffect, useState } from 'react';
import { useStardomStore } from '@/stores/stardom';
import { Button } from '@/components/ui/button';
import { Bell, Check, X, Clock } from 'lucide-react';
import { t } from '@/locales';

function CountdownTimer({ endsAt }: { endsAt: string }) {
  const [remaining, setRemaining] = useState(() => {
    const diff = new Date(endsAt).getTime() - Date.now();
    return Math.max(0, Math.ceil(diff / 1000));
  });

  useEffect(() => {
    const interval = setInterval(() => {
      const diff = new Date(endsAt).getTime() - Date.now();
      const seconds = Math.max(0, Math.ceil(diff / 1000));
      setRemaining(seconds);
      if (seconds <= 0) {
        clearInterval(interval);
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [endsAt]);

  return (
    <span className="flex items-center gap-1 text-xs text-amber-600">
      <Clock className="h-3 w-3" />
      {remaining}s {t("stardom.notify.autoAccept")}
    </span>
  );
}

export function TaskNotify() {
  const { notifications, acceptTask, rejectTask } = useStardomStore();

  // 只显示 notify 和 manual 模式的通知
  const visibleNotifications = notifications.filter(
    (n) => n.mode === 'notify' || n.mode === 'manual'
  );

  if (visibleNotifications.length === 0) return null;

  return (
    <div className="absolute top-12 left-0 right-0 z-40 flex flex-col items-center gap-2 px-4 pointer-events-none">
      {visibleNotifications.map((notification) => (
        <div
          key={notification.notificationId}
          className="pointer-events-auto w-full max-w-lg bg-background border border-primary/30 rounded-lg shadow-lg p-3 animate-in slide-in-from-top-2"
        >
          <div className="flex items-start gap-3">
            <div className="shrink-0 mt-0.5">
              <Bell className="h-5 w-5 text-primary" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-sm font-medium">{t("stardom.notify.collabRequest")}</span>
                {notification.mode === 'notify' && (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-700">
                    {t("stardom.settings.modeNotify")}
                  </span>
                )}
                {notification.mode === 'manual' && (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-blue-100 text-blue-700">
                    {t("stardom.notify.needConfirm")}
                  </span>
                )}
              </div>
              <p className="text-sm text-muted-foreground mb-1">
                <span className="font-medium text-foreground">{notification.from}</span>
                {' {t("stardom.notify.requestHelp")}'}
              </p>
              <p className="text-sm line-clamp-2">{notification.question}</p>

              {/* 倒计时 / 按钮 */}
              <div className="flex items-center gap-2 mt-2">
                {notification.mode === 'notify' && notification.countdownEndsAt && (
                  <CountdownTimer endsAt={notification.countdownEndsAt} />
                )}
                <div className="flex-1" />
                <Button
                  size="sm"
                  className="h-7 text-xs"
                  onClick={() => acceptTask(notification.taskId)}
                >
                  <Check className="h-3 w-3 mr-1" />
                  {t("stardom.notify.accept")}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs"
                  onClick={() => rejectTask(notification.taskId)}
                >
                  <X className="h-3 w-3 mr-1" />
                  {t("stardom.notify.reject")}
                </Button>
              </div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
