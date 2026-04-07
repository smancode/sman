/**
 * Knowledge Loader — reads overview.md files from .claude/knowledge/
 * and returns a string for injection into session system prompt.
 */
import fs from 'node:fs';
import path from 'node:path';

export function loadKnowledgeOverviews(workspace: string): string | null {
  const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
  const manifestPath = path.join(knowledgeDir, 'manifest.json');

  if (!fs.existsSync(manifestPath)) return null;

  const sections: string[] = [];
  for (const subdir of ['structure', 'apis', 'external-calls']) {
    const overviewPath = path.join(knowledgeDir, subdir, 'overview.md');
    if (fs.existsSync(overviewPath)) {
      sections.push(fs.readFileSync(overviewPath, 'utf-8'));
    }
  }

  return sections.length > 0
    ? sections.join('\n\n---\n\n')
      + '\n\nFor details, read files from `.claude/knowledge/{category}/`.'
    : null;
}
