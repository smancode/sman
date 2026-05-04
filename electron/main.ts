import { app, BrowserWindow, Menu, ipcMain, dialog, shell } from 'electron';
import path from 'path';
import http from 'http';
import { execSync } from 'child_process';

let mainWindow: BrowserWindow | null = null;
let serverModule: any = null;
let serverStopping = false;

// Windows GPU compatibility: only disable hardware acceleration in remote/VDI sessions
// where GPU drivers are known to cause white-screen issues. Local Windows machines
// should use GPU acceleration for smooth rendering.
function isRemoteSession(): boolean {
  if (process.platform !== 'win32') return false;
  // Detect RDP / Citrix / VDI sessions
  return (
    process.env.SESSIONNAME === 'RDP-Tcp' ||
    !!process.env.CITRIX_SESSION_ID ||
    process.env.QTILE_WM === 'xrdp' ||
    (process.env.COMPUTERNAME && process.env.CLIENTNAME && process.env.COMPUTERNAME !== process.env.CLIENTNAME)
  );
}

if (isRemoteSession()) {
  console.log('[Electron] Remote session detected, disabling GPU acceleration');
  app.disableHardwareAcceleration();
  app.commandLine.appendSwitch('disable-gpu');
}

// Dev mode: bypass system proxy so localhost Vite/HMR always works
if (!app.isPackaged) {
  app.commandLine.appendSwitch('no-proxy-server');
}

const isDev = !app.isPackaged;
const BACKEND_PORT = 5880;
const FRONTEND_URL = isDev ? 'http://localhost:5881' : `http://localhost:${BACKEND_PORT}`;

function createWindow(): void {
  const preloadPath = path.join(__dirname, '../preload/preload.cjs');
  console.log('[Electron] Preload path:', preloadPath);

  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    title: 'Sman',
    titleBarStyle: 'hidden',
    ...(process.platform === 'win32' ? { frame: false } : {}),
    show: false,
    icon: path.join(__dirname, isDev ? '../public/favicon.png' : '../../public/favicon.png'),
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.loadURL(FRONTEND_URL);
  // Dev mode: disable HTTP cache to ensure Vite HMR changes are always picked up
  if (isDev) {
    mainWindow.webContents.session.webRequest.onHeadersReceived((details, callback) => {
      callback({
        responseHeaders: {
          ...details.responseHeaders,
          'Cache-Control': ['no-store'],
        },
      });
    });
  }

  // Open all external links in system default browser
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http://') || url.startsWith('https://')) {
      shell.openExternal(url);
    }
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (event, url) => {
    const current = mainWindow!.webContents.getURL();
    if (url !== current && !url.startsWith(FRONTEND_URL) && !url.startsWith('http://localhost:')) {
      event.preventDefault();
      shell.openExternal(url);
    }
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow!.show();
    mainWindow!.focus();
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

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

  // Window controls
  ipcMain.handle('window:minimize', () => {
    mainWindow?.minimize();
  });

  ipcMain.handle('window:maximize', () => {
    if (!mainWindow) return;
    if (mainWindow.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow.maximize();
    }
  });

  ipcMain.handle('window:close', () => {
    mainWindow?.close();
  });

  ipcMain.handle('window:isMaximized', () => {
    return mainWindow?.isMaximized() ?? false;
  });

  ipcMain.handle('shell:openExternal', (_event, url: string) => {
    if (!url || typeof url !== 'string') return;
    if (!url.startsWith('http://') && !url.startsWith('https://')) return;
    shell.openExternal(url);
  });

  ipcMain.handle('git:getBranch', async (_event, dirPath: string) => {
    try {
      const result = execSync('git rev-parse --abbrev-ref HEAD', {
        cwd: dirPath,
        encoding: 'utf8',
        timeout: 5000,
      });
      const branch = result.trim();
      return branch || null;
    } catch {
      return null;
    }
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

/**
 * Production: load server as CJS module via require().
 * Server is compiled to CJS and lives inside app.asar.
 * Electron's built-in asar support handles require() from asar correctly.
 *
 * Dev mode: server is started externally by dev.sh (pnpm dev:server).
 */
async function startServerInProcess(): Promise<void> {
  if (isDev) return;

  // __dirname in asar = resources/app.asar/electron/dist/main/
  // Need to go up 3 levels to reach app.asar/ root, then into dist/server/server/
  const serverPath = path.join(__dirname, '..', '..', '..', 'dist', 'server', 'server', 'index.js');
  console.log('[Electron] Loading server from:', serverPath);
  console.log('[Electron] __dirname:', __dirname);
  console.log('[Electron] process.resourcesPath:', process.resourcesPath);

  try {
    // Use file:// URL for dynamic import() — required on Windows
    const serverUrl = 'file:///' + serverPath.replace(/\\/g, '/');
    serverModule = await import(serverUrl);

    // Server creates http server at import time but only listens when run directly.
    // We call listen() ourselves.
    const HOST = process.env.HOST || '127.0.0.1';
    serverModule.server.listen(BACKEND_PORT, HOST, () => {
      console.log(`[Electron] Server listening on ${HOST}:${BACKEND_PORT}`);
      console.log(`[Electron] Home: ${serverModule.homeDir}`);
    });
  } catch (err) {
    console.error('[Electron] Failed to start server:', err);
    // Show error dialog so we can see what went wrong
    const errDetail = err instanceof Error
      ? `${err.message}\n\n${err.stack || ''}`
      : String(err);
    dialog.showErrorBox(
      'Server Startup Error',
      `Failed to start backend server:\n${errDetail}\n\nPath: ${serverPath}\n__dirname: ${__dirname}`
    );
    // Don't throw — let the app continue so user can see the error
  }
}

function stopServerInProcess(): void {
  if (isDev || serverStopping) return;
  serverStopping = true;

  try {
    if (serverModule && typeof serverModule.stopServer === 'function') {
      serverModule.stopServer();
      console.log('[Electron] Server stopped');
    }
  } catch {}
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
      }).on('error', () => {});
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

  // Production: start server in-process via dynamic import()
  await startServerInProcess();

  await waitForFrontend();
  createWindow();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    stopServerInProcess();
    app.quit();
  }
});

app.on('before-quit', () => {
  stopServerInProcess();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});
