import { useState, useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { Minus, Square, X, Copy } from 'lucide-react';
import { useChatStore } from '@/stores/chat';
import { cn } from '@/lib/utils';

declare global {
  interface Window {
    sman?: {
      platform: string;
      windowMinimize: () => Promise<void>;
      windowMaximize: () => Promise<void>;
      windowClose: () => Promise<void>;
      windowIsMaximized: () => Promise<boolean>;
      onMaximizeChanged: (callback: (maximized: boolean) => void) => () => void;
    };
  }
}

export function Titlebar() {
  const [isMaximized, setIsMaximized] = useState(false);
  const isElectron = !!window.sman;
  const isWindows = window.sman?.platform === 'win32';
  const sessions = useChatStore((s) => s.sessions);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const currentSession = sessions.find((s) => s.key === currentSessionId);
  const workspace = currentSession?.workspace;
  const location = useLocation();
  const inChat = location.pathname === '/chat';

  useEffect(() => {
    if (!window.sman?.onMaximizeChanged) return;
    const unsubscribe = window.sman.onMaximizeChanged(setIsMaximized);
    window.sman.windowIsMaximized?.().then(setIsMaximized);
    return unsubscribe;
  }, []);

  if (!isElectron) return null;

  return (
    <div
      className="flex items-center h-6 select-none shrink-0 bg-transparent"
      style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
    >
      {/* Spacer - drag area */}
      <div className="flex-1" />

      {/* Workspace path - centered, only in chat */}
      {inChat && workspace && (
        <span className="text-[13px] font-mono text-muted-foreground/60 truncate max-w-[400px]" style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}>
          {workspace}
        </span>
      )}

      {/* Spacer - drag area */}
      <div className="flex-1" />

      {/* macOS: no custom buttons, use native traffic lights */}
      {/* Windows: custom min/max/close buttons */}
      {isWindows && (
        <div
          className="flex h-full"
          style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}
        >
          <button
            onClick={() => window.sman?.windowMinimize?.()}
            className={cn(
              'inline-flex items-center justify-center w-10 h-full',
              'text-foreground/60 hover:text-foreground hover:bg-foreground/10',
              'transition-colors duration-100',
            )}
          >
            <Minus className="w-3.5 h-3.5" />
          </button>

          <button
            onClick={() => window.sman?.windowMaximize?.()}
            className={cn(
              'inline-flex items-center justify-center w-10 h-full',
              'text-foreground/60 hover:text-foreground hover:bg-foreground/10',
              'transition-colors duration-100',
            )}
          >
            {isMaximized ? (
              <Copy className="w-3 h-3" />
            ) : (
              <Square className="w-3 h-3" />
            )}
          </button>

          <button
            onClick={() => window.sman?.windowClose?.()}
            className={cn(
              'inline-flex items-center justify-center w-10 h-full',
              'text-foreground/60 hover:text-white hover:bg-red-500',
              'transition-colors duration-100',
            )}
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}
    </div>
  );
}
