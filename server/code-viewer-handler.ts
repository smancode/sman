import fs from 'node:fs';
import path from 'node:path';

export const MAX_FILE_SIZE = 1_048_576; // 1MB

/** Normalize path separators to forward slashes for cross-platform frontend compatibility */
function toPosix(p: string): string {
  return p.split(path.sep).join('/');
}

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

  if (!resolved.startsWith(normalizedWorkspace + path.sep) && resolved !== normalizedWorkspace) {
    throw Object.assign(new Error('Path is outside workspace'), { code: 'PATH_TRAVERSAL' });
  }

  // Resolve workspace realpath too (macOS /var -> /private/var)
  let realWorkspace = normalizedWorkspace;
  try {
    realWorkspace = fs.realpathSync(normalizedWorkspace);
  } catch {
    // workspace itself doesn't exist yet, keep resolved
  }

  let realPath: string;
  try {
    realPath = fs.realpathSync(resolved);
  } catch {
    return resolved;
  }

  if (!realPath.startsWith(realWorkspace + path.sep) && realPath !== realWorkspace) {
    throw Object.assign(new Error('Symlink escapes workspace'), { code: 'PATH_TRAVERSAL' });
  }

  return resolved;
}

export function isBinaryFile(fileName: string): boolean {
  const ext = path.extname(fileName).toLowerCase();
  return BINARY_EXTENSIONS.has(ext);
}

export function hasNullBytes(buffer: Buffer): boolean {
  const checkLength = Math.min(buffer.length, 8192);
  for (let i = 0; i < checkLength; i++) {
    if (buffer[i] === 0) return true;
  }
  return false;
}

export function shouldHide(name: string): boolean {
  return HIDDEN_DIRS.has(name) || name.startsWith('.');
}

// --- Types ---

export interface DirEntry {
  name: string;
  type: 'file' | 'directory';
  size?: number;
}

export interface ListDirResult {
  path: string;
  entries: DirEntry[];
}

export interface ReadFileResult {
  path: string;
  content: string;
  language: string;
  totalLines: number;
  truncated: boolean;
  totalSize: number;
}

export interface BinaryFileResult {
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

export interface SearchResult {
  symbol: string;
  matches: SearchMatch[];
}

// --- Handlers ---

export function handleListDir(workspace: string, dirPath: string): ListDirResult {
  const resolved = validatePath(workspace, dirPath);

  let stat: fs.Stats;
  try {
    stat = fs.statSync(resolved);
  } catch {
    throw Object.assign(new Error('Directory not found'), { code: 'NOT_FOUND' });
  }

  if (!stat.isDirectory()) {
    throw Object.assign(new Error('Directory not found'), { code: 'NOT_FOUND' });
  }

  const rawEntries = fs.readdirSync(resolved, { withFileTypes: true });
  const entries: DirEntry[] = [];

  for (const entry of rawEntries) {
    if (shouldHide(entry.name)) continue;

    if (entry.isDirectory()) {
      entries.push({ name: entry.name, type: 'directory' });
    } else if (entry.isFile()) {
      try {
        const size = fs.statSync(path.join(resolved, entry.name)).size;
        entries.push({ name: entry.name, type: 'file', size });
      } catch {
        entries.push({ name: entry.name, type: 'file' });
      }
    }
  }

  entries.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });

  return { path: toPosix(resolved), entries };
}

export function handleReadFile(workspace: string, filePath: string): ReadFileResult | BinaryFileResult {
  let resolved = validatePath(workspace, filePath);

  // If file not found at exact path, try to find it by filename in the workspace
  let stat: fs.Stats;
  try {
    stat = fs.statSync(resolved);
  } catch {
    const found = findFileByName(workspace, path.basename(filePath));
    if (found) {
      resolved = found;
      try {
        stat = fs.statSync(resolved);
      } catch {
        throw Object.assign(new Error('File not found'), { code: 'NOT_FOUND' });
      }
    } else {
      throw Object.assign(new Error('File not found'), { code: 'NOT_FOUND' });
    }
  }

  const fileName = path.basename(resolved);

  if (isBinaryFile(fileName)) {
    const ext = path.extname(fileName).toLowerCase();
    const mimeMap: Record<string, string> = {
      '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
      '.gif': 'image/gif', '.bmp': 'image/bmp', '.ico': 'image/x-icon',
      '.svg': 'image/svg+xml', '.mp3': 'audio/mpeg', '.mp4': 'video/mp4',
      '.pdf': 'application/pdf', '.zip': 'application/zip',
    };
    return {
      path: toPosix(resolved),
      type: 'binary',
      mimeType: mimeMap[ext] || 'application/octet-stream',
      size: stat.size,
      fileName,
    };
  }

  const buffer = fs.readFileSync(resolved);

  if (hasNullBytes(buffer)) {
    return {
      path: toPosix(resolved),
      type: 'binary',
      mimeType: 'application/octet-stream',
      size: stat.size,
      fileName,
    };
  }

  const totalSize = buffer.length;
  const truncated = totalSize > MAX_FILE_SIZE;
  const contentBuffer = truncated ? buffer.subarray(0, MAX_FILE_SIZE) : buffer;
  const content = contentBuffer.toString('utf-8');
  const totalLines = content.split('\n').length;

  return {
    path: toPosix(resolved),
    content,
    language: detectLanguage(resolved),
    totalLines,
    truncated,
    totalSize,
  };
}

const DEFAULT_SEARCH_EXTENSIONS = new Set([
  '.ts', '.tsx', '.js', '.jsx', '.py', '.java', '.go', '.rs',
  '.c', '.cpp', '.h', '.hpp', '.rb', '.php', '.vue', '.svelte',
]);

export function handleSaveFile(workspace: string, filePath: string, content: string): { success: true } | { error: string } {
  let resolved = validatePath(workspace, filePath);

  let stat: fs.Stats;
  try {
    stat = fs.statSync(resolved);
  } catch {
    // Try fuzzy find by filename, same as handleReadFile
    const found = findFileByName(workspace, path.basename(filePath));
    if (found) {
      resolved = found;
      try {
        stat = fs.statSync(resolved);
      } catch {
        return { error: 'File not found' };
      }
    } else {
      return { error: 'File not found' };
    }
  }

  if (!stat.isFile()) {
    return { error: 'Not a file' };
  }

  if (isBinaryFile(path.basename(resolved))) {
    return { error: 'Cannot save binary file' };
  }

  try {
    fs.writeFileSync(resolved, content, 'utf-8');
    return { success: true };
  } catch (err) {
    return { error: (err as Error).message };
  }
}

export function handleSearchSymbols(
  workspace: string,
  symbol: string,
  fileExt?: string,
  maxResults: number = 20,
): SearchResult {
  // Sanitize symbol: keep only alphanumeric and underscore, collapse consecutive
  const cleanSymbol = symbol.replace(/[^a-zA-Z0-9_]+/g, '_').replace(/^_+|_+$/g, '');

  const regex = new RegExp(`\\b${escapeRegExp(cleanSymbol)}\\b`);

  const extSet = fileExt
    ? new Set([fileExt.startsWith('.') ? fileExt : '.' + fileExt])
    : DEFAULT_SEARCH_EXTENSIONS;

  const matches: SearchMatch[] = [];

  function walk(dir: string, relativeTo: string): void {
    if (matches.length >= maxResults) return;

    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }

    for (const entry of entries) {
      if (matches.length >= maxResults) return;
      if (shouldHide(entry.name)) continue;

      const fullPath = path.join(dir, entry.name);
      const relPath = toPosix(path.join(relativeTo, entry.name));

      if (entry.isDirectory()) {
        walk(fullPath, relPath);
      } else if (entry.isFile()) {
        const ext = path.extname(entry.name).toLowerCase();
        if (!extSet.has(ext)) continue;

        try {
          const content = fs.readFileSync(fullPath, 'utf-8');
          const lines = content.split('\n');
          for (let i = 0; i < lines.length; i++) {
            if (matches.length >= maxResults) return;
            if (regex.test(lines[i])) {
              const contextStart = Math.max(0, i - 2);
              const contextEnd = Math.min(lines.length, i + 3);
              const context = lines.slice(contextStart, contextEnd).join('\n');
              matches.push({
                filePath: relPath,
                line: i + 1,
                lineContent: lines[i],
                context,
              });
            }
          }
        } catch {
          // Skip files we can't read
        }
      }
    }
  }

  walk(path.resolve(workspace), '');

  return { symbol: cleanSymbol, matches };
}

/**
 * Search for a file by name in the workspace (limited depth, skip hidden dirs).
 * Returns the first match's resolved path, or null.
 */
function findFileByName(workspace: string, fileName: string, maxDepth: number = 5): string | null {
  const resolvedWorkspace = path.resolve(workspace);

  function walk(dir: string, depth: number): string | null {
    if (depth > maxDepth) return null;

    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return null;
    }

    for (const entry of entries) {
      if (shouldHide(entry.name)) continue;

      const fullPath = path.join(dir, entry.name);

      if (entry.isFile() && entry.name === fileName) {
        return fullPath;
      }

      if (entry.isDirectory()) {
        const found = walk(fullPath, depth + 1);
        if (found) return found;
      }
    }

    return null;
  }

  return walk(resolvedWorkspace, 0);
}

export interface FileSearchResult {
  filePath: string;
  fileName: string;
}

export function handleSearchFiles(workspace: string, query: string, maxResults = 50): FileSearchResult[] {
  if (!query || query.length < 1) return [];

  const normalizedQuery = query.toLowerCase();
  const results: FileSearchResult[] = [];
  const resolvedWorkspace = path.resolve(workspace);

  function walk(dir: string, relativeTo: string): void {
    if (results.length >= maxResults) return;

    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }

    for (const entry of entries) {
      if (results.length >= maxResults) return;
      if (shouldHide(entry.name)) continue;

      const fullPath = path.join(dir, entry.name);
      const relPath = toPosix(path.join(relativeTo, entry.name));

      if (entry.isFile()) {
        // Fuzzy match: query chars must appear in order in filename (case-insensitive)
        if (fuzzyMatch(entry.name.toLowerCase(), normalizedQuery)) {
          results.push({ filePath: relPath, fileName: entry.name });
        }
      } else if (entry.isDirectory()) {
        walk(fullPath, relPath);
      }
    }
  }

  walk(resolvedWorkspace, '');
  return results;
}

/**
 * Simple fuzzy match: all chars in `query` must appear in `target` in order.
 * "bc" matches "Abc.java" (b at index 1, c at index 2).
 */
function fuzzyMatch(target: string, query: string): boolean {
  let ti = 0;
  for (let qi = 0; qi < query.length; qi++) {
    const ch = query[qi];
    while (ti < target.length && target[ti] !== ch) ti++;
    if (ti >= target.length) return false;
    ti++;
  }
  return true;
}

function escapeRegExp(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

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
    '.zsh': 'bash', '.lua': 'lua', '.r': 'r', '.dart': 'dart', '.zig': 'zig',
  };
  return map[ext] || 'text';
}
