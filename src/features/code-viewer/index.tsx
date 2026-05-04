import { useEffect, useCallback, useState } from 'react';
import { useCodeViewerStore } from '@/stores/code-viewer';
import { FileTree } from './FileTree';
import { CodePanel } from './CodePanel';
import { useAltClick } from './useAltClick';
import { cn } from '@/lib/utils';

export function CodeViewerOverlay() {
  const open = useCodeViewerStore((s) => s.open);
  const workspace = useCodeViewerStore((s) => s.workspace);
  const filePath = useCodeViewerStore((s) => s.filePath);
  const closeViewer = useCodeViewerStore((s) => s.closeViewer);
  const loadFile = useCodeViewerStore((s) => s.loadFile);

  const [visible, setVisible] = useState(false);
  const [mounted, setMounted] = useState(false);
  const [treeWidth, setTreeWidth] = useState(240);

  // Animation: mount first, then trigger CSS transition
  useEffect(() => {
    if (open) {
      setMounted(true);
      requestAnimationFrame(() => setVisible(true));
    } else {
      setVisible(false);
      const timer = setTimeout(() => setMounted(false), 150);
      return () => clearTimeout(timer);
    }
  }, [open]);

  // Esc to close
  useEffect(() => {
    if (!open) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeViewer();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [open, closeViewer]);

  const handleSelectFile = useCallback((fp: string) => {
    loadFile(fp);
  }, [loadFile]);

  // Drag to resize tree
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = treeWidth;
    const handleMouseMove = (moveEvent: MouseEvent) => {
      setTreeWidth(Math.max(160, Math.min(400, startWidth + moveEvent.clientX - startX)));
    };
    const handleMouseUp = () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  }, [treeWidth]);

  if (!mounted) return null;

  // Detect dark mode
  const isDark = document.documentElement.classList.contains('dark');

  return (
    <div className={cn(
      'fixed inset-0 z-50 flex transition-opacity duration-150',
      visible ? 'opacity-100' : 'opacity-0',
    )}>
      {/* File tree */}
      <div
        className={cn(
          'h-full shrink-0 border-r flex flex-col',
          isDark ? 'bg-[#161b22] border-[#30363d]' : 'bg-[#f6f8fa] border-[#d0d7de]',
        )}
        style={{ width: treeWidth }}
      >
        <FileTree workspace={workspace} activeFilePath={filePath} onSelectFile={handleSelectFile} onClose={closeViewer} />
      </div>

      {/* Drag handle */}
      <div
        className="w-1 cursor-col-resize hover:bg-[hsl(var(--accent))] active:bg-[hsl(var(--accent))] transition-colors shrink-0"
        onMouseDown={handleMouseDown}
      />

      {/* Code panel */}
      <div className={cn('flex-1 h-full overflow-hidden', isDark ? 'bg-[#0d1117]' : 'bg-white')}>
        <CodePanel workspace={workspace} />
      </div>
    </div>
  );
}

export function CodeViewerProvider() {
  useAltClick();
  return <CodeViewerOverlay />;
}
