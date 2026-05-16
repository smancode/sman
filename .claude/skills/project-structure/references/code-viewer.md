# Code Viewer Reference

> Enhanced code browser with navigation, search, and symbol support

## Purpose
Integrated code viewer with file tree navigation, symbol search, and ripgrep content search.

## Key Enhancements (v26.517.1)

### 1. Navigation History
- Forward/backward navigation through viewed files
- File tree follows current selection
- Keyboard shortcuts: Alt+Left/Right

### 2. Symbol Search
- Ctrl/Cmd+click on symbols to jump to definition
- Language-specific symbol extraction (CodeMirror APIs)
- Caching for performance

### 3. Ripgrep Search
- **server/code-viewer-handler.ts**: Backend ripgrep search
- `code.searchFiles(pattern, path?)`: Content search across workspace
- **Fast**: Uses ripgrep binary, scales to large repos

## Key Files
- **server/code-viewer-handler.ts**: WebSocket handlers (listDir/readFile/searchFiles/searchSymbols)
- **src/features/code-viewer/CodePanel.tsx**: Main code viewer UI
- **src/features/code-viewer/CodeNavigator.tsx**: Navigation controls
- **src/features/code-viewer/FileTree.tsx**: File tree with selection
- **src/stores/code-viewer.ts**: State management (history, current file, search)

## WebSocket API

### List Directory
```typescript
code.listDir(path: string): Promise<DirectoryEntry[]>
```

### Read File
```typescript
code.readFile(path: string): Promise<{ content: string; language: string }>
```

### Search Symbols
```typescript
code.searchSymbols(path: string): Promise<Symbol[]>
```

### Search Files (Ripgrep)
```typescript
code.searchFiles(pattern: string, path?: string): Promise<SearchResult[]>
```

## State Management (Zustand)
```typescript
interface CodeViewerState {
  // Navigation
  currentFile: string | null;
  navigationHistory: string[];
  historyIndex: number;

  // File tree
  expandedPaths: Set<string>;

  // Search
  searchResults: SearchResult[];
  searchPattern: string;

  // Actions
  openFile(path: string, content: string): void;
  goBack(): void;
  goForward(): void;
  searchFiles(pattern: string, path?: string): Promise<void>;
}
```

## Dependencies
- **@codemirror/*: Multi-language syntax highlighting
- **ripgrep**: Fast content search (system binary)
- **better-sqlite3**: Optional caching for large projects

## UI Features
- Split panel: File tree (left) + Code editor (right)
- Language auto-detection
- Line numbers
- Symbol highlighting
- Search result highlighting
