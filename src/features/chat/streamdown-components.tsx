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

const COLLAPSED_MAX_HEIGHT = 320; // px
const PROCESSED_ATTR = 'data-code-collapse-processed';

/**
 * Adds collapse/expand behavior to code blocks rendered by Streamdown.
 * Uses actual rendered height to decide whether to collapse.
 * Button is placed inside code-block-actions alongside copy/download.
 */
export function applyCodeBlockCollapse(container: HTMLElement | null) {
  if (!container) return;

  const codeBlocks = container.querySelectorAll(
    '[data-streamdown="code-block"]:not([data-language=""])'
  );

  codeBlocks.forEach((block) => {
    const el = block as HTMLElement;

    if (el.hasAttribute(PROCESSED_ATTR)) return;
    el.setAttribute(PROCESSED_ATTR, '');

    const body = el.querySelector('[data-streamdown="code-block-body"]') as HTMLElement | null;
    const target = body || el;

    requestAnimationFrame(() => {
      if (target.scrollHeight <= COLLAPSED_MAX_HEIGHT + 20) {
        el.removeAttribute(PROCESSED_ATTR);
        return;
      }

      // Set collapsed state
      target.dataset.collapsed = 'true';
      target.style.maxHeight = `${COLLAPSED_MAX_HEIGHT}px`;
      target.style.overflow = 'hidden';
      target.style.position = 'relative';

      // Find or create the actions container
      const actionsWrapper = el.querySelector(':scope > :has([data-streamdown="code-block-actions"])');
      let actionsContainer: HTMLElement | null = null;
      if (actionsWrapper) {
        actionsContainer = actionsWrapper.querySelector('[data-streamdown="code-block-actions"]');
      }

      // Create toggle button
      const toggleBtn = document.createElement('button');
      toggleBtn.className = 'code-collapse-toggle';
      toggleBtn.title = '展开全部';
      toggleBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="7 13 12 18 17 13"/><polyline points="7 6 12 11 17 6"/></svg>';
      toggleBtn.addEventListener('click', () => {
        const isCollapsed = target.dataset.collapsed === 'true';
        if (isCollapsed) {
          target.dataset.collapsed = 'false';
          target.style.maxHeight = 'none';
          toggleBtn.title = '收起';
          toggleBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 11 12 6 7 11"/><polyline points="17 18 12 13 7 18"/></svg>';
        } else {
          target.dataset.collapsed = 'true';
          target.style.maxHeight = `${COLLAPSED_MAX_HEIGHT}px`;
          toggleBtn.title = '展开全部';
          toggleBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="7 13 12 18 17 13"/><polyline points="7 6 12 11 17 6"/></svg>';
        }
      });

      // Insert into actions container if found, otherwise append to el
      if (actionsContainer) {
        actionsContainer.prepend(toggleBtn);
      } else {
        el.appendChild(toggleBtn);
      }
    });
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
    const observer = new MutationObserver(() => {
      applyCodeBlockCollapse(container);
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
