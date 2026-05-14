import fs from 'fs';
import path from 'path';
import type { WorkspaceScanResult, ProjectType } from './init-types.js';

const NOISE_DIRS = new Set([
  'node_modules', '.git', 'dist', 'build', 'target', '.next',
  '.sveltekit', '.nuxt', 'coverage', '__pycache__', '.tox',
  'venv', '.venv', 'env', '.env', '.idea', '.vscode',
]);

const MARKER_FILES: Record<string, ProjectType> = {
  'package.json': 'node',
  'pom.xml': 'java',
  'build.gradle': 'java',
  'build.gradle.kts': 'java',
  'go.mod': 'go',
  'Cargo.toml': 'rust',
  'requirements.txt': 'python',
  'pyproject.toml': 'python',
  'setup.py': 'python',
  'Pipfile': 'python',
};

export function scanWorkspace(workspace: string): WorkspaceScanResult {
  const entries = fs.readdirSync(workspace, { withFileTypes: true });
  const fileNames = entries.filter(e => e.isFile()).map(e => e.name);
  const dirNames = entries.filter(e => e.isDirectory()).map(e => e.name);

  // File extension counts
  const languages: Record<string, number> = {};
  collectExtensions(workspace, languages, 0, 2, new Set([workspace]));
  let fileCount = 0;
  for (const count of Object.values(languages)) {
    fileCount += count;
  }

  // Detect markers
  const markers: string[] = [];
  const types: Set<ProjectType> = new Set();
  for (const [marker, type] of Object.entries(MARKER_FILES)) {
    if (fileNames.includes(marker)) {
      markers.push(marker);
      types.add(type);
    }
  }

  // Check for React
  let packageJson: WorkspaceScanResult['packageJson'] = undefined;
  if (fileNames.includes('package.json')) {
    try {
      const pkg = JSON.parse(fs.readFileSync(path.join(workspace, 'package.json'), 'utf-8'));
      const allDeps = { ...pkg.dependencies, ...pkg.devDependencies };
      if (allDeps['react'] || allDeps['next'] || allDeps['@remix-run/react']) {
        types.add('react');
      }
      packageJson = {
        name: pkg.name || '',
        scripts: Object.keys(pkg.scripts || {}),
        deps: Object.keys(allDeps || {}),
      };
    } catch { /* ignore */ }
  }

  // Parse pom.xml
  let pomXml: WorkspaceScanResult['pomXml'] = undefined;
  if (fileNames.includes('pom.xml')) {
    try {
      const content = fs.readFileSync(path.join(workspace, 'pom.xml'), 'utf-8');
      const groupId = content.match(/<groupId>([^<]+)<\/groupId>/)?.[1] || '';
      const artifactId = content.match(/<artifactId>([^<]+)<\/artifactId>/)?.[1] || '';
      const deps = [...content.matchAll(/<artifactId>([^<]+)<\/artifactId>/g)].map(m => m[1]);
      pomXml = { groupId, artifactId, deps };
    } catch { /* ignore */ }
  }

  // Detect docs directory
  if (types.size === 0) {
    const mdFiles = fileNames.filter(f => f.endsWith('.md'));
    const nonMdFiles = fileNames.filter(f => !f.endsWith('.md') && !f.startsWith('.'));
    if (mdFiles.length > 0 && nonMdFiles.length === 0) {
      types.add('docs');
    }
  }

  if (types.size === 0 && fileCount > 0) types.add('mixed');
  if (fileCount === 0 && !types.has('empty')) types.add('empty');

  const topDirs = dirNames.filter(d => !NOISE_DIRS.has(d) && !d.startsWith('.'));

  // Read CLAUDE.md content (first 150 lines) for richer project understanding
  let claudeMdContent: string | undefined;
  if (fileNames.includes('CLAUDE.md')) {
    try {
      const fullContent = fs.readFileSync(path.join(workspace, 'CLAUDE.md'), 'utf-8');
      const lines = fullContent.split('\n').slice(0, 150);
      claudeMdContent = lines.join('\n');
    } catch { /* ignore */ }
  }

  // Lightweight subdirectory structure (1 level deep, top 20 dirs)
  const dirStructure: Array<{ name: string; fileCount: number; subDirs: string[] }> = [];
  for (const dir of topDirs.slice(0, 20)) {
    const dirPath = path.join(workspace, dir);
    try {
      const subEntries = fs.readdirSync(dirPath, { withFileTypes: true });
      const subFileCount = subEntries.filter(e => e.isFile()).length;
      const subDirs = subEntries.filter(e => e.isDirectory()).map(e => e.name).slice(0, 10);
      dirStructure.push({ name: dir, fileCount: subFileCount, subDirs });
    } catch { /* ignore */ }
  }

  return {
    types: [...types],
    languages,
    markers,
    packageJson,
    pomXml,
    topDirs,
    fileCount,
    isGitRepo: dirNames.includes('.git'),
    hasClaudeMd: fileNames.includes('CLAUDE.md'),
    claudeMdContent,
    dirStructure,
  };
}

function collectExtensions(
  dir: string,
  languages: Record<string, number>,
  depth: number,
  maxDepth: number,
  visited: Set<string>,
): void {
  if (depth > maxDepth) return;
  try {
    const realPath = fs.realpathSync(dir);
    if (visited.has(realPath)) return;
    visited.add(realPath);
  } catch { return; }

  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch { return; }

  for (const entry of entries) {
    if (entry.name.startsWith('.') || NOISE_DIRS.has(entry.name)) continue;
    if (entry.isFile()) {
      const ext = path.extname(entry.name);
      if (ext) languages[ext] = (languages[ext] || 0) + 1;
    } else if (entry.isDirectory()) {
      collectExtensions(path.join(dir, entry.name), languages, depth + 1, maxDepth, visited);
    }
  }
}
