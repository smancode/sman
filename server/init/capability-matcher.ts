import type { WorkspaceScanResult, CapabilityMatchResult, CapabilityMatch } from './init-types.js';
import type { CapabilityEntry, SemanticSearchLlmConfig } from '../capabilities/types.js';

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
  const systemPrompt = `You are a project capability advisor. Given project metadata, select the most useful capabilities from the catalog.

Rules:
- Select capabilities that would be MOST useful for this specific project
- Always include safety capabilities (careful, guard) for any project with code
- Consider the project type, tech stack, and directory structure
- Don't select browser/QA capabilities for non-web projects
- Return a JSON object with: { "matches": [{"capabilityId": "...", "reason": "..."}], "projectSummary": "...", "techStack": ["..."] }
- Return ONLY the JSON, no other text`;

  const userPrompt = `Project metadata:
- Types: ${scanResult.types.join(', ')}
- Languages: ${Object.entries(scanResult.languages).map(([ext, count]) => `${ext}: ${count}`).join(', ')}
- Markers: ${scanResult.markers.join(', ')}
- Top dirs: ${scanResult.topDirs.join(', ')}
- File count: ${scanResult.fileCount}
- Git repo: ${scanResult.isGitRepo}
- Has CLAUDE.md: ${scanResult.hasClaudeMd}
${scanResult.packageJson ? `- Package: ${scanResult.packageJson.name}\n- Scripts: ${scanResult.packageJson.scripts.join(', ')}\n- Key deps: ${scanResult.packageJson.deps.slice(0, 20).join(', ')}` : ''}
${scanResult.pomXml ? `- Maven: ${scanResult.pomXml.groupId}:${scanResult.pomXml.artifactId}\n- Key deps: ${scanResult.pomXml.deps.slice(0, 20).join(', ')}` : ''}

Available capabilities:
${catalog}`;

  const baseUrl = (llmConfig.baseUrl || 'https://api.anthropic.com').replace(/\/$/, '');

  const response = await fetch(`${baseUrl}/v1/messages`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': llmConfig.apiKey,
      'anthropic-version': '2023-06-01',
      'anthropic-dangerous-direct-browser-access': 'true',
    },
    body: JSON.stringify({
      model: llmConfig.model,
      max_tokens: 1024,
      messages: [{ role: 'user', content: userPrompt }],
      system: systemPrompt,
    }),
  });

  if (!response.ok) {
    throw new Error(`LLM call failed: ${response.status}`);
  }

  const data = await response.json() as any;
  const text = data.content?.[0]?.text || '';

  const jsonMatch = text.match(/\{[\s\S]*\}/);
  if (!jsonMatch) throw new Error('No JSON in LLM response');

  const parsed = JSON.parse(jsonMatch[0]);

  const knownIds = new Set(capabilities.map(c => c.id));
  const validMatches = (parsed.matches || []).filter(
    (m: CapabilityMatch) => knownIds.has(m.capabilityId),
  );

  return {
    matches: validMatches,
    projectSummary: parsed.projectSummary || scanResult.types.join(', '),
    techStack: parsed.techStack || [],
  };
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
    techStack: [
      ...Object.keys(scanResult.languages).map(ext => ext.replace('.', '').toUpperCase()),
      ...scanResult.markers,
    ],
  };
}
