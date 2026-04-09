# electron/ — Electron Desktop App (electron-vite)

## Purpose
Electron main process: window management, IPC with renderer, spawning/managing the backend server process, GPU acceleration handling.

## Key Files

| File | Purpose |
|---|---|
| `electron/main.ts` | Main process entry: BrowserWindow creation, IPC handlers, backend process spawn, GPU disable for VDI, window state persistence |
| `electron/preload.ts` | Context bridge: exposes `selectDirectory` API to renderer |
| `electron/dist/` | Build output (compiled main + preload) |

## Key Responsibilities
- **Window**: create main BrowserWindow, manage close/minimize/maximize
- **IPC**: handle `select-directory` (native dialog), `backend-started` event
- **Backend spawn**: launch `tsx watch server/index.ts` (dev) or use built `dist/server/`
- **VDI compatibility**: `app.disableHardwareAcceleration()` on Windows VDI
- **ASAR disabled**: `better-sqlite3` native module requires no ASAR

## Dependencies
- `electron-vite` for build tooling
- `electron-builder` for packaging (NSIS on Windows, DMG on macOS)
- Communicates with frontend via IPC (contextBridge)
