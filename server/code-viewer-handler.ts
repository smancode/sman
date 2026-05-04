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

  let realPath: string;
  try {
    realPath = fs.realpathSync(resolved);
  } catch {
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
  const checkLength = Math.min(buffer.length, 8192);
  for (let i = 0; i < checkLength; i++) {
    if (buffer[i] === 0) return true;
  }
  return false;
}

export function shouldHide(name: string): boolean {
  return HIDDEN_DIRS.has(name) || name.startsWith('.');
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
