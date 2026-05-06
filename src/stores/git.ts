import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import { useChatStore } from '@/stores/chat';

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapHandler(
  client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void },
  event: string,
  handler: MsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

function getWorkspace(): string | undefined {
  const { currentSessionId, sessions } = useChatStore.getState();
  const session = sessions.find((s) => s.key === currentSessionId);
  return session?.workspace;
}

// ── Commit Template (localStorage) ─────────────────────────────────

const TEMPLATE_KEY = 'sman-commit-template';

const DEFAULT_TEMPLATE = '使用中文，commit message格式：{变更类型}:{变更内容描述}';

function loadCommitTemplate(): string {
  try { return localStorage.getItem(TEMPLATE_KEY) || DEFAULT_TEMPLATE; } catch { return DEFAULT_TEMPLATE; }
}

function saveCommitTemplate(template: string) {
  try { localStorage.setItem(TEMPLATE_KEY, template); } catch { /* ignore */ }
}

// ── Types ──────────────────────────────────────────────────────────

export interface GitFileStatus {
  path: string;
  status: 'added' | 'modified' | 'deleted' | 'renamed' | 'untracked';
  staged: boolean;
}

export interface GitStatusResult {
  branch: string;
  files: GitFileStatus[];
  ahead: number;
  behind: number;
  hasUpstream: boolean;
}

export interface GitDiffLine {
  type: 'added' | 'removed' | 'context';
  content: string;
  oldLineNo: number | null;
  newLineNo: number | null;
}

export interface GitDiffHunk {
  header: string;
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  lines: GitDiffLine[];
}

export interface GitDiffFile {
  path: string;
  hunks: GitDiffHunk[];
}

export interface GitLogEntry {
  hash: string;
  shortHash: string;
  message: string;
  author: string;
  date: string;
  refs: string;
}

export interface GitBranch {
  name: string;
  current: boolean;
  remote: boolean;
  remoteName?: string;
}

export type DiffTab = 'local' | 'remote' | 'log';

export interface GitLogGraphNode {
  hash: string;
  shortHash: string;
  message: string;
  author: string;
  date: string;
  refs: string;
  graphLine: string;
}

// ── Store ──────────────────────────────────────────────────────────

interface GitState {
  open: boolean;
  loading: boolean;
  error: string | null;

  status: GitStatusResult | null;
  diffFiles: GitDiffFile[];
  diffLoading: boolean;
  selectedFile: string | null;
  diffTab: DiffTab;
  remoteDiff: GitDiffFile[];
  remoteDiffLoading: boolean;

  log: GitLogEntry[];
  logGraph: GitLogGraphNode[];
  logSearchResults: GitLogGraphNode[];
  logSearchLoading: boolean;
  aheadCommits: { hash: string; shortHash: string; message: string; author: string; date: string }[];
  branches: GitBranch[];
  branchesLoading: boolean;

  committing: boolean;
  commitTemplate: string;
  generating: boolean;
  pushing: boolean;

  // Actions
  openPanel: () => void;
  closePanel: () => void;
  fetchStatus: () => void;
  fetchDiff: (filePath?: string) => void;
  selectFile: (filePath: string | null) => void;
  commit: (message: string, files?: string[]) => void;
  fetchLog: () => void;
  fetchLogGraph: () => void;
  searchLog: (query: string) => void;
  fetchAheadCommits: () => void;
  fetchBranches: () => void;
  checkout: (branch: string) => void;
  fetchRemote: () => void;
  fetchRemoteDiff: () => void;
  setDiffTab: (tab: DiffTab) => void;
  setCommitTemplate: (template: string) => void;
  generateCommit: (onGenerated: (message: string) => void) => void;
  push: () => void;
}

export const useGitStore = create<GitState>((set, get) => ({
  open: false,
  loading: false,
  error: null,
  status: null,
  diffFiles: [],
  diffLoading: false,
  selectedFile: null,
  diffTab: 'local',
  remoteDiff: [],
  remoteDiffLoading: false,
  log: [],
  logGraph: [],
  logSearchResults: [],
  logSearchLoading: false,
  aheadCommits: [],
  branches: [],
  branchesLoading: false,
  committing: false,
  commitTemplate: loadCommitTemplate(),
  generating: false,
  pushing: false,

  openPanel() {
    set({ open: true, diffTab: 'local' });
    get().fetchStatus();
  },

  closePanel() {
    set({ open: false, status: null, diffFiles: [], selectedFile: null, log: [], logGraph: [], branches: [], remoteDiff: [] });
  },

  fetchStatus() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    set({ loading: true, error: null });

    const unsub = wrapHandler(client, 'git.status', (msg) => {
      unsub();
      const result = msg.result as (GitStatusResult & { error?: string }) | undefined;
      if (result?.error) {
        set({ loading: false, error: result.error });
        return;
      }
      set({ status: result as GitStatusResult, loading: false, error: null });
      if ((result as GitStatusResult).ahead > 0) {
        get().fetchAheadCommits();
      }
    });

    client.send({ type: 'git.status', workspace });
  },

  fetchDiff(filePath?: string) {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    set({ diffLoading: true, diffFiles: [], selectedFile: filePath ?? null });

    const unsub = wrapHandler(client, 'git.diff', (msg) => {
      unsub();
      const result = msg.result as (GitDiffFile[] & { error?: string }) | undefined;
      if (result?.error) {
        set({ diffLoading: false, error: result.error });
        return;
      }
      const files = Array.isArray(result) ? result : [];
      set({ diffFiles: files, diffLoading: false });
    });

    client.send({ type: 'git.diff', workspace, filePath });
  },

  selectFile(filePath: string | null) {
    if (filePath === get().selectedFile) {
      set({ selectedFile: null, diffFiles: [] });
      return;
    }
    get().fetchDiff(filePath ?? undefined);
  },

  commit(message: string, files?: string[]) {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    set({ committing: true, error: null });

    const unsub = wrapHandler(client, 'git.commit', (msg) => {
      unsub();
      const result = msg.result as ({ hash: string } & { error?: string }) | undefined;
      if (result?.error) {
        set({ committing: false, error: result.error });
        return;
      }
      set({ committing: false });
      get().fetchStatus();
      if (get().diffTab === 'log') {
        get().fetchLogGraph();
      }
    });

    client.send({ type: 'git.commit', workspace, message, files });
  },

  fetchLog() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    const unsub = wrapHandler(client, 'git.log', (msg) => {
      unsub();
      const result = msg.result as (GitLogEntry[] & { error?: string }) | undefined;
      if (result?.error) return;
      const log = Array.isArray(result) ? result : [];
      set({ log });
    });

    client.send({ type: 'git.log', workspace });
  },

  fetchLogGraph() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    const unsub = wrapHandler(client, 'git.logGraph', (msg) => {
      unsub();
      const result = msg.result as (GitLogGraphNode[] & { error?: string }) | undefined;
      if (result?.error) return;
      const logGraph = Array.isArray(result) ? result : [];
      set({ logGraph });
    });

    client.send({ type: 'git.logGraph', workspace });
  },

  searchLog(query: string) {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    if (!query.trim()) {
      set({ logSearchResults: [], logSearchLoading: false });
      return;
    }

    set({ logSearchLoading: true });

    const unsub = wrapHandler(client, 'git.logSearch', (msg) => {
      unsub();
      const result = msg.result as (GitLogGraphNode[] & { error?: string }) | undefined;
      set({
        logSearchResults: Array.isArray(result) ? result : [],
        logSearchLoading: false,
      });
    });

    client.send({ type: 'git.logSearch', workspace, query });
  },

  fetchAheadCommits() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    const unsub = wrapHandler(client, 'git.aheadCommits', (msg) => {
      unsub();
      const result = msg.result as ({ hash: string; shortHash: string; message: string; author: string; date: string }[] & { error?: string }) | undefined;
      if (result?.error) return;
      set({ aheadCommits: Array.isArray(result) ? result : [] });
    });

    client.send({ type: 'git.aheadCommits', workspace });
  },

  fetchBranches() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    set({ branchesLoading: true });

    const unsub = wrapHandler(client, 'git.branchList', (msg) => {
      unsub();
      const result = msg.result as (GitBranch[] & { error?: string }) | undefined;
      if (result?.error) {
        set({ branchesLoading: false, error: result.error });
        return;
      }
      const branches = Array.isArray(result) ? result : [];
      set({ branches, branchesLoading: false });
    });

    client.send({ type: 'git.branchList', workspace });
  },

  checkout(branch: string) {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    set({ loading: true, error: null });

    const unsub = wrapHandler(client, 'git.checkout', (msg) => {
      unsub();
      const result = msg.result as ({ success: boolean } & { error?: string }) | undefined;
      if (result?.error) {
        set({ loading: false, error: result.error });
        return;
      }
      set({ loading: false });
      get().fetchStatus();
      // Refresh titlebar branch
      (window as any).__sman_gitBranchRefresh?.();
    });

    client.send({ type: 'git.checkout', workspace, branch });
  },

  fetchRemote() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    const unsub = wrapHandler(client, 'git.fetch', (msg) => {
      unsub();
      const result = msg.result as ({ success: boolean } & { error?: string }) | undefined;
      if (result?.error) {
        set({ error: result.error });
        return;
      }
      // After fetch, refresh status and remote diff
      get().fetchStatus();
      get().fetchRemoteDiff();
    });

    client.send({ type: 'git.fetch', workspace });
  },

  fetchRemoteDiff() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    set({ remoteDiffLoading: true, remoteDiff: [] });

    const unsub = wrapHandler(client, 'git.remoteDiff', (msg) => {
      unsub();
      const result = msg.result as (GitDiffFile[] & { error?: string }) | undefined;
      if (result?.error) {
        set({ remoteDiffLoading: false });
        return;
      }
      const files = Array.isArray(result) ? result : [];
      set({ remoteDiff: files, remoteDiffLoading: false });
    });

    client.send({ type: 'git.remoteDiff', workspace });
  },

  setDiffTab(tab: DiffTab) {
    set({ diffTab: tab, selectedFile: null, diffFiles: [] });
    if (tab === 'remote' && get().remoteDiff.length === 0) {
      get().fetchRemoteDiff();
    }
    if (tab === 'log') {
      get().fetchLogGraph();
    }
  },

  setCommitTemplate(template: string) {
    set({ commitTemplate: template });
    saveCommitTemplate(template);
  },

  generateCommit(onGenerated: (message: string) => void) {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    const template = get().commitTemplate || undefined;
    set({ generating: true, error: null });

    const unsub = wrapHandler(client, 'git.generateCommit', (msg) => {
      unsub();
      const result = msg.result as ({ message: string } & { error?: string }) | undefined;
      if (result?.error) {
        set({ generating: false, error: result.error });
        return;
      }
      set({ generating: false });
      if (result?.message) {
        onGenerated(result.message);
      }
    });

    client.send({ type: 'git.generateCommit', workspace, template });
  },

  push() {
    const client = getWsClient();
    const workspace = getWorkspace();
    if (!client || !workspace) return;

    set({ pushing: true, error: null });

    const unsub = wrapHandler(client, 'git.push', (msg) => {
      unsub();
      const result = msg.result as ({ success: boolean; message?: string } & { error?: string }) | undefined;
      if (result?.error) {
        set({ pushing: false, error: result.error });
        return;
      }
      set({ pushing: false });
      get().fetchStatus();
      get().fetchRemoteDiff();
    });

    client.send({ type: 'git.push', workspace });
  },
}));

// ── Commit template helper ─────────────────────────────────────────

export function applyTemplate(template: string, files: GitFileStatus[]): string {
  const added = files.filter(f => f.status === 'added').map(f => f.path);
  const modified = files.filter(f => f.status === 'modified').map(f => f.path);
  const deleted = files.filter(f => f.status === 'deleted').map(f => f.path);
  const renamed = files.filter(f => f.status === 'renamed').map(f => f.path);
  const untracked = files.filter(f => f.status === 'untracked').map(f => f.path);

  const shortNames = (paths: string[]) =>
    paths.length <= 3 ? paths.map(p => p.split('/').pop()).join(', ') : `${paths.length} files`;

  return template
    .replace(/\$\{added\}/g, shortNames(added))
    .replace(/\$\{modified\}/g, shortNames(modified))
    .replace(/\$\{deleted\}/g, shortNames(deleted))
    .replace(/\$\{renamed\}/g, shortNames(renamed))
    .replace(/\$\{untracked\}/g, shortNames(untracked))
    .replace(/\$\{added_count\}/g, String(added.length))
    .replace(/\$\{modified_count\}/g, String(modified.length))
    .replace(/\$\{deleted_count\}/g, String(deleted.length))
    .replace(/\$\{total_count\}/g, String(files.length));
}
