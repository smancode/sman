import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('sman', {
  platform: process.platform,
  versions: {
    node: process.versions.node,
    chrome: process.versions.chrome,
    electron: process.versions.electron,
  },
  selectDirectory: () => ipcRenderer.invoke('dialog:selectDirectory'),
});
