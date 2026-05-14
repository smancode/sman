import { app, BrowserWindow, Menu, ipcMain, dialog, shell, clipboard, nativeImage } from 'electron';
import path from 'path';
import fs from 'fs/promises';
import http from 'http';
import { execSync } from 'child_process';
import electronUpdater from 'electron-updater';
import Screenshots from 'electron-screenshots';
const { autoUpdater } = electronUpdater;

let mainWindow: BrowserWindow | null = null;
let serverModule: any = null;
let serverStopping = false;



// Screenshot instance (initialized after app.whenReady)
let screenshots: Screenshots | null = null;

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
const DEFAULT_PORT = 5880;
let backendPort = DEFAULT_PORT;

// Build-time enterprise injection.
// electron-vite define replaces process.env.SMAN_* with string literals when env vars are set.
// Open-source builds leave them as actual process.env reads (undefined at build time).
const INJECTED_UPDATE_URL = process.env.SMAN_UPDATE_URL;
const INJECTED_HUB_URL = process.env.SMAN_HUB_URL;
const INJECTED_PSK = process.env.SMAN_PSK;
const INJECTED_FALLBACK_URL = process.env.SMAN_FALLBACK_URL;

// Propagate to process.env so server-side code (loaded at runtime) can also read them.
if (INJECTED_UPDATE_URL) process.env.SMAN_UPDATE_URL = INJECTED_UPDATE_URL;
if (INJECTED_HUB_URL) process.env.SMAN_HUB_URL = INJECTED_HUB_URL;
if (INJECTED_PSK) process.env.SMAN_PSK = INJECTED_PSK;
if (INJECTED_FALLBACK_URL) process.env.SMAN_FALLBACK_URL = INJECTED_FALLBACK_URL;

// Auto-updater: silent download, never auto-install
autoUpdater.autoDownload = true;
autoUpdater.autoInstallOnAppQuit = false;
autoUpdater.autoRunAppAfterInstall = true;
function getFrontendUrl(): string {
  return isDev ? 'http://localhost:5881' : `http://localhost:${backendPort}`;
}

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
    ...(process.platform === 'win32' ? { frame: false, transparent: true, backgroundColor: '#00000000' } : {}),
    show: false,
    icon: path.join(__dirname, isDev ? '../public/favicon.png' : '../../public/favicon.png'),
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.loadURL(getFrontendUrl());
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
    if (url !== current && !url.startsWith(getFrontendUrl()) && !url.startsWith('http://localhost:')) {
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

  // Notify renderer when maximize state changes (double-click titlebar, Win+Up, snap, etc.)
  mainWindow.on('maximize', () => {
    mainWindow?.webContents.send('window:maximizeChanged', true);
  });
  mainWindow.on('unmaximize', () => {
    mainWindow?.webContents.send('window:maximizeChanged', false);
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

  // Update handlers
  let updateInfo: { version: string } | null = null;

  ipcMain.handle('updater:check', async () => {
    if (isDev) return { status: 'not-available' };
    try {
      const result = await Promise.race([
        autoUpdater.checkForUpdates(),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), 10000)),
      ]);
      if (!result) return { status: 'error', message: 'Check timed out (10s)' };
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

  ipcMain.handle('updater:probeServer', async (_event, url: string) => {
    if (!url || typeof url !== 'string') return { ok: false, error: 'Invalid URL' };
    try {
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), 5000);
      const healthUrl = url.replace(/\/+$/, '') + '/health';
      const res = await fetch(healthUrl, { method: 'HEAD', signal: controller.signal });
      clearTimeout(tid);
      return { ok: res.ok };
    } catch (err: any) {
      return { ok: false, error: err?.message || 'Connection failed' };
    }
  });

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

  // Screenshot capture (using electron-screenshots)
  ipcMain.handle('screen:startCapture', async (_event, options?: { hideWindow?: boolean }) => {
    if (!screenshots) return null;

    const hideWindow = options?.hideWindow === true;
    const wasVisible = mainWindow?.isVisible() ?? false;

    if (hideWindow && mainWindow) {
      mainWindow.hide();
      await new Promise(r => setTimeout(r, 200));
    }

    try {
      await screenshots.startCapture();
      return { started: true };
    } catch (err) {
      console.error('[Screenshot] Failed:', err);
      if (hideWindow && wasVisible && mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.show();
      }
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
        { type: 'separator' },
        { role: 'toggleDevTools', label: '开发者工具' },
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
async function ensureHubConfig(homeDir: string): Promise<void> {
  const configPath = path.join(homeDir, 'config.json');
  let config: Record<string, any> = {};
  try {
    const content = await fs.readFile(configPath, 'utf-8');
    config = JSON.parse(content);
  } catch {
    // config.json doesn't exist yet, server will create it
  }

  // Enterprise build: auto-enable hub when HUB_URL is injected
  if (INJECTED_HUB_URL) {
    if (!config.hub?.serverBaseUrl || config.hub.serverBaseUrl !== INJECTED_HUB_URL) {
      config.hub = {
        ...config.hub,
        serverBaseUrl: INJECTED_HUB_URL,
        serverUrl: INJECTED_HUB_URL,
        enabled: true,
      };
      await fs.writeFile(configPath, JSON.stringify(config, null, 2));
    }
  }

  // Set autoUpdater feed URL from serverBaseUrl (update endpoint is a sub-path)
  // Priority: INJECTED_UPDATE_URL > serverBaseUrl + '/updates/sman' > config.hub.updateUrl
  const baseUrl = INJECTED_HUB_URL || config.hub?.serverBaseUrl || config.hub?.serverUrl;
  const updateUrl = INJECTED_UPDATE_URL
    || (baseUrl ? `${baseUrl}/updates/sman` : '')
    || config.hub?.updateUrl;
  if (updateUrl) {
    autoUpdater.setFeedURL({ provider: 'generic', url: updateUrl });
  }
}

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
    // We call listen() ourselves, with auto port fallback on EADDRINUSE.
    const HOST = process.env.HOST || '127.0.0.1';
    const PORT_ENV = process.env.PORT ? parseInt(process.env.PORT, 10) : 0;
    if (PORT_ENV > 0) {
      backendPort = PORT_ENV;
    }

    await new Promise<void>((resolve, reject) => {
      const tryListen = (port: number, attempts: number) => {
        serverModule.server.listen(port, HOST, async () => {
          backendPort = port;
          console.log(`[Electron] Server listening on ${HOST}:${port}`);
          console.log(`[Electron] Home: ${serverModule.homeDir}`);
          await ensureHubConfig(serverModule.homeDir);
          if (typeof serverModule.startHub === 'function') {
            try {
              await serverModule.startHub();
              console.log('[Electron] Hub initialized');
            } catch (err) {
              console.error('[Electron] startHub failed:', err);
            }
          }
          // Re-check after hub init (probe may have resolved serverBaseUrl)
          await ensureHubConfig(serverModule.homeDir);
          resolve();
        }).on('error', (err: NodeJS.ErrnoException) => {
          if (err.code === 'EADDRINUSE' && attempts > 0) {
            console.log(`[Electron] Port ${port} in use, trying ${port + 1}...`);
            tryListen(port + 1, attempts - 1);
          } else {
            reject(err);
          }
        });
      };
      tryListen(backendPort, 10);
    });
  } catch (err) {
    console.error('[Electron] Failed to start server:', err);
    const errDetail = err instanceof Error
      ? `${err.message}\n\n${err.stack || ''}`
      : String(err);
    dialog.showErrorBox(
      'Server Startup Error',
      `Failed to start backend server:\n${errDetail}\n\nPath: ${serverPath}\n__dirname: ${__dirname}`
    );
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

  // Initialize screenshot tool
  screenshots = new Screenshots();
  screenshots.on('ok', (_event, buffer: Buffer) => {
    const image = nativeImage.createFromBuffer(buffer);
    clipboard.writeImage(image);
    const dataUrl = image.toDataURL('image/png');
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
      mainWindow.focus();
      mainWindow.webContents.send('screen:captureResult', dataUrl);
    }
  });
  screenshots.on('cancel', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
      mainWindow.focus();
    }
  });

  // Production: start server in-process via dynamic import()
  await startServerInProcess();

  await waitForFrontend();
  createWindow();

  // Auto-check for updates: initial 5s delay + every 2 hours (production only)
  if (!isDev) {
    const CHECK_INTERVAL = 2 * 60 * 60 * 1000; // 2 hours
    setTimeout(() => {
      autoUpdater.checkForUpdates().catch(() => {});
      setInterval(() => {
        autoUpdater.checkForUpdates().catch(() => {});
      }, CHECK_INTERVAL);
    }, 5000);
  }
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
