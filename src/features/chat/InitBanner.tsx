import { useEffect, useState } from 'react';
import { useChatStore } from '../../stores/chat';
import { t } from '@/locales';


/** All card types auto-dismiss after 5s — init is background work, don't bother the user */
const AUTO_DISMISS_MS = 5000;

export function InitBanner() {
  const initCard = useChatStore(s => s.initCard);
  const [dismissed, setDismissed] = useState(false);

  // Reset dismissed when card changes
  useEffect(() => {
    setDismissed(false);
  }, [initCard]);

  // Auto-dismiss all cards after 5s
  useEffect(() => {
    if (!initCard) return;
    const timer = setTimeout(() => setDismissed(true), AUTO_DISMISS_MS);
    return () => clearTimeout(timer);
  }, [initCard]);

  if (!initCard || dismissed || initCard.type === 'already') return null;

  const borderColor = {
    initializing: 'border-blue-300 bg-blue-50',
    complete: 'border-green-300 bg-green-50',
    error: 'border-red-300 bg-red-50',
  }[initCard.type];

  return (
    <div className={`mx-4 mt-2 mb-1 border rounded-lg p-3 flex items-start gap-3 ${borderColor}`}>
      {initCard.type === 'initializing' && (
        <>
          <span className="text-lg animate-spin">&#9676;</span>
          <div className="flex-1">
            <div className="font-medium text-sm">{t('chat.init.title')}</div>
            <div className="text-xs text-gray-400 mt-1">
              {initCard.phase === 'scanning' && t('chat.init.scanning')}
              {initCard.phase === 'matching' && t('chat.init.matching')}
              {initCard.phase === 'injecting' && t('chat.init.injecting')}
              {!initCard.phase && t('chat.init.default')}
            </div>
          </div>
        </>
      )}

      {initCard.type === 'complete' && (
        <>
          <span className="text-lg">&#10003;</span>
          <div className="flex-1">
            <div className="font-medium text-sm">{t('chat.init.complete')}</div>
            {initCard.projectSummary && (
              <div className="text-xs mt-1">
                <span className="font-medium">{initCard.projectSummary}</span>
                {initCard.techStack && initCard.techStack.length > 0 && (
                  <span className="text-gray-500 ml-2">({initCard.techStack.join(', ')})</span>
                )}
              </div>
            )}
            {initCard.injectedSkills && initCard.injectedSkills.length > 0 && (
              <div className="text-xs text-gray-500 mt-1">
                {t('chat.init.loadedSkills')} {initCard.injectedSkills.length} {t('chat.init.skills')}
                {' ' + initCard.injectedSkills.map(s => s.name).join(', ')}
              </div>
            )}
          </div>
        </>
      )}

      {initCard.type === 'error' && (
        <>
          <span className="text-lg">&#9888;</span>
          <div className="flex-1">
            <div className="font-medium text-sm">{t('chat.init.failed')}</div>
            <div className="text-xs text-gray-500">{initCard.error}</div>
          </div>
        </>
      )}
    </div>
  );
}
