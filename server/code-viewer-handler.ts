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

  return { path: resolved, entries };
}

export function handleReadFile(workspace: string, filePath: string): ReadFileResult | BinaryFileResult {
  const resolved = validatePath(workspace, filePath);

  let stat: fs.Stats;
  try {
    stat = fs.statSync(resolved);
  } catch {
    throw Object.assign(new Error('File not found'), { code: 'NOT_FOUND' });
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
      path: resolved,
      type: 'binary',
      mimeType: mimeMap[ext] || 'application/octet-stream',
      size: stat.size,
      fileName,
    };
  }

  const buffer = fs.readFileSync(resolved);

  if (hasNullBytes(buffer)) {
    return {
      path: resolved,
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
    path: resolved,
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
      const relPath = path.join(relativeTo, entry.name);

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
