import { contextBridge, ipcRenderer, webUtils } from 'electron';

contextBridge.exposeInMainWorld('sman', {
  platform: process.platform,
  versions: {
    node: process.versions.node,
    chrome: process.versions.chrome,
    electron: process.versions.electron,
  },
  // Get local file path from File object (works for both drag-drop and file picker)
  getPathForFile: (file: File) => {
    try {
      return webUtils.getPathForFile(file);
    } catch {
      return undefined;
    }
  },
  selectDirectory: () => ipcRenderer.invoke('dialog:selectDirectory'),

  // Window controls
  windowMinimize: () => ipcRenderer.invoke('window:minimize'),
  windowMaximize: () => ipcRenderer.invoke('window:maximize'),
  windowClose: () => ipcRenderer.invoke('window:close'),
  windowIsMaximized: () => ipcRenderer.invoke('window:isMaximized'),
  onMaximizeChanged: (callback: (maximized: boolean) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, maximized: boolean) => callback(maximized);
    ipcRenderer.on('window:maximizeChanged', handler);
    return () => ipcRenderer.removeListener('window:maximizeChanged', handler);
  },

  // Open URL in system default browser
  openExternal: (url: string) => ipcRenderer.invoke('shell:openExternal', url),

  // Git
  getGitBranch: (dirPath: string) => ipcRenderer.invoke('git:getBranch', dirPath),

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
});
