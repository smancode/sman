export function renderTemplate(template: string, item: Record<string, unknown>): string {
  return template.replace(/\$\{(\w+)\}/g, (match, field) => {
    const value = item[field];
    return value !== undefined ? String(value) : match;
  });
}

export type Interpreter = 'python3' | 'node' | 'bash';

export function detectInterpreter(code: string): Interpreter {
  const bashPatterns = [/^#!.*bash/, /^#!.*sh\b/];
  for (const pattern of bashPatterns) {
    if (pattern.test(code)) return 'bash';
  }
  const pythonPatterns = [
    /^\s*(?:import |from )/m,
    /^#!.*python/,
    /mysql\.connector/,
    /pymysql/,
    /psycopg/,
  ];
  for (const pattern of pythonPatterns) {
    if (pattern.test(code)) return 'python3';
  }
  return 'node';
}
