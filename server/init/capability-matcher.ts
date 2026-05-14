import type { WorkspaceScanResult, CapabilityMatchResult, CapabilityMatch } from './init-types.js';
import type { CapabilityEntry, SemanticSearchLlmConfig } from '../capabilities/types.js';
import { unstable_v2_prompt } from '@anthropic-ai/claude-agent-sdk';
import { createLogger } from '../utils/logger.js';

const log = createLogger('CapabilityMatcher');
const ALWAYS_MATCH_IDS = new Set(['careful', 'guard']);

export function formatCapabilityCatalog(capabilities: CapabilityEntry[]): string {
  return capabilities
    .filter(c => c.enabled)
    .map(c => `ID: ${c.id} | Name: ${c.name} | Desc: ${c.description} | Triggers: ${c.triggers.join(', ')}`)
    .join('\n');
}

export async function matchCapabilities(
  scanResult: WorkspaceScanResult,
  capabilities: CapabilityEntry[],
  llmConfig: SemanticSearchLlmConfig,
): Promise<CapabilityMatchResult> {
  const catalog = formatCapabilityCatalog(capabilities);
  const knownIds = new Set(capabilities.map(c => c.id));

  const claudeMdSection = scanResult.claudeMdContent
    ? `\n## CLAUDE.md content (project's own description):\n\`\`\`\n${scanResult.claudeMdContent}\n\`\`\``
    : '';

  const dirStructureSection = scanResult.dirStructure && scanResult.dirStructure.length > 0
    ? `- Dir structure: ${scanResult.dirStructure.map(d => `${d.name}/(${d.fileCount} files, subdirs: ${d.subDirs.slice(0, 5).join(',')})`).join(', ')}`
    : `- Top dirs: ${scanResult.topDirs.join(', ')}`;

  const prompt = `You are a project capability advisor. Given project metadata, select the most useful capabilities from the catalog.

Rules:
- Select capabilities that would be MOST useful for this specific project
- Always include safety capabilities (careful, guard) for any project with code
- Consider the project type, tech stack, and directory structure
- Don't select browser/QA capabilities for non-web projects

Output format — a simple list, one capability ID per line, like:
RECOMMENDED:
careful
guard
review
investigate

SUMMARY: <one sentence project summary — must be specific, NOT generic like "java 项目">
DESCRIPTION: <2-3 sentences describing what this project does, its purpose, and key features>
TECHSTACK: <comma separated tech stack — use framework names, not file extensions>

Project metadata:
- Types: ${scanResult.types.join(', ')}
- Languages: ${Object.entries(scanResult.languages).map(([ext, count]) => `${ext}: ${count}`).join(', ')}
- Markers: ${scanResult.markers.join(', ')}
${dirStructureSection}
- File count: ${scanResult.fileCount}
- Git repo: ${scanResult.isGitRepo}
- Has CLAUDE.md: ${scanResult.hasClaudeMd}
${scanResult.packageJson ? `- Package: ${scanResult.packageJson.name}\n- Scripts: ${scanResult.packageJson.scripts.join(', ')}\n- Key deps: ${scanResult.packageJson.deps.slice(0, 20).join(', ')}` : ''}
${scanResult.pomXml ? `- Maven: ${scanResult.pomXml.groupId}:${scanResult.pomXml.artifactId}\n- Key deps: ${scanResult.pomXml.deps.slice(0, 20).join(', ')}` : ''}${claudeMdSection}

Available capabilities:
${catalog}`;

  const env: Record<string, string | undefined> = { ...process.env as Record<string, string> };
  env['ANTHROPIC_API_KEY'] = llmConfig.apiKey;
  if (llmConfig.baseUrl) {
    env['ANTHROPIC_BASE_URL'] = llmConfig.baseUrl;
  }

  try {
    const result = await unstable_v2_prompt(prompt, {
      model: llmConfig.model,
      env,
    });

    if (result.is_error || result.subtype !== 'success') {
      log.warn(`SDK prompt returned error, using minimal matches`);
      return buildMinimalResult(scanResult);
    }

    const text = result.result || '';
    log.info(`SDK response: ${text.slice(0, 500)}`);

    // Extract capability IDs from response — find known IDs in the text
    const matchedIds: string[] = [];
    for (const id of knownIds) {
      if (text.includes(id)) {
        matchedIds.push(id);
      }
    }

    // Ensure safety capabilities are always included
    for (const id of ALWAYS_MATCH_IDS) {
      if (!matchedIds.includes(id)) {
        matchedIds.push(id);
      }
    }

    // Extract summary, description, and tech stack
    const summaryMatch = text.match(/SUMMARY:\s*(.+)/i);
    const descMatch = text.match(/DESCRIPTION:\s*(.+(?:\n(?!TECHSTACK|RECOMMENDED).+)*)/i);
    const techMatch = text.match(/TECHSTACK:\s*(.+)/i);

    const projectSummary = summaryMatch?.[1]?.trim() || scanResult.types.join(', ') + ' 项目';
    const projectDescription = descMatch?.[1]?.trim() || projectSummary;
    const techStack = techMatch?.[1]?.split(',').map((s: string) => s.trim()).filter(Boolean) || [
      ...Object.keys(scanResult.languages).map(ext => ext.replace('.', '').toUpperCase()),
      ...scanResult.markers,
    ];

    return {
      matches: matchedIds.map(id => ({
        capabilityId: id,
        reason: 'AI 推荐能力',
      })),
      projectSummary,
      projectDescription,
      techStack,
    };
  } catch (err: any) {
    log.warn(`SDK prompt failed: ${err.message}, using minimal matches`);
    return buildMinimalResult(scanResult);
  }
}

export function keywordFallback(
  scanResult: WorkspaceScanResult,
  capabilities: CapabilityEntry[],
): CapabilityMatchResult {
  const matches: CapabilityMatch[] = [];

  for (const cap of capabilities) {
    if (!cap.enabled) continue;
    if (ALWAYS_MATCH_IDS.has(cap.id)) {
      matches.push({ capabilityId: cap.id, reason: '安全基础能力，适用于所有项目' });
      continue;
    }
    const triggerLower = cap.triggers.map(t => t.toLowerCase());
    const typeLower = scanResult.types.map(t => t.toLowerCase());
    const hasMatch = typeLower.some(t => triggerLower.some(tr => tr.includes(t)));
    if (hasMatch) {
      matches.push({ capabilityId: cap.id, reason: `关键词匹配: ${scanResult.types.join(', ')}` });
    }
  }

  return {
    matches,
    projectSummary: scanResult.types.join(', ') + ' 项目',
    projectDescription: scanResult.claudeMdContent
      ? scanResult.claudeMdContent.split('\n').filter(l => l.trim() && !l.startsWith('#') && !l.startsWith('|') && !l.startsWith('>') && !l.startsWith('-') && !l.startsWith('```')).slice(0, 3).join(' ').trim()
      : scanResult.types.join(', ') + ' 项目',
    techStack: [
      ...Object.keys(scanResult.languages).map(ext => ext.replace('.', '').toUpperCase()),
      ...scanResult.markers,
    ],
  };
}

/** Build minimal result when LLM is unavailable — only safety capabilities */
function buildMinimalResult(scanResult: WorkspaceScanResult): CapabilityMatchResult {
  const matches: CapabilityMatch[] = [];
  for (const id of ALWAYS_MATCH_IDS) {
    matches.push({ capabilityId: id, reason: '安全基础能力，LLM 不可用时默认注入' });
  }
  return {
    matches,
    projectSummary: scanResult.types.join(', ') + ' 项目',
    projectDescription: scanResult.claudeMdContent
      ? scanResult.claudeMdContent.split('\n').filter(l => l.trim() && !l.startsWith('#') && !l.startsWith('|') && !l.startsWith('>') && !l.startsWith('-') && !l.startsWith('```')).slice(0, 3).join(' ').trim()
      : scanResult.types.join(', ') + ' 项目',
    techStack: [
      ...Object.keys(scanResult.languages).map(ext => ext.replace('.', '').toUpperCase()),
      ...scanResult.markers,
    ],
  };
}
