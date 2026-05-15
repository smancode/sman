// src/stores/code-viewer.ts

import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

/** Wrap a typed handler to match WsClient's EventHandler signature */
function wrapHandler(
  client: {
    on: (e: string, h: (...a: unknown[]) => void) => void;
    off: (e: string, h: (...a: unknown[]) => void) => void;
  },
  event: string,
  handler: MsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

// ── Exported Types ──

export interface DirEntry {
  name: string;
  type: 'file' | 'directory';
  size?: number;
}

export interface FileContent {
  path: string;
  content: string;
  language: string;
  totalLines: number;
  truncated: boolean;
  totalSize: number;
}

export interface BinaryFileInfo {
  path: string;
  type: 'binary';
  mimeType: string;
  size: number;
  fileName: string;
}

export interface SearchMatch {
  filePath: string;
  line: number;
  lineContent: string;
  context: string;
}

export interface FileSearchResult {
  filePath: string;
  fileName: string;
}

// ── Internal Types ──

interface FileCacheEntry {
  file: FileContent | BinaryFileInfo;
  timestamp: number;
}

const MAX_FILE_CACHE = 10;
const REFRESH_INTERVAL_MS = 5000;
let _refreshTimer: ReturnType<typeof setInterval> | null = null;

function startFileRefresh() {
  stopFileRefresh();
  _refreshTimer = setInterval(() => {
    const state = useCodeViewerStore.getState();
    if (!state.open || !state.filePath || state.dirty || state.saving) return;
    // Silently re-fetch current file without updating loading state
    const client = getWsClient();
    if (!client) return;
    const filePath = state.filePath;
    const loadId = state._activeLoadId;
    const unsub = wrapHandler(client, 'code.readFile', (msg) => {
      if (useCodeViewerStore.getState()._activeLoadId !== loadId) { unsub(); return; }
      unsub();
      const result = msg.result as (FileContent | BinaryFileInfo | { error?: string }) | undefined;
      if (!result || ('error' in result && result.error)) return;
      const file = result as FileContent | BinaryFileInfo;
      // Only update if content actually changed
      const current = useCodeViewerStore.getState().currentFile;
      if (current && 'content' in current && 'content' in file) {
        if ((current as FileContent).content === (file as FileContent).content) return;
      }
      // Don't overwrite if user is editing
      if (useCodeViewerStore.getState().dirty) return;
      const newCache = new Map(useCodeViewerStore.getState().fileCache);
      newCache.set(filePath, { file, timestamp: Date.now() });
      useCodeViewerStore.setState({ currentFile: file, fileCache: newCache });
    });
    client.send({
      type: 'code.readFile',
      workspace: state.workspace,
      filePath,
    });
  }, REFRESH_INTERVAL_MS);
}

function stopFileRefresh() {
  if (_refreshTimer) {
    clearInterval(_refreshTimer);
    _refreshTimer = null;
  }
}

// In-flight loadDir promises to prevent duplicate requests for the same path
const _dirInFlight = new Map<string, Promise<DirEntry[]>>();

// ── Navigation History ──

export interface NavLocation {
  filePath: string;
  line: number | null;
  column: number | null;
}

// ── Store State & Actions ──

interface CodeViewerState {
  // Overlay
  open: boolean;
  workspace: string;
  filePath: string;
  lineNumber: number | null;
  sessionId: string | null;

  // File
  currentFile: FileContent | BinaryFileInfo | null;
  loading: boolean;
  error: string | null;

  // Dir cache
  dirCache: Record<string, DirEntry[]>;

  // File cache (LRU)
  fileCache: Map<string, FileCacheEntry>;

  // Search
  searchResults: SearchMatch[];
  searching: boolean;
  searchSymbol: string;

  // File search (fuzzy by name)
  fileSearchQuery: string;
  fileSearchResults: FileSearchResult[];
  fileSearching: boolean;
  fileSearchSourceOnly: boolean;

  // Navigation history
  navHistory: NavLocation[];
  navIndex: number;

  // Follow file (tree tracks right panel)
  followFile: boolean;

  // Edit
  editable: boolean;
  dirty: boolean;
  saving: boolean;

  // Internal: request dedup counter
  _activeLoadId: number;
  _activeFileSearchId: number;

  // Actions
  openViewer: (workspace: string, filePath: string, lineNumber?: number | null, sessionId?: string | null) => void;
  closeViewer: () => void;
  loadFile: (filePath: string, forceRefresh?: boolean) => void;
  loadDir: (dirPath: string) => Promise<DirEntry[]>;
  searchSymbols: (symbol: string, fileExt?: string) => void;
  clearSearch: () => void;
  searchFiles: (query: string) => void;
  clearFileSearch: () => void;
  setFileSearchSourceOnly: (sourceOnly: boolean) => void;
  setEditable: (editable: boolean) => void;
  markDirty: () => void;
  saveFile: () => void;
  pushNav: (filePath: string, line?: number | null, column?: number | null) => void;
  goBack: () => void;
  goForward: () => void;
  setFollowFile: (follow: boolean) => void;
}

export const useCodeViewerStore = create<CodeViewerState>((set, get) => ({
  // Overlay
  open: false,
  workspace: '',
  filePath: '',
  lineNumber: null,
  sessionId: null,

  // File
  currentFile: null,
  loading: false,
  error: null,

  // Dir cache
  dirCache: {},

  // File cache
  fileCache: new Map<string, FileCacheEntry>(),

  // Search
  searchResults: [],
  searching: false,
  searchSymbol: '',

  // File search
  fileSearchQuery: '',
  fileSearchResults: [],
  fileSearching: false,
  fileSearchSourceOnly: true,

  // Navigation history
  navHistory: [],
  navIndex: -1,

  // Follow file
  followFile: typeof window !== 'undefined' ? localStorage.getItem('sman:codeFollowFile') !== 'false' : true,

  // Edit
  editable: false,
  dirty: false,
  saving: false,

  // Internal
  _activeLoadId: 0,
  _activeFileSearchId: 0,

  openViewer(workspace, filePath, lineNumber = null, sessionId = null) {
    _dirInFlight.clear();
    set({
      open: true,
      workspace,
      filePath,
      lineNumber,
      sessionId,
      error: null,
      dirCache: {},
      fileCache: new Map<string, FileCacheEntry>(),
      navHistory: [],
      navIndex: -1,
    });
    get().loadFile(filePath);

    // Pre-expand file tree directories along the filePath so the file shows as selected
    if (filePath && filePath.includes('/')) {
      const parts = filePath.split('/');
      // e.g. filePath = "src/features/code-viewer/FileTree.tsx"
      // Pre-load: "", "src", "src/features", "src/features/code-viewer"
      const dirsToLoad: string[] = [''];
      let acc = '';
      for (let i = 0; i < parts.length - 1; i++) {
        acc = acc ? `${acc}/${parts[i]}` : parts[i];
        dirsToLoad.push(acc);
      }

      // Load directories sequentially so each level is cached before the next
      const loadDirFn = get().loadDir;
      (async () => {
        try {
          for (const dir of dirsToLoad) {
            await loadDirFn(dir);
          }
        } catch {
          // Silently ignore — tree will still work, just may not auto-expand
        }
      })();
    }
  },

  closeViewer() {
    stopFileRefresh();
    set({
      open: false,
      currentFile: null,
      loading: false,
      error: null,
      searchResults: [],
      searching: false,
      searchSymbol: '',
      navHistory: [],
      navIndex: -1,
    });
  },

  loadFile(filePath: string, forceRefresh = false) {
    const client = getWsClient();
    if (!client) return;

    const { workspace, fileCache } = get();

    // Empty path — just clear the code panel, don't request
    if (!filePath) {
      set({ currentFile: null, loading: false, error: null, filePath: '', dirty: false });
      return;
    }

    // Check cache first (skip if forceRefresh or user has unsaved edits)
    if (!forceRefresh) {
      const cached = fileCache.get(filePath);
      if (cached) {
        set({
          currentFile: cached.file,
          loading: false,
          error: null,
          filePath,
          dirty: false,
        });
        startFileRefresh();
        return;
      }
    }

    const loadId = get()._activeLoadId + 1;
    set({
      loading: true,
      error: null,
      filePath,
      _activeLoadId: loadId,
    });

    const unsub = wrapHandler(client, 'code.readFile', (msg) => {
      if (get()._activeLoadId !== loadId) return;
      unsub();

      const result = msg.result as (FileContent | BinaryFileInfo | { error?: string }) | undefined;
      if (!result) {
        set({ loading: false, error: 'No file data received' });
        return;
      }
      if ('error' in result && result.error) {
        set({ loading: false, error: result.error });
        return;
      }
      const file = result as FileContent | BinaryFileInfo;

      // Resolve filePath to a proper relative path using the backend's resolved absolute path
      const resolvedFilePath = file.path;
      const normWorkspace = workspace.replace(/\\/g, '/').replace(/\/$/, '');
      const normResolved = resolvedFilePath.replace(/\\/g, '/');
      let relativePath = filePath;
      if (normResolved.startsWith(normWorkspace + '/')) {
        relativePath = normResolved.slice(normWorkspace.length + 1);
      }

      // Update LRU cache (key by relative path for consistency)
      const newCache = new Map(get().fileCache);
      newCache.set(relativePath, { file, timestamp: Date.now() });
      // Evict oldest entries if over limit
      while (newCache.size > MAX_FILE_CACHE) {
        const oldest = [...newCache.entries()].sort((a, b) => a[1].timestamp - b[1].timestamp)[0];
        if (oldest) newCache.delete(oldest[0]);
      }

      set({
        currentFile: file,
        loading: false,
        error: null,
        filePath: relativePath,
        fileCache: newCache,
        dirty: false,
      });
      startFileRefresh();
    });

    client.send({
      type: 'code.readFile',
      workspace,
      filePath,
    });
  },

  loadDir(dirPath: string): Promise<DirEntry[]> {
    const client = getWsClient();
    if (!client) return Promise.reject(new Error('WebSocket not connected'));

    const { workspace, dirCache } = get();

    // Check cache
    if (dirCache[dirPath]) {
      return Promise.resolve(dirCache[dirPath]);
    }

    // Check in-flight to prevent duplicate WS requests for the same path
    const inFlight = _dirInFlight.get(dirPath);
    if (inFlight) return inFlight;

    const promise = new Promise<DirEntry[]>((resolve, reject) => {
      const unsub = wrapHandler(client, 'code.listDir', (msg) => {
        unsub();
        _dirInFlight.delete(dirPath);

        if (msg.error) {
          reject(new Error(msg.error as string));
          return;
        }

        const result = msg.result as { entries?: DirEntry[]; error?: string } | undefined;
        if (result?.error) {
          reject(new Error(result.error));
          return;
        }
        const entries = result?.entries || [];
        set({
          dirCache: { ...get().dirCache, [dirPath]: entries },
        });
        resolve(entries);
      });

      client.send({
        type: 'code.listDir',
        workspace,
        dirPath,
      });
    });

    _dirInFlight.set(dirPath, promise);
    return promise;
  },

  searchSymbols(symbol: string, fileExt?: string) {
    const client = getWsClient();
    if (!client) return;

    const { workspace } = get();

    set({ searching: true, searchSymbol: symbol, searchResults: [] });

    const unsub = wrapHandler(client, 'code.searchSymbols', (msg) => {
      unsub();

      if (msg.error) {
        set({ searching: false, error: msg.error as string });
        return;
      }

      const result = msg.result as { matches?: SearchMatch[]; error?: string } | undefined;
      if (result?.error) {
        set({ searching: false, error: result.error });
        return;
      }
      const results = result?.matches || [];
      set({ searchResults: results, searching: false });
    });

    client.send({
      type: 'code.searchSymbols',
      workspace,
      symbol,
      ...(fileExt ? { fileExt } : {}),
    });
  },

  clearSearch() {
    set({
      searchResults: [],
      searching: false,
      searchSymbol: '',
    });
  },

  searchFiles(query: string) {
    const client = getWsClient();
    if (!client) return;

    const { workspace, fileSearchSourceOnly } = get();
    const trimmed = query.trim();

    if (!trimmed) {
      set({ fileSearchQuery: '', fileSearchResults: [], fileSearching: false });
      return;
    }

    const searchId = get()._activeFileSearchId + 1;
    set({ fileSearchQuery: trimmed, fileSearching: true, _activeFileSearchId: searchId });

    const unsub = wrapHandler(client, 'code.searchFiles', (msg) => {
      if (get()._activeFileSearchId !== searchId) return;
      unsub();

      const result = msg.result as (FileSearchResult[] | { error?: string }) | undefined;
      if (result && 'error' in result) {
        set({ fileSearching: false });
        return;
      }
      set({ fileSearchResults: Array.isArray(result) ? result : [], fileSearching: false });
    });

    client.send({ type: 'code.searchFiles', workspace, query: trimmed, sourceOnly: fileSearchSourceOnly });
  },

  clearFileSearch() {
    set({ fileSearchQuery: '', fileSearchResults: [], fileSearching: false });
  },

  setFileSearchSourceOnly(sourceOnly: boolean) {
    set({ fileSearchSourceOnly: sourceOnly });
  },

  setEditable(editable: boolean) {
    set({ editable });
  },

  markDirty() {
    set({ dirty: true });
  },

  saveFile() {
    const client = getWsClient();
    if (!client) return;

    const { workspace, filePath, currentFile } = get();
    if (!currentFile || !('content' in currentFile)) return;

    // Use the resolved absolute path from the backend (file.path) instead of
    // the original filePath which may be a short name requiring fuzzy search
    const resolvedPath = currentFile.path;

    set({ saving: true });

    const unsub = wrapHandler(client, 'code.saveFile', (msg) => {
      unsub();
      const result = msg.result as { success?: boolean; error?: string } | undefined;
      if (result?.error) {
        set({ saving: false, error: result.error });
        return;
      }
      // Update cache with new content
      const newCache = new Map(get().fileCache);
      newCache.set(filePath, { file: currentFile, timestamp: Date.now() });
      set({ saving: false, dirty: false, fileCache: newCache });
    });

    client.send({
      type: 'code.saveFile',
      workspace,
      filePath: resolvedPath,
      content: (currentFile as FileContent).content,
    });
  },

  pushNav(filePath: string, line?: number | null, column?: number | null) {
    const { navHistory, navIndex } = get();
    const location: NavLocation = { filePath, line: line ?? null, column: column ?? null };

    const newHistory = navHistory.slice(0, navIndex + 1);
    newHistory.push(location);

    set({ navHistory: newHistory, navIndex: newHistory.length - 1 });
  },

  goBack() {
    const { navIndex, navHistory } = get();
    if (navIndex <= 0) return;
    const newIndex = navIndex - 1;
    const loc = navHistory[newIndex];
    set({ navIndex: newIndex, lineNumber: loc.line });
    get().loadFile(loc.filePath);
  },

  goForward() {
    const { navIndex, navHistory } = get();
    if (navIndex >= navHistory.length - 1) return;
    const newIndex = navIndex + 1;
    const loc = navHistory[newIndex];
    set({ navIndex: newIndex, lineNumber: loc.line });
    get().loadFile(loc.filePath);
  },

  setFollowFile(follow: boolean) {
    localStorage.setItem('sman:codeFollowFile', String(follow));
    set({ followFile: follow });
  },
}));
