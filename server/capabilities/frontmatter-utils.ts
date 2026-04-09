import fs from 'node:fs';

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
/**
 * Find the index of the first colon that is NOT inside quotes.
 */
function findKeyValueSeparator(content: string): number {
  let inSingleQuote = false;
  let inDoubleQuote = false;
  for (let i = 0; i < content.length; i++) {
    const char = content[i];
    if (char === "'" && !inDoubleQuote) inSingleQuote = !inSingleQuote;
    else if (char === '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;
    else if (char === ':' && !inSingleQuote && !inDoubleQuote) return i;
  }
  return -1;
}

function parseYamlLine(content: string): { key: string; value: string } | null {
  const colonIdx = findKeyValueSeparator(content);
  if (colonIdx === -1) return null;
  const key = content.slice(0, colonIdx).trim();
  const value = content.slice(colonIdx + 1).trim();
  return { key, value };
}

function parseYaml(yaml: string): Record<string, any> {
  const result: Record<string, any> = {};
  const lines = yaml.split('\n');
  const stack: { obj: Record<string, any>; indent: number }[] = [];
  let currentObj = result;

  for (const rawLine of lines) {
    // Empty line - skip
    if (!rawLine.trim()) {
      continue;
    }

    // Calculate indent and content
    const indentMatch = rawLine.match(/^(\s*)(.*)$/);
    if (!indentMatch) continue;
    const indent = indentMatch[1].length;
    const content = indentMatch[2];

    // Skip comments
    if (content.startsWith('#')) {
      continue;
    }

    // Handle list items (lines starting with "- ")
    if (content.startsWith('- ')) {
      const itemContent = content.slice(2);
      const kv = parseYamlLine(itemContent);

      // Pop stack while current indent is <= stack indent
      while (stack.length > 0 && indent <= stack[stack.length - 1].indent) {
        stack.pop();
      }
      currentObj = stack.length > 0 ? stack[stack.length - 1].obj : result;

      if (kv) {
        // Nested object in list: - name: javascript
        currentObj[kv.key] = kv.value;
        stack.push({ obj: currentObj, indent });
      } else {
        // Plain list item: - javascript
        currentObj.push(itemContent);
      }
      continue;
    }

    // Pop stack while current indent is <= stack indent
    while (stack.length > 0 && indent <= stack[stack.length - 1].indent) {
      stack.pop();
    }
    currentObj = stack.length > 0 ? stack[stack.length - 1].obj : result;

    // Check if this is a key: value line
    const kv = parseYamlLine(content);
    if (kv) {
      if (kv.value) {
        // Simple key: value
        currentObj[kv.key] = parseYamlValue(kv.value);
      } else {
        // This is an object - create empty object and push to stack
        currentObj[kv.key] = {};
        stack.push({ obj: currentObj[kv.key], indent });
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