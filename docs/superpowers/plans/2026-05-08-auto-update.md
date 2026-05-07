# Auto-Update Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add silent background auto-update for Electron desktop app using static file server (generic provider), with user-controlled installation from Settings page.

**Architecture:** `electron-updater` checks for updates on app startup (delayed 5s), downloads silently in background. When download completes, a top banner notifies the user. Installation is only triggered manually from the Settings page.

**Tech Stack:** electron-updater, Zustand (update store), IPC (main→renderer communication)

---

## Chunk 1: Backend — electron-updater + IPC

### Task 1: Install electron-updater dependency

**Files:**
- Modify: `package.json`

- [ ] **Step 1: Install package**

Run: `pnpm add electron-updater`

- [ ] **Step 2: Add publish config to package.json build section**

In `package.json`, inside the `"build"` object, add the `publish` config before `"mac"`:

```json
"publish": {
  "provider": "generic",
  "url": "https://update.example.com/sman"
},
```

This is a default placeholder; users will override it via Settings.

- [ ] **Step 3: Commit**

```bash
git add package.json pnpm-lock.yaml
git commit -m "chore: add electron-updater dependency and publish config"
```

---

### Task 2: Add update IPC handlers in main process

**Files:**
- Modify: `electron/main.ts`

- [ ] **Step 1: Add electron-updater import and setup**

At the top of `electron/main.ts`, add after existing imports:

```ts
import { autoUpdater } from 'electron-updater';
```

After `const isDev` line (line 35), add autoUpdater configuration:

```ts
// Auto-updater: silent download, never auto-install
autoUpdater.autoDownload = true;
autoUpdater.autoInstallOnAppQuit = false;
autoUpdater.autoRunAppAfterInstall = true;
```

- [ ] **Step 2: Add update IPC handlers**

In `registerIpcHandlers()`, add after the existing `git:getBranch` handler:

```ts
// Update handlers
let updateInfo: { version: string } | null = null;

ipcMain.handle('updater:check', async () => {
  if (isDev) return { status: 'not-available' };
  try {
    const result = await autoUpdater.checkForUpdates();
    if (result?.updateInfo) {
      return { status: 'available', version: result.updateInfo.version };
    }
    return { status: 'not-available' };
  } catch (err) {
    return { status: 'error', message: err instanceof Error ? err.message : String(err) };
  }
});

ipcMain.handle('updater:install', async () => {
  if (!updateInfo) return;
  autoUpdater.quitAndInstall();
});

ipcMain.handle('updater:setFeedURL', async (_event, url: string) => {
  if (!url || typeof url !== 'string') return;
  autoUpdater.setFeedURL({ provider: 'generic', url });
});
```

- [ ] **Step 3: Add update event forwarding**

After `registerIpcHandlers()` function, still inside it, add event listeners:

```ts
// Forward autoUpdater events to renderer
autoUpdater.on('update-available', (info) => {
  updateInfo = { version: info.version };
  mainWindow?.webContents.send('updater:available', { version: info.version, releaseNotes: info.releaseNotes });
});

autoUpdater.on('update-not-available', () => {
  mainWindow?.webContents.send('updater:not-available');
});

autoUpdater.on('update-downloaded', (info) => {
  updateInfo = { version: info.version };
  mainWindow?.webContents.send('updater:downloaded', { version: info.version });
});

autoUpdater.on('error', (err) => {
  mainWindow?.webContents.send('updater:error', { message: err?.message || 'Unknown error' });
});
```

- [ ] **Step 4: Add auto-check on startup**

In the `app.whenReady().then(async () => { ... })` block, after `createWindow();` (line 301), add:

```ts
// Auto-check for updates after 5s delay (production only)
if (!isDev) {
  setTimeout(() => {
    autoUpdater.checkForUpdates().catch(() => {});
  }, 5000);
}
```

- [ ] **Step 5: Verify compilation**

Run: `pnpm build:electron`
Expected: No errors

- [ ] **Step 6: Commit**

```bash
git add electron/main.ts
git commit -m "feat(updater): add auto-updater IPC handlers in main process"
```

---

### Task 3: Add preload bridge for updater

**Files:**
- Modify: `electron/preload.ts`

- [ ] **Step 1: Add updater API to preload**

In `electron/preload.ts`, inside the `contextBridge.exposeInMainWorld('sman', { ... })` object, add after `getGitBranch`:

```ts
  // Auto-updater
  updater: {
    check: () => ipcRenderer.invoke('updater:check'),
    install: () => ipcRenderer.invoke('updater:install'),
    setFeedURL: (url: string) => ipcRenderer.invoke('updater:setFeedURL', url),
    onUpdateAvailable: (callback: (info: { version: string; releaseNotes?: string }) => void) => {
      const handler = (_event: Electron.IpcRendererEvent, data: { version: string; releaseNotes?: string }) => callback(data);
      ipcRenderer.on('updater:available', handler);
      return () => ipcRenderer.removeListener('updater:available', handler);
    },
    onUpdateNotAvailable: (callback: () => void) => {
      const handler = () => callback();
      ipcRenderer.on('updater:not-available', handler);
      return () => ipcRenderer.removeListener('updater:not-available', handler);
    },
    onUpdateDownloaded: (callback: (info: { version: string }) => void) => {
      const handler = (_event: Electron.IpcRendererEvent, data: { version: string }) => callback(data);
      ipcRenderer.on('updater:downloaded', handler);
      return () => ipcRenderer.removeListener('updater:downloaded', handler);
    },
    onUpdateError: (callback: (info: { message: string }) => void) => {
      const handler = (_event: Electron.IpcRendererEvent, data: { message: string }) => callback(data);
      ipcRenderer.on('updater:error', handler);
      return () => ipcRenderer.removeListener('updater:error', handler);
    },
  },
```

- [ ] **Step 2: Verify compilation**

Run: `pnpm build:electron`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add electron/preload.ts
git commit -m "feat(updater): add updater IPC bridge to preload"
```

---

## Chunk 2: Frontend — Update Store + Banner + Settings

### Task 4: Add i18n keys for updater

**Files:**
- Modify: `src/locales/zh-CN.json`
- Modify: `src/locales/en-US.json`

- [ ] **Step 1: Add Chinese translations**

In `src/locales/zh-CN.json`, before the closing `}`, add after `"chat.input.hint"` entry:

```json
  "update.banner.ready": {
    "text": "新版本 v{version} 已就绪",
    "context": "更新下载完成后的横幅提示，{version} 是版本号"
  },
  "update.banner.goSettings": {
    "text": "前往设置",
    "context": "横幅中跳转设置页的按钮"
  },
  "update.banner.close": {
    "text": "关闭",
    "context": "关闭更新横幅"
  },
  "update.banner.error": {
    "text": "更新检查失败",
    "context": "更新检查出错的横幅提示"
  },
  "update.settings.title": {
    "text": "检查更新",
    "context": "设置页更新区域标题"
  },
  "update.settings.currentVersion": {
    "text": "当前版本",
    "context": "当前版本号标签"
  },
  "update.settings.checkButton": {
    "text": "检查更新",
    "context": "手动检查更新按钮"
  },
  "update.settings.checking": {
    "text": "正在检查...",
    "context": "正在检查更新中的状态"
  },
  "update.settings.downloading": {
    "text": "正在下载更新...",
    "context": "正在下载更新中的状态"
  },
  "update.settings.ready": {
    "text": "新版本 v{version} 已就绪",
    "context": "更新已下载完成，{version} 是版本号"
  },
  "update.settings.installButton": {
    "text": "立即安装",
    "context": "安装更新按钮"
  },
  "update.settings.notAvailable": {
    "text": "已是最新版本",
    "context": "没有可用更新"
  },
  "update.settings.error": {
    "text": "更新出错：{message}",
    "context": "更新错误信息，{message} 是错误详情"
  },
  "update.settings.serverUrl": {
    "text": "更新服务器地址",
    "context": "更新服务器URL输入框标签"
  },
  "update.settings.serverUrlPlaceholder": {
    "text": "http://your-server/updates",
    "context": "更新服务器URL输入框占位符"
  },
  "update.settings.serverUrlSaved": {
    "text": "更新服务器地址已保存",
    "context": "保存更新服务器地址成功"
  },
  "update.settings.retryButton": {
    "text": "重试",
    "context": "重试检查更新按钮"
  },
  "settings.sections.update": {
    "text": "检查更新",
    "context": "检查更新导航"
  }
```

- [ ] **Step 2: Add English translations**

In `src/locales/en-US.json`, add the same keys with English text:

```json
  "update.banner.ready": {
    "text": "New version v{version} is ready",
    "context": "Banner shown after update download completes, {version} is version number"
  },
  "update.banner.goSettings": {
    "text": "Go to Settings",
    "context": "Button in banner to navigate to settings"
  },
  "update.banner.close": {
    "text": "Close",
    "context": "Close update banner"
  },
  "update.banner.error": {
    "text": "Update check failed",
    "context": "Banner for update check error"
  },
  "update.settings.title": {
    "text": "Check for Updates",
    "context": "Settings page update section title"
  },
  "update.settings.currentVersion": {
    "text": "Current Version",
    "context": "Current version label"
  },
  "update.settings.checkButton": {
    "text": "Check for Updates",
    "context": "Manual check update button"
  },
  "update.settings.checking": {
    "text": "Checking...",
    "context": "Checking for updates status"
  },
  "update.settings.downloading": {
    "text": "Downloading update...",
    "context": "Downloading update status"
  },
  "update.settings.ready": {
    "text": "New version v{version} is ready",
    "context": "Update downloaded, {version} is version number"
  },
  "update.settings.installButton": {
    "text": "Install Now",
    "context": "Install update button"
  },
  "update.settings.notAvailable": {
    "text": "You're up to date",
    "context": "No update available"
  },
  "update.settings.error": {
    "text": "Update error: {message}",
    "context": "Update error message, {message} is error detail"
  },
  "update.settings.serverUrl": {
    "text": "Update Server URL",
    "context": "Update server URL input label"
  },
  "update.settings.serverUrlPlaceholder": {
    "text": "http://your-server/updates",
    "context": "Update server URL input placeholder"
  },
  "update.settings.serverUrlSaved": {
    "text": "Update server URL saved",
    "context": "Update server URL saved successfully"
  },
  "update.settings.retryButton": {
    "text": "Retry",
    "context": "Retry check update button"
  },
  "settings.sections.update": {
    "text": "Check for Updates",
    "context": "Check for updates nav item"
  }
```

- [ ] **Step 3: Commit**

```bash
git add src/locales/zh-CN.json src/locales/en-US.json
git commit -m "feat(i18n): add updater translation keys"
```

---

### Task 5: Create update store

**Files:**
- Create: `src/stores/update.ts`

- [ ] **Step 1: Create the store**

Create `src/stores/update.ts`:

```ts
import { create } from 'zustand';

type UpdateStatus = 'idle' | 'checking' | 'downloading' | 'ready' | 'not-available' | 'error';

interface UpdateState {
  status: UpdateStatus;
  newVersion: string | null;
  errorMessage: string | null;
  bannerDismissed: boolean;
  isElectron: boolean;

  checkUpdate: () => void;
  installUpdate: () => void;
  dismissBanner: () => void;
  initListeners: () => () => void;
}

export const useUpdateStore = create<UpdateState>((set) => ({
  status: 'idle',
  newVersion: null,
  errorMessage: null,
  bannerDismissed: false,
  isElectron: !!window.sman?.updater,

  checkUpdate: () => {
    if (!window.sman?.updater) return;
    set({ status: 'checking' });
    window.sman.updater.check().catch(() => {
      set({ status: 'error', errorMessage: 'Failed to check' });
    });
  },

  installUpdate: () => {
    if (!window.sman?.updater) return;
    window.sman.updater.install();
  },

  dismissBanner: () => {
    set({ bannerDismissed: true });
  },

  initListeners: () => {
    if (!window.sman?.updater) return () => {};

    const unsubAvailable = window.sman.updater.onUpdateAvailable((info) => {
      set({ status: 'downloading', newVersion: info.version, bannerDismissed: false });
    });

    const unsubNotAvailable = window.sman.updater.onUpdateNotAvailable(() => {
      set({ status: 'not-available' });
    });

    const unsubDownloaded = window.sman.updater.onUpdateDownloaded((info) => {
      set({ status: 'ready', newVersion: info.version, bannerDismissed: false });
    });

    const unsubError = window.sman.updater.onUpdateError((info) => {
      set({ status: 'error', errorMessage: info.message });
    });

    return () => {
      unsubAvailable();
      unsubNotAvailable();
      unsubDownloaded();
      unsubError();
    };
  },
}));
```

- [ ] **Step 2: Commit**

```bash
git add src/stores/update.ts
git commit -m "feat(updater): create update Zustand store"
```

---

### Task 6: Create UpdateBanner component

**Files:**
- Create: `src/components/UpdateBanner.tsx`

- [ ] **Step 1: Create the banner component**

Create `src/components/UpdateBanner.tsx`:

```tsx
import { useNavigate } from 'react-router-dom';
import { X, Download, AlertCircle } from 'lucide-react';
import { useUpdateStore } from '@/stores/update';
import { t } from '@/locales';

export function UpdateBanner() {
  const navigate = useNavigate();
  const status = useUpdateStore((s) => s.status);
  const newVersion = useUpdateStore((s) => s.newVersion);
  const bannerDismissed = useUpdateStore((s) => s.bannerDismissed);
  const isElectron = useUpdateStore((s) => s.isElectron);
  const dismissBanner = useUpdateStore((s) => s.dismissBanner);

  if (!isElectron || bannerDismissed) return null;

  // Ready: update downloaded, waiting for user to install
  if (status === 'ready' && newVersion) {
    return (
      <div className="flex items-center justify-between px-4 py-2 bg-primary/10 border-b border-primary/20 text-sm animate-in slide-in-from-top duration-300">
        <div className="flex items-center gap-2">
          <Download className="h-4 w-4 text-primary" />
          <span>{t('update.banner.ready').replace('{version}', newVersion)}</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate('/settings')}
            className="px-3 py-1 text-xs font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors"
          >
            {t('update.banner.goSettings')}
          </button>
          <button onClick={dismissBanner} className="p-1 rounded hover:bg-muted transition-colors">
            <X className="h-3.5 w-3.5 text-muted-foreground" />
          </button>
        </div>
      </div>
    );
  }

  // Error: update check/download failed
  if (status === 'error') {
    return (
      <div className="flex items-center justify-between px-4 py-2 bg-destructive/10 border-b border-destructive/20 text-sm animate-in slide-in-from-top duration-300">
        <div className="flex items-center gap-2">
          <AlertCircle className="h-4 w-4 text-destructive" />
          <span className="text-destructive">{t('update.banner.error')}</span>
        </div>
        <button onClick={dismissBanner} className="p-1 rounded hover:bg-muted transition-colors">
          <X className="h-3.5 w-3.5 text-muted-foreground" />
        </button>
      </div>
    );
  }

  return null;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/components/UpdateBanner.tsx
git commit -m "feat(updater): create UpdateBanner component"
```

---

### Task 7: Mount UpdateBanner in MainLayout

**Files:**
- Modify: `src/components/layout/MainLayout.tsx`

- [ ] **Step 1: Add import**

In `src/components/layout/MainLayout.tsx`, add import at top:

```ts
import { UpdateBanner } from '@/components/UpdateBanner';
```

- [ ] **Step 2: Mount banner below Titlebar**

In the JSX, after `<Titlebar />` (line 70), add:

```tsx
<UpdateBanner />
```

The structure becomes:

```tsx
<div className={cn('flex flex-col flex-1 overflow-hidden', !hideSidebar && 'ml-64')}>
  <Titlebar />
  <UpdateBanner />
  <main className="flex-1 overflow-y-auto bg-transparent">
    <Outlet />
  </main>
</div>
```

- [ ] **Step 3: Init update listeners**

In `MainLayout.tsx`, add a `useEffect` to initialize IPC listeners. Import `useUpdateStore`:

```ts
import { useUpdateStore } from '@/stores/update';
```

Add inside the component, after existing `useEffect` blocks:

```ts
// Initialize auto-updater IPC listeners
useEffect(() => {
  const cleanup = useUpdateStore.getState().initListeners();
  return cleanup;
}, []);
```

- [ ] **Step 4: Commit**

```bash
git add src/components/layout/MainLayout.tsx
git commit -m "feat(updater): mount UpdateBanner in MainLayout"
```

---

### Task 8: Create UpdateSettings component

**Files:**
- Create: `src/features/settings/UpdateSettings.tsx`

- [ ] **Step 1: Create the settings component**

Create `src/features/settings/UpdateSettings.tsx`:

```tsx
import { useState } from 'react';
import { Download, RefreshCw, Server } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useUpdateStore } from '@/stores/update';
import { t } from '@/locales';

declare const __APP_VERSION__: string;
const APP_VERSION = __APP_VERSION__;

interface UpdateSettingsProps {
  id: string;
}

export function UpdateSettings({ id }: UpdateSettingsProps) {
  const status = useUpdateStore((s) => s.status);
  const newVersion = useUpdateStore((s) => s.newVersion);
  const errorMessage = useUpdateStore((s) => s.errorMessage);
  const isElectron = useUpdateStore((s) => s.isElectron);
  const checkUpdate = useUpdateStore((s) => s.checkUpdate);
  const installUpdate = useUpdateStore((s) => s.installUpdate);
  const [serverUrl, setServerUrl] = useState('');
  const [urlSaved, setUrlSaved] = useState(false);

  if (!isElectron) return null;

  const handleSaveServerUrl = () => {
    if (!serverUrl.trim()) return;
    window.sman?.updater?.setFeedURL?.(serverUrl.trim());
    setUrlSaved(true);
    setTimeout(() => setUrlSaved(false), 2000);
  };

  return (
    <Card id={id}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Download className="h-5 w-5" />
          {t('update.settings.title')}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Current version */}
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground">{t('update.settings.currentVersion')}:</span>
          <span className="font-mono font-medium">v{APP_VERSION}</span>
        </div>

        {/* Status + Actions */}
        <div className="flex items-center gap-3">
          {status === 'idle' && (
            <Button onClick={checkUpdate} variant="outline" size="sm">
              <RefreshCw className="h-4 w-4 mr-1.5" />
              {t('update.settings.checkButton')}
            </Button>
          )}
          {status === 'checking' && (
            <span className="text-sm text-muted-foreground flex items-center gap-2">
              <RefreshCw className="h-4 w-4 animate-spin" />
              {t('update.settings.checking')}
            </span>
          )}
          {status === 'downloading' && (
            <span className="text-sm text-muted-foreground flex items-center gap-2">
              <Download className="h-4 w-4 animate-bounce" />
              {t('update.settings.downloading')}
            </span>
          )}
          {status === 'ready' && newVersion && (
            <div className="flex items-center gap-3">
              <span className="text-sm text-primary font-medium">
                {t('update.settings.ready').replace('{version}', newVersion)}
              </span>
              <Button onClick={installUpdate} size="sm">
                <Download className="h-4 w-4 mr-1.5" />
                {t('update.settings.installButton')}
              </Button>
            </div>
          )}
          {status === 'not-available' && (
            <div className="flex items-center gap-3">
              <span className="text-sm text-muted-foreground">{t('update.settings.notAvailable')}</span>
              <Button onClick={checkUpdate} variant="outline" size="sm">
                <RefreshCw className="h-4 w-4 mr-1.5" />
                {t('update.settings.checkButton')}
              </Button>
            </div>
          )}
          {status === 'error' && (
            <div className="flex items-center gap-3">
              <span className="text-sm text-destructive">
                {t('update.settings.error').replace('{message}', errorMessage || '')}
              </span>
              <Button onClick={checkUpdate} variant="outline" size="sm">
                <RefreshCw className="h-4 w-4 mr-1.5" />
                {t('update.settings.retryButton')}
              </Button>
            </div>
          )}
        </div>

        {/* Update server URL */}
        <div className="space-y-2 pt-2 border-t">
          <label className="text-sm font-medium flex items-center gap-1.5">
            <Server className="h-4 w-4 text-muted-foreground" />
            {t('update.settings.serverUrl')}
          </label>
          <div className="flex gap-2">
            <Input
              value={serverUrl}
              onChange={(e) => { setServerUrl(e.target.value); setUrlSaved(false); }}
              placeholder={t('update.settings.serverUrlPlaceholder')}
              className="flex-1"
            />
            <Button onClick={handleSaveServerUrl} variant="outline" size="sm" disabled={!serverUrl.trim()}>
              {urlSaved ? '✓' : t('common.confirm')}
            </Button>
          </div>
          {urlSaved && (
            <p className="text-xs text-green-600">{t('update.settings.serverUrlSaved')}</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/settings/UpdateSettings.tsx
git commit -m "feat(updater): create UpdateSettings component"
```

---

### Task 9: Integrate UpdateSettings into Settings page

**Files:**
- Modify: `src/features/settings/index.tsx`

- [ ] **Step 1: Add import**

Add import at top of `src/features/settings/index.tsx`:

```ts
import { UpdateSettings } from './UpdateSettings';
import { Download } from 'lucide-react';
```

- [ ] **Step 2: Add update section to SECTIONS array**

In the `SECTIONS` array, add before the community entry (before `{ id: 'community', ... }`):

```ts
{ id: 'update', label: '检查更新', icon: Download },
```

- [ ] **Step 3: Add update label mapping**

In the `labels` object inside the `SECTIONS.map()` render, add:

```ts
update: t('settings.sections.update'),
```

- [ ] **Step 4: Add UpdateSettings component**

In the JSX, add before the community `<Card>`:

```tsx
<UpdateSettings id="settings-update" />
```

- [ ] **Step 5: Verify build**

Run: `pnpm build`
Expected: No errors

- [ ] **Step 6: Commit**

```bash
git add src/features/settings/index.tsx
git commit -m "feat(updater): integrate UpdateSettings into settings page"
```

---

## Chunk 3: Type declaration + Build verification

### Task 10: Add window.sman.updater type declaration

**Files:**
- Modify: `src/types/` (find the existing global type declaration file)

- [ ] **Step 1: Find and update type declarations**

Find the file that declares `window.sman` type (likely in `src/types/` or a `.d.ts` file). Add the `updater` property:

```ts
updater?: {
  check: () => Promise<{ status: string; version?: string; message?: string }>;
  install: () => Promise<void>;
  setFeedURL: (url: string) => Promise<void>;
  onUpdateAvailable: (callback: (info: { version: string; releaseNotes?: string }) => void) => () => void;
  onUpdateNotAvailable: (callback: () => void) => () => void;
  onUpdateDownloaded: (callback: (info: { version: string }) => void) => () => void;
  onUpdateError: (callback: (info: { message: string }) => void) => () => void;
};
```

- [ ] **Step 2: Commit**

```bash
git add src/types/
git commit -m "feat(updater): add window.sman.updater type declarations"
```

---

### Task 11: Full build verification

**Files:** None (verification only)

- [ ] **Step 1: Run full build**

Run: `pnpm build && pnpm build:electron`
Expected: Both pass with no errors

- [ ] **Step 2: Run dev mode smoke test**

Run: `pnpm dev` (in background)
- Open browser to http://localhost:5881/settings
- Verify: "检查更新" section appears in left nav
- Verify: Update section renders with current version, check button, server URL input
- Close dev server

- [ ] **Step 3: Final commit (if any fixes needed)**

If any issues were found and fixed during verification, commit the fixes.

---

## Summary of files changed

| File | Action | Purpose |
|------|--------|---------|
| `package.json` | Modify | Add electron-updater dep + publish config |
| `electron/main.ts` | Modify | Auto-updater setup + IPC handlers |
| `electron/preload.ts` | Modify | Expose updater IPC to renderer |
| `src/stores/update.ts` | Create | Zustand store for update state |
| `src/components/UpdateBanner.tsx` | Create | Top notification banner |
| `src/components/layout/MainLayout.tsx` | Modify | Mount banner + init listeners |
| `src/features/settings/UpdateSettings.tsx` | Create | Settings page update section |
| `src/features/settings/index.tsx` | Modify | Add update nav entry + component |
| `src/locales/zh-CN.json` | Modify | Chinese i18n keys |
| `src/locales/en-US.json` | Modify | English i18n keys |
| `src/types/` (d.ts) | Modify | Window.sman.updater types |
