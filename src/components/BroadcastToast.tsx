import { useEffect } from 'react';
import { t } from '@/locales';
import { useBroadcastStore } from '@/stores/broadcast';

export function BroadcastToast() {
  const queue = useBroadcastStore(s => s.queue);
  const dismiss = useBroadcastStore(s => s.dismiss);
  const subscribe = useBroadcastStore(s => s.subscribe);

  useEffect(() => {
    const unsub = subscribe();
    return unsub;
  }, [subscribe]);

  const current = queue[0];
  if (!current) return null;

  return (
    <div className="fixed bottom-4 right-4 z-50 max-w-sm">
      <div className="bg-white dark:bg-gray-800 rounded-lg shadow-lg border border-gray-200 dark:border-gray-700 p-4">
        <h3 className="font-semibold text-sm">{current.title || t('hub.broadcast.title')}</h3>
        <p className="text-sm text-gray-600 dark:text-gray-300 mt-1">{current.body}</p>
        <button
          onClick={() => dismiss(current.id)}
          className="mt-2 text-xs text-blue-500 hover:text-blue-600"
        >
          {t('hub.broadcast.dismiss')}
        </button>
      </div>
    </div>
  );
}
