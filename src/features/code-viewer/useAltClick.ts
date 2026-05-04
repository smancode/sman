/**
 * Alt+Click hook — listens for Alt+Click on chat content and extracts
 * file paths to open the code viewer overlay.
 *
 * Extraction strategies:
 * 1. Streamdown code block (data-streamdown="code-block") with language header
 * 2. Inline <code> element containing a file path
 * 3. Plain text that looks like a file path (walk up to nearest meaningful container)
 */

import { useEffect, useCallback } from 'react';
import { useCodeViewerStore } from '@/stores/code-viewer';
import { useChatStore } from '@/stores/chat';

// ── Path helpers ─────────────────────────────────────────────────

/** Characters that commonly surround file paths in tool output */
const PATH_DELIMITERS = /[\s"'`<>{}()[\]|;,&]/;

/**
 * Check if a string looks like a relative file path.
 * Must contain at least one path separator OR have a recognized extension.
 */
function looksLikeRelativePath(s: string): boolean {
  if (s.length < 2 || s.length > 512) return false;
  // Skip URLs
  if (/^https?:\/\//i.test(s)) return false;
  // Skip pure numbers or hex
  if (/^[0-9a-f]+$/i.test(s)) return false;

  const hasSlash = s.includes('/');
  const extMatch = /\.[a-zA-Z0-9]{1,12}$/.test(s) && !/\.(com|org|net|io|dev)$/i.test(s);
  return hasSlash || extMatch;
}

/**
 * Check if a string looks like an absolute Unix file path.
 */
function looksLikeAbsPath(s: string): boolean {
  return s.startsWith('/') && s.length > 2 && !s.startsWith('//');
}

/**
 * Try to extract a file path from a text string, returning {path, start, end} or null.
 */
function extractPathFromText(
  text: string,
  workspace: string,
): { filePath: string; start: number; end: number } | null {
  // Patterns to try, ordered by specificity
  const patterns: Array<{ regex: RegExp; transform: (m: RegExpMatchArray) => string }> = [
    // Absolute path
    {
      regex: /(?<=^|[\s"'`<>{}()[\]|;,&])(\/[^\s"'`<>{}()[\]|;,&]+)/g,
      transform: (m) => m[1],
    },
    // Relative path with extension (src/foo.ts, ./bar/baz.py)
    {
      regex: /(?<=^|[\s"'`<>{}()[\]|;,&])(\.?\.?\/?[a-zA-Z0-9._-]+(?:\/[a-zA-Z0-9._-]+)+\.[a-zA-Z0-9]{1,12})/g,
      transform: (m) => m[1],
    },
  ];

  for (const { regex, transform } of patterns) {
    regex.lastIndex = 0;
    let match: RegExpMatchArray | null;
    while ((match = regex.exec(text)) !== null) {
      const candidate = transform(match);
      if (looksLikeAbsPath(candidate) || looksLikeRelativePath(candidate)) {
        const idx = match.index ?? 0;
        // For absolute paths, verify they start with the workspace
        if (looksLikeAbsPath(candidate)) {
          if (candidate.startsWith(workspace)) {
            const relativePath = candidate.slice(workspace.length).replace(/^\//, '');
            return { filePath: relativePath, start: idx, end: idx + candidate.length };
          }
          continue;
        }
        return { filePath: candidate, start: idx, end: idx + candidate.length };
      }
    }
  }
  return null;
}

/**
 * Extract a file path from the clicked element and its context.
 * Returns { filePath, lineNumber? } or null.
 */
function extractFilePath(
  target: HTMLElement,
  workspace: string,
): { filePath: string; lineNumber?: number } | null {
  // Strategy 1: Streamdown code block
  // The DOM structure is:
  //   div[data-streamdown="code-block"][data-language="ts"]
  //     div[data-streamdown="code-block-header"] → "ts"
  //     div[data-streamdown="code-block-body"] → pre > code
  // When clicking inside the code body or header, walk up to find the code-block container.
  const codeBlock = target.closest('[data-streamdown="code-block"]') as HTMLElement | null;
  if (codeBlock) {
    // The code block header shows the language, but for file path we need to look
    // at the preceding context — typically a markdown heading or the file path
    // shown before the code block in Claude's output.
    // Strategy: look for a filename in the text immediately before this code block.
    const precedingText = getPrecedingTextForCodeBlock(codeBlock);
    if (precedingText) {
      const extracted = extractPathFromText(precedingText, workspace);
      if (extracted) {
        const lineNumber = extractLineNumber(target);
        return { filePath: extracted.filePath, lineNumber };
      }
    }

    // Fallback: check the code-block-header for a filename
    const header = codeBlock.querySelector('[data-streamdown="code-block-header"]');
    if (header?.textContent) {
      const headerText = header.textContent.trim();
      // If the header text looks like a path (contains / or has extension)
      if (looksLikeRelativePath(headerText) || looksLikeAbsPath(headerText)) {
        const extracted = extractPathFromText(headerText, workspace);
        if (extracted) {
          return { filePath: extracted.filePath, lineNumber: extractLineNumber(target) };
        }
      }
    }
  }

  // Strategy 2: Inline <code> element
  // Claude often writes `src/foo/bar.ts` or `path/to/file.ts` in inline code.
  const codeEl = target.closest('code') as HTMLElement | null;
  if (codeEl && !codeBlock) {
    const codeText = codeEl.textContent?.trim() || '';
    if (codeText) {
      const extracted = extractPathFromText(codeText, workspace);
      if (extracted) {
        return { filePath: extracted.filePath };
      }
    }
  }

  // Strategy 3: Plain text node — check if the clicked text itself is a path
  if (target.tagName === 'SPAN' || target.tagName === 'P' || target.tagName === 'A') {
    const text = target.textContent?.trim() || '';
    if (text) {
      const extracted = extractPathFromText(text, workspace);
      if (extracted) {
        return { filePath: extracted.filePath };
      }
    }
  }

  return null;
}

/**
 * Get the text content preceding a code block, typically containing the file path.
 * Claude's output format is usually:
 *   Some descriptive text
 *   `src/path/to/file.ts`  ← could be inline code before the block
 *   ```ts
 *   code here
 *   ```
 */
function getPrecedingTextForCodeBlock(codeBlock: HTMLElement): string {
  const parts: string[] = [];
  let sibling = codeBlock.previousElementSibling as HTMLElement | null;
  // Collect up to 3 preceding siblings for context
  let count = 0;
  while (sibling && count < 3) {
    // Skip other code blocks
    if (sibling.hasAttribute?.('data-streamdown') || sibling.querySelector?.('[data-streamdown]')) {
      break;
    }
    const text = sibling.textContent?.trim() || '';
    if (text) {
      parts.unshift(text);
    }
    sibling = sibling.previousElementSibling as HTMLElement | null;
    count++;
  }
  return parts.join('\n');
}

/**
 * Extract a line number from the click context.
 * Looks for patterns like L42, 第42行, :42, line 42 near the clicked element.
 */
function extractLineNumber(target: HTMLElement): number | undefined {
  // Walk up from target to find the nearest container with surrounding text
  let el: HTMLElement | null = target;

  for (let i = 0; i < 10 && el; i++) {
    const text = el.textContent || '';
    const lineNum = findLineNumberInText(text);
    if (lineNum !== undefined) return lineNum;
    el = el.parentElement;
  }

  return undefined;
}

/**
 * Search a text string for line number patterns.
 */
function findLineNumberInText(text: string): number | undefined {
  // Patterns ordered by specificity
  const patterns = [
    /L(\d{1,5})\b/,           // L42
    /第(\d{1,5})行/,           // 第42行
    /line\s+(\d{1,5})\b/i,    // line 42
    /:(\d{1,5})(?::|\s|$)/,   // :42: or :42 followed by space/end
  ];

  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match) {
      const num = parseInt(match[1], 10);
      if (num > 0 && num < 100000) return num;
    }
  }

  return undefined;
}

// ── Hook ─────────────────────────────────────────────────────────

export function useAltClick() {
  const openViewer = useCodeViewerStore((s) => s.openViewer);

  const handleClick = useCallback(
    (e: MouseEvent) => {
      if (!e.altKey) return;
      const target = e.target as HTMLElement;
      if (!target) return;

      // CRITICAL: Use getState() NOT selector to avoid re-binding during streaming
      const { currentSessionId, sessions } = useChatStore.getState();
      const activeSession = sessions.find((s) => s.key === currentSessionId);
      if (!activeSession?.workspace) return;

      const workspace = activeSession.workspace;
      const extracted = extractFilePath(target, workspace);
      if (extracted) {
        e.preventDefault();
        e.stopPropagation();
        const lineNumber = extractLineNumber(target) || extracted.lineNumber;
        openViewer(workspace, extracted.filePath, lineNumber, currentSessionId);
      }
    },
    [openViewer],
  );

  useEffect(() => {
    document.addEventListener('click', handleClick, true); // capture phase
    return () => document.removeEventListener('click', handleClick, true);
  }, [handleClick]);
}
