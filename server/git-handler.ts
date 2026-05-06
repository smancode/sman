import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { validatePath } from './code-viewer-handler.js';

function isDirectory(workspace: string, filePath: string): boolean {
  try {
    return fs.statSync(path.resolve(workspace, filePath)).isDirectory();
  } catch {
    return false;
  }
}

// Lazy-loaded Claude SDK for push (only when needed)
let _sessionManager: any = null;

export function setSessionManagerForPush(sm: any): void {
  _sessionManager = sm;
}

function git(workspace: string, args: string, timeout = 10000): string {
  try {
    return execSync(`git ${args}`, {
      cwd: workspace,
      encoding: 'utf-8',
      timeout,
      maxBuffer: 10 * 1024 * 1024, // 10MB
    }).trim();
  } catch (err: unknown) {
    const e = err as { stderr?: string; message?: string };
    const msg = e.stderr?.trim() || e.message || String(err);
    throw new Error(msg);
  }
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

export interface GitDiffFile {
  path: string;
  hunks: GitDiffHunk[];
}

export interface GitDiffHunk {
  header: string;
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  lines: GitDiffLine[];
}

export interface GitDiffLine {
  type: 'added' | 'removed' | 'context';
  content: string;
  oldLineNo: number | null;
  newLineNo: number | null;
}

export interface GitLogEntry {
  hash: string;
  shortHash: string;
  message: string;
  author: string;
  date: string;
  refs: string;
}

export interface GitLogGraphNode {
  hash: string;
  shortHash: string;
  message: string;
  author: string;
  date: string;
  refs: string;
  graphLine: string;
}

export interface GitBranch {
  name: string;
  current: boolean;
  remote: boolean;
  remoteName?: string; // e.g. "origin/main"
}

// ── Handlers ───────────────────────────────────────────────────────

export function handleGitStatus(workspace: string): GitStatusResult {
  const branch = git(workspace, 'rev-parse --abbrev-ref HEAD');
  const porcelain = git(workspace, 'status --porcelain=v2 --branch');
  const lines = porcelain.split('\n');

  const files: GitFileStatus[] = [];
  let ahead = 0;
  let behind = 0;
  let hasUpstream = false;

  for (const line of lines) {
    if (line.startsWith('# branch.ab ')) {
      hasUpstream = true;
      const parts = line.split(' ');
      for (const part of parts) {
        if (part.startsWith('+')) ahead = parseInt(part.slice(1), 10) || 0;
        if (part.startsWith('-')) behind = parseInt(part.slice(1), 10) || 0;
      }
      continue;
    }

    if (line.startsWith('1 ') || line.startsWith('2 ') || line.startsWith('u ')) {
      // Ordinary/staged entry
      const parts = line.split(' ');
      const xy = parts.length > 1 ? parts[1] : '';
      const filePath = parts[parts.length - 1];
      files.push(parseStatusXY(filePath, xy));
    } else if (line.startsWith('? ')) {
      files.push({ path: line.slice(2), status: 'untracked', staged: false });
    } else if (line.startsWith('! ')) {
      // ignored, skip
    }
  }

  // Expand untracked directories into individual files
  const expandedFiles: GitFileStatus[] = [];
  for (const f of files) {
    if (f.status === 'untracked' && isDirectory(workspace, f.path)) {
      expandDirFiles(workspace, f.path, expandedFiles);
    } else {
      expandedFiles.push(f);
    }
  }

  return { branch, files: expandedFiles, ahead, behind, hasUpstream };
}

function expandDirFiles(workspace: string, dirPath: string, out: GitFileStatus[]): void {
  const resolved = path.resolve(workspace, dirPath);
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(resolved, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    if (entry.name.startsWith('.')) continue;
    const rel = dirPath ? `${dirPath}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      expandDirFiles(workspace, rel, out);
    } else if (entry.isFile()) {
      out.push({ path: rel, status: 'untracked', staged: false });
    }
  }
}

function parseStatusXY(filePath: string, xy: string): GitFileStatus {
  const x = xy[0] || '.';
  const y = xy[1] || '.';

  if (x === 'R' || y === 'R') return { path: filePath, status: 'renamed', staged: x !== '.' };
  if (x === 'A' || y === 'A') return { path: filePath, status: 'added', staged: x !== '.' };
  if (x === 'D' || y === 'D') return { path: filePath, status: 'deleted', staged: x !== '.' };
  if (x === '?' && y === '?') return { path: filePath, status: 'untracked', staged: false };

  return { path: filePath, status: 'modified', staged: x !== '.' || y !== '.' };
}

export function handleGitDiff(workspace: string, filePath?: string, staged?: boolean): GitDiffFile[] {
  let args = 'diff';
  if (staged) args += ' --cached';
  if (filePath) {
    // Validate the file path is within workspace
    validatePath(workspace, filePath);
    args += ' -- ' + filePath;
  }
  args += ' --no-color -U3';

  const raw = git(workspace, args, 30000);
  if (raw) return parseDiffOutput(raw);

  // Fallback for files with no standard diff output (untracked, deleted)
  if (!filePath) return [];
  return buildFullFileDiff(workspace, filePath);
}

/**
 * Build a synthetic diff for files where `git diff` returns nothing:
 * - Untracked/added files: show all lines as added (green)
 * - Deleted files: show all lines as removed (red)
 */
function buildFullFileDiff(workspace: string, filePath: string): GitDiffFile[] {
  const resolved = validatePath(workspace, filePath);

  // Check if file exists on disk (added/untracked) or only in git (deleted)
  const existsOnDisk = fs.existsSync(resolved);

  if (existsOnDisk) {
    // Added or untracked file — show entire content as added lines
    const content = fs.readFileSync(resolved, 'utf-8');
    if (!content) return [];
    const lines = content.split('\n');
    const diffLines: GitDiffLine[] = lines.map((line, i) => ({
      type: 'added',
      content: line,
      oldLineNo: null,
      newLineNo: i + 1,
    }));
    const totalLines = lines.length;
    return [{
      path: filePath,
      hunks: [{
        header: `@@ -0,0 +1,${totalLines} @@`,
        oldStart: 0, oldLines: 0,
        newStart: 1, newLines: totalLines,
        lines: diffLines,
      }],
    }];
  }

  // Deleted file — show git HEAD content as removed lines
  let headContent = '';
  try {
    headContent = git(workspace, `show HEAD:"${filePath}"`, 10000);
  } catch {
    return [];
  }
  if (!headContent) return [];
  const lines = headContent.split('\n');
  const diffLines: GitDiffLine[] = lines.map((line, i) => ({
    type: 'removed',
    content: line,
    oldLineNo: i + 1,
    newLineNo: null,
  }));
  const totalLines = lines.length;
  return [{
    path: filePath,
    hunks: [{
      header: `@@ -1,${totalLines} +0,0 @@`,
      oldStart: 1, oldLines: totalLines,
      newStart: 0, newLines: 0,
      lines: diffLines,
    }],
  }];
}

export function handleGitDiffFile(workspace: string, filePath: string): { oldContent: string; newContent: string } {
  validatePath(workspace, filePath);

  let oldContent = '';
  try {
    oldContent = git(workspace, `show HEAD:"${filePath}"`, 10000);
  } catch {
    // New file — no old content
  }

  let newContent = '';
  try {
    const resolved = validatePath(workspace, filePath);
    newContent = fs.readFileSync(resolved, 'utf-8');
  } catch {
    // Deleted file — no new content
  }

  return { oldContent, newContent };
}

export function handleGitCommit(workspace: string, message: string, files?: string[]): { hash: string } {
  if (!message.trim()) throw new Error('Commit message is empty');

  if (files && files.length > 0) {
    for (const f of files) {
      validatePath(workspace, f);
      git(workspace, `add -- "${f}"`);
    }
  } else {
    git(workspace, 'add -A');
  }

  const hash = git(workspace, `commit -m ${JSON.stringify(message)}`);
  const match = hash.match(/\[[\w-]+ ([a-f0-9]+)\]/);
  return { hash: match?.[1] || hash.split('\n').pop()?.slice(0, 7) || 'unknown' };
}

export function handleGitLog(workspace: string, maxCount = 20): GitLogEntry[] {
  const format = '%H%n%h%n%s%n%an%n%aI%n%D%n---END---';
  const raw = git(workspace, `log --max-count=${maxCount} --pretty=format:"${format}"`);

  return raw.split('---END---')
    .map(block => {
      const lines = block.trim().split('\n').filter(Boolean);
      if (lines.length < 5) return null;
      return {
        hash: lines[0],
        shortHash: lines[1],
        message: lines[2],
        author: lines[3],
        date: lines[4],
        refs: lines[5] || '',
      };
    })
    .filter(Boolean) as GitLogEntry[];
}

export function handleGitLogGraph(workspace: string, maxCount = 40): GitLogGraphNode[] {
  // Use --graph with custom format to get both graph prefix and commit data
  // %x00 (NUL) separates graph prefix from commit data, %x01 separates fields
  const format = '%x00%H%x01%h%x01%s%x01%an%x01%aI%x01%D';
  const raw = git(workspace, `log --graph --all --decorate --max-count=${maxCount} --pretty=format:"${format}"`);
  if (!raw) return [];

  const lines = raw.split('\n');
  const result: GitLogGraphNode[] = [];

  for (const line of lines) {
    const sepIdx = line.indexOf('\0');
    if (sepIdx === -1) {
      // Pure graph line (merge continuation), skip
      continue;
    }

    const graphLine = line.slice(0, sepIdx);
    const dataPart = line.slice(sepIdx + 1);
    const fields = dataPart.split('\x01');

    if (fields.length < 5) continue;

    result.push({
      graphLine,
      hash: fields[0],
      shortHash: fields[1],
      message: fields[2],
      author: fields[3],
      date: fields[4],
      refs: fields[5] || '',
    });
  }

  return result;
}

export function handleGitBranchList(workspace: string): GitBranch[] {
  const raw = git(workspace, 'branch -a --no-color');
  return raw.split('\n')
    .map(line => line.trim())
    .filter(Boolean)
    .map(line => {
      const current = line.startsWith('*');
      const name = line.replace(/^\* /, '').trim();
      const remote = name.startsWith('remotes/');
      const remoteName = remote ? name : undefined;
      return { name, current, remote, remoteName };
    });
}

export function handleGitCheckout(workspace: string, branch: string): { success: boolean; message: string } {
  if (!/^[a-zA-Z0-9\/_.@-]+$/.test(branch)) {
    throw new Error('Invalid branch name');
  }

  if (branch.startsWith('remotes/')) {
    // Remote branch: extract local name and create tracking branch
    // remotes/origin/feature_x -> feature_x
    const withoutRemote = branch.replace(/^remotes\/[^/]+\//, '');
    if (!withoutRemote || withoutRemote === branch) {
      throw new Error(`Cannot parse branch name from: ${branch}`);
    }
    const result = git(workspace, `checkout -b ${withoutRemote} ${branch}`);
    return { success: true, message: result || `Checked out ${withoutRemote} tracking ${branch}` };
  }

  const result = git(workspace, `checkout ${branch}`);
  return { success: true, message: result };
}

export function handleGitFetch(workspace: string): { success: boolean; message: string } {
  const result = git(workspace, 'fetch --all --prune', 30000);
  return { success: true, message: result || 'Fetch completed' };
}

export async function handleGitPush(workspace: string): Promise<{ success: boolean; message: string }> {
  if (!_sessionManager) {
    throw new Error('Session manager not initialized');
  }

  const branch = git(workspace, 'rev-parse --abbrev-ref HEAD');
  const hasUpstream = (() => {
    try { git(workspace, `rev-parse --abbrev-ref ${branch}@{upstream}`); return true; } catch { return false; }
  })();

  // If no upstream, set one and push
  if (!hasUpstream) {
    try {
      const pushResult = git(workspace, `push -u origin ${branch}`, 60000);
      return { success: true, message: pushResult || 'Push completed (set upstream)' };
    } catch (err) {
      throw new Error(`Push failed: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  // Try simple pull --rebase first
  try {
    const pullResult = git(workspace, 'pull --rebase', 60000);
    const pushResult = git(workspace, 'push', 60000);
    return { success: true, message: `${pullResult}\n${pushResult}`.trim() || 'Pull + Push completed' };
  } catch (pullErr: unknown) {
    const errMsg = pullErr instanceof Error ? pullErr.message : String(pullErr);

    // Check for merge conflicts
    if (!errMsg.includes('conflict') && !errMsg.includes('CONFLICT')) {
      throw new Error(`Push failed: ${errMsg}`);
    }

    // Has conflicts — use Claude Agent SDK to resolve
    const pushPrompt = [
      `You are resolving git merge conflicts in the workspace: ${workspace}`,
      '',
      'There are merge conflicts after git pull --rebase. Your task:',
      '1. Run `git status` to see conflicted files',
      '2. Read each conflicted file and resolve conflicts intelligently',
      '3. For each conflict: keep both sides\' meaningful changes, remove conflict markers (<<<<<<, ======, >>>>>>)',
      '4. After resolving all conflicts: `git add -A` then `git rebase --continue`',
      '5. Finally run `git push`',
      '',
      'IMPORTANT:',
      '- Do NOT ask questions, just resolve and push',
      '- Keep the work area clean',
      '- Output a brief summary of what you resolved',
    ].join('\n');

    try {
      const tempSessionId = _sessionManager.createEphemeralSession(workspace);
      const abort = new AbortController();
      let fullResponse = '';

      await _sessionManager.sendMessageForStep(
        tempSessionId,
        pushPrompt,
        abort,
        () => {},
        'You are a git conflict resolver. Resolve all merge conflicts and push. Be concise.',
      );

      _sessionManager.closeV2Session(tempSessionId);
      _sessionManager.removeEphemeralSession(tempSessionId);

      // Verify push succeeded
      const status = handleGitStatus(workspace);
      if (status.ahead > 0) {
        return { success: false, message: `AI resolved conflicts but push may not have completed. Ahead: ${status.ahead}` };
      }

      return { success: true, message: 'Conflicts resolved by AI, push completed' };
    } catch (sdkErr) {
      throw new Error(`AI conflict resolution failed: ${sdkErr instanceof Error ? sdkErr.message : String(sdkErr)}`);
    }
  }
}

export function handleGitRemoteDiff(workspace: string): GitDiffFile[] {
  // First ensure we have fetched
  try {
    git(workspace, 'fetch --all --prune', 30000);
  } catch {
    // Fetch might fail if no remote, continue anyway
  }

  // Try to diff against upstream
  const branch = git(workspace, 'rev-parse --abbrev-ref HEAD');
  let upstream: string;
  try {
    upstream = git(workspace, `rev-parse --abbrev-ref ${branch}@{upstream}`);
  } catch {
    // No upstream set, try origin/HEAD or origin/<branch>
    try {
      const remote = git(workspace, 'remote').split('\n')[0];
      upstream = `${remote}/${branch}`;
    } catch {
      return [];
    }
  }

  const raw = git(workspace, `diff HEAD...${upstream} --no-color -U3`, 30000);
  if (!raw) return [];
  return parseDiffOutput(raw);
}

// ── AI Commit Message Generation ───────────────────────────────────

export async function handleGitGenerateCommit(
  workspace: string,
  template?: string,
): Promise<{ message: string }> {
  if (!_sessionManager) {
    throw new Error('Session manager not initialized');
  }

  const diffSummary = buildDiffSummary(workspace);
  if (!diffSummary) {
    return { message: template || 'chore: update files' };
  }

  const systemPrompt = `You are a commit message generator. Generate a concise, professional git commit message based on the code changes.

Rules:
- Use the user's template if provided (fill in variables naturally)
- If no template, use Conventional Commits format: type(scope): description
- Types: feat, fix, docs, style, refactor, perf, test, chore, ci, build
- Keep the subject line under 72 characters
- Be specific about what changed, not vague
- Respond in the same language as the code comments (default: English)
- Output ONLY the commit message text, nothing else`;

  const userPrompt = template
    ? `Template:\n${template}\n\nChanges:\n${diffSummary}`
    : `Changes:\n${diffSummary}`;

  const tempSessionId = _sessionManager.createEphemeralSession(workspace);
  const abort = new AbortController();

  try {
    const result = await _sessionManager.sendMessageForStep(
      tempSessionId,
      userPrompt,
      abort,
      () => {},
      systemPrompt,
    );

    return { message: (result || '').trim() || template || 'chore: update files' };
  } finally {
    _sessionManager.closeV2Session(tempSessionId);
    _sessionManager.removeEphemeralSession(tempSessionId);
  }
}

function buildDiffSummary(workspace: string): string {
  try {
    // Get file status summary
    const status = handleGitStatus(workspace);
    if (status.files.length === 0) return '';

    const parts: string[] = [];

    // File changes summary
    const byStatus: Record<string, string[]> = {};
    for (const f of status.files) {
      if (!byStatus[f.status]) byStatus[f.status] = [];
      byStatus[f.status].push(f.path);
    }
    for (const [s, paths] of Object.entries(byStatus)) {
      parts.push(`${s}: ${paths.join(', ')}`);
    }

    // Get actual diff content (limited)
    const diffRaw = git(workspace, 'diff --stat --no-color', 10000);
    if (diffRaw) {
      parts.push('\nDiff stats:\n' + diffRaw);
    }

    // Get actual diff (limited to first 3000 chars for prompt efficiency)
    const diffContent = git(workspace, 'diff --no-color -U1', 15000);
    if (diffContent) {
      const truncated = diffContent.length > 3000 ? diffContent.slice(0, 3000) + '\n... (truncated)' : diffContent;
      parts.push('\nDiff content:\n' + truncated);
    }

    return parts.join('\n');
  } catch {
    return '';
  }
}

// ── Diff Parser ────────────────────────────────────────────────────

function parseDiffOutput(raw: string): GitDiffFile[] {
  const files: GitDiffFile[] = [];
  const fileBlocks = raw.split(/(?=^diff --git )/m);

  for (const block of fileBlocks) {
    if (!block.startsWith('diff --git')) continue;

    const headerMatch = block.match(/^diff --git a\/(.+?) b\/(.+?)$/m);
    if (!headerMatch) continue;

    const filePath = headerMatch[2];
    const hunks: GitDiffHunk[] = [];

    const hunkBlocks = block.split(/(?=^@@ )/m);
    for (const hunkBlock of hunkBlocks) {
      if (!hunkBlock.startsWith('@@')) continue;

      const hunkHeaderMatch = hunkBlock.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$/m);
      if (!hunkHeaderMatch) continue;

      const hunk: GitDiffHunk = {
        header: hunkHeaderMatch[0],
        oldStart: parseInt(hunkHeaderMatch[1], 10),
        oldLines: parseInt(hunkHeaderMatch[2] || '1', 10),
        newStart: parseInt(hunkHeaderMatch[3], 10),
        newLines: parseInt(hunkHeaderMatch[4] || '1', 10),
        lines: [],
      };

      const diffLines = hunkBlock.split('\n').slice(1);
      let oldLine = hunk.oldStart;
      let newLine = hunk.newStart;

      for (const dl of diffLines) {
        if (dl.startsWith('diff --git') || dl.startsWith('@@')) break;
        if (dl.startsWith('+')) {
          hunk.lines.push({ type: 'added', content: dl.slice(1), oldLineNo: null, newLineNo: newLine++ });
        } else if (dl.startsWith('-')) {
          hunk.lines.push({ type: 'removed', content: dl.slice(1), oldLineNo: oldLine++, newLineNo: null });
        } else if (dl.startsWith(' ')) {
          hunk.lines.push({ type: 'context', content: dl.slice(1), oldLineNo: oldLine++, newLineNo: newLine++ });
        }
        // Skip \ No newline at end of file
      }

      hunks.push(hunk);
    }

    files.push({ path: filePath, hunks });
  }

  return files;
}
