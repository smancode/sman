/**
 * Skills initialization script.
 *
 * Scans resources/skills/ directory and syncs entries to registry.json.
 * Run: npx tsx scripts/init-skills.ts
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const ROOT = path.resolve(__dirname, '..');
const RESOURCES_SKILLS_DIR = path.join(ROOT, 'resources', 'skills');
const HOME_DIR = path.join(
  process.env.SMANBASE_HOME || path.join(require('os').homedir(), '.sman'),
);

interface SkillEntry {
  name: string;
  description: string;
  version: string;
  path: string;
  triggers: ('auto-on-init' | 'manual')[];
  tags: string[];
}

interface Registry {
  version: string;
  skills: Record<string, SkillEntry>;
}

function parseSkillMd(filePath: string): SkillEntry | null {
  const content = fs.readFileSync(filePath, 'utf-8');
  const frontmatterMatch = content.match(/^---\n([\s\S]*?)\n---/);
  if (!frontmatterMatch) return null;

  const frontmatter: Record<string, any> = {};
  for (const line of frontmatterMatch[1].split('\n')) {
    const match = line.match(/^(\w+):\s*(.+)$/);
    if (match) {
      const key = match[1];
      let value: any = match[2].trim();
      if (value.startsWith('[') && value.endsWith(']')) {
        value = value.slice(1, -1).split(',').map((s: string) => s.trim().replace(/^['"]|['"]$/g, ''));
      }
      frontmatter[key] = value;
    }
  }

  if (!frontmatter.name) return null;

  return {
    name: frontmatter.name,
    description: frontmatter.description || '',
    version: frontmatter.version || '1.0.0',
    path: `skills/${path.basename(path.dirname(filePath))}`,
    triggers: (frontmatter.triggers || ['manual']).map((t: string) =>
      t === 'auto-on-init' ? 'auto-on-init' : 'manual'
    ),
    tags: frontmatter.tags || [],
  };
}

function main(): void {
  const registryPath = path.join(HOME_DIR, 'registry.json');

  // Load existing registry or create new
  let registry: Registry;
  if (fs.existsSync(registryPath)) {
    registry = JSON.parse(fs.readFileSync(registryPath, 'utf-8'));
  } else {
    registry = { version: '1.0', skills: {} };
  }

  // Scan resources/skills
  if (!fs.existsSync(RESOURCES_SKILLS_DIR)) {
    console.log('No resources/skills/ directory found');
    return;
  }

  const skillDirs = fs.readdirSync(RESOURCES_SKILLS_DIR, { withFileTypes: true })
    .filter(d => d.isDirectory());

  let added = 0;
  let updated = 0;

  for (const dir of skillDirs) {
    const skillMdPath = path.join(RESOURCES_SKILLS_DIR, dir.name, 'skill.md');
    if (!fs.existsSync(skillMdPath)) continue;

    const skill = parseSkillMd(skillMdPath);
    if (!skill) continue;

    const skillId = skill.name;
    if (registry.skills[skillId]) {
      // Update existing
      const old = registry.skills[skillId];
      if (old.version !== skill.version || old.description !== skill.description) {
        registry.skills[skillId] = skill;
        updated++;
      }
    } else {
      registry.skills[skillId] = skill;
      added++;
    }
  }

  // Save
  fs.mkdirSync(HOME_DIR, { recursive: true });
  fs.writeFileSync(registryPath, JSON.stringify(registry, null, 2), 'utf-8');

  console.log(`Skills initialized: ${added} added, ${updated} updated, ${Object.keys(registry.skills).length} total`);
}

main();
