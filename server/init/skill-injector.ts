import fs from 'fs';
import path from 'path';

const AUXILIARY_EXTENSIONS = new Set(['.md', '.py', '.css', '.js', '.json']);
const SKIP_FILES = new Set(['SKILL.md.tmpl', 'package.json', 'package-lock.json']);

interface SkillSource {
  capabilityId: string;
  pluginPath: string;
}

export function injectSkills(
  matches: SkillSource[],
  pluginsDir: string,
  workspace: string,
): string[] {
  const injected: string[] = [];
  const skillsDir = path.join(workspace, '.claude', 'skills');

  for (const { capabilityId, pluginPath } of matches) {
    const sourceDir = path.join(pluginsDir, pluginPath);
    if (!fs.existsSync(sourceDir)) continue;

    const targetDir = path.join(skillsDir, capabilityId);

    // Never overwrite existing user skills
    if (fs.existsSync(path.join(targetDir, 'SKILL.md'))) continue;

    fs.mkdirSync(targetDir, { recursive: true });

    // Copy SKILL.md
    const skillMd = path.join(sourceDir, 'SKILL.md');
    if (fs.existsSync(skillMd)) {
      fs.copyFileSync(skillMd, path.join(targetDir, 'SKILL.md'));
    }

    // Copy auxiliary files
    try {
      for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
        if (entry.name === 'SKILL.md') continue;
        if (SKIP_FILES.has(entry.name)) continue;

        if (entry.isFile()) {
          const ext = path.extname(entry.name);
          if (AUXILIARY_EXTENSIONS.has(ext)) {
            fs.copyFileSync(
              path.join(sourceDir, entry.name),
              path.join(targetDir, entry.name),
            );
          }
        } else if (entry.isDirectory() && !entry.name.startsWith('.') && entry.name !== 'node_modules') {
          copyDirRecursive(
            path.join(sourceDir, entry.name),
            path.join(targetDir, entry.name),
          );
        }
      }
    } catch { /* partial copy is acceptable */ }

    injected.push(capabilityId);
  }

  return injected;
}

function copyDirRecursive(src: string, dst: string): void {
  fs.mkdirSync(dst, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const srcPath = path.join(src, entry.name);
    const dstPath = path.join(dst, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(srcPath, dstPath);
    } else if (entry.isFile()) {
      fs.copyFileSync(srcPath, dstPath);
    }
  }
}
