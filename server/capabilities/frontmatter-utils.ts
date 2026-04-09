import fs from 'node:fs';
import path from 'node:path';

/**
 * Parse YAML frontmatter from a markdown file.
 * Returns null if no valid frontmatter found.
 */
export function parseFrontmatter(content: string): Record<string, any> | null {
  if (!content.startsWith('---')) return null;
  const endIdx = content.indexOf('\n---', 3);
  if (endIdx === -1) return null;
  const yamlContent = content.slice(3, endIdx).trim();
  return parseYaml(yamlContent);
}

/**
 * Minimal YAML parser for frontmatter.
 * Handles only: key: value, nested objects with indent, quoted strings.
 */
function parseYaml(yaml: string): Record<string, any> {
  const result: Record<string, any> = {};
  const lines = yaml.split('\n');
  const stack: { obj: Record<string, any>; indent: number }[] = [];
  let currentObj = result;
  let currentIndent = 0;

  for (const rawLine of lines) {
    const line = rawLine;

    // Empty line - skip
    if (!line.trim()) {
      continue;
    }

    // Calculate indent and content
    const indentMatch = line.match(/^(\s*)(.*)$/);
    if (!indentMatch) continue;
    const indent = indentMatch[1].length;
    const content = indentMatch[2];

    // Pop stack while current indent is <= stack indent
    while (stack.length > 0 && indent <= stack[stack.length - 1].indent) {
      stack.pop();
    }
    currentObj = stack.length > 0 ? stack[stack.length - 1].obj : result;
    currentIndent = stack.length > 0 ? stack[stack.length - 1].indent : 0;

    // Check if this is a key: value line
    const kvMatch = content.match(/^([^:]+):\s*(.*)$/);
    if (kvMatch) {
      const key = kvMatch[1].trim();
      const value = kvMatch[2].trim();

      if (value) {
        // Simple key: value
        currentObj[key] = parseYamlValue(value);
      } else {
        // This is an object - create empty object and push to stack
        currentObj[key] = {};
        stack.push({ obj: currentObj[key], indent });
      }
    }
  }

  return result;
}

function parseYamlValue(value: string): any {
  if (value.startsWith('"') && value.endsWith('"')) {
    return value.slice(1, -1);
  }
  if (value.startsWith("'") && value.endsWith("'")) {
    return value.slice(1, -1);
  }
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null') return null;
  if (/^\d+$/.test(value)) return parseInt(value, 10);
  return value;
}

/**
 * Validate that SKILL.md has correct frontmatter.
 */
export function validateSkillMd(filePath: string): boolean {
  if (!fs.existsSync(filePath)) return false;
  const content = fs.readFileSync(filePath, 'utf-8');
  const frontmatter = parseFrontmatter(content);
  if (!frontmatter) return false;
  if (!frontmatter.name) return false;
  if (!frontmatter.description || frontmatter.description.length < 5) return false;
  if (!frontmatter._scanned?.commitHash) return false;
  if (content.split('\n').length > 80) return false;
  return true;
}