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
import { Streamdown } from 'streamdown';
import 'streamdown/styles.css';
import { Loader2, AlertTriangle, FileQuestion, Pencil, Save, Eye, ZoomIn, ZoomOut, Code2 } from 'lucide-react';
import { useCodeViewerStore, type FileContent, type BinaryFileInfo } from '@/stores/code-viewer';
import { cn } from '@/lib/utils';
import { authFetch } from '@/lib/auth';
import { streamdownComponents } from '@/features/chat/streamdown-components';
import { useCodePlugin } from '@/lib/streamdown-plugins';
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
      <div className="h-full flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <Loader2 className="h-8 w-8 animate-spin" />
          <span className="text-[13px]">加载文件中...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="h-full flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-destructive max-w-md px-4 text-center">
          <AlertTriangle className="h-8 w-8 shrink-0" />
          <span className="text-[13px]">{error}</span>
        </div>
      </div>
    );
  }

  if (!currentFile) {
    return (
      <div className="h-full flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <FileQuestion className="h-8 w-8 shrink-0" />
          <span className="text-[13px]">点击左侧文件查看内容</span>
        </div>
      </div>
    );
  }

  if ('type' in currentFile && currentFile.type === 'binary') {
    const isImage = currentFile.mimeType.startsWith('image/');
    if (isImage) {
      return <ImagePanel file={currentFile} workspace={workspace} />;
    }
    return <BinaryPanel file={currentFile} />;
  }

  return (
    <CodeContent
      file={currentFile as FileContent}
      highlightLine={lineNumber}
      workspace={workspace}
      isMarkdown={(currentFile as FileContent).language === 'markdown'}
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
    <div className="h-full flex items-center justify-center">
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

// ── ImagePanel ─────────────────────────────────────────────────────

const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.bmp', '.ico', '.svg', '.webp']);

function isImageViewable(file: BinaryFileInfo): boolean {
  if (file.mimeType.startsWith('image/')) return true;
  const ext = '.' + (file.fileName.split('.').pop() || '').toLowerCase();
  return IMAGE_EXTENSIONS.has(ext);
}

function ImagePanel({ file, workspace }: { file: BinaryFileInfo; workspace: string }) {
  const [imgUrl, setImgUrl] = useState<string | null>(null);
  const [imgError, setImgError] = useState(false);
  const [zoom, setZoom] = useState(100);
  const [naturalSize, setNaturalSize] = useState({ w: 0, h: 0 });

  useEffect(() => {
    let revoked = false;
    const params = new URLSearchParams({ workspace, file: file.path });
    authFetch(`/api/code/image?${params}`)
      .then((res) => {
        if (!res.ok) throw new Error('Failed to load image');
        return res.blob();
      })
      .then((blob) => {
        if (revoked) return;
        setImgUrl(URL.createObjectURL(blob));
        setImgError(false);
      })
      .catch(() => {
        if (!revoked) setImgError(true);
      });
    return () => {
      revoked = true;
      if (imgUrl) URL.revokeObjectURL(imgUrl);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace, file.path]);

  const handleZoomIn = useCallback(() => setZoom((z) => Math.min(z + 25, 500)), []);
  const handleZoomOut = useCallback(() => setZoom((z) => Math.max(z - 25, 10)), []);
  const handleZoomReset = useCallback(() => setZoom(100), []);

  if (imgError) {
    return <BinaryPanel file={file} />;
  }

  if (!imgUrl) {
    return (
      <div className="h-full flex items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  const zoomLabel = zoom === 100 ? '1:1' : `${zoom}%`;

  return (
    <div className="h-full flex flex-col min-h-0">
      {/* Image area */}
      <div className="flex-1 min-h-0 overflow-auto flex items-center justify-center p-4 bg-[hsl(var(--muted))]/30">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={imgUrl}
          alt={file.fileName}
          style={{ transform: `scale(${zoom / 100})`, transformOrigin: 'center center' }}
          className="max-w-none transition-transform duration-150"
          onLoad={(e) => {
            const img = e.currentTarget;
            setNaturalSize({ w: img.naturalWidth, h: img.naturalHeight });
          }}
          draggable={false}
        />
      </div>

      {/* Bottom toolbar */}
      <div className="shrink-0 flex items-center justify-center gap-3 px-4 py-1.5 border-t border-[hsl(var(--border))] bg-[hsl(var(--card))]">
        <span className="text-[11px] text-muted-foreground/60 mr-2">
          {file.fileName} {naturalSize.w > 0 && `(${naturalSize.w}×${naturalSize.h})`}
        </span>
        <button
          onClick={handleZoomOut}
          disabled={zoom <= 10}
          className="p-1 rounded hover:bg-[hsl(var(--muted))] transition-colors disabled:opacity-30"
          title="缩小"
        >
          <ZoomOut className="w-4 h-4 text-muted-foreground" />
        </button>
        <button
          onClick={handleZoomReset}
          className="px-2 py-0.5 text-[11px] text-muted-foreground rounded hover:bg-[hsl(var(--muted))] transition-colors min-w-[40px] text-center"
          title="1:1 原始大小"
        >
          {zoomLabel}
        </button>
        <button
          onClick={handleZoomIn}
          disabled={zoom >= 500}
          className="p-1 rounded hover:bg-[hsl(var(--muted))] transition-colors disabled:opacity-30"
          title="放大"
        >
          <ZoomIn className="w-4 h-4 text-muted-foreground" />
        </button>
      </div>
    </div>
  );
}

// ── MarkdownPreview ─────────────────────────────────────────────────

function MarkdownPreview({ content }: { content: string }) {
  const codePlugin = useCodePlugin();
  return (
    <div className="px-6 py-4 prose prose-sm dark:prose-invert max-w-none break-words text-foreground">
      <Streamdown
        mode="static"
        components={streamdownComponents}
        controls={{ code: true, table: true }}
        plugins={codePlugin ? { code: codePlugin } : undefined}
      >
        {content}
      </Streamdown>
    </div>
  );
}

// ── CodeContent ────────────────────────────────────────────────────

interface CodeContentProps {
  file: FileContent;
  highlightLine: number | null;
  workspace: string;
  isMarkdown: boolean;
}

function CodeContent({ file, highlightLine, workspace, isMarkdown }: CodeContentProps) {
  const editable = useCodeViewerStore((s) => s.editable);
  const dirty = useCodeViewerStore((s) => s.dirty);
  const saving = useCodeViewerStore((s) => s.saving);
  const setEditable = useCodeViewerStore((s) => s.setEditable);
  const markDirty = useCodeViewerStore((s) => s.markDirty);
  const saveFile = useCodeViewerStore((s) => s.saveFile);

  const cmRef = useRef<ReactCodeMirrorRef>(null);
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));

  // Markdown-specific state
  const [showSource, setShowSource] = useState(false);
  const [renderedContent, setRenderedContent] = useState(file.content);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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

  // Markdown: debounce re-render on content change
  const updateMarkdownRender = useCallback((content: string) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setRenderedContent(content);
    }, 1000);
  }, []);

  // Clean debounce timer on unmount
  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  // Sync rendered content when file changes (not from editing)
  useEffect(() => {
    if (!editable) {
      setRenderedContent(file.content);
    }
  }, [file.content, editable]);

  // Reset showSource when file changes
  useEffect(() => {
    setShowSource(false);
  }, [filePath]);

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
      EditorState.phrases.of({
        'Find': '查找',
        'Replace': '替换',
        'next': '下一个',
        'previous': '上一个',
        'all': '全部',
        'match case': '区分大小写',
        'regexp': '正则表达式',
        'by word': '全词匹配',
        'replace': '替换',
        'replace all': '全部替换',
        'close': '关闭',
        'current match': '当前匹配',
        'on line': '行',
        'replaced match on line $': '已替换第 $ 行的匹配',
        'replaced $ matches': '已替换 $ 处匹配',
        'Go to line': '跳转到行',
        'go': '跳转',
      }),
      search({ top: true }),
      highlightSelectionMatches(),
      highlightLineField,
      highlightLineDeco,
      EditorView.lineWrapping,
      EditorState.readOnly.of(!editable),
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
    const currentFile = useCodeViewerStore.getState().currentFile;
    if (currentFile && 'content' in currentFile) {
      useCodeViewerStore.setState({
        currentFile: { ...currentFile, content: value },
      });
      markDirty();
    }
    // Markdown: debounce re-render
    if (isMarkdown) {
      updateMarkdownRender(value);
    }
  }, [markDirty, isMarkdown, updateMarkdownRender]);

  const handleToggleEdit = useCallback(() => {
    setEditable(!editable);
  }, [editable, setEditable]);

  // Determine what to show in the body area
  const isMarkdownRenderMode = isMarkdown && !showSource && !editable;
  const isMarkdownEditMode = isMarkdown && !showSource && editable;
  const showCodeEditor = !isMarkdown || showSource || editable;

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
        isMarkdown={isMarkdown}
        showSource={showSource}
        onToggleSource={() => setShowSource(!showSource)}
      />

      {/* Body area */}
      {isMarkdownEditMode ? (
        // Markdown edit mode: left editor + right preview
        <div className="flex-1 min-h-0 flex">
          {/* Editor pane */}
          <div className="w-1/2 min-h-0 overflow-hidden border-r border-[hsl(var(--border))]">
            <CodeMirror
              ref={cmRef}
              value={file.content}
              height="100%"
              theme={isDark ? oneDark : undefined}
              extensions={extensions}
              onChange={handleChange}
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
          {/* Preview pane */}
          <div className="w-1/2 min-h-0 overflow-auto">
            <MarkdownPreview content={renderedContent} />
          </div>
        </div>
      ) : isMarkdownRenderMode ? (
        // Markdown read mode: full render
        <div className="flex-1 min-h-0 overflow-auto">
          <MarkdownPreview content={file.content} />
        </div>
      ) : (
        // Source code mode (or non-markdown)
        <div className="flex-1 min-h-0 overflow-hidden">
          <CodeMirror
            ref={cmRef}
            value={file.content}
            height="100%"
            theme={isDark ? oneDark : undefined}
            extensions={extensions}
            onChange={handleChange}
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
      )}

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
  isMarkdown?: boolean;
  showSource?: boolean;
  onToggleSource?: () => void;
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
  isMarkdown,
  showSource,
  onToggleSource,
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

      {/* Show source toggle (markdown only) */}
      {isMarkdown && onToggleSource && (
        <button
          onClick={onToggleSource}
          className={cn(
            'flex items-center gap-1 px-2 py-1 text-[12px] rounded border transition-colors shrink-0',
            showSource
              ? 'border-purple-400 bg-purple-500/10 text-purple-500'
              : 'border-[hsl(var(--border))] hover:bg-[hsl(var(--muted))] text-muted-foreground',
          )}
          title={showSource ? '显示渲染' : '显示源码'}
        >
          <Code2 className="h-3 w-3" />
          源码
        </button>
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
