/**
 * Custom Streamdown components to override default rendering.
 *
 * Problem 1: Streamdown wraps tables in a "code-block-like" container
 *            (rounded-lg border bg-sidebar) — looks ugly.
 * Solution:  Override table/theader/tbody/tr/th/td to render clean HTML tables.
 *
 * Problem 2: Shiki github-dark/github-light themes have poor contrast for some tokens.
 * Solution:  Override pre/code to ensure proper contrast.
 */

import type { ComponentType } from 'react';
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
