# Sman Auto-Update Design

## Goal

Add automatic update capability for Electron desktop app. Update server is a static file server (generic provider), suitable for intranet deployment. User has full control over when to install.

## Core Principle

- **Download silently**: check + download happen in background, user is unaware
- **Notify only when ready**: banner appears only after download completes
- **User controls installation**: install action only triggered from Settings page, never automatic

## Architecture

```
App Startup
  → electron-updater (main process)
    → autoUpdater.autoDownload = true (silent download)
    → autoUpdater.autoInstallOnAppQuit = false (never auto-install)
    → Delay 5s after ready-to-show, then check for updates
    → New version found → auto-download in background
    → Download complete → IPC notify renderer
      → Renderer: top banner "New version v1.0.1 ready, go to Settings to install"
        → User clicks "Go to Settings" → navigate to settings update section
        → User clicks "Close" → banner dismisses
      → Settings page update section:
        → Shows "New version v1.0.1 ready"
        → [Install Now] button → quitAndInstall()
```

## State Machine

```
idle → (background checking → background downloading) → ready → installed
```

- `idle`: no update info
- `checking`: checking update server (transient, user doesn't see this)
- `downloading`: downloading in background (transient, user doesn't see this)
- `ready`: download complete, waiting for user to install
- `installed`: user clicked install, app will restart

## Files to Change

| File | Change |
|------|--------|
| `package.json` | Add `electron-updater` dependency + `publish` config |
| `electron/main.ts` | Import autoUpdater, register update IPC handlers, auto-check on startup |
| `electron/preload.ts` | Expose update IPC bridge to renderer |
| `src/stores/update.ts` | **New** - Zustand store for update state |
| `src/components/UpdateBanner.tsx` | **New** - Top banner component (only shows when update is ready) |
| `src/App.tsx` or root layout | Mount UpdateBanner |
| `src/features/settings/UpdateSettings.tsx` | **New** - Settings page update section |
| `src/features/settings/index.tsx` | Add "Check Update" nav entry |
| `src/locales/zh-CN.json` + `en-US.json` | Add update i18n keys |

## Main Process (electron/main.ts)

- Import `autoUpdater` from `electron-updater`
- Configure `autoUpdater.autoDownload = true`
- Configure `autoUpdater.autoInstallOnAppQuit = false`
- On `app.whenReady()`, after `createWindow()`, delay 5s then `autoUpdater.checkForUpdates()`
- IPC handlers:
  - `updater:check` → manually trigger check (for Settings page button)
  - `updater:install` → `autoUpdater.quitAndInstall()`
  - `updater:setFeedURL` → change update server URL at runtime
  - `updater:getStatus` → return current update state
- Forward events to renderer:
  - `updater:available` → `{ version, releaseNotes }`
  - `updater:not-available`
  - `updater:downloaded` → `{ version }`
  - `updater:error` → `{ message }`

## Preload Bridge

```ts
updater: {
  check: () => ipcRenderer.invoke('updater:check'),
  install: () => ipcRenderer.invoke('updater:install'),
  setFeedURL: (url: string) => ipcRenderer.invoke('updater:setFeedURL', url),
  getStatus: () => ipcRenderer.invoke('updater:getStatus'),
  onUpdateAvailable: (cb) => { ipcRenderer.on('updater:available', (_, d) => cb(d)); return () => ... },
  onUpdateNotAvailable: (cb) => { ipcRenderer.on('updater:not-available', () => cb()); return () => ... },
  onUpdateDownloaded: (cb) => { ipcRenderer.on('updater:downloaded', (_, d) => cb(d)); return () => ... },
  onUpdateError: (cb) => { ipcRenderer.on('updater:error', (_, d) => cb(d)); return () => ... },
}
```

## Update Store (src/stores/update.ts)

Zustand store managing:
- `status`: `'idle' | 'checking' | 'downloading' | 'ready' | 'error'`
- `newVersion`: string | null
- `errorMessage`: string | null
- `bannerDismissed`: boolean
- `checkUpdate()`: trigger check
- `installUpdate()`: trigger install
- `dismissBanner()`: dismiss the banner
- `setUpdateServerUrl(url)`: change update server URL

## Top Banner UI

Only appears when `status === 'ready'` and `bannerDismissed === false`:

```
┌──────────────────────────────────────────────────────────────┐
│ ✅ 新版本 v1.0.1 已就绪                      [前往设置] [关闭] │
└──────────────────────────────────────────────────────────────┘
```

Error banner when `status === 'error'`:

```
┌──────────────────────────────────────────────────────────────┐
│ ❌ 更新检查失败                                                 [关闭] │
└──────────────────────────────────────────────────────────────┘
```

## Settings Page Update Section

New section in left nav: "检查更新" with refresh icon.

Content:
- Current version: `v{APP_VERSION}`
- Update server URL input (loaded from `~/.sman/config.json`, editable)
- Status display:
  - idle: [Check Update] button
  - checking: "Checking..."
  - downloading: "Downloading update..."
  - ready: "New version v{newVersion} ready" + [Install Now] button
  - error: error message + [Retry] button

## i18n Keys

All user-visible text goes through `t()` function, no hardcoded strings.

## Update Server Structure

Static file server (Nginx/MinIO/any HTTP server):

```
http://server/updates/
├── latest.yml          # Version metadata (auto-generated by electron-builder)
├── Sman-Setup-1.0.1.exe  # Windows installer
└── Sman-1.0.1.dmg        # macOS installer
```

## Edge Cases

- **Non-Electron environment** (browser/web): Update features are hidden/disabled
- **Update server unreachable**: Silently fail, no banner shown
- **User closes banner**: Banner gone, update still available in Settings page
- **Multiple checks**: Debounce, don't trigger duplicate checks
- **Download interrupted**: electron-updater handles resume internally
