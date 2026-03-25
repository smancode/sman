import { app, BrowserWindow, Menu, globalShortcut, ipcMain, dialog } from 'electron';
import path from 'path';
import { spawn, ChildProcess } from 'child_process';
import http from 'http';

let mainWindow: BrowserWindow | null = null;
let serverProcess: ChildProcess | null = null;

// 开发模式用 Vite (5881)，生产模式用后端 serve (5880)
const isDev = !app.isPackaged;
const BACKEND_PORT = 5880;
const FRONTEND_URL = isDev ? 'http://localhost:5881' : `http://localhost:${BACKEND_PORT}`;

function createWindow(): void {
  // 确保 preload 路径正确
  const preloadPath = path.join(__dirname, '../preload/preload.cjs');
  console.log('[Electron] Preload path:', preloadPath);
  console.log('[Electron] __dirname:', __dirname);

  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    title: 'Sman',
    titleBarStyle: 'hidden',
    show: false,
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.loadURL(FRONTEND_URL);

  mainWindow.once('ready-to-show', () => {
    mainWindow!.show();
    mainWindow!.focus();
  });

  // 全局快捷键
  const registerShortcuts = () => {
    // Ctrl+Shift+R 强制刷新
    globalShortcut.register('CommandOrControl+Shift+R', () => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.reloadIgnoringCache();
      }
    });

    // Ctrl+R / F5 刷新
    globalShortcut.register('CommandOrControl+R', () => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.reload();
      }
    });
    globalShortcut.register('F5', () => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.reload();
      }
    });

    // F12 / Ctrl+Shift+I 开发者工具（仅开发模式）
    if (isDev) {
      globalShortcut.register('F12', () => {
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.toggleDevTools();
        }
      });
      globalShortcut.register('CommandOrControl+Shift+I', () => {
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.toggleDevTools();
        }
      });
    }
  };

  registerShortcuts();

  mainWindow.on('focus', () => {
    registerShortcuts();
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    globalShortcut.unregisterAll();
  });
}

// IPC handlers
function registerIpcHandlers(): void {
  ipcMain.handle('dialog:selectDirectory', async () => {
    if (!mainWindow) return null;
    const result = await dialog.showOpenDialog(mainWindow, {
      properties: ['openDirectory'],
      title: '选择业务系统目录',
    });
    if (result.canceled || result.filePaths.length === 0) {
      return null;
    }
    return result.filePaths[0];
  });
}

function buildMenu(): void {
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: '文件',
      submenu: [
        { role: 'quit', label: '退出' },
      ],
    },
    {
      label: '编辑',
      submenu: [
        { role: 'undo', label: '撤销' },
        { role: 'redo', label: '重做' },
        { type: 'separator' },
        { role: 'cut', label: '剪切' },
        { role: 'copy', label: '复制' },
        { role: 'paste', label: '粘贴' },
        { role: 'selectAll', label: '全选' },
      ],
    },
    {
      label: '视图',
      submenu: [
        { role: 'reload', label: '刷新' },
        { role: 'forceReload', label: '强制刷新' },
        { type: 'separator' },
        { role: 'resetZoom', label: '重置缩放' },
        { role: 'zoomIn', label: '放大' },
        { role: 'zoomOut', label: '缩小' },
        { type: 'separator' },
        { role: 'togglefullscreen', label: '全屏' },
      ],
    },
    {
      label: '窗口',
      submenu: [
        { role: 'minimize', label: '最小化' },
        { role: 'close', label: '关闭' },
      ],
    },
    {
      label: '帮助',
      submenu: [
        { role: 'about', label: '关于 Sman' },
      ],
    },
  ];

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

function startServer(): void {
  if (isDev) {
    console.log('[Electron] Dev mode — waiting for backend and frontend...');
    return;
  }

  const serverPath = path.join(__dirname, '..', 'server', 'index.js');
  serverProcess = spawn(process.execPath, [serverPath], {
    env: { ...process.env, PORT: String(BACKEND_PORT) },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  serverProcess.stdout?.on('data', (data: Buffer) => {
    console.log(`[Server] ${data.toString().trim()}`);
  });

  serverProcess.stderr?.on('data', (data: Buffer) => {
    console.error(`[Server] ${data.toString().trim()}`);
  });

  serverProcess.on('close', (code) => {
    console.log(`Server process exited with code ${code}`);
    serverProcess = null;
  });
}

function stopServer(): void {
  if (serverProcess) {
    serverProcess.kill('SIGTERM');
    serverProcess = null;
  }
}

function waitForBackend(): Promise<void> {
  return new Promise((resolve) => {
    const check = setInterval(() => {
      http.get(`http://localhost:${BACKEND_PORT}/api/health`, (res) => {
        if (res.statusCode === 200) {
          clearInterval(check);
          res.resume();
          console.log('[Electron] Backend is ready');
          resolve();
        } else {
          res.resume();
        }
      }).on('error', () => {
        // not ready yet
      });
    }, 500);

    setTimeout(() => {
      clearInterval(check);
      resolve();
    }, 30000);
  });
}

function waitForFrontend(): Promise<void> {
  return new Promise((resolve) => {
    if (!isDev) {
      resolve();
      return;
    }
    const check = setInterval(() => {
      http.get(`http://localhost:5881`, (res) => {
        clearInterval(check);
        res.resume();
        console.log('[Electron] Frontend is ready');
        resolve();
      }).on('error', () => {
        // not ready yet
      });
    }, 500);

    setTimeout(() => {
      clearInterval(check);
      resolve();
    }, 30000);
  });
}

app.whenReady().then(async () => {
  registerIpcHandlers();
  buildMenu();
  startServer();

  await waitForBackend();
  await waitForFrontend();
  createWindow();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    stopServer();
    app.quit();
  }
});

app.on('before-quit', () => {
  stopServer();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});
