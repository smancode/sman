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

// ── Internal Types ──

interface FileCacheEntry {
  file: FileContent | BinaryFileInfo;
  timestamp: number;
}

const MAX_FILE_CACHE = 10;

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

  // Internal: request dedup counter
  _activeLoadId: number;

  // Actions
  openViewer: (workspace: string, filePath: string, lineNumber?: number | null, sessionId?: string | null) => void;
  closeViewer: () => void;
  loadFile: (filePath: string) => void;
  loadDir: (dirPath: string) => Promise<DirEntry[]>;
  searchSymbols: (symbol: string, fileExt?: string) => void;
  clearSearch: () => void;
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

  // Internal
  _activeLoadId: 0,

  openViewer(workspace, filePath, lineNumber = null, sessionId = null) {
    set({
      open: true,
      workspace,
      filePath,
      lineNumber,
      sessionId,
      error: null,
    });
    get().loadFile(filePath);
  },

  closeViewer() {
    set({
      open: false,
      currentFile: null,
      loading: false,
      error: null,
      searchResults: [],
      searching: false,
      searchSymbol: '',
    });
  },

  loadFile(filePath: string) {
    const client = getWsClient();
    if (!client) return;

    const { workspace, fileCache } = get();

    // Check cache first
    const cached = fileCache.get(filePath);
    if (cached) {
      set({
        currentFile: cached.file,
        loading: false,
        error: null,
        filePath,
      });
      return;
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

      console.log('[code-viewer] code.readFile msg:', JSON.stringify(msg, null, 2));

      const result = msg.result as (FileContent | BinaryFileInfo | { error?: string }) | undefined;
      console.log('[code-viewer] extracted result:', result);
      if (!result) {
        set({ loading: false, error: 'No file data received' });
        return;
      }
      if ('error' in result && result.error) {
        set({ loading: false, error: result.error });
        return;
      }
      const file = result as FileContent | BinaryFileInfo;

      // Update LRU cache
      const newCache = new Map(get().fileCache);
      newCache.set(filePath, { file, timestamp: Date.now() });
      // Evict oldest entries if over limit
      while (newCache.size > MAX_FILE_CACHE) {
        const oldest = [...newCache.entries()].sort((a, b) => a[1].timestamp - b[1].timestamp)[0];
        if (oldest) newCache.delete(oldest[0]);
      }

      set({
        currentFile: file,
        loading: false,
        error: null,
        fileCache: newCache,
      });
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

    return new Promise((resolve, reject) => {
      const unsub = wrapHandler(client, 'code.listDir', (msg) => {
        unsub();

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
}));
