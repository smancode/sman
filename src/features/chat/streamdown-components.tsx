/**
 * Custom Streamdown components to override default rendering.
 *
 * 1. Tables: render clean HTML without the "card-like" wrapper
 * 2. Code blocks: no border for untyped blocks; compact header for typed blocks
 */

import { useEffect, useRef } from 'react';
import type { Components } from 'streamdown';

/** Custom components for Streamdown — clean table rendering without the wrapper */
export const streamdownComponents: Components = {
  // ── Table: render directly without the "card-like" wrapper ──
  table: ({ children, ...props }: Record<string, any>) => (
    <div className="my-2 overflow-x-auto">
      <table
        className="w-full border-collapse text-sm"
        {...props}
      >
        {children}
      </table>
    </div>
  ),
  thead: ({ children, ...props }: Record<string, any>) => (
    <thead className="bg-muted/60" {...props}>{children}</thead>
  ),
  tbody: ({ children, ...props }: Record<string, any>) => (
    <tbody className="divide-y divide-border" {...props}>{children}</tbody>
  ),
  tr: ({ children, ...props }: Record<string, any>) => (
    <tr className="border-b border-border hover:bg-muted/30 transition-colors" {...props}>{children}</tr>
  ),
  th: ({ children, ...props }: Record<string, any>) => (
    <th className="px-3 py-1.5 text-left font-semibold text-sm whitespace-nowrap" {...props}>{children}</th>
  ),
  td: ({ children, ...props }: Record<string, any>) => (
    <td className="px-3 py-1.5 text-sm" {...props}>{children}</td>
  ),

  // ── Links ──
  a: ({ href, children, ...props }: Record<string, any>) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary hover:underline break-words" {...props}>
      {children}
    </a>
  ),
} as any;

// ── Code Block Collapse Logic ──────────────────────────────────

const COLLAPSE_THRESHOLD = 15;
const COLLAPSED_MAX_HEIGHT = 384; // ~15 lines * ~25.6px per line

/**
 * Adds collapse/expand behavior to code blocks rendered by Streamdown.
 * Called via useEffect after DOM is ready.
 */
export function applyCodeBlockCollapse(container: HTMLElement | null) {
  if (!container) return;

  const codeBlocks = container.querySelectorAll('[data-streamdown="code-block-body"]');

  codeBlocks.forEach((block) => {
    // Skip already processed blocks
    if ((block as HTMLElement).dataset.collapsed !== undefined) return;

    const el = block as HTMLElement;
    const lines = el.querySelectorAll('.line');
    const lineCount = lines.length;

    // Also check via newline count as fallback
    const textContent = el.textContent || '';
    const textLineCount = textContent.split('\n').filter(l => l.trim()).length;
    const effectiveLines = Math.max(lineCount, textLineCount);

    if (effectiveLines <= COLLAPSE_THRESHOLD) return;

    // Mark as processed and set initial collapsed state
    el.dataset.collapsed = 'true';
    el.style.maxHeight = `${COLLAPSED_MAX_HEIGHT}px`;
    el.style.overflow = 'hidden';
    el.style.position = 'relative';

    // Create expand button
    const expandBtn = document.createElement('button');
    expandBtn.className = 'code-collapse-btn';
    expandBtn.textContent = `展开全部 (${effectiveLines} 行)`;
    expandBtn.addEventListener('click', () => {
      if (el.dataset.collapsed === 'true') {
        el.dataset.collapsed = 'false';
        el.style.maxHeight = 'none';
        expandBtn.textContent = '收起';
        expandBtn.classList.add('expanded');
      } else {
        el.dataset.collapsed = 'false';
        // Re-collapse
        el.dataset.collapsed = 'true';
        el.style.maxHeight = `${COLLAPSED_MAX_HEIGHT}px`;
        expandBtn.textContent = `展开全部 (${effectiveLines} 行)`;
        expandBtn.classList.remove('expanded');
      }
    });

    // Insert button after the code block body
    el.insertAdjacentElement('afterend', expandBtn);
  });
}

/**
 * React hook that applies code block collapse behavior to a container ref.
 * Re-runs on content changes via MutationObserver.
 */
export function useCodeBlockCollapse<T extends HTMLElement = HTMLDivElement>() {
  const ref = useRef<T>(null);

  useEffect(() => {
    const container = ref.current;
    if (!container) return;

    // Initial pass
    applyCodeBlockCollapse(container);

    // Watch for new code blocks added during streaming
    const observer = new MutationObserver((mutations) => {
      let hasNewBlocks = false;
      for (const mutation of mutations) {
        if (mutation.type === 'childList' && mutation.addedNodes.length > 0) {
          hasNewBlocks = true;
          break;
        }
        // Also handle text changes during streaming
        if (mutation.type === 'characterData') {
          hasNewBlocks = true;
          break;
        }
      }
      if (hasNewBlocks) {
        applyCodeBlockCollapse(container);
      }
    });

    observer.observe(container, {
      childList: true,
      subtree: true,
      characterData: true,
    });

    return () => observer.disconnect();
  }, []);

  return ref;
}
