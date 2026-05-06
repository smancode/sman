import { contextBridge, ipcRenderer, webUtils } from 'electron';

contextBridge.exposeInMainWorld('sman', {
  platform: process.platform,
  needsRoundedCorners: ipcRenderer.sendSync('system:isWindows10'),
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
});
