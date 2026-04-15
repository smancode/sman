import React, { useEffect, useState } from 'react';
import { useChatStore } from '../../stores/chat';

export function InitBanner() {
  const initCard = useChatStore(s => s.initCard);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    setDismissed(false);
  }, [initCard]);

  // Auto-dismiss "already initialized" after 5s
  useEffect(() => {
    if (initCard?.type === 'already') {
      const timer = setTimeout(() => setDismissed(true), 5000);
      return () => clearTimeout(timer);
    }
  }, [initCard?.type]);

  if (!initCard || dismissed) return null;

  const borderColor = {
    initializing: 'border-blue-300 bg-blue-50',
    complete: 'border-green-300 bg-green-50',
    already: 'border-gray-300 bg-gray-50',
    error: 'border-red-300 bg-red-50',
  }[initCard.type];

  return (
    <div className={`mx-4 mt-2 mb-1 border rounded-lg p-3 flex items-start gap-3 ${borderColor}`}>
      {initCard.type === 'initializing' && (
        <>
          <span className="text-lg animate-spin">&#9676;</span>
          <div className="flex-1">
            <div className="font-medium text-sm">项目初始化中</div>
            <div className="text-xs text-gray-500 mt-0.5">{initCard.workspace}</div>
            <div className="text-xs text-gray-400 mt-1">
              {initCard.phase === 'scanning' && '正在扫描项目结构...'}
              {initCard.phase === 'matching' && '正在分析并匹配最佳能力...'}
              {initCard.phase === 'injecting' && '正在注入能力...'}
              {!initCard.phase && '正在初始化...'}
            </div>
          </div>
        </>
      )}

      {initCard.type === 'complete' && (
        <>
          <span className="text-lg">&#10003;</span>
          <div className="flex-1">
            <div className="font-medium text-sm">项目初始化完成</div>
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
                已加载 {initCard.injectedSkills.length} 个能力:
                {' ' + initCard.injectedSkills.map(s => s.name).join(', ')}
              </div>
            )}
          </div>
        </>
      )}

      {initCard.type === 'already' && (
        <>
          <span className="text-lg">&#128193;</span>
          <div className="flex-1">
            <div className="text-sm">
              <span className="font-medium">{initCard.projectSummary}</span>
              {initCard.techStack && initCard.techStack.length > 0 && (
                <span className="text-gray-500 ml-2">({initCard.techStack.join(', ')})</span>
              )}
            </div>
            {initCard.injectedSkills && (
              <div className="text-xs text-gray-400 mt-0.5">
                已加载 {initCard.injectedSkills.length} 个能力
                {initCard.initializedAt && ` · 初始化于 ${new Date(initCard.initializedAt).toLocaleDateString()}`}
              </div>
            )}
          </div>
        </>
      )}

      {initCard.type === 'error' && (
        <>
          <span className="text-lg">&#9888;</span>
          <div className="flex-1">
            <div className="font-medium text-sm">初始化失败</div>
            <div className="text-xs text-gray-500">{initCard.error}</div>
          </div>
        </>
      )}

      <button
        className="text-gray-400 hover:text-gray-600 text-sm p-1"
        onClick={() => setDismissed(true)}
      >
        &#10005;
      </button>
    </div>
  );
}
