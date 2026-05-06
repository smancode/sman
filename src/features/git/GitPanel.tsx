import React, { memo, useCallback, useEffect, useState } from 'react';
import {
  X, GitBranch as GitBranchIcon, FilePlus, FileMinus, FileEdit, FileSearch,
  Loader2, Send, Sparkles, ChevronDown, ChevronRight, ArrowUp, ArrowDown,
  RefreshCw, Settings2, Check, Upload, Download, Search, Square, CheckSquare,
} from 'lucide-react';
import { useGitStore, applyTemplate, type GitFileStatus, type GitDiffHunk, type GitDiffLine, type GitBranch, type GitLogGraphNode } from '@/stores/git';
import { useCodeViewerStore } from '@/stores/code-viewer';
import { useChatStore } from '@/stores/chat';
import { cn } from '@/lib/utils';

// ── Status helpers ─────────────────────────────────────────────────

function statusIcon(status: GitFileStatus['status']) {
  switch (status) {
    case 'added': return <FilePlus className="w-3.5 h-3.5 text-green-500 shrink-0" />;
    case 'deleted': return <FileMinus className="w-3.5 h-3.5 text-red-500 shrink-0" />;
    case 'modified': return <FileEdit className="w-3.5 h-3.5 text-amber-500 shrink-0" />;
    case 'renamed': return <FileEdit className="w-3.5 h-3.5 text-blue-500 shrink-0" />;
    case 'untracked': return <FileSearch className="w-3.5 h-3.5 text-muted-foreground shrink-0" />;
  }
}

function statusLabel(status: GitFileStatus['status']) {
  switch (status) {
    case 'added': return <span className="text-green-500 text-[10px]">A</span>;
    case 'deleted': return <span className="text-red-500 text-[10px]">D</span>;
    case 'modified': return <span className="text-amber-500 text-[10px]">M</span>;
    case 'renamed': return <span className="text-blue-500 text-[10px]">R</span>;
    case 'untracked': return <span className="text-muted-foreground text-[10px]">?</span>;
  }
}

// ── Main Panel ─────────────────────────────────────────────────────

export function GitPanel() {
  const open = useGitStore((s) => s.open);
  const closePanel = useGitStore((s) => s.closePanel);
  const [visible, setVisible] = useState(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    if (open) {
      setMounted(true);
      requestAnimationFrame(() => setVisible(true));
    } else {
      setVisible(false);
      const timer = setTimeout(() => setMounted(false), 200);
      return () => clearTimeout(timer);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        // Don't close if branch selector is open
        if (document.querySelector('[data-git-branch-list]')) return;
        closePanel();
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [open, closePanel]);

  if (!mounted) return null;

  const isDark = document.documentElement.classList.contains('dark');

  return (
    <div className={cn(
      'fixed inset-0 z-50 flex transition-all duration-200',
      visible ? 'opacity-100' : 'opacity-0',
    )}>
      <div className="absolute inset-0 bg-black/20" onClick={closePanel} />
      <div className={cn(
        'absolute top-0 right-0 h-full flex flex-col shadow-2xl transition-transform duration-200',
        isDark ? 'bg-[#1c2128]' : 'bg-white',
        visible ? 'translate-x-0' : 'translate-x-full',
      )} style={{ width: 600 }}>
        <PanelContent />
      </div>
    </div>
  );
}

// ── Panel Content ──────────────────────────────────────────────────

function PanelContent() {
  const status = useGitStore((s) => s.status);
  const loading = useGitStore((s) => s.loading);
  const error = useGitStore((s) => s.error);
  const closePanel = useGitStore((s) => s.closePanel);
  const diffTab = useGitStore((s) => s.diffTab);
  const setDiffTab = useGitStore((s) => s.setDiffTab);

  const isDark = document.documentElement.classList.contains('dark');

  return (
    <>
      {/* Header */}
      <div className={cn(
        'flex items-center justify-between px-4 py-3 border-b shrink-0',
        isDark ? 'border-[#30363d]' : 'border-gray-200',
      )}>
        <div className="flex items-center gap-2">
          <GitBranchIcon className="w-4 h-4" />
          <BranchSelector />
          {status?.hasUpstream && (
            <div className="flex items-center gap-1 text-[12px] text-muted-foreground">
              {status.ahead > 0 && (
                <span className="flex items-center gap-0.5"><ArrowUp className="w-3 h-3" />{status.ahead}</span>
              )}
              {status.behind > 0 && (
                <span className="flex items-center gap-0.5"><ArrowDown className="w-3 h-3" />{status.behind}</span>
              )}
            </div>
          )}
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={() => useGitStore.getState().fetchRemote()}
            className="p-1.5 rounded hover:bg-[hsl(var(--muted))] transition-colors text-muted-foreground"
            title="Fetch 远端"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
          <button onClick={closePanel} className="p-1 rounded hover:bg-[hsl(var(--muted))] transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Tab bar: local / remote */}
      <div className={cn('flex border-b shrink-0', isDark ? 'border-[#30363d]' : 'border-gray-200')}>
        <TabButton active={diffTab === 'local'} onClick={() => setDiffTab('local')}>
          本地变更
        </TabButton>
        <TabButton active={diffTab === 'remote'} onClick={() => setDiffTab('remote')}>
          与远端差异
        </TabButton>
        <TabButton active={diffTab === 'log'} onClick={() => setDiffTab('log')}>
          Git Log
        </TabButton>
      </div>

      {/* Error */}
      {error && (
        <div className="px-4 py-2 bg-red-500/10 text-red-500 text-[13px]">{error}</div>
      )}

      {/* Loading */}
      {loading && !status && (
        <div className="flex-1 flex items-center justify-center">
          <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
        </div>
      )}

      {/* Content */}
      {diffTab === 'local' && status && (
        <div className="flex-1 min-h-0 flex flex-col">
          {status.files.length > 0 ? (
            <FileList files={status.files} />
          ) : status.ahead === 0 ? (
            <div className="flex-1 flex items-center justify-center text-muted-foreground text-[13px]">
              工作区干净，无变更
            </div>
          ) : null}
          {status.ahead > 0 && <AheadCommitsView ahead={status.ahead} />}
          {status.ahead > 0 && <div className="shrink-0 h-2" />}
          <CommitSection fileCount={status.files.length} files={status.files} />
        </div>
      )}

      {diffTab === 'remote' && (
        <RemoteDiffTab />
      )}

      {diffTab === 'log' && (
        <GitLogTab />
      )}
    </>
  );
}

// ── Tab Button ─────────────────────────────────────────────────────

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  const isDark = document.documentElement.classList.contains('dark');
  return (
    <button
      onClick={onClick}
      className={cn(
        'px-4 py-2 text-[13px] font-medium transition-colors border-b-2 -mb-px',
        active
          ? 'text-blue-500 border-blue-500'
          : cn(
            'text-muted-foreground border-transparent',
            isDark ? 'hover:text-[#c9d1d9]' : 'hover:text-gray-700',
          ),
      )}
    >
      {children}
    </button>
  );
}

// ── Branch Selector ────────────────────────────────────────────────

function BranchSelector() {
  const status = useGitStore((s) => s.status);
  const branches = useGitStore((s) => s.branches);
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');

  const fetchBranches = useGitStore((s) => s.fetchBranches);
  const checkout = useGitStore((s) => s.checkout);

  const handleToggle = useCallback(() => {
    if (!open) {
      setSearch('');
      fetchBranches();
    }
    setOpen(!open);
  }, [open, fetchBranches]);

  const handleCheckout = useCallback((branch: string) => {
    checkout(branch);
    setOpen(false);
  }, [checkout]);

  const isDark = document.documentElement.classList.contains('dark');

  const localBranches = branches.filter(b => !b.remote && (!search || b.name.toLowerCase().includes(search.toLowerCase())));
  const remoteBranches = branches.filter(b => b.remote && (!search || b.name.toLowerCase().includes(search.toLowerCase())));

  // Check which remote branches already have a local counterpart
  const localNames = new Set(branches.filter(b => !b.remote).map(b => b.name));

  return (
    <div className="relative">
      <button
        onClick={handleToggle}
        className={cn(
          'flex items-center gap-1 text-[14px] font-semibold rounded px-1.5 py-0.5 transition-colors',
          open ? 'bg-[hsl(var(--muted))]' : 'hover:bg-[hsl(var(--muted))]',
        )}
      >
        {status?.branch ?? 'Git'}
        <ChevronDown className={cn('w-3 h-3 transition-transform', open && 'rotate-180')} />
      </button>

      {open && (
        <div
          data-git-branch-list
          className={cn(
            'absolute top-full left-0 mt-1 w-64 rounded-lg shadow-xl border z-50',
            isDark ? 'bg-[#262c36] border-[#30363d]' : 'bg-white border-gray-200',
          )}
        >
          {/* Search input */}
          <div className="p-2 border-b" style={isDark ? { borderColor: '#30363d' } : undefined}>
            <div className={cn('flex items-center gap-1.5 px-2 py-1 rounded text-[13px]',
              isDark ? 'bg-[#1e2228]' : 'bg-gray-100',
            )}>
              <Search className="w-3 h-3 shrink-0 text-muted-foreground" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="搜索分支..."
                className={cn(
                  'flex-1 bg-transparent outline-none text-[13px] placeholder:text-muted-foreground',
                  isDark ? 'text-gray-200' : 'text-gray-800',
                )}
                autoFocus
              />
            </div>
          </div>

          {/* Branch list */}
          <div className="max-h-72 overflow-y-auto">
            {localBranches.length > 0 && (
              <>
                <div className="px-3 py-1.5 text-[11px] text-muted-foreground font-medium">本地分支</div>
                {localBranches.map(b => (
                  <button
                    key={b.name}
                    onClick={() => handleCheckout(b.name)}
                    className={cn(
                      'w-full text-left flex items-center gap-2 px-3 py-1.5 text-[13px] transition-colors',
                      b.current
                        ? isDark ? 'bg-[#1a3a5c] text-blue-400' : 'bg-blue-50 text-blue-600'
                        : isDark ? 'hover:bg-[#30363d]' : 'hover:bg-gray-50',
                    )}
                  >
                    {b.current && <Check className="w-3 h-3 shrink-0" />}
                    <span className={cn('truncate', !b.current && 'pl-5')}>{b.name}</span>
                  </button>
                ))}
              </>
            )}
            {remoteBranches.length > 0 && (
              <>
                <div className={cn('px-3 py-1.5 text-[11px] text-muted-foreground font-medium border-t', isDark && 'border-[#30363d]')}>
                  远端分支
                </div>
                {remoteBranches.map(b => {
                  const localName = b.name.replace(/^remotes\/[^/]+\//, '');
                  const hasLocal = localNames.has(localName);
                  return (
                    <div
                      key={b.name}
                      className={cn(
                        'group flex items-center px-3 py-1.5 text-[13px] text-muted-foreground transition-colors',
                        isDark ? 'hover:bg-[#30363d]' : 'hover:bg-gray-50',
                      )}
                      title={hasLocal ? `切换到 ${localName}` : `Checkout ${b.name} 到本地`}
                    >
                      <span className="truncate flex-1">{b.name.replace('remotes/', '')}</span>
                      <button
                        onClick={() => handleCheckout(b.name)}
                        className={cn(
                          'shrink-0 ml-1 p-0.5 rounded transition-colors',
                          isDark
                            ? 'text-muted-foreground hover:text-blue-400 hover:bg-[#1a3a5c]'
                            : 'text-muted-foreground hover:text-blue-600 hover:bg-blue-50',
                          'opacity-0 group-hover:opacity-100',
                        )}
                        title={hasLocal ? '切换到此分支' : 'Checkout 到本地'}
                      >
                        <Download className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  );
                })}
              </>
            )}
            {branches.length === 0 && (
              <div className="px-3 py-3 text-[12px] text-muted-foreground text-center">加载中...</div>
            )}
            {branches.length > 0 && localBranches.length === 0 && remoteBranches.length === 0 && (
              <div className="px-3 py-3 text-[12px] text-muted-foreground text-center">无匹配分支</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ── Ahead Commits (pending push) ───────────────────────────────────

function AheadCommitsView({ ahead }: { ahead: number }) {
  const aheadCommits = useGitStore((s) => s.aheadCommits);
  const fetchAheadCommits = useGitStore((s) => s.fetchAheadCommits);
  const isDark = document.documentElement.classList.contains('dark');

  React.useEffect(() => {
    fetchAheadCommits();
  }, [fetchAheadCommits]);

  return (
    <div className={cn(
      'shrink-0 overflow-y-auto max-h-[50%] mt-auto',
      isDark ? 'bg-[#0d1117]' : 'bg-gray-50',
    )}>
      <div className={cn(
        'px-4 py-1.5 text-[12px] font-medium flex items-center gap-2',
        isDark ? 'text-amber-400/80' : 'text-amber-600',
      )}>
        <ArrowUp className="w-3 h-3" />
        Outgoing Changes ({ahead})
      </div>
      {aheadCommits.map((c) => {
        const dateStr = (() => {
          try {
            const d = new Date(c.date);
            const now = new Date();
            const diffMs = now.getTime() - d.getTime();
            const diffMins = Math.floor(diffMs / 60000);
            if (diffMins < 60) return `${diffMins}分钟前`;
            const diffHours = Math.floor(diffMins / 60);
            if (diffHours < 24) return `${diffHours}小时前`;
            const diffDays = Math.floor(diffHours / 24);
            if (diffDays < 30) return `${diffDays}天前`;
            return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
          } catch {
            return c.date;
          }
        })();

        return (
          <div key={c.hash} className={cn(
            'flex items-center gap-2 px-4 py-1 text-[12px]',
            isDark ? 'hover:bg-[#161b22]' : 'hover:bg-white',
          )}>
            <span className={cn(
              'shrink-0 text-[11px] font-mono',
              isDark ? 'text-[#8b949e]' : 'text-gray-400',
            )}>
              {c.shortHash}
            </span>
            <span className={cn(
              'flex-1 min-w-0 truncate',
              isDark ? 'text-[#c9d1d9]' : 'text-gray-700',
            )}>
              {c.message}
            </span>
            <span className="shrink-0 text-[11px] text-muted-foreground/60">
              {c.author} · {dateStr}
            </span>
          </div>
        );
      })}
    </div>
  );
}

// ── File List ──────────────────────────────────────────────────────

function FileList({ files }: { files: GitFileStatus[] }) {
  const selectedFile = useGitStore((s) => s.selectedFile);
  const diffFiles = useGitStore((s) => s.diffFiles);
  const diffLoading = useGitStore((s) => s.diffLoading);
  const selectFile = useGitStore((s) => s.selectFile);
  const stagedFiles = useGitStore((s) => s.stagedFiles);
  const toggleStagedFile = useGitStore((s) => s.toggleStagedFile);
  const toggleAllStaged = useGitStore((s) => s.toggleAllStaged);
  const isDark = document.documentElement.classList.contains('dark');

  if (files.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center text-muted-foreground text-[13px]">
        工作区干净，无变更
      </div>
    );
  }

  const allPaths = files.map(f => f.path);
  const allSelected = allPaths.length > 0 && allPaths.every(p => stagedFiles.has(p));

  const groups: Record<string, GitFileStatus[]> = {};
  for (const f of files) {
    const key = f.status === 'untracked' ? 'untracked' : f.staged ? 'staged' : 'unstaged';
    if (!groups[key]) groups[key] = [];
    groups[key].push(f);
  }

  const groupLabels: Record<string, string> = {
    staged: '已暂存', unstaged: '未暂存', untracked: '未跟踪',
  };

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      {/* Select all header */}
      <div className={cn(
        'sticky top-0 flex items-center gap-2 px-4 py-1.5 text-[12px] font-medium text-muted-foreground z-10',
        isDark ? 'bg-[#1c2128]' : 'bg-white',
      )}>
        <button
          onClick={() => toggleAllStaged(allPaths)}
          className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
        >
          {allSelected
            ? <CheckSquare className="w-3.5 h-3.5 text-blue-500" />
            : <Square className="w-3.5 h-3.5" />
          }
        </button>
        变更文件 ({files.length})
      </div>

      {Object.entries(groups).map(([groupKey, groupFiles]) => (
        <div key={groupKey}>
          <div className={cn(
            'sticky top-[30px] px-4 py-1 pl-9 text-[11px] text-muted-foreground/70',
            isDark ? 'bg-[#1c2128]' : 'bg-white',
          )}>
            {groupLabels[groupKey]} ({groupFiles.length})
          </div>
          {groupFiles.map((file) => (
            <div key={file.path}>
              <FileItem
                file={file}
                isSelected={selectedFile === file.path}
                isStaged={stagedFiles.has(file.path)}
                onClick={() => selectFile(file.path)}
                onToggleStaged={() => toggleStagedFile(file.path)}
                isDark={isDark}
              />
              {selectedFile === file.path && diffFiles.length > 0 && (
                <DiffView hunks={diffFiles[0].hunks} isDark={isDark} filePath={file.path} />
              )}
              {selectedFile === file.path && diffLoading && (
                <div className="px-4 py-3 flex items-center gap-2 text-[12px] text-muted-foreground">
                  <Loader2 className="w-3 h-3 animate-spin" /> 加载差异...
                </div>
              )}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

// ── File Item ──────────────────────────────────────────────────────

const FileItem = memo(function FileItem({ file, isSelected, isStaged, onClick, onToggleStaged, isDark }: {
  file: GitFileStatus; isSelected: boolean; isStaged: boolean; onClick: () => void; onToggleStaged: () => void; isDark: boolean;
}) {
  return (
    <div
      className={cn(
        'flex items-center gap-1 px-3 py-1.5 text-[13px] transition-colors cursor-pointer',
        isSelected ? isDark ? 'bg-[#1a3a5c]' : 'bg-blue-50' : isDark ? 'hover:bg-[#262c36]' : 'hover:bg-gray-50',
      )}
      onClick={onClick}
    >
      <button
        onClick={(e) => { e.stopPropagation(); onToggleStaged(); }}
        className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
      >
        {isStaged
          ? <CheckSquare className="w-3.5 h-3.5 text-blue-500" />
          : <Square className="w-3.5 h-3.5" />
        }
      </button>
      {statusIcon(file.status)}
      <span className="flex-1 min-w-0 truncate font-mono text-[12px]">{file.path}</span>
      {statusLabel(file.status)}
      {isSelected ? <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground" /> : <ChevronRight className="w-3 h-3 shrink-0 text-muted-foreground" />}
    </div>
  );
});

// ── Diff View ──────────────────────────────────────────────────────

function DiffView({ hunks, isDark, filePath }: { hunks: GitDiffHunk[]; isDark: boolean; filePath: string }) {
  const workspace = useChatStore((s) => {
    const session = s.sessions.find(ss => ss.key === s.currentSessionId);
    return session?.workspace;
  });
  const openViewer = useCodeViewerStore((s) => s.openViewer);

  const handleOpenFile = useCallback(() => {
    if (workspace) openViewer(workspace, filePath);
  }, [workspace, filePath, openViewer]);

  if (hunks.length === 0) {
    return <div className="px-6 py-2 text-[12px] text-muted-foreground">无差异内容</div>;
  }

  return (
    <div className={cn(
      'mx-2 mb-2 rounded border overflow-hidden text-[12px] font-mono',
      isDark ? 'border-[#30363d] bg-[#0d1117]' : 'border-gray-200 bg-gray-50',
    )}>
      <button
        onClick={handleOpenFile}
        className={cn(
          'w-full text-left px-3 py-1 text-[11px] text-blue-500 hover:underline border-b',
          isDark ? 'border-[#30363d]' : 'border-gray-200',
        )}
      >
        在编辑器中打开 →
      </button>
      {hunks.map((hunk, hi) => (
        <div key={hi}>
          <div className={cn('px-3 py-0.5 text-[11px] text-blue-400', isDark ? 'bg-[#161b22]' : 'bg-blue-50')}>
            {hunk.header}
          </div>
          {hunk.lines.map((line, li) => <DiffLine key={li} line={line} isDark={isDark} />)}
        </div>
      ))}
    </div>
  );
}

function DiffLine({ line, isDark }: { line: GitDiffLine; isDark: boolean }) {
  const bgClass = line.type === 'added' ? isDark ? 'bg-[#1a3a2a]' : 'bg-green-50' : line.type === 'removed' ? isDark ? 'bg-[#3a1a1a]' : 'bg-red-50' : '';
  const textClass = line.type === 'added' ? 'text-green-600 dark:text-green-400' : line.type === 'removed' ? 'text-red-600 dark:text-red-400' : isDark ? 'text-[#c9d1d9]' : 'text-gray-700';
  const prefix = line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' ';

  return (
    <div className={cn('flex', bgClass)}>
      <span className="select-none shrink-0 w-8 text-right pr-2 text-muted-foreground/40 text-[11px]">{line.oldLineNo ?? ''}</span>
      <span className="select-none shrink-0 w-8 text-right pr-2 text-muted-foreground/40 text-[11px]">{line.newLineNo ?? ''}</span>
      <span className={cn('shrink-0 w-4 text-center', textClass)}>{prefix}</span>
      <span className={cn('flex-1 min-w-0', textClass)} style={{ whiteSpace: 'pre' }}>{line.content}</span>
    </div>
  );
}

// ── Remote Diff Tab ────────────────────────────────────────────────

function RemoteDiffTab() {
  const remoteDiff = useGitStore((s) => s.remoteDiff);
  const remoteDiffLoading = useGitStore((s) => s.remoteDiffLoading);
  const status = useGitStore((s) => s.status);
  const isDark = document.documentElement.classList.contains('dark');

  if (remoteDiffLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!status?.hasUpstream) {
    return (
      <div className="flex-1 flex items-center justify-center text-muted-foreground text-[13px]">
        未设置远端跟踪分支
      </div>
    );
  }

  if (remoteDiff.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center text-muted-foreground text-[13px]">
        {status.ahead === 0 && status.behind === 0 ? '与远端完全同步' : '本地与远端无文件差异'}
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      <div className={cn('px-4 py-1.5 text-[12px] font-medium text-muted-foreground', isDark ? 'bg-[#1c2128]' : 'bg-white')}>
        差异文件 ({remoteDiff.length})
      </div>
      {remoteDiff.map(file => (
        <RemoteDiffFile key={file.path} file={file} isDark={isDark} />
      ))}
    </div>
  );
}

function RemoteDiffFile({ file, isDark }: { file: { path: string; hunks: GitDiffHunk[] }; isDark: boolean }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div>
      <button
        onClick={() => setExpanded(!expanded)}
        className={cn(
          'w-full text-left flex items-center gap-2 px-4 py-1.5 text-[13px] transition-colors',
          isDark ? 'hover:bg-[#262c36]' : 'hover:bg-gray-50',
        )}
      >
        {expanded ? <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground" /> : <ChevronRight className="w-3 h-3 shrink-0 text-muted-foreground" />}
        <span className="flex-1 min-w-0 truncate font-mono text-[12px]">{file.path}</span>
        <span className="text-[11px] text-muted-foreground">{file.hunks.reduce((acc, h) => acc + h.lines.filter(l => l.type !== 'context').length, 0)} changes</span>
      </button>
      {expanded && (
        <div className={cn(
          'mx-2 mb-2 rounded border overflow-hidden text-[12px] font-mono',
          isDark ? 'border-[#30363d] bg-[#0d1117]' : 'border-gray-200 bg-gray-50',
        )}>
          {file.hunks.map((hunk, hi) => (
            <div key={hi}>
              <div className={cn('px-3 py-0.5 text-[11px] text-blue-400', isDark ? 'bg-[#161b22]' : 'bg-blue-50')}>{hunk.header}</div>
              {hunk.lines.map((line, li) => <DiffLine key={li} line={line} isDark={isDark} />)}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Git Log Graph Renderer ──────────────────────────────────────────

/**
 * Parse git --graph ASCII output into structured lane data for SVG rendering.
 * Characters: | * / \ space  (each char = 1 column, 2 chars wide per lane)
 * Returns per-row: array of { col, type } where type is 'commit' | 'pipe' | 'merge-l' | 'merge-r' | 'fork-l' | 'fork-r'
 */
interface GraphSegment {
  col: number;        // lane index (0-based)
  char: string;       // raw char: | * / \
}

function parseGraphLine(line: string): GraphSegment[] {
  const segs: GraphSegment[] = [];
  // git graph uses 2-char columns: "| " "* " "|/" "|\\" etc
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '|' || ch === '*' || ch === '/' || ch === '\\') {
      segs.push({ col: Math.floor(i / 2), char: ch });
    }
  }
  return segs;
}

const LANE_W = 12;   // px per lane
const ROW_H = 26;    // px per row
const DOT_R = 3.5;   // commit dot radius

const BRANCH_COLORS = [
  '#58a6ff', // blue
  '#f78166', // orange
  '#3fb950', // green
  '#d2a8ff', // purple
  '#79c0ff', // light blue
  '#ffa657', // amber
  '#7ee787', // light green
  '#ff7b72', // red
];

function getLaneColor(lane: number): string {
  return BRANCH_COLORS[lane % BRANCH_COLORS.length];
}

function GraphSvg({ nodes, isDark }: { nodes: GitLogGraphNode[]; isDark: boolean }) {
  const maxLanes = Math.max(
    1,
    ...nodes.map(n => {
      const segs = parseGraphLine(n.graphLine);
      return segs.length > 0 ? Math.max(...segs.map(s => s.col)) + 1 : 1;
    }),
  );
  const svgW = maxLanes * LANE_W + 4;
  const svgH = nodes.length * ROW_H;

  // Build SVG elements
  const paths: React.ReactElement[] = [];
  const dots: React.ReactElement[] = [];

  for (let row = 0; row < nodes.length; row++) {
    const segs = parseGraphLine(nodes[row].graphLine);
    const y = row * ROW_H + ROW_H / 2;

    for (const seg of segs) {
      const cx = seg.col * LANE_W + LANE_W / 2 + 2;
      const color = getLaneColor(seg.col);

      if (seg.char === '*') {
        // Commit dot
        dots.push(
          <circle key={`d-${row}-${seg.col}`} cx={cx} cy={y} r={DOT_R} fill={color} />,
        );
        // Vertical pipe through this row
        paths.push(
          <line key={`pv-${row}-${seg.col}`} x1={cx} y1={y - ROW_H / 2} x2={cx} y2={y + ROW_H / 2}
            stroke={color} strokeWidth={1.5} opacity={0.4} />,
        );
      } else if (seg.char === '|') {
        // Vertical pipe
        paths.push(
          <line key={`p-${row}-${seg.col}`} x1={cx} y1={y - ROW_H / 2} x2={cx} y2={y + ROW_H / 2}
            stroke={color} strokeWidth={1.5} opacity={0.4} />,
        );
      } else if (seg.char === '/') {
        // Merge: line going from bottom-left to top-right
        paths.push(
          <line key={`ml-${row}-${seg.col}`} x1={cx} y1={y + ROW_H / 2} x2={cx - LANE_W} y2={y - ROW_H / 2}
            stroke={color} strokeWidth={1.5} opacity={0.4} />,
        );
      } else if (seg.char === '\\') {
        // Fork: line going from bottom-right to top-left
        paths.push(
          <line key={`fr-${row}-${seg.col}`} x1={cx} y1={y + ROW_H / 2} x2={cx + LANE_W} y2={y - ROW_H / 2}
            stroke={color} strokeWidth={1.5} opacity={0.4} />,
        );
      }
    }
  }

  return (
    <svg width={svgW} height={svgH} className="shrink-0" style={{ display: 'block' }}>
      {paths}
      {dots}
    </svg>
  );
}

// ── Git Log Tab ────────────────────────────────────────────────────

const LOG_PAGE_SIZE = 40;

function GitLogTab() {
  const logGraph = useGitStore((s) => s.logGraph);
  const logSearchResults = useGitStore((s) => s.logSearchResults);
  const logSearchLoading = useGitStore((s) => s.logSearchLoading);
  const searchLog = useGitStore((s) => s.searchLog);
  const status = useGitStore((s) => s.status);
  const isDark = document.documentElement.classList.contains('dark');
  const [search, setSearch] = useState('');
  const [visibleCount, setVisibleCount] = useState(LOG_PAGE_SIZE);
  const scrollRef = React.useRef<HTMLDivElement>(null);
  const searchTimerRef = React.useRef<ReturnType<typeof setTimeout>>(undefined);

  // Debounced search — call backend git log search
  React.useEffect(() => {
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    if (!search.trim()) return;
    searchTimerRef.current = setTimeout(() => {
      searchLog(search.trim());
    }, 300);
    return () => { if (searchTimerRef.current) clearTimeout(searchTimerRef.current); };
  }, [search, searchLog]);

  const isSearching = search.trim().length > 0;
  const searchResults = logSearchResults;

  // Non-search: incremental loading from loaded logGraph
  const visible = logGraph.slice(0, visibleCount);
  const hasMore = !isSearching && visibleCount < logGraph.length;

  React.useEffect(() => {
    setVisibleCount(LOG_PAGE_SIZE);
  }, [logGraph]);

  const handleScroll = React.useCallback(() => {
    const el = scrollRef.current;
    if (!el || isSearching || !hasMore) return;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 100) {
      setVisibleCount(c => Math.min(c + LOG_PAGE_SIZE, logGraph.length));
    }
  }, [hasMore, isSearching, logGraph.length]);

  if (logGraph.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* Search bar */}
      <div className="shrink-0 px-2 py-1.5 border-b" style={isDark ? { borderColor: '#21262d' } : undefined}>
        <div className={cn('flex items-center gap-1.5 px-2 py-1 rounded text-[12px]',
          isDark ? 'bg-[#161b22]' : 'bg-gray-100',
        )}>
          {logSearchLoading ? <Loader2 className="w-3 h-3 shrink-0 animate-spin text-muted-foreground" /> : <Search className="w-3 h-3 shrink-0 text-muted-foreground" />}
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="搜索 hash、消息、作者..."
            className={cn(
              'flex-1 bg-transparent outline-none text-[12px] placeholder:text-muted-foreground',
              isDark ? 'text-gray-200' : 'text-gray-800',
            )}
          />
          {search && (
            <button onClick={() => { setSearch(''); }} className="text-muted-foreground hover:text-foreground">
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      {/* Log list */}
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        className={cn(
          'flex-1 min-h-0 overflow-y-auto text-[12px]',
          isDark ? 'bg-[#0d1117]' : 'bg-gray-50',
        )}
      >
        {isSearching ? (
          // Search results: flat list (no graph SVG)
          <div className="flex-1 min-w-0">
            {searchResults.map((node, i) => (
              <LogEntry key={`${node.hash}-${i}`} node={node} currentBranch={status?.branch} isDark={isDark} />
            ))}
            {searchResults.length === 0 && !logSearchLoading && (
              <div className="px-3 py-4 text-center text-[12px] text-muted-foreground">
                无匹配结果
              </div>
            )}
          </div>
        ) : (
          // Normal view: graph SVG + commit info
          <div className="flex">
            <GraphSvg nodes={visible} isDark={isDark} />
            <div className="flex-1 min-w-0">
              {visible.map((node, i) => (
                <LogEntry key={`${node.hash}-${i}`} node={node} currentBranch={status?.branch} isDark={isDark} />
              ))}
              {hasMore && (
                <div className="px-3 py-2 text-center text-[11px] text-muted-foreground">
                  向下滚动加载更多...
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function LogEntry({ node, currentBranch, isDark }: { node: GitLogGraphNode; currentBranch?: string; isDark: boolean }) {
  // Parse refs: "HEAD -> main, origin/main, origin/HEAD"
  const refs = node.refs
    ? node.refs.split(',').map(r => r.trim()).filter(Boolean)
    : [];

  const isCurrent = refs.some(r => r === currentBranch || r === `HEAD -> ${currentBranch}`);

  // Format date
  const dateStr = (() => {
    try {
      const d = new Date(node.date);
      const now = new Date();
      const diffMs = now.getTime() - d.getTime();
      const diffMins = Math.floor(diffMs / 60000);
      if (diffMins < 60) return `${diffMins}分钟前`;
      const diffHours = Math.floor(diffMins / 60);
      if (diffHours < 24) return `${diffHours}小时前`;
      const diffDays = Math.floor(diffHours / 24);
      if (diffDays < 30) return `${diffDays}天前`;
      return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
    } catch {
      return node.date;
    }
  })();

  return (
    <div className={cn(
      'flex items-center gap-2 px-2 border-b',
      isCurrent ? isDark ? 'bg-[#1a3a5c]/40' : 'bg-blue-50/50' : '',
      isDark ? 'border-[#21262d] hover:bg-[#161b22]' : 'border-gray-100 hover:bg-white',
    )} style={{ height: ROW_H }}>
      {/* Commit info */}
      <div className="flex-1 min-w-0 flex items-center gap-2">
        {/* Short hash */}
        <span className={cn(
          'shrink-0 text-[11px]',
          isDark ? 'text-[#8b949e]' : 'text-gray-400',
        )}>
          {node.shortHash}
        </span>

        {/* Ref badges */}
        {refs.map((ref, i) => {
          if (ref.includes('HEAD -> ')) {
            const branch = ref.replace('HEAD -> ', '');
            return (
              <span key={i} className="shrink-0 px-1.5 py-0 rounded bg-blue-500/20 text-blue-400 text-[10px] font-bold">
                {branch}
              </span>
            );
          }
          if (ref.startsWith('origin/')) {
            return (
              <span key={i} className="shrink-0 px-1.5 py-0 rounded bg-purple-500/15 text-purple-400 text-[10px]">
                {ref}
              </span>
            );
          }
          if (ref === 'HEAD') {
            return (
              <span key={i} className="shrink-0 px-1.5 py-0 rounded bg-amber-500/15 text-amber-400 text-[10px]">
                HEAD
              </span>
            );
          }
          return (
            <span key={i} className="shrink-0 px-1.5 py-0 rounded bg-green-500/15 text-green-400 text-[10px]">
              {ref}
            </span>
          );
        })}

        {/* Message */}
        <span className={cn(
          'flex-1 min-w-0 truncate',
          isDark ? 'text-[#c9d1d9]' : 'text-gray-700',
        )}>
          {node.message}
        </span>

        {/* Author + date */}
        <span className="shrink-0 text-[11px] text-muted-foreground/60">
          {node.author} · {dateStr}
        </span>
      </div>
    </div>
  );
}

// ── Commit Section ─────────────────────────────────────────────────

function CommitSection({ fileCount, files }: { fileCount: number; files: GitFileStatus[] }) {
  const [message, setMessage] = useState('');
  const [showTemplate, setShowTemplate] = useState(false);
  const committing = useGitStore((s) => s.committing);
  const generating = useGitStore((s) => s.generating);
  const pushing = useGitStore((s) => s.pushing);
  const status = useGitStore((s) => s.status);
  const stagedFiles = useGitStore((s) => s.stagedFiles);
  const commit = useGitStore((s) => s.commit);
  const commitTemplate = useGitStore((s) => s.commitTemplate);
  const setCommitTemplate = useGitStore((s) => s.setCommitTemplate);
  const generateCommit = useGitStore((s) => s.generateCommit);
  const push = useGitStore((s) => s.push);
  const isDark = document.documentElement.classList.contains('dark');

  const hasAheadCommits = (status?.ahead ?? 0) > 0;
  const stagedArray = files.filter(f => stagedFiles.has(f.path)).map(f => f.path);

  const handleCommit = useCallback(() => {
    if (committing || stagedArray.length === 0) return;

    if (message.trim()) {
      commit(message.trim(), stagedArray);
      setMessage('');
      return;
    }

    // Empty message → AI generate then commit
    if (generating) return;
    generateCommit((generated) => {
      commit(generated, stagedArray);
      setMessage('');
    }, stagedArray);
  }, [message, committing, stagedArray, generating, commit, generateCommit]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      handleCommit();
    }
  }, [handleCommit]);

  const handleAutoGenerate = useCallback(() => {
    if (generating || stagedArray.length === 0) return;
    generateCommit((generated) => setMessage(generated), stagedArray);
  }, [generating, generateCommit, stagedArray]);

  const handlePush = useCallback(() => {
    if (pushing) return;
    push();
  }, [pushing, push]);

  const isBusy = committing || generating || pushing || stagedArray.length === 0;

  return (
    <div className={cn('border-t px-4 py-3 shrink-0', isDark ? 'border-[#30363d]' : 'border-gray-200')}>
      <textarea
        value={message}
        onChange={(e) => setMessage(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={`描述变更内容（留空则 AI 自动生成）...\nCtrl+Enter 提交`}
        rows={3}
        className={cn(
          'w-full px-3 py-2 text-[13px] rounded-md border resize-none font-mono mb-2',
          'focus:outline-none focus:ring-1 focus:ring-[hsl(var(--ring))]',
          isDark
            ? 'bg-[#0d1117] border-[#30363d] text-[#c9d1d9] placeholder:text-[#484f58]'
            : 'bg-gray-50 border-gray-200 text-gray-900 placeholder:text-gray-400',
        )}
      />

      {/* Template editor */}
      {showTemplate && (
        <div className="mb-2">
          <textarea
            value={commitTemplate}
            onChange={(e) => setCommitTemplate(e.target.value)}
            placeholder={`自定义模板（作为 AI 提示的一部分）:\n\n示例:\n请用中文生成 commit message，格式为 Conventional Commits\n\n可用变量:\n\${added} \${modified} \${deleted}\n\${added_count} \${modified_count} \${total_count}\n\n示例:\nfeat(\${scope}): \${summary}`}
            rows={5}
            className={cn(
              'w-full px-3 py-2 text-[12px] rounded-md border resize-none font-mono',
              'focus:outline-none focus:ring-1 focus:ring-[hsl(var(--ring))]',
              isDark
                ? 'bg-[#0d1117] border-[#30363d] text-[#c9d1d9] placeholder:text-[#484f58]'
                : 'bg-gray-50 border-gray-200 text-gray-900 placeholder:text-gray-400',
            )}
          />
        </div>
      )}

      <div className="flex items-center gap-2">
        <button
          onClick={handleAutoGenerate}
          disabled={generating || stagedArray.length === 0}
          className={cn(
            'flex items-center gap-1 px-2.5 py-1.5 text-[12px] rounded-md border transition-colors',
            generating
              ? 'text-amber-500 border-amber-400'
              : 'text-muted-foreground hover:text-[hsl(var(--foreground))] disabled:opacity-40 disabled:cursor-not-allowed',
          )}
          title="AI 分析代码差异自动生成 commit message"
        >
          {generating ? <Loader2 className="w-3 h-3 animate-spin" /> : <Sparkles className="w-3 h-3" />}
          {generating ? '生成中...' : 'AI 生成'}
        </button>
        <button
          onClick={() => setShowTemplate(!showTemplate)}
          className={cn(
            'flex items-center gap-1 px-2.5 py-1.5 text-[12px] rounded-md border transition-colors',
            showTemplate ? 'text-blue-500 border-blue-400' : 'text-muted-foreground hover:text-[hsl(var(--foreground))]',
          )}
          title="自定义 commit message 模板"
        >
          <Settings2 className="w-3 h-3" />
          模板
        </button>
        <div className="flex-1" />
        <button
          onClick={handleCommit}
          disabled={isBusy}
          className={cn(
            'flex items-center gap-1.5 px-3 py-1.5 text-[13px] rounded-md border transition-colors',
            'text-muted-foreground hover:text-[hsl(var(--foreground))] hover:bg-[hsl(var(--muted))]',
            'disabled:opacity-40 disabled:cursor-not-allowed',
          )}
          title="Commit 到本地（留空消息则 AI 自动生成 commit message）"
        >
          {committing || generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
          {committing ? 'Committing...' : generating ? '生成中...' : 'Commit'}
        </button>
        <button
          onClick={handlePush}
          disabled={pushing || (!hasAheadCommits && fileCount === 0)}
          className={cn(
            'flex items-center gap-1.5 px-3 py-1.5 text-[13px] rounded-md border transition-colors',
            'text-muted-foreground hover:text-[hsl(var(--foreground))] hover:bg-[hsl(var(--muted))]',
            'disabled:opacity-40 disabled:cursor-not-allowed',
          )}
          title="智能 Push：自动 git pull 合并远端变更，如有冲突由 AI 解决，然后 git push"
        >
          {pushing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Upload className="w-3.5 h-3.5" />}
          {pushing ? 'Pushing...' : 'Push'}
        </button>
      </div>
    </div>
  );
}
