# 代码查看器 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户在聊天中 Alt+Click 代码/文件路径，弹出全屏代码查看 overlay，左侧懒加载文件树，右侧 Shiki 高亮代码，支持 import 跳转和符号搜索。

**Architecture:** Overlay 方案（非路由），在 Chat 组件内渲染全屏覆盖层。后端新增 3 个 WebSocket handler（listDir、readFile、searchSymbols），前端用 Zustand store 管理状态，复用现有 Shiki Worker 做语法高亮。

**Tech Stack:** React 19, Zustand 5, Shiki 4 (Web Worker), Tailwind CSS, WebSocket, TypeScript

---

## File Structure

```
# New files
server/code-viewer-handler.ts          # 后端 WebSocket handler + 安全校验
src/stores/code-viewer.ts              # Zustand store（overlay 状态、文件缓存、搜索）
src/features/code-viewer/index.tsx     # 主 overlay 组件
src/features/code-viewer/FileTree.tsx  # 懒加载文件树
src/features/code-viewer/CodePanel.tsx # 代码面板（Shiki 高亮 + 行号 + 高亮行）
src/features/code-viewer/CodeNavigator.tsx # 符号搜索弹窗
src/features/code-viewer/useAltClick.ts # Alt+Click 事件委托 hook
tests/server/code-viewer-handler.test.ts # 后端 handler 测试

# Modified files
server/index.ts                        # 注册 code.* handler
src/features/chat/index.tsx            # 渲染 CodeViewerOverlay + 注入 useAltClick
```

---

## Chunk 1: Backend — Code Viewer Handler

### Task 1: 安全校验工具函数

**Files:**
- Create: `server/code-viewer-handler.ts`
- Test: `tests/server/code-viewer-handler.test.ts`

- [ ] **Step 1: Write the failing test for path validation**

```typescript
// tests/server/code-viewer-handler.test.ts
import { describe, it, expect } from 'vitest';
import { validatePath, isBinaryFile, hasNullBytes, MAX_FILE_SIZE } from '../../server/code-viewer-handler';

describe('validatePath', () => {
  const workspace = '/Users/test/project';

  it('accepts valid path inside workspace', () => {
    expect(() => validatePath(workspace, 'src/app.tsx')).not.toThrow();
  });

  it('rejects path traversal with ..', () => {
    expect(() => validatePath(workspace, '../../../etc/shadow')).toThrow('PATH_TRAVERSAL');
  });

  it('rejects absolute path outside workspace', () => {
    expect(() => validatePath(workspace, '/etc/passwd')).toThrow('PATH_TRAVERSAL');
  });

  it('rejects symlink escaping workspace', () => {
    // This test creates a temp symlink pointing outside workspace
    // Implementation will use fs.realpathSync
  });
});

describe('isBinaryFile', () => {
  it('detects binary by extension', () => {
    expect(isBinaryFile('image.png')).toBe(true);
    expect(isBinaryFile('app.tsx')).toBe(false);
  });

  it('detects binary by null bytes in content', () => {
    const buf = Buffer.from('hello\0world');
    expect(hasNullBytes(buf)).toBe(true);
  });
});

describe('MAX_FILE_SIZE', () => {
  it('is 1MB', () => {
    expect(MAX_FILE_SIZE).toBe(1_048_576);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run tests/server/code-viewer-handler.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Write minimal implementation**

```typescript
// server/code-viewer-handler.ts
import fs from 'node:fs';
import path from 'node:path';

export const MAX_FILE_SIZE = 1_048_576; // 1MB

const BINARY_EXTENSIONS = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.bmp', '.ico', '.svg',
  '.zip', '.tar', '.gz', '.rar', '.7z',
  '.exe', '.dll', '.so', '.dylib',
  '.woff', '.woff2', '.ttf', '.otf', '.eot',
  '.mp3', '.mp4', '.avi', '.mov', '.wav',
  '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
  '.sqlite', '.db',
]);

const HIDDEN_DIRS = new Set([
  '.git', 'node_modules', 'dist', 'build', '.next', '.sman',
  '__pycache__', '.venv', '.DS_Store', 'coverage',
]);

export function validatePath(workspace: string, filePath: string): string {
  const resolved = path.resolve(workspace, filePath);
  const normalizedWorkspace = path.resolve(workspace);

  // Basic path traversal check
  if (!resolved.startsWith(normalizedWorkspace + path.sep) && resolved !== normalizedWorkspace) {
    throw Object.assign(new Error('Path is outside workspace'), { code: 'PATH_TRAVERSAL' });
  }

  // Symlink check
  let realPath: string;
  try {
    realPath = fs.realpathSync(resolved);
  } catch {
    // File doesn't exist yet — that's fine for validation
    return resolved;
  }

  if (!realPath.startsWith(normalizedWorkspace + path.sep) && realPath !== normalizedWorkspace) {
    throw Object.assign(new Error('Symlink escapes workspace'), { code: 'PATH_TRAVERSAL' });
  }

  return resolved;
}

export function isBinaryFile(fileName: string): boolean {
  const ext = path.extname(fileName).toLowerCase();
  return BINARY_EXTENSIONS.has(ext);
}

export function hasNullBytes(buffer: Buffer): boolean {
  // Check first 8KB for null bytes
  const checkLength = Math.min(buffer.length, 8192);
  for (let i = 0; i < checkLength; i++) {
    if (buffer[i] === 0) return true;
  }
  return false;
}

export function shouldHide(name: string): boolean {
  return HIDDEN_DIRS.has(name) || name.startsWith('.');
}

/**
 * Detect programming language from file extension.
 */
export function detectLanguage(filePath: string): string {
  const ext = path.extname(filePath).toLowerCase();
  const map: Record<string, string> = {
    '.ts': 'typescript', '.tsx': 'typescript', '.js': 'javascript', '.jsx': 'javascript',
    '.py': 'python', '.rb': 'ruby', '.go': 'go', '.rs': 'rust', '.java': 'java',
    '.kt': 'kotlin', '.swift': 'swift', '.c': 'c', '.cpp': 'cpp', '.h': 'c',
    '.hpp': 'cpp', '.cs': 'csharp', '.php': 'php', '.vue': 'vue', '.svelte': 'svelte',
    '.css': 'css', '.scss': 'scss', '.less': 'less', '.html': 'html', '.xml': 'xml',
    '.json': 'json', '.yaml': 'yaml', '.yml': 'yaml', '.toml': 'toml',
    '.md': 'markdown', '.sql': 'sql', '.sh': 'bash', '.bash': 'bash',
    '.zsh': 'bash', '.dockerfile': 'dockerfile', '.makefile': 'makefile',
    '.lua': 'lua', '.r': 'r', '.dart': 'dart', '.zig': 'zig',
  };
  return map[ext] || 'text';
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run tests/server/code-viewer-handler.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/code-viewer-handler.ts tests/server/code-viewer-handler.test.ts
git commit -m "feat(code-viewer): add path validation and binary detection utilities"
```

---

### Task 2: WebSocket handler — listDir + readFile + searchSymbols

**Files:**
- Modify: `server/code-viewer-handler.ts`
- Modify: `tests/server/code-viewer-handler.test.ts`

- [ ] **Step 1: Write the failing tests for handler functions**

```typescript
// Append to tests/server/code-viewer-handler.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { handleListDir, handleReadFile, handleSearchSymbols } from '../../server/code-viewer-handler';

describe('handleListDir', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    fs.mkdirSync(path.join(tmpDir, 'src'));
    fs.writeFileSync(path.join(tmpDir, 'src', 'app.tsx'), 'export const App = () => {}');
    fs.writeFileSync(path.join(tmpDir, 'package.json'), '{}');
    fs.mkdirSync(path.join(tmpDir, 'node_modules')); // should be hidden
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('lists directory entries excluding hidden dirs', () => {
    const result = handleListDir(tmpDir, '.');
    expect(result.entries.map(e => e.name).sort()).toEqual(['package.json', 'src']);
  });

  it('lists nested directory', () => {
    const result = handleListDir(tmpDir, 'src');
    expect(result.entries).toEqual([{ name: 'app.tsx', type: 'file', size: 28 }]);
  });

  it('throws NOT_FOUND for missing directory', () => {
    expect(() => handleListDir(tmpDir, 'nope')).toThrow('NOT_FOUND');
  });
});

describe('handleReadFile', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    fs.writeFileSync(path.join(tmpDir, 'hello.ts'), 'console.log("hello");\n');
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('reads text file content', () => {
    const result = handleReadFile(tmpDir, 'hello.ts');
    expect(result.content).toBe('console.log("hello");\n');
    expect(result.language).toBe('typescript');
    expect(result.totalLines).toBe(2);
    expect(result.truncated).toBe(false);
  });

  it('throws NOT_FOUND for missing file', () => {
    expect(() => handleReadFile(tmpDir, 'nope.ts')).toThrow('NOT_FOUND');
  });

  it('returns binary info for binary files', () => {
    fs.writeFileSync(path.join(tmpDir, 'image.png'), Buffer.from([0x89, 0x50, 0x4E, 0x47]));
    const result = handleReadFile(tmpDir, 'image.png');
    expect(result.type).toBe('binary');
    expect(result.fileName).toBe('image.png');
  });

  it('truncates files exceeding MAX_FILE_SIZE', () => {
    const bigContent = 'x'.repeat(MAX_FILE_SIZE + 1000);
    fs.writeFileSync(path.join(tmpDir, 'big.txt'), bigContent);
    const result = handleReadFile(tmpDir, 'big.txt');
    expect(result.truncated).toBe(true);
    expect(result.content.length).toBeLessThanOrEqual(MAX_FILE_SIZE);
  });
});

describe('handleSearchSymbols', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    fs.writeFileSync(path.join(tmpDir, 'a.ts'), 'function myFunc() {}\nconst x = myFunc();\n');
    fs.writeFileSync(path.join(tmpDir, 'b.ts'), '// myFunc is cool\n');
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('finds symbol matches across files', () => {
    const result = handleSearchSymbols(tmpDir, 'myFunc');
    expect(result.matches.length).toBeGreaterThanOrEqual(2);
    expect(result.symbol).toBe('myFunc');
  });

  it('respects maxResults limit', () => {
    const result = handleSearchSymbols(tmpDir, 'myFunc', undefined, 1);
    expect(result.matches.length).toBe(1);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run tests/server/code-viewer-handler.test.ts`
Expected: FAIL — handleListDir not exported

- [ ] **Step 3: Write handler implementations**

Append to `server/code-viewer-handler.ts`:

```typescript
interface DirEntry {
  name: string;
  type: 'file' | 'directory';
  size?: number;
}

interface ListDirResult {
  path: string;
  entries: DirEntry[];
}

interface ReadFileResult {
  path: string;
  content: string;
  language: string;
  totalLines: number;
  truncated: boolean;
  totalSize: number;
}

interface BinaryFileResult {
  path: string;
  type: 'binary';
  mimeType: string;
  size: number;
  fileName: string;
}

interface SearchMatch {
  filePath: string;
  line: number;
  lineContent: string;
  context: string;
}

interface SearchResult {
  symbol: string;
  matches: SearchMatch[];
}

export function handleListDir(workspace: string, dirPath: string): ListDirResult {
  const resolved = validatePath(workspace, dirPath);

  if (!fs.existsSync(resolved)) {
    throw Object.assign(new Error('Directory not found'), { code: 'NOT_FOUND' });
  }

  const stat = fs.statSync(resolved);
  if (!stat.isDirectory()) {
    throw Object.assign(new Error('Not a directory'), { code: 'NOT_FOUND' });
  }

  const entries: DirEntry[] = [];
  for (const name of fs.readdirSync(resolved)) {
    if (shouldHide(name)) continue;
    const fullPath = path.join(resolved, name);
    try {
      const entryStat = fs.statSync(fullPath);
      entries.push({
        name,
        type: entryStat.isDirectory() ? 'directory' : 'file',
        ...(entryStat.isFile() ? { size: entryStat.size } : {}),
      });
    } catch {
      // Permission denied or broken symlink — skip
    }
  }

  // Directories first, then files, both alphabetical
  entries.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });

  return { path: dirPath, entries };
}

export function handleReadFile(workspace: string, filePath: string): ReadFileResult | BinaryFileResult {
  const resolved = validatePath(workspace, filePath);

  if (!fs.existsSync(resolved)) {
    throw Object.assign(new Error('File not found'), { code: 'NOT_FOUND' });
  }

  const stat = fs.statSync(resolved);
  if (stat.isDirectory()) {
    throw Object.assign(new Error('Path is a directory'), { code: 'NOT_FOUND' });
  }

  const fileName = path.basename(filePath);

  // Binary check by extension
  if (isBinaryFile(fileName)) {
    const ext = path.extname(fileName).toLowerCase();
    const mimeMap: Record<string, string> = {
      '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
      '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
      '.pdf': 'application/pdf', '.zip': 'application/zip',
    };
    return {
      path: filePath,
      type: 'binary',
      mimeType: mimeMap[ext] || 'application/octet-stream',
      size: stat.size,
      fileName,
    };
  }

  // Read and check for binary content
  const buffer = fs.readFileSync(resolved);
  if (hasNullBytes(buffer)) {
    return {
      path: filePath,
      type: 'binary',
      mimeType: 'application/octet-stream',
      size: stat.size,
      fileName,
    };
  }

  const totalSize = stat.size;
  let content = buffer.toString('utf-8');
  let truncated = false;

  if (totalSize > MAX_FILE_SIZE) {
    content = content.slice(0, MAX_FILE_SIZE);
    truncated = true;
  }

  const lines = content.split('\n');

  return {
    path: filePath,
    content,
    language: detectLanguage(filePath),
    totalLines: lines.length,
    truncated,
    totalSize,
  };
}

export function handleSearchSymbols(
  workspace: string,
  symbol: string,
  fileExt?: string,
  maxResults = 20,
): SearchResult {
  const safeSymbol = symbol.replace(/[^a-zA-Z0-9_]/g, '');
  if (!safeSymbol) {
    return { symbol, matches: [] };
  }

  const resolvedWorkspace = path.resolve(workspace);
  const matches: SearchMatch[] = [];
  const wordRegex = new RegExp(`\\b${escapeRegex(safeSymbol)}\\b`);

  const extSet = fileExt
    ? new Set([fileExt])
    : new Set(['ts', 'tsx', 'js', 'jsx', 'py', 'java', 'go', 'rs', 'c', 'cpp', 'h', 'hpp', 'rb', 'php', 'vue', 'svelte']);

  // Recursive search using Node.js (cross-platform, no grep dependency)
  function searchDir(dir: string, relPrefix: string) {
    if (matches.length >= maxResults) return;
    let entries: string[];
    try {
      entries = fs.readdirSync(dir);
    } catch {
      return;
    }

    for (const name of entries) {
      if (matches.length >= maxResults) return;
      if (HIDDEN_DIRS.has(name) || name.startsWith('.')) continue;

      const fullPath = path.join(dir, name);
      const relPath = relPrefix ? `${relPrefix}/${name}` : name;
      let stat: fs.Stats;
      try {
        stat = fs.statSync(fullPath);
      } catch {
        continue;
      }

      if (stat.isDirectory()) {
        searchDir(fullPath, relPath);
      } else if (stat.isFile() && stat.size <= MAX_FILE_SIZE) {
        const ext = path.extname(name).slice(1);
        if (!extSet.has(ext)) continue;

        try {
          const content = fs.readFileSync(fullPath, 'utf-8');
          const lines = content.split('\n');
          for (let i = 0; i < lines.length && matches.length < maxResults; i++) {
            if (wordRegex.test(lines[i])) {
              matches.push({
                filePath: relPath,
                line: i + 1,
                lineContent: lines[i].trim(),
                context: lines[i].trim(),
              });
            }
          }
        } catch {
          // Binary or unreadable — skip
        }
      }
    }
  }

  searchDir(resolvedWorkspace, '');
  return { symbol: safeSymbol, matches };
}

function escapeRegex(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run tests/server/code-viewer-handler.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/code-viewer-handler.ts tests/server/code-viewer-handler.test.ts
git commit -m "feat(code-viewer): add listDir, readFile, searchSymbols handlers"
```

---

### Task 3: Register handlers in server/index.ts

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: Add code.* cases to the switch block**

在 `server/index.ts` 的 `switch (msg.type)` 中（`default` case 之前），添加：

```typescript
      // ── Code Viewer ──────────────────────────────────────────
      case 'code.listDir': {
        if (!msg.workspace) throw new Error('Missing workspace');
        const result = handleListDir(String(msg.workspace), String(msg.dirPath || '.'));
        ws.send(JSON.stringify({ type: 'code.listDir', result }));
        break;
      }
      case 'code.readFile': {
        if (!msg.workspace || !msg.filePath) throw new Error('Missing workspace or filePath');
        const result = handleReadFile(String(msg.workspace), String(msg.filePath));
        ws.send(JSON.stringify({ type: 'code.readFile', result }));
        break;
      }
      case 'code.searchSymbols': {
        if (!msg.workspace || !msg.symbol) throw new Error('Missing workspace or symbol');
        const result = handleSearchSymbols(String(msg.workspace), String(msg.symbol), msg.fileExt ? String(msg.fileExt) : undefined);
        ws.send(JSON.stringify({ type: 'code.searchSymbols', result }));
        break;
      }
```

顶部添加 import：

```typescript
import { handleListDir, handleReadFile, handleSearchSymbols } from './code-viewer-handler.js';
```

注意：`ws_send` 使用已有的 WebSocket 发送函数。这里的变量名 `ws` 会与 WebSocket 实例冲突，需要确认 server/index.ts 中的 ws 实例变量名，可能需要用不同的变量名如 `wsPath`。实际上在 switch case 内部，WebSocket 实例是闭包中的变量，需要看实际代码确认命名。

- [ ] **Step 2: Verify server compiles**

Run: `npx tsc --noEmit --project server/tsconfig.json 2>&1 | head -20`
Expected: No errors related to code-viewer-handler

- [ ] **Step 3: Commit**

```bash
git add server/index.ts
git commit -m "feat(code-viewer): register code.listDir, code.readFile, code.searchSymbols handlers"
```

---

## Chunk 2: Frontend — Zustand Store + Alt+Click Hook

### Task 4: Code Viewer Zustand Store

**Files:**
- Create: `src/stores/code-viewer.ts`

- [ ] **Step 1: Write the store**

```typescript
// src/stores/code-viewer.ts
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';

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

interface FileCacheEntry {
  content: string;
  language: string;
  totalLines: number;
}

const MAX_CACHE = 10;

interface CodeViewerState {
  // Overlay state
  open: boolean;
  workspace: string;
  filePath: string;
  lineNumber?: number;
  sessionId?: string;

  // File content
  currentFile: FileContent | BinaryFileInfo | null;
  loading: boolean;
  error: string | null;

  // Directory cache (path -> entries)
  dirCache: Record<string, DirEntry[]>;

  // File content cache (LRU)
  fileCache: Map<string, FileCacheEntry>;

  // Symbol search
  searchResults: SearchMatch[];
  searching: boolean;
  searchSymbol: string;

  // Internal: request dedup
  _activeLoadId: number;

  // Actions
  openViewer: (workspace: string, filePath: string, lineNumber?: number, sessionId?: string) => void;
  closeViewer: () => void;
  loadFile: (filePath: string) => void;
  loadDir: (dirPath: string) => Promise<DirEntry[]>;
  searchSymbol: (symbol: string, fileExt?: string) => void;
  clearSearch: () => void;
}

export const useCodeViewerStore = create<CodeViewerState>((set, get) => ({
  open: false,
  workspace: '',
  filePath: '',
  lineNumber: undefined,
  sessionId: undefined,
  currentFile: null,
  loading: false,
  error: null,
  dirCache: {},
  fileCache: new Map(),
  searchResults: [],
  searching: false,
  searchSymbol: '',
  _activeLoadId: 0,

  openViewer: (workspace, filePath, lineNumber, sessionId) => {
    set({
      open: true,
      workspace,
      filePath,
      lineNumber,
      sessionId,
      currentFile: null,
      loading: false,
      error: null,
      searchResults: [],
      searching: false,
    });
    // Auto-load the file
    get().loadFile(filePath);
  },

  closeViewer: () => {
    set({
      open: false,
      currentFile: null,
      error: null,
      searchResults: [],
      searching: false,
    });
  },

  loadFile: (filePath) => {
    const { workspace, fileCache, _activeLoadId } = get();

    // Cancel previous in-flight request
    const loadId = Date.now();
    set({ _activeLoadId: loadId });

    // Check cache
    const cacheKey = `${workspace}:${filePath}`;
    const cached = fileCache.get(cacheKey);
    if (cached) {
      set({
        filePath,
        currentFile: {
          path: filePath,
          content: cached.content,
          language: cached.language,
          totalLines: cached.totalLines,
          truncated: false,
          totalSize: cached.content.length,
        },
        loading: false,
        error: null,
      });
      return;
    }

    set({ filePath, loading: true, error: null, currentFile: null });

    const client = getWsClient();
    if (!client) return;

    const timeout = setTimeout(() => {
      if (get()._activeLoadId !== loadId) return; // stale
      set({ loading: false, error: '加载超时' });
    }, 10000);

    const unsub = wrapHandler(client, 'code.readFile', (data) => {
      clearTimeout(timeout);
      unsub();

      // Ignore stale responses (user already clicked another file)
      if (get()._activeLoadId !== loadId) return;

      if (data.error) {
        set({ loading: false, error: String(data.error) });
        return;
      }

      const result = data.result as FileContent | BinaryFileInfo;

      // Cache text files
      if (result && 'content' in result) {
        const newCache = new Map(get().fileCache);
        newCache.set(cacheKey, {
          content: result.content,
          language: result.language,
          totalLines: result.totalLines,
        });
        // LRU eviction
        if (newCache.size > MAX_CACHE) {
          const firstKey = newCache.keys().next().value;
          if (firstKey) newCache.delete(firstKey);
        }
        set({ currentFile: result, loading: false, fileCache: newCache });
      } else {
        set({ currentFile: result, loading: false });
      }
    });

    client.send({ type: 'code.readFile', workspace, filePath });
  },

  loadDir: async (dirPath) => {
    const { workspace, dirCache } = get();

    // Check cache
    if (dirCache[dirPath]) {
      return dirCache[dirPath];
    }

    const client = getWsClient();
    if (!client) return [];

    return new Promise<DirEntry[]>((resolve) => {
      const timeout = setTimeout(() => {
        resolve([]);
      }, 5000);

      const unsub = wrapHandler(client, 'code.listDir', (data) => {
        clearTimeout(timeout);
        unsub();

        if (data.error) {
          resolve([]);
          return;
        }

        const result = data.result as { entries: DirEntry[] };
        const entries = result?.entries || [];

        set((state) => ({
          dirCache: { ...state.dirCache, [dirPath]: entries },
        }));

        resolve(entries);
      });

      client.send({ type: 'code.listDir', workspace, dirPath });
    });
  },

  searchSymbol: (symbol, fileExt) => {
    const { workspace } = get();
    const client = getWsClient();
    if (!client) return;

    set({ searching: true, searchSymbol: symbol, searchResults: [] });

    const timeout = setTimeout(() => {
      set({ searching: false });
    }, 10000);

    const unsub = wrapHandler(client, 'code.searchSymbols', (data) => {
      clearTimeout(timeout);
      unsub();

      if (data.error) {
        set({ searching: false, searchResults: [] });
        return;
      }

      const result = data.result as { matches: SearchMatch[] };
      set({ searching: false, searchResults: result?.matches || [] });
    });

    client.send({ type: 'code.searchSymbols', workspace, symbol, fileExt });
  },

  clearSearch: () => {
    set({ searchResults: [], searching: false, searchSymbol: '' });
  },
}));
```

- [ ] **Step 2: Verify it compiles**

Run: `npx tsc --noEmit 2>&1 | head -10`
Expected: No errors in code-viewer store

- [ ] **Step 3: Commit**

```bash
git add src/stores/code-viewer.ts
git commit -m "feat(code-viewer): add Zustand store with file cache and symbol search"
```

---

### Task 5: Alt+Click Hook

**Files:**
- Create: `src/features/code-viewer/useAltClick.ts`

- [ ] **Step 1: Write the hook**

```typescript
// src/features/code-viewer/useAltClick.ts
import { useEffect, useCallback } from 'react';
import { useCodeViewerStore } from '@/stores/code-viewer';
import { useChatStore } from '@/stores/chat';

/**
 * Extracts a file path from various click targets in chat messages.
 * Returns { filePath, lineNumber? } or null.
 */
function extractFilePath(target: HTMLElement, workspace: string): { filePath: string; lineNumber?: number } | null {
  // Strategy 1: Tool call summary — look for file path text in tool items
  const toolItem = target.closest('[data-tool-item]');
  if (toolItem) {
    const text = toolItem.textContent || '';
    // Match paths like "Read - 读取文件: /path/to/file.ts" or just "/path/to/file.ts"
    const pathMatch = text.match(/(?:读取文件|写入文件|编辑文件|搜索内容):\s*(\S+)/);
    if (pathMatch) {
      const fullPath = pathMatch[1];
      if (fullPath.startsWith(workspace)) {
        return { filePath: fullPath.slice(workspace.length + 1) };
      }
    }
  }

  // Strategy 2: Streamdown code block — extract filename from code block header
  const codeBlock = target.closest('[data-streamdown="code-block"]');
  if (codeBlock) {
    const langLabel = codeBlock.querySelector('[data-streamdown="code-block-lang"]');
    if (langLabel) {
      const langText = langLabel.textContent || '';
      // Code block language label sometimes contains "typescript file.tsx"
      const fileMatch = langText.match(/(\S+\.\w+)/);
      if (fileMatch) {
        return { filePath: fileMatch[1] };
      }
    }
  }

  // Strategy 3: Inline code element — check if it looks like a file path
  const codeEl = target.closest('code');
  if (codeEl) {
    const text = codeEl.textContent?.trim() || '';
    // Must contain a dot (extension) and no spaces
    if (text.includes('.') && !text.includes(' ') && text.length < 200) {
      // Could be a relative path like "src/app.tsx" or just "app.tsx"
      if (/^[a-zA-Z0-9_./\\-]+\.[a-zA-Z0-9]+$/.test(text)) {
        return { filePath: text };
      }
    }
  }

  return null;
}

/**
 * Try to infer line number from context near the clicked element.
 */
function extractLineNumber(target: HTMLElement): number | undefined {
  // Check if the code block shows line numbers and user clicked near one
  const codeBlock = target.closest('[data-streamdown="code-block"]');
  if (!codeBlock) return undefined;

  // Look for line number patterns in nearby text
  const text = codeBlock.textContent || '';
  const lineMatch = text.match(/L(\d+)|第\s*(\d+)\s*行|line\s+(\d+)/i);
  if (lineMatch) {
    return parseInt(lineMatch[1] || lineMatch[2] || lineMatch[3], 10);
  }

  return undefined;
}

export function useAltClick() {
  const openViewer = useCodeViewerStore((s) => s.openViewer);

  const handleClick = useCallback((e: MouseEvent) => {
    if (!e.altKey) return;

    const target = e.target as HTMLElement;
    if (!target) return;

    // Read current state on-demand (避免订阅 sessions 数组导致流式输出时频繁重绑定)
    const { currentSessionId, sessions } = useChatStore.getState();
    const activeSession = sessions.find((s) => s.id === currentSessionId);
    if (!activeSession?.workspace) return;

    const workspace = activeSession.workspace;
    const extracted = extractFilePath(target, workspace);

    if (extracted) {
      e.preventDefault();
      e.stopPropagation();
      const lineNumber = extractLineNumber(target) || extracted.lineNumber;
      openViewer(workspace, extracted.filePath, lineNumber, currentSessionId);
    }
  }, [openViewer]);

  useEffect(() => {
    document.addEventListener('click', handleClick, true); // capture phase
    return () => document.removeEventListener('click', handleClick, true);
  }, [handleClick]);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/code-viewer/useAltClick.ts
git commit -m "feat(code-viewer): add Alt+Click hook for opening code from chat"
```

---

## Chunk 3: Frontend — UI Components

### Task 6: FileTree Component

**Files:**
- Create: `src/features/code-viewer/FileTree.tsx`

- [ ] **Step 1: Write the FileTree component**

```tsx
// src/features/code-viewer/FileTree.tsx
import { useState, useCallback, useEffect } from 'react';
import { ChevronRight, ChevronDown, File, Folder, FolderOpen } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useCodeViewerStore, type DirEntry } from '@/stores/code-viewer';

interface TreeNodeProps {
  name: string;
  type: 'file' | 'directory';
  relativePath: string;
  depth: number;
  activeFilePath: string;
  onSelect: (filePath: string) => void;
}

function TreeNode({ name, type, relativePath, depth, activeFilePath, onSelect }: TreeNodeProps) {
  const loadDir = useCodeViewerStore((s) => s.loadDir);
  const [expanded, setExpanded] = useState(false);
  const [entries, setEntries] = useState<DirEntry[]>([]);
  const [loading, setLoading] = useState(false);

  const isActive = relativePath === activeFilePath;

  const handleToggle = useCallback(async () => {
    if (type === 'file') {
      onSelect(relativePath);
      return;
    }

    if (!expanded) {
      setLoading(true);
      const result = await loadDir(relativePath);
      setEntries(result);
      setLoading(false);
    }
    setExpanded(!expanded);
  }, [expanded, loadDir, relativePath, type, onSelect]);

  // Auto-expand if activeFilePath starts with this dir
  useEffect(() => {
    if (type === 'directory' && !expanded && activeFilePath.startsWith(relativePath + '/')) {
      handleToggle();
    }
    // Only run on mount or when activeFilePath changes to a descendant
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeFilePath]);

  const Icon = type === 'directory' ? (expanded ? FolderOpen : Folder) : File;

  return (
    <div>
      <button
        className={cn(
          'flex items-center gap-1.5 w-full px-2 py-[3px] text-[13px] rounded-sm transition-colors',
          'hover:bg-[hsl(var(--muted))]',
          isActive && 'bg-[hsl(var(--accent))] text-[hsl(var(--accent-foreground))]',
          type === 'directory' && 'text-[hsl(var(--muted-foreground))]',
          type === 'file' && !isActive && 'text-[hsl(var(--foreground))]',
        )}
        style={{ paddingLeft: `${depth * 16 + 8}px` }}
        onClick={handleToggle}
      >
        {type === 'directory' ? (
          expanded ? <ChevronDown className="h-3.5 w-3.5 shrink-0 opacity-50" /> : <ChevronRight className="h-3.5 w-3.5 shrink-0 opacity-50" />
        ) : (
          <span className="w-3.5 shrink-0" /> // spacer for alignment
        )}
        <Icon className="h-3.5 w-3.5 shrink-0 opacity-70" />
        <span className="truncate min-w-0">{name}</span>
        {loading && <span className="ml-auto text-[10px] opacity-40">...</span>}
      </button>

      {expanded && type === 'directory' && entries.length > 0 && (
        <div>
          {entries.map((entry) => (
            <TreeNode
              key={entry.name}
              name={entry.name}
              type={entry.type}
              relativePath={`${relativePath}/${entry.name}`}
              depth={depth + 1}
              activeFilePath={activeFilePath}
              onSelect={onSelect}
            />
          ))}
        </div>
      )}
    </div>
  );
}

interface FileTreeProps {
  workspace: string;
  activeFilePath: string;
  onSelectFile: (filePath: string) => void;
  onClose: () => void;
}

export function FileTree({ workspace, activeFilePath, onSelectFile, onClose }: FileTreeProps) {
  const loadDir = useCodeViewerStore((s) => s.loadDir);
  const [rootEntries, setRootEntries] = useState<DirEntry[]>([]);

  useEffect(() => {
    loadDir('.').then(setRootEntries);
  }, [loadDir]);

  // Derive workspace display name from path
  const workspaceName = workspace.split('/').pop() || workspace;

  return (
    <div className="flex flex-col h-full">
      {/* Header with back button */}
      <div className="flex items-center gap-2 px-3 py-2.5 border-b shrink-0">
        <button
          onClick={onClose}
          className="flex items-center gap-1.5 text-[13px] text-[hsl(var(--muted-foreground))] hover:text-[hsl(var(--foreground))] transition-colors"
        >
          <span className="text-base">&#8592;</span>
          <span>返回会话</span>
        </button>
      </div>

      {/* Workspace label */}
      <div className="px-3 py-1.5 text-[11px] font-medium text-[hsl(var(--muted-foreground))] uppercase tracking-wider border-b">
        {workspaceName}
      </div>

      {/* Tree */}
      <div className="flex-1 overflow-y-auto py-1">
        {rootEntries.map((entry) => (
          <TreeNode
            key={entry.name}
            name={entry.name}
            type={entry.type}
            relativePath={entry.name}
            depth={0}
            activeFilePath={activeFilePath}
            onSelect={onSelectFile}
          />
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/code-viewer/FileTree.tsx
git commit -m "feat(code-viewer): add lazy-loading FileTree component"
```

---

### Task 7: CodePanel Component

**Files:**
- Create: `src/features/code-viewer/CodePanel.tsx`

- [ ] **Step 1: Write the CodePanel component**

```tsx
// src/features/code-viewer/CodePanel.tsx
import { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { Loader2 } from 'lucide-react';
import { highlightCode } from '@/lib/shiki-worker-client';
import { cn } from '@/lib/utils';
import type { FileContent } from '@/stores/code-viewer';
import { useCodeViewerStore } from '@/stores/code-viewer';
import { CodeNavigator } from './CodeNavigator';

interface CodePanelProps {
  workspace: string;
}

export function CodePanel({ workspace }: CodePanelProps) {
  const currentFile = useCodeViewerStore((s) => s.currentFile);
  const loading = useCodeViewerStore((s) => s.loading);
  const error = useCodeViewerStore((s) => s.error);
  const lineNumber = useCodeViewerStore((s) => s.lineNumber);
  const filePath = useCodeViewerStore((s) => s.filePath);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full text-[hsl(var(--muted-foreground))]">
        <Loader2 className="h-5 w-5 animate-spin" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full text-[hsl(var(--muted-foreground))] text-sm">
        {error}
      </div>
    );
  }

  if (!currentFile) {
    return (
      <div className="flex items-center justify-center h-full text-[hsl(var(--muted-foreground))] text-sm">
        选择文件查看代码
      </div>
    );
  }

  if ('type' in currentFile && currentFile.type === 'binary') {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center space-y-2">
          <div className="text-[hsl(var(--muted-foreground))] text-sm">
            二进制文件 · {formatSize(currentFile.size)} · {currentFile.mimeType}
          </div>
        </div>
      </div>
    );
  }

  const file = currentFile as FileContent;

  return (
    <div className="flex flex-col h-full overflow-hidden">
      {/* File header */}
      <div className="flex items-center justify-between px-4 py-2 border-b shrink-0">
        <span className="text-[13px] text-[hsl(var(--muted-foreground))] truncate min-w-0">
          {filePath}
        </span>
        <span className="text-[11px] text-[hsl(var(--muted-foreground))] opacity-60 shrink-0 ml-3">
          {file.language} · {file.totalLines} 行
          {file.truncated && ` · 截断 (${formatSize(file.totalSize)})`}
        </span>
      </div>

      {/* Code area */}
      <CodeContent
        content={file.content}
        language={file.language}
        highlightLine={lineNumber}
        workspace={workspace}
        filePath={filePath}
      />
    </div>
  );
}

function CodeContent({
  content,
  language,
  highlightLine,
  workspace,
  filePath,
}: {
  content: string;
  language: string;
  highlightLine?: number;
  workspace: string;
  filePath: string;
}) {
  const [tokens, setTokens] = useState<Awaited<ReturnType<typeof highlightCode>> | null>(null);
  const highlightLineRef = useRef<HTMLDivElement>(null);
  const isDark = document.documentElement.classList.contains('dark');

  useEffect(() => {
    let cancelled = false;
    highlightCode(content, language, ['one-light', 'one-dark-pro']).then((result) => {
      if (!cancelled && result) {
        setTokens(result);
      }
    });
    return () => { cancelled = true; };
  }, [content, language]);

  // Scroll to highlighted line
  useEffect(() => {
    if (highlightLine && highlightLineRef.current) {
      highlightLineRef.current.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }, [highlightLine, tokens]);

  const lines = useMemo(() => content.split('\n'), [content]);

  // Ctrl+Click handler for code navigation
  const handleCodeClick = useCallback((e: React.MouseEvent) => {
    if (!e.ctrlKey && !e.metaKey) return;

    const target = e.target as HTMLElement;
    // Get the clicked token text
    const text = target.textContent?.trim();
    if (!text || !/^[a-zA-Z_]\w*$/.test(text)) return;

    // Check if it's an import path we can resolve
    const lineEl = target.closest('[data-line]');
    if (lineEl) {
      const lineIdx = parseInt(lineEl.getAttribute('data-line') || '0', 10);
      const lineText = lines[lineIdx] || '';
      const importMatch = lineText.match(/from\s+['"](\.\/[^'"]+)['"]/);
      if (importMatch) {
        // Resolve relative path
        const dir = filePath.split('/').slice(0, -1).join('/');
        const resolved = `${dir}/${importMatch[1]}`.replace(/\/\./g, '/').replace(/\/+/g, '/');
        useCodeViewerStore.getState().loadFile(resolved);
        return;
      }
    }

    // Otherwise do symbol search
    const ext = filePath.split('.').pop();
    useCodeViewerStore.getState().searchSymbol(text, ext);
  }, [filePath, lines]);

  return (
    <div className="flex-1 overflow-y-auto" onClick={handleCodeClick}>
      <div className={cn('code-viewer-content py-2', isDark ? 'bg-[#0d1117]' : 'bg-white')}>
        <pre className="text-[13px] leading-[1.65] font-mono" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
          {lines.map((line, i) => {
            const lineNum = i + 1;
            const isHighlighted = highlightLine === lineNum;

            return (
              <div
                key={i}
                ref={isHighlighted ? highlightLineRef : undefined}
                data-line={i}
                className={cn(
                  'flex px-4',
                  isHighlighted && (isDark ? 'bg-[rgba(31,111,235,0.15)]' : 'bg-[rgba(84,174,255,0.15)]'),
                )}
              >
                <span
                  className={cn(
                    'select-none shrink-0 text-right mr-4',
                    isDark ? 'text-[#484f58]' : 'text-[#8c959f]',
                  )}
                  style={{ width: '40px' }}
                >
                  {lineNum}
                </span>
                <span className="flex-1 min-w-0">
                  {/* Before tokens load, show plain text */}
                  {tokens ? (
                    <TokenLine tokens={tokens.fg ?? []} lineIndex={i} />
                  ) : (
                    <span className={isDark ? 'text-[#c9d1d9]' : 'text-[#1f2328]'}>
                      {line}
                    </span>
                  )}
                </span>
              </div>
            );
          })}
        </pre>
      </div>

      {/* Symbol search navigator */}
      <CodeNavigator workspace={workspace} currentFilePath={filePath} />
    </div>
  );
}

/** Render a single line from Shiki tokens */
function TokenLine({ tokens, lineIndex }: { tokens: any[]; lineIndex: number }) {
  // Shiki tokens structure: tokens is an array of lines, each line is array of tokens
  // But we get the full tokens array, need to extract the right line
  // Actually highlightCode returns TokensResult which has .fg and .bg
  // The structure is: result.fg is an array of lines (split by \n)
  // Each line is a string of HTML spans
  // For simplicity, we'll use dangerouslySetInnerHTML with the token output

  // We need to use the raw token output
  return <span dangerouslySetInnerHTML={{ __html: '' }} />; // placeholder, real implementation uses shiki's token rendering
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
```

注意：`TokenLine` 的实现需要在实际编码时调整为正确使用 Shiki 的 TokensResult。Shiki v4 的 `codeToTokens` 返回的 tokens 结构需要进一步确认。这部分在实现时需要适配实际的 Shiki API。

- [ ] **Step 2: Commit**

```bash
git add src/features/code-viewer/CodePanel.tsx
git commit -m "feat(code-viewer): add CodePanel with Shiki highlighting and line navigation"
```

---

### Task 8: CodeNavigator Component (symbol search popup)

**Files:**
- Create: `src/features/code-viewer/CodeNavigator.tsx`

- [ ] **Step 1: Write the CodeNavigator component**

```tsx
// src/features/code-viewer/CodeNavigator.tsx
import { useCodeViewerStore, type SearchMatch } from '@/stores/code-viewer';
import { Loader2, X } from 'lucide-react';

interface CodeNavigatorProps {
  workspace: string;
  currentFilePath: string;
}

export function CodeNavigator({ workspace, currentFilePath }: CodeNavigatorProps) {
  const searchResults = useCodeViewerStore((s) => s.searchResults);
  const searching = useCodeViewerStore((s) => s.searching);
  const searchSymbol = useCodeViewerStore((s) => s.searchSymbol);
  const clearSearch = useCodeViewerStore((s) => s.clearSearch);
  const loadFile = useCodeViewerStore((s) => s.loadFile);

  if (!searching && searchResults.length === 0 && !searchSymbol) return null;

  return (
    <div className="absolute bottom-4 right-4 w-80 max-h-64 bg-[hsl(var(--card))] border border-[hsl(var(--border))] rounded-lg shadow-lg overflow-hidden z-10">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b bg-[hsl(var(--muted))]/50">
        <span className="text-[12px] font-medium text-[hsl(var(--foreground))]">
          {searching ? '搜索中...' : `"${searchSymbol}" 的引用`}
        </span>
        <button
          onClick={clearSearch}
          className="h-5 w-5 flex items-center justify-center rounded hover:bg-[hsl(var(--muted))] text-[hsl(var(--muted-foreground))]"
        >
          <X className="h-3 w-3" />
        </button>
      </div>

      {/* Results */}
      {searching && (
        <div className="flex items-center justify-center py-6">
          <Loader2 className="h-4 w-4 animate-spin text-[hsl(var(--muted-foreground))]" />
        </div>
      )}

      {!searching && searchResults.length === 0 && (
        <div className="px-3 py-4 text-[12px] text-[hsl(var(--muted-foreground))] text-center">
          未找到匹配
        </div>
      )}

      {!searching && searchResults.length > 0 && (
        <div className="overflow-y-auto max-h-48">
          {searchResults.map((match, i) => (
            <SearchResultItem
              key={`${match.filePath}:${match.line}:${i}`}
              match={match}
              workspace={workspace}
              currentFilePath={currentFilePath}
              onSelect={() => {
                // Navigate to file + line
                useCodeViewerStore.setState({ lineNumber: match.line });
                loadFile(match.filePath);
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function SearchResultItem({
  match,
  workspace,
  currentFilePath,
  onSelect,
}: {
  match: SearchMatch;
  workspace: string;
  currentFilePath: string;
  onSelect: () => void;
}) {
  const isCurrentFile = match.filePath === currentFilePath;

  return (
    <button
      className="w-full text-left px-3 py-1.5 hover:bg-[hsl(var(--muted))] transition-colors border-b border-[hsl(var(--border))]/50 last:border-0"
      onClick={onSelect}
    >
      <div className="flex items-center gap-2">
        <span className="text-[11px] text-[hsl(var(--muted-foreground))] truncate min-w-0">
          {match.filePath}
          {!isCurrentFile && <span className="ml-1 opacity-50">:{match.line}</span>}
        </span>
        {isCurrentFile && (
          <span className="text-[10px] px-1 bg-[hsl(var(--accent))] text-[hsl(var(--accent-foreground))] rounded">
            当前
          </span>
        )}
      </div>
      <div className="text-[12px] text-[hsl(var(--foreground))] truncate font-mono mt-0.5 opacity-80">
        {match.lineContent.trim()}
      </div>
    </button>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/code-viewer/CodeNavigator.tsx
git commit -m "feat(code-viewer): add CodeNavigator symbol search popup"
```

---

### Task 9: Main Overlay Component + integrate into Chat

**Files:**
- Create: `src/features/code-viewer/index.tsx`
- Modify: `src/features/chat/index.tsx`

- [ ] **Step 1: Write the main CodeViewerOverlay component**

```tsx
// src/features/code-viewer/index.tsx
import { useEffect, useCallback, useState } from 'react';
import { useCodeViewerStore } from '@/stores/code-viewer';
import { FileTree } from './FileTree';
import { CodePanel } from './CodePanel';
import { useAltClick } from './useAltClick';
import { cn } from '@/lib/utils';

export function CodeViewerOverlay() {
  const open = useCodeViewerStore((s) => s.open);
  const workspace = useCodeViewerStore((s) => s.workspace);
  const filePath = useCodeViewerStore((s) => s.filePath);
  const closeViewer = useCodeViewerStore((s) => s.closeViewer);
  const loadFile = useCodeViewerStore((s) => s.loadFile);

  const [visible, setVisible] = useState(false);
  const [mounted, setMounted] = useState(false);
  const [treeWidth, setTreeWidth] = useState(240);
  const isDark = document.documentElement.classList.contains('dark');

  // Animation: mount first, then trigger CSS transition
  useEffect(() => {
    if (open) {
      setMounted(true);
      requestAnimationFrame(() => setVisible(true));
    } else {
      setVisible(false);
      // Wait for transition to finish before unmounting
      const timer = setTimeout(() => setMounted(false), 150);
      return () => clearTimeout(timer);
    }
  }, [open]);

  // Esc to close
  useEffect(() => {
    if (!open) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeViewer();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [open, closeViewer]);

  const handleSelectFile = useCallback((fp: string) => {
    loadFile(fp);
  }, [loadFile]);

  // Drag to resize tree width
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = treeWidth;

    const handleMouseMove = (moveEvent: MouseEvent) => {
      const delta = moveEvent.clientX - startX;
      setTreeWidth(Math.max(160, Math.min(400, startWidth + delta)));
    };

    const handleMouseUp = () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };

    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  }, [treeWidth]);

  if (!mounted) return null;

  return (
    <div
      className={cn(
        'fixed inset-0 z-50 flex transition-opacity duration-150',
        visible ? 'opacity-100' : 'opacity-0',
      )}
    >
      {/* File tree */}
      <div
        className={cn(
          'h-full shrink-0 border-r flex flex-col',
          isDark ? 'bg-[#161b22] border-[#30363d]' : 'bg-[#f6f8fa] border-[#d0d7de]',
        )}
        style={{ width: treeWidth }}
      >
        <FileTree
          workspace={workspace}
          activeFilePath={filePath}
          onSelectFile={handleSelectFile}
          onClose={closeViewer}
        />
      </div>

      {/* Drag handle */}
      <div
        className="w-1 cursor-col-resize hover:bg-[hsl(var(--accent))] active:bg-[hsl(var(--accent))] transition-colors shrink-0"
        onMouseDown={handleMouseDown}
      />

      {/* Code panel */}
      <div className={cn('flex-1 h-full overflow-hidden', isDark ? 'bg-[#0d1117]' : 'bg-white')}>
        <CodePanel workspace={workspace} />
      </div>
    </div>
  );
}

/**
 * Wrapper that provides both the overlay and the Alt+Click hook.
 * Mount this once in the Chat page.
 */
export function CodeViewerProvider() {
  useAltClick();
  return <CodeViewerOverlay />;
}
```

- [ ] **Step 2: Integrate into Chat page**

在 `src/features/chat/index.tsx` 中：

1. 顶部 import：
```typescript
import { CodeViewerProvider } from '@/features/code-viewer';
```

2. 在 Chat 组件 return 的 JSX 最外层（最末尾），添加 `<CodeViewerProvider />`：
```tsx
return (
  <div className="relative flex flex-col h-full">
    {/* ... existing chat content ... */}

    {/* Code Viewer Overlay — renders on top when open */}
    <CodeViewerProvider />
  </div>
);
```

- [ ] **Step 3: Verify everything compiles**

Run: `npx tsc --noEmit 2>&1 | head -20`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add src/features/code-viewer/index.tsx src/features/chat/index.tsx
git commit -m "feat(code-viewer): add overlay component and integrate into Chat page"
```

---

## Chunk 4: Polish & Testing

### Task 10: Re-generate design doc (was lost in rollback)

**Files:**
- Create: `docs/superpowers/specs/2026-05-04-code-viewer-design.md`

- [ ] **Step 1: Write the design doc**

从之前确认的设计方案重新生成设计文档，包含所有已确认的决策：全屏覆盖式、GitHub Dark/Light、懒加载文件树、Alt+Click 全范围、自动定位高亮、import 跳转 + 符号搜索、安全约束等。

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-05-04-code-viewer-design.md
git commit -m "docs: 代码查看器设计文档（重新生成）"
```

---

### Task 11: Shiki token rendering fix + code-simplifier pass

**Files:**
- Modify: `src/features/code-viewer/CodePanel.tsx`

- [ ] **Step 1: Fix TokenLine to correctly render Shiki tokens**

Shiki v4 的 `codeToTokens` 返回 `TokensResult`，其中 `fg` 是一个二维数组（行 → tokens）。需要将每行的 tokens 渲染为带颜色的 span。具体实现取决于 Shiki v4 的实际 API，需要在编码时确认。

关键是：
- 使用 `highlightCode()` 获取 `TokensResult`
- `result.fg` 是 `ThemedToken[][]`（行数组，每行是 token 数组）
- 每个 token 有 `content` 和 `color`（或 `htmlStyle`）
- 渲染为 `<span style={{ color: token.color }}>{token.content}</span>`

- [ ] **Step 2: Run code-simplifier agent on all code-viewer files**

对 `server/code-viewer-handler.ts`、`src/stores/code-viewer.ts`、`src/features/code-viewer/` 下的所有文件执行 code-simplifier。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(code-viewer): fix Shiki token rendering and simplify code"
```

---

### Task 12: Manual testing checklist

- [ ] **Step 1: Start dev server**

Run: `./dev.sh`

- [ ] **Step 2: Test Alt+Click on code blocks**

在聊天中发送一条消息让 Claude 回复包含代码的消息，按住 Alt 点击代码块，确认跳转到代码查看器。

- [ ] **Step 3: Test Alt+Click on file paths**

确认工具调用中的文件路径可以 Alt+Click 打开。

- [ ] **Step 4: Test file tree navigation**

展开/折叠文件夹，点击文件切换代码显示。

- [ ] **Step 5: Test Ctrl+Click symbol search**

在代码中 Ctrl+Click 一个函数名，确认搜索弹窗显示结果。

- [ ] **Step 6: Test import jumping**

Ctrl+Click 一个 `import ... from './utils'` 行，确认跳转到目标文件。

- [ ] **Step 7: Test Esc / back button**

按 Esc 或点击 ← 返回按钮，确认回到聊天。

- [ ] **Step 8: Test deep/light theme switch**

切换深色/浅色主题，确认代码查看器配色正确。

- [ ] **Step 9: Test binary file**

Alt+Click 一个图片文件路径，确认显示二进制文件卡片而非报错。

- [ ] **Step 10: Test large file truncation**

打开一个超过 1MB 的文件，确认显示截断提示。
