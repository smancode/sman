#!/usr/bin/env zx

/**
 * bundle-claude-code.mjs
 *
 * Bundles Claude Code CLI for self-contained deployment.
 * Claude Code is a npm package (~59MB) that includes its own binary.
 */

import 'zx/globals';
import { realpathSync, existsSync, mkdirSync, rmSync, cpSync, statSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'bundled', 'claude-code');
const NODE_MODULES = path.join(ROOT, 'node_modules');

echo`📦 Bundling Claude Code for SmanWeb...`;

// 1. Resolve claude-code package
const claudeLink = path.join(NODE_MODULES, '@anthropic-ai', 'claude-code');
if (!existsSync(claudeLink)) {
  echo`❌ @anthropic-ai/claude-code not found. Run pnpm install first.`;
  process.exit(1);
}

const claudeReal = realpathSync(claudeLink);
echo`   claude-code resolved: ${claudeReal}`;

// 2. Clean output
if (existsSync(OUTPUT)) {
  rmSync(OUTPUT, { recursive: true });
}
mkdirSync(OUTPUT, { recursive: true });

// 3. Copy claude-code package
echo`   Copying claude-code package...`;
cpSync(claudeReal, OUTPUT, { recursive: true, dereference: true });

// 4. Verify
const cliExists = existsSync(path.join(OUTPUT, 'cli.js'));
const packageExists = existsSync(path.join(OUTPUT, 'package.json'));

function getDirSize(dir) {
  let total = 0;
  try {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, entry.name);
      if (entry.isDirectory()) total += getDirSize(p);
      else if (entry.isFile()) total += statSync(p).size;
    }
  } catch { /* ignore */ }
  return total;
}

function formatSize(bytes) {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)}G`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}M`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)}K`;
  return `${bytes}B`;
}

const size = getDirSize(OUTPUT);

echo``;
echo`✅ Claude Code bundle complete: ${OUTPUT}`;
echo`   cli.js: ${cliExists ? '✓' : '✗'}`;
echo`   package.json: ${packageExists ? '✓' : '✗'}`;
echo`   Size: ${formatSize(size)}`;

if (!cliExists) {
  echo`❌ Bundle verification failed!`;
  process.exit(1);
}
