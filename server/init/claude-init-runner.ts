import { execFile } from 'child_process';
import { promisify } from 'util';
import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from '../utils/logger.js';

const execFileAsync = promisify(execFile);
const log: Logger = createLogger('ClaudeInitRunner');
const INIT_TIMEOUT_MS = 60_000;

export async function generateClaudeMd(workspace: string): Promise<boolean> {
  const claudeMdPath = path.join(workspace, 'CLAUDE.md');
  if (fs.existsSync(claudeMdPath)) {
    return false; // Already exists
  }

  // Find claude CLI
  const claudePath = process.env.CLAUDE_CODE_PATH || 'claude';
  try {
    const { stdout } = await execFileAsync('which', [claudePath], { timeout: 5000 });
    if (!stdout.trim()) return false;
  } catch {
    log.warn('claude CLI not found, skipping CLAUDE.md generation');
    return false;
  }

  try {
    await execFileAsync(claudePath, [
      '-p', 'Generate a CLAUDE.md file for this project. Analyze the codebase structure, tech stack, coding conventions, and write a comprehensive CLAUDE.md.',
      '--allowedTools', 'Write,Read,Glob,Grep',
      '--max-turns', '10',
    ], {
      cwd: workspace,
      timeout: INIT_TIMEOUT_MS,
      env: { ...process.env },
    });
    return fs.existsSync(claudeMdPath);
  } catch (err: any) {
    log.warn(`CLAUDE.md generation failed: ${err.message}`);
    return false;
  }
}
