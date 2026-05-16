---
_scanned.commitHash: "57e98c308c1cd0fc5693b3ebab5282836e02a241"
---

# 开发规范

Top 7 conventions from incremental scan (commit 57e98c308c1cd0fc5693b3ebab5282836e02a241).

## 1. Zustand Store Pattern

All client state uses Zustand with WebSocket sync pattern:
- Export `useXxxStore` from `src/stores/xxx.ts`
- State interface includes data + async actions + loading/error states
- Get WebSocket client via `useWsConnection.getState().client`
- Wrap event handlers with `wrapHandler()` for type safety
- Store initialization in `src/lib/query-client.ts`

## 2. Feature Directory Structure

Each feature is self-contained under `src/features/`:
```
src/features/{feature-name}/
  ├── index.tsx           # Main feature component (exports default)
  ├── {Feature}Panel.tsx  # Feature-specific UI (if complex)
  └── sub-components.tsx  # Supporting components
```
Feature components are routed in `src/app/routes.tsx` and rendered in layout.

## 3. Server Handler Pattern

Server modules in `server/` follow consistent patterns:
- Handler files: `{module}-handler.ts` (e.g., `git-handler.ts`, `code-viewer-handler.ts`)
- Store files: `{module}-store.ts` (e.g., `session-store.ts`, `settings-manager.ts`)
- Engine files: `{module}-engine.ts` for business logic (e.g., `batch-engine.ts`, `smart-path-engine.ts`)
- Use `createLogger()` from `./utils/logger.js` for logging
- Database operations use `better-sqlite3` with prepared statements

## 4. TypeScript Interface Exports

All modules export explicit TypeScript interfaces:
- Server: define interfaces at top of handler files (e.g., `GitStatusResult`, `ListDirResult`)
- Client: export interfaces from `src/types/` (e.g., `ChatSession`, `SmanSettings`)
- Props: define `export interface XxxProps` for React components
- Use JSDoc comments for complex types (e.g., `ContentBlock`, `StreamingBlock`)

## 5. Async Error Handling

Consistent error handling patterns across codebase:
- `try { ... } catch { /* silent or fallback */ }` for optional operations
- `try { ... } catch (err: unknown) { const e = err as { code?: string }; ... }` for typed errors
- Throw `Error` with code assignment: `throw Object.assign(new Error('msg'), { code: 'PATH_TRAVERSAL' })`
- Server handlers wrap errors in try-catch and return via WebSocket
- Client stores set `error: string | null` in state for UI display

## 6. 常量数组国际化模式

> by nasakim | 验证: 2026-05 | ✅ [已验证] src/types/settings.ts:WEB_SEARCH_PROVIDER_OPTIONS

所有用户可见的常量数组必须使用 `labelKey` 模式，禁止硬编码中文：

**❌ 错误示例**（当前代码待修复）：
```typescript
// src/types/settings.ts
export const WEB_SEARCH_PROVIDER_OPTIONS = [
  { value: 'baidu', label: '百度搜索', description: '百度 AI 搜索 API' },
  { value: 'brave', label: 'Brave Search', description: 'API Key 搜索引擎' },
];
```

**✅ 正确示例**（已在 smart-paths/index.tsx 使用）：
```typescript
const STATUS_CONFIG = {
  draft: { labelKey: 'smartpath.status.draft', variant: 'secondary' },
  running: { labelKey: 'smartpath.executing', variant: 'default' },
};

// 渲染时
<Badge>{t(sc.labelKey)}</Badge>
```

**改造要点**：
- 常量定义用 `labelKey` 存储 i18n key
- 组件内调用 `t(labelKey)` 渲染
- 搜索替换时要覆盖所有常量文件（如 `src/types/`）
- 非设置页面（Git、CodeViewer）也不能遗漏

## 7. 国际化函数调用约束

> by nasakim | 验证: 2026-05 | ✅ [已验证] CLAUDE.md:多语言规范

- **用户界面文本**：禁止硬编码，必须通过 `t()` 函数
- **日志/调试信息**：可硬编码（开发者可见，非用户界面）
- **模块顶层禁止**：不能在模块顶层调用 `t()`（locale 未初始化）
- **动态拼接**：翻译文件用完整句子 + 参数插值，禁止拼接片段

**错误示例**：
```typescript
// ❌ 模块顶层调用
const MENU_ITEMS = [
  { label: t('menu.new'), key: 'new' }
];

// ❌ 动态拼接
t('file.count') + ': ' + count
```

**正确示例**：
```typescript
// ✅ 常量用 labelKey
const MENU_ITEMS = [
  { labelKey: 'menu.new', key: 'new' }
];
{MENU_ITEMS.map(item => <MenuItem>{t(item.labelKey)}</MenuItem>)}

// ✅ 翻译文件用参数插值
// zh-CN.json: "file.count": { "text": "共 {count} 个文件" }
t('file.count', { count })
```

## References

See `references/conventions.md` for detailed examples and rationale.
