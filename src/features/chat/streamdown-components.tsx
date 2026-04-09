/**
 * Custom Streamdown components to override default rendering.
 *
 * 1. Tables: render clean HTML without the "card-like" wrapper
 * 2. Code blocks: no border for untyped blocks; compact header for typed blocks
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
