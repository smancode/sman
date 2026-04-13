/**
 * Syntax highlighter using shiki
 */
import { codeToHtml } from 'shiki';

export interface HighlightResult {
  html: string;
  lineCount: number;
}

/**
 * Highlight code block with syntax highlighting (dual theme support)
 * @param code - The code content to highlight
 * @param lang - The language identifier (e.g., 'typescript', 'python')
 * @returns HTML string with syntax highlighting for both light and dark themes
 */
export async function highlightCode(code: string, lang: string): Promise<HighlightResult> {
  try {
    const normalizedLang = normalizeLang(lang);

    // Generate both light and dark theme HTML
    const [lightHtml, darkHtml] = await Promise.all([
      codeToHtml(code, {
        lang: normalizedLang,
        theme: 'one-light',
      }),
      codeToHtml(code, {
        lang: normalizedLang,
        theme: 'one-dark-pro',
      }),
    ]);

    // Merge themes into a single HTML structure with theme-specific classes
    const mergedHtml = mergeThemes(lightHtml, darkHtml);
    const lineCount = code.split('\n').length;

    return {
      html: mergedHtml,
      lineCount,
    };
  } catch {
    // Fallback to escaped HTML if highlighting fails
    const escaped = escapeHtml(code);
    const lineCount = code.split('\n').length;
    return {
      html: `<code class="shiki shiki-fallback">${escaped}</code>`,
      lineCount,
    };
  }
}

/**
 * Merge light and dark theme HTML into a single structure
 * Light theme is default, dark theme is wrapped in dark mode class
 */
function mergeThemes(lightHtml: string, darkHtml: string): string {
  // Extract the inner code content from both themes
  const lightContent = extractCodeContent(lightHtml);
  const darkContent = extractCodeContent(darkHtml);

  return `<span class="shiki-light" aria-hidden="false">${lightContent}</span><span class="shiki-dark" aria-hidden="true">${darkContent}</span>`;
}

/**
 * Extract code content from shiki-generated HTML
 */
function extractCodeContent(html: string): string {
  // Extract content between <code> tags
  const match = html.match(/<code[^>]*>([\s\S]*)<\/code>/);
  return match ? match[1] : html;
}

/**
 * Normalize language identifier to shiki-compatible format
 */
function normalizeLang(lang: string): string {
  const langMap: Record<string, string> = {
    'js': 'javascript',
    'ts': 'typescript',
    'py': 'python',
    'rb': 'ruby',
    'sh': 'bash',
    'shell': 'bash',
    'yml': 'yaml',
    'md': 'markdown',
    'plaintext': 'text',
  };

  const normalized = lang.toLowerCase().trim();
  return langMap[normalized] || normalized || 'text';
}

/**
 * Get display name for a language
 */
export function getLangDisplayName(lang: string): string {
  const displayNames: Record<string, string> = {
    'javascript': 'JavaScript',
    'typescript': 'TypeScript',
    'python': 'Python',
    'java': 'Java',
    'go': 'Go',
    'rust': 'Rust',
    'cpp': 'C++',
    'c': 'C',
    'csharp': 'C#',
    'php': 'PHP',
    'ruby': 'Ruby',
    'swift': 'Swift',
    'kotlin': 'Kotlin',
    'bash': 'Bash',
    'shell': 'Shell',
    'sql': 'SQL',
    'json': 'JSON',
    'yaml': 'YAML',
    'xml': 'XML',
    'html': 'HTML',
    'css': 'CSS',
    'scss': 'SCSS',
    'markdown': 'Markdown',
    'dockerfile': 'Dockerfile',
    'plaintext': 'Plain Text',
    'text': 'Text',
  };

  const normalized = lang.toLowerCase().trim();
  return displayNames[normalized] || normalized.charAt(0).toUpperCase() + normalized.slice(1);
}

/**
 * Escape HTML special characters
 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
