import { useState, useEffect, useRef, useCallback } from 'react';
import { useLocation } from 'react-router-dom';
import { Minus, Square, X, Copy, GitBranch } from 'lucide-react';
import { useChatStore } from '@/stores/chat';
import { useGitStore } from '@/stores/git';
import { useCodeViewerStore } from '@/stores/code-viewer';
import { cn } from '@/lib/utils';

declare global {
  interface Window {
    sman?: {
      platform: string;
      needsRoundedCorners: boolean;
      windowMinimize: () => Promise<void>;
      windowMaximize: () => Promise<void>;
      windowClose: () => Promise<void>;
      windowIsMaximized: () => Promise<boolean>;
      onMaximizeChanged: (callback: (maximized: boolean) => void) => () => void;
      getGitBranch: (dirPath: string) => Promise<string | null>;
    };
  }
}

export function Titlebar() {
  const [isMaximized, setIsMaximized] = useState(false);
  const isElectron = !!window.sman;
  const isWindows = window.sman?.platform === 'win32';
  const needsRoundedCorners = window.sman?.needsRoundedCorners ?? false;
  const workspace = useChatStore((s) => {
    const session = s.sessions.find(ss => ss.key === s.currentSessionId);
    return session?.workspace;
  });
  const location = useLocation();
  const inChat = location.pathname === '/chat';

  // Git branch
  const [gitBranch, setGitBranch] = useState<string | null>(null);
  const workspaceRef = useRef<string | undefined>(undefined);
  useEffect(() => { workspaceRef.current = workspace; }, [workspace]);

  const fetchBranch = useCallback(() => {
    const ws = workspaceRef.current;
    if (!ws || !window.sman?.getGitBranch) { setGitBranch(null); return; }
    window.sman.getGitBranch(ws).then(setGitBranch);
  }, []);

  useEffect(() => { fetchBranch(); }, [workspace, fetchBranch]);
  useEffect(() => {
    const onFocus = () => fetchBranch();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [fetchBranch]);

  // Register as global git branch refresh target (triggered by ChatInput typing)
  useEffect(() => {
    const prev = (window as any).__sman_gitBranchRefresh;
    (window as any).__sman_gitBranchRefresh = fetchBranch;
    return () => { if ((window as any).__sman_gitBranchRefresh === fetchBranch) (window as any).__sman_gitBranchRefresh = prev; };
  }, [fetchBranch]);

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

      {/* Workspace path + git branch - centered, only in chat */}
      {inChat && workspace && (
        <span className="flex items-center gap-2 text-[13px] font-mono max-w-[500px] text-foreground/70 dark:text-foreground/80" style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}>
          <span
            className="truncate min-w-0 cursor-pointer hover:text-[hsl(var(--foreground))] transition-colors"
            onClick={() => {
              const sessionId = useChatStore.getState().currentSessionId;
              useCodeViewerStore.getState().openViewer(workspace, '', null, sessionId);
            }}
            title="点击打开代码浏览器"
          >
            {workspace}
          </span>
          {gitBranch && (
            <>
              <span className="text-foreground/30 shrink-0">·</span>
              <span
                className="flex items-center gap-1 shrink-0 px-1.5 py-0.5 rounded hover:bg-[hsl(var(--muted))] cursor-pointer transition-colors"
                onClick={() => useGitStore.getState().openPanel()}
                title="点击打开 Git 管理"
              >
                <GitBranch className="w-3 h-3" />
                <span>{gitBranch}</span>
              </span>
            </>
          )}
        </span>
      )}

      {/* Spacer - drag area */}
      <div className="flex-1" />

      {/* macOS: no custom buttons, use native traffic lights */}
      {/* Windows 11: native buttons from DWM */}
      {/* Windows 10 (frameless): custom min/max/close buttons */}
      {needsRoundedCorners && (
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
