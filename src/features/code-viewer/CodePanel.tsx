/**
 * CodePanel — right side of the code viewer overlay.
 *
 * Uses CodeMirror 6 for viewing, editing, search/replace.
 */

import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import CodeMirror, { type ReactCodeMirrorRef } from '@uiw/react-codemirror';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import { java } from '@codemirror/lang-java';
import { css } from '@codemirror/lang-css';
import { html } from '@codemirror/lang-html';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { rust } from '@codemirror/lang-rust';
import { go } from '@codemirror/lang-go';
import { sql } from '@codemirror/lang-sql';
import { xml } from '@codemirror/lang-xml';
import { yaml } from '@codemirror/lang-yaml';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView, Decoration, keymap } from '@codemirror/view';
import { EditorState, StateField, StateEffect } from '@codemirror/state';
import type { Extension } from '@codemirror/state';
import { search, highlightSelectionMatches } from '@codemirror/search';
import { Loader2, AlertTriangle, FileQuestion, Pencil, Save, Eye } from 'lucide-react';
import { useCodeViewerStore, type FileContent, type BinaryFileInfo } from '@/stores/code-viewer';
import { cn } from '@/lib/utils';
import { CodeNavigator } from './CodeNavigator';

// ── Language extension mapping ────────────────────────────────────

function getLanguageExtension(lang: string) {
  const map: Record<string, () => unknown> = {
    javascript: () => javascript({ jsx: true, typescript: false }),
    typescript: () => javascript({ jsx: true, typescript: true }),
    python: () => python(),
    java: () => java(),
    css: () => css(),
    html: () => html(),
    json: () => json(),
    markdown: () => markdown(),
    rust: () => rust(),
    go: () => go(),
    sql: () => sql(),
    xml: () => xml(),
    yaml: () => yaml(),
    vue: () => javascript({ jsx: true }),
  };
  return map[lang]?.() ?? undefined;
}

// ── Effects for line highlight ────────────────────────────────────

const setHighlightLine = StateEffect.define<number | null>();
const highlightLineField = StateField.define<number | null>({
  create: () => null,
  update: (val, tr) => {
    for (const e of tr.effects) {
      if (e.is(setHighlightLine)) return e.value;
    }
    return val;
  },
});

const highlightLineDeco = EditorView.decorations.compute([highlightLineField], (state) => {
  const line = state.field(highlightLineField);
  if (line === null || line < 1 || line > state.doc.lines) return Decoration.none;
  const lineInfo = state.doc.line(line);
  const lineDeco = Decoration.line({ class: 'cm-activeLineBackground' });
  return Decoration.set([lineDeco.range(lineInfo.from)]);
});

// ── CodePanel (top-level) ──────────────────────────────────────────

interface CodePanelProps {
  workspace: string;
}

export function CodePanel({ workspace }: CodePanelProps) {
  const currentFile = useCodeViewerStore((s) => s.currentFile);
  const loading = useCodeViewerStore((s) => s.loading);
  const error = useCodeViewerStore((s) => s.error);
  const lineNumber = useCodeViewerStore((s) => s.lineNumber);

  if (loading && !currentFile) {
    return (
      <div className="flex-1 min-h-0 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <Loader2 className="h-8 w-8 animate-spin" />
          <span className="text-[13px]">加载文件中...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex-1 min-h-0 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-destructive max-w-md px-4 text-center">
          <AlertTriangle className="h-8 w-8 shrink-0" />
          <span className="text-[13px]">{error}</span>
        </div>
      </div>
    );
  }

  if (!currentFile) {
    return (
      <div className="flex-1 min-h-0 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <FileQuestion className="h-8 w-8 shrink-0" />
          <span className="text-[13px]">点击左侧文件查看内容</span>
        </div>
      </div>
    );
  }

  if ('type' in currentFile && currentFile.type === 'binary') {
    return <BinaryPanel file={currentFile} />;
  }

  return (
    <CodeContent
      file={currentFile as FileContent}
      highlightLine={lineNumber}
      workspace={workspace}
    />
  );
}

// ── BinaryPanel ────────────────────────────────────────────────────

function BinaryPanel({ file }: { file: BinaryFileInfo }) {
  const sizeStr = useMemo(() => {
    const bytes = file.size;
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }, [file.size]);

  return (
    <div className="flex-1 min-h-0 flex items-center justify-center">
      <div className="flex flex-col items-center gap-3 text-muted-foreground text-center">
        <FileQuestion className="h-8 w-8 shrink-0" />
        <span className="text-[12px] font-medium">二进制文件</span>
        <span className="text-[12px]">
          {file.fileName} ({sizeStr}, {file.mimeType})
        </span>
        <span className="text-[12px] text-muted-foreground/60">
          此文件类型无法预览
        </span>
      </div>
    </div>
  );
}

// ── CodeContent ────────────────────────────────────────────────────

interface CodeContentProps {
  file: FileContent;
  highlightLine: number | null;
  workspace: string;
}

function CodeContent({ file, highlightLine, workspace }: CodeContentProps) {
  const editable = useCodeViewerStore((s) => s.editable);
  const dirty = useCodeViewerStore((s) => s.dirty);
  const saving = useCodeViewerStore((s) => s.saving);
  const setEditable = useCodeViewerStore((s) => s.setEditable);
  const markDirty = useCodeViewerStore((s) => s.markDirty);
  const saveFile = useCodeViewerStore((s) => s.saveFile);

  const cmRef = useRef<ReactCodeMirrorRef>(null);
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));

  const filePath = file.path;
  const language = file.language;

  // Listen for dark mode changes
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(document.documentElement.classList.contains('dark'));
    });
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    });
    return () => observer.disconnect();
  }, []);

  // Scroll to highlight line
  useEffect(() => {
    if (!highlightLine || !cmRef.current?.view) return;
    const view = cmRef.current.view;
    if (highlightLine <= view.state.doc.lines) {
      const pos = view.state.doc.line(highlightLine).from;
      view.dispatch({
        effects: [
          EditorView.scrollIntoView(pos, { y: 'center' }),
          setHighlightLine.of(highlightLine),
        ],
      });
    }
  }, [highlightLine]);

  // Ctrl+S to save
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        if (editable && dirty) saveFile();
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [editable, dirty, saveFile]);

  // Build extensions
  const extensions = useMemo(() => {
    const exts: Extension[] = [
      search({ top: true }),
      highlightSelectionMatches(),
      highlightLineField,
      highlightLineDeco,
      EditorView.lineWrapping,
      keymap.of([
        {
          key: 'Mod-s',
          run: () => {
            if (editable && dirty) saveFile();
            return true;
          },
        },
      ]),
    ];

    const langExt = getLanguageExtension(language);
    if (langExt) exts.push(langExt as Extension);

    return exts;
  }, [language, editable, dirty, saveFile]);

  const handleChange = useCallback((value: string) => {
    // Update file content in store so saveFile can read it
    const currentFile = useCodeViewerStore.getState().currentFile;
    if (currentFile && 'content' in currentFile) {
      useCodeViewerStore.setState({
        currentFile: { ...currentFile, content: value },
      });
      markDirty();
    }
  }, [markDirty]);

  const handleToggleEdit = useCallback(() => {
    setEditable(!editable);
  }, [editable, setEditable]);

  return (
    <div className="flex flex-col h-full min-h-0 relative">
      {/* File header with edit toggle */}
      <FileHeader
        filePath={filePath}
        language={language}
        lineCount={file.totalLines}
        truncated={file.truncated}
        editable={editable}
        dirty={dirty}
        saving={saving}
        onToggleEdit={handleToggleEdit}
        onSave={saveFile}
      />

      {/* CodeMirror editor */}
      <div className="flex-1 min-h-0 overflow-hidden">
        <CodeMirror
          ref={cmRef}
          value={file.content}
          height="100%"
          theme={isDark ? oneDark : undefined}
          extensions={extensions}
          editable={editable}
          onChange={editable ? handleChange : undefined}
          className="h-full text-[13px]"
          basicSetup={{
            lineNumbers: true,
            highlightActiveLine: true,
            bracketMatching: true,
            closeBrackets: editable,
            indentOnInput: editable,
            foldGutter: true,
            searchKeymap: true,
          }}
        />
      </div>

      {/* Navigator overlay */}
      <CodeNavigator workspace={workspace} currentFilePath={filePath} />
    </div>
  );
}

// ── FileHeader ─────────────────────────────────────────────────────

interface FileHeaderProps {
  filePath: string;
  language: string;
  lineCount: number;
  truncated: boolean;
  editable: boolean;
  dirty: boolean;
  saving: boolean;
  onToggleEdit: () => void;
  onSave: () => void;
}

const FileHeader = memo(function FileHeader({
  filePath,
  language,
  lineCount,
  truncated,
  editable,
  dirty,
  saving,
  onToggleEdit,
  onSave,
}: FileHeaderProps) {
  return (
    <div className="flex items-center gap-2 px-4 py-2 border-b border-[hsl(var(--border))] shrink-0 bg-[hsl(var(--card))]">
      <span className="text-[13px] text-[hsl(var(--foreground))] font-medium truncate flex-1 min-w-0">
        {filePath}
      </span>
      <span className="text-[11px] text-muted-foreground/60 shrink-0">
        {language}
      </span>
      <span className="text-[11px] text-muted-foreground/60 shrink-0">
        {lineCount} 行
      </span>
      {truncated && (
        <span className="text-[11px] text-amber-500 shrink-0">
          (已截断)
        </span>
      )}

      {/* Edit/Save controls */}
      <div className="flex items-center gap-1 shrink-0 ml-2">
        <button
          onClick={onToggleEdit}
          className={cn(
            'flex items-center gap-1 px-2 py-1 text-[12px] rounded border transition-colors',
            editable
              ? 'border-blue-400 bg-blue-500/10 text-blue-500'
              : 'border-[hsl(var(--border))] hover:bg-[hsl(var(--muted))] text-muted-foreground',
          )}
          title={editable ? '切换为只读' : '切换为编辑'}
        >
          {editable ? <Eye className="h-3 w-3" /> : <Pencil className="h-3 w-3" />}
          {editable ? '只读' : '编辑'}
        </button>
        {editable && dirty && (
          <button
            onClick={onSave}
            disabled={saving}
            className="flex items-center gap-1 px-2 py-1 text-[12px] rounded border border-green-400 bg-green-500/10 text-green-500 hover:bg-green-500/20 disabled:opacity-50 transition-colors"
            title="保存 (Ctrl+S)"
          >
            {saving ? <Loader2 className="h-3 w-3 animate-spin" /> : <Save className="h-3 w-3" />}
            {saving ? '保存中...' : '保存'}
          </button>
        )}
        {editable && dirty && (
          <span className="text-[11px] text-amber-500 shrink-0">已修改</span>
        )}
      </div>
    </div>
  );
});
