/**
 * Generic instruction runner — reads SKILL.md + all auxiliary files from a plugin directory.
 *
 * Used by capabilities that have auxiliary files (examples, themes, scripts, etc.)
 * alongside their main SKILL.md. Simple single-file skills fall through to the
 * default `readPluginSkillMd` in gateway-mcp-server.ts.
 */

import fs from 'node:fs';
import path from 'node:path';

/** File extensions to include when gathering auxiliary files */
const AUXILIARY_EXTENSIONS = new Set(['.md', '.py', '.css', '.js', '.json']);

/**
 * Read SKILL.md plus all auxiliary files from the plugin directory,
 * concatenate them into a single instruction string.
 */
export function createGenericInstructions(pluginsDir: string, pluginPath: string): string | null {
  const pluginDir = path.join(pluginsDir, pluginPath);

  if (!fs.existsSync(pluginDir)) {
    return null;
  }

  const parts: string[] = [];

  // 1. Read main SKILL.md first
  const skillMdPath = path.join(pluginDir, 'SKILL.md');
  if (fs.existsSync(skillMdPath)) {
    parts.push(fs.readFileSync(skillMdPath, 'utf-8'));
  }

  // 2. Recursively read auxiliary files from subdirectories
  collectAuxiliaryFiles(pluginDir, parts, new Set(['SKILL.md']));

  if (parts.length === 0) {
    return null;
  }

  return parts.join('\n\n---\n\n');
}

function collectAuxiliaryFiles(dir: string, parts: string[], seen: Set<string>): void {
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }

  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);

    if (entry.isDirectory()) {
      collectAuxiliaryFiles(fullPath, parts, seen);
      continue;
    }

    if (!entry.isFile()) {
      continue;
    }

    // Skip already-read SKILL.md and non-matching extensions
    if (seen.has(entry.name)) {
      continue;
    }

    const ext = path.extname(entry.name).toLowerCase();
    if (!AUXILIARY_EXTENSIONS.has(ext)) {
      continue;
    }

    seen.add(entry.name);

    const relativePath = path.relative(dir, fullPath);
    const content = fs.readFileSync(fullPath, 'utf-8');
    parts.push(`<!-- ${relativePath} -->\n${content}`);
  }
}
