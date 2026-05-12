import { app, BrowserWindow, Menu, ipcMain, dialog, shell, screen, desktopCapturer, nativeImage, Display } from 'electron';
import path from 'path';
import fs from 'fs/promises';
import http from 'http';
import { execSync } from 'child_process';
import electronUpdater from 'electron-updater';
const { autoUpdater } = electronUpdater;

let mainWindow: BrowserWindow | null = null;
let serverModule: any = null;
let serverStopping = false;

interface WindowSource {
  name: string;
  id: string;
  thumbnailDataUrl: string;
  thumbnailWidth: number;
  thumbnailHeight: number;
}

function buildScreenshotHtml(
  screenPng: string,
  screenWidth: number,
  screenHeight: number,
  windows: WindowSource[],
  _displays: { bounds: { x: number; y: number; width: number; height: number }; scaleFactor: number; isPrimary: boolean }[],
): string {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { width: 100%; height: 100%; overflow: hidden; cursor: crosshair; user-select: none; }
#bg { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
#overlay { position: absolute; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.3); }
#selection { position: absolute; border: 2px solid #3b82f6; background: transparent; pointer-events: none; display: none; z-index: 10; }
#selection .dimension { position: absolute; bottom: -24px; right: 0; background: rgba(0,0,0,0.7); color: white; font: 11px/1 monospace; padding: 2px 6px; border-radius: 3px; white-space: nowrap; }
#highlight { position: absolute; border: 2px solid #3b82f6; background: rgba(59,130,246,0.08); pointer-events: none; display: none; z-index: 5; }
#highlight .label { position: absolute; top: -22px; left: 0; background: #3b82f6; color: white; font: 11px/1.2 system-ui, sans-serif; padding: 2px 8px; border-radius: 3px 3px 0 0; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#toolbar { position: absolute; display: none; z-index: 20; background: rgba(0,0,0,0.8); border-radius: 6px; padding: 4px; gap: 4px; }
#toolbar button { border: none; background: transparent; color: white; padding: 6px 12px; border-radius: 4px; cursor: pointer; font: 13px/1 system-ui, sans-serif; }
#toolbar button:hover { background: rgba(255,255,255,0.15); }
#toolbar .confirm { background: #3b82f6; }
#toolbar .confirm:hover { background: #2563eb; }
</style>
</head>
<body>
<canvas id="bg"></canvas>
<div id="overlay"></div>
<div id="highlight"><span class="label"></span></div>
<div id="selection"><span class="dimension"></span></div>
<div id="toolbar">
  <button class="confirm" id="btn-confirm">✓ OK</button>
  <button id="btn-cancel">✕ Cancel</button>
</div>
<script>
const screenPng = ${JSON.stringify(screenPng)};
const screenWidth = ${screenWidth};
const screenHeight = ${screenHeight};
const windows = ${JSON.stringify(windows)};

const canvas = document.getElementById('bg');
const ctx = canvas.getContext('2d');
const overlay = document.getElementById('overlay');
const highlight = document.getElementById('highlight');
const selectionEl = document.getElementById('selection');
const toolbar = document.getElementById('toolbar');
const dimensionEl = selectionEl.querySelector('.dimension');
const highlightLabel = highlight.querySelector('.label');

canvas.width = window.innerWidth;
canvas.height = window.innerHeight;
overlay.style.width = window.innerWidth + 'px';
overlay.style.height = window.innerHeight + 'px';

const bgImg = new Image();
bgImg.onload = () => { ctx.drawImage(bgImg, 0, 0, canvas.width, canvas.height); };
bgImg.src = screenPng;

let mode = 'idle'; // idle | window-detect | selecting | selected
let startX = 0, startY = 0;
let selRect = { x: 0, y: 0, w: 0, h: 0 };
let detectedWin = null;
let mouseMovedDist = 0;
let lastMouseX = 0, lastMouseY = 0;

function getWindowBoundsAtScreen(win) {
  const thumb = new Image();
  thumb.src = win.thumbnailDataUrl;
  return new Promise(resolve => {
    thumb.onload = () => {
      const ratio = thumb.naturalWidth / thumb.naturalHeight;
      let w = Math.min(thumb.naturalWidth * 0.7, canvas.width * 0.6);
      let h = w / ratio;
      resolve({ x: Math.round((canvas.width - w) / 2), y: Math.round((canvas.height - h) / 2), w: Math.round(w), h: Math.round(h) });
    };
    thumb.onerror = () => resolve(null);
  });
}

const windowBoundsCache = [];
async function cacheWindowBounds() {
  for (const win of windows) {
    const bounds = await getWindowBoundsAtScreen(win);
    if (bounds) windowBoundsCache.push({ ...bounds, name: win.name, id: win.id, thumbDataUrl: win.thumbnailDataUrl });
  }
}
cacheWindowBounds();

function findWindowAt(x, y) {
  for (let i = windowBoundsCache.length - 1; i >= 0; i--) {
    const b = windowBoundsCache[i];
    if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) return b;
  }
  return null;
}

function showHighlight(b) {
  highlight.style.display = 'block';
  highlight.style.left = b.x + 'px';
  highlight.style.top = b.y + 'px';
  highlight.style.width = b.w + 'px';
  highlight.style.height = b.h + 'px';
  highlightLabel.textContent = b.name;
}

function hideHighlight() { highlight.style.display = 'none'; }

function showSelection(x, y, w, h) {
  selectionEl.style.display = 'block';
  selectionEl.style.left = x + 'px';
  selectionEl.style.top = y + 'px';
  selectionEl.style.width = w + 'px';
  selectionEl.style.height = h + 'px';
  dimensionEl.textContent = w + ' x ' + h;
}

function hideSelection() { selectionEl.style.display = 'none'; }

function showToolbar(x, y, w, h) {
  toolbar.style.display = 'flex';
  let tx = x + w;
  let ty = y + h + 8;
  if (tx + 160 > window.innerWidth) tx = x + w - 160;
  if (ty + 40 > window.innerHeight) ty = y - 44;
  toolbar.style.left = tx + 'px';
  toolbar.style.top = ty + 'px';
}
function hideToolbar() { toolbar.style.display = 'none'; }

function clearOverlayArea(x, y, w, h) {
  overlay.style.clipPath = \`polygon(0% 0%, 100% 0%, 100% 100%, 0% 100%, 0% 0%, 0% \${y}px, \${x}px \${y}px, \${x}px \${y+h}px, \${x+w}px \${y+h}px, \${x+w}px \${y}px, 0% \${y}px)\`;
}

function resetOverlay() { overlay.style.clipPath = 'none'; }

function captureSelection() {
  const r = selRect;
  if (r.w < 5 || r.h < 5) return;
  const tempCanvas = document.createElement('canvas');
  tempCanvas.width = r.w;
  tempCanvas.height = r.h;
  const tempCtx = tempCanvas.getContext('2d');
  tempCtx.drawImage(bgImg, r.x, r.y, r.w, r.h, 0, 0, r.w, r.h);
  const dataUrl = tempCanvas.toDataURL('image/png');
  window.sman.completeCapture(dataUrl);
}

document.addEventListener('mousemove', (e) => {
  lastMouseX = e.clientX;
  lastMouseY = e.clientY;

  if (mode === 'idle' || mode === 'window-detect') {
    if (mode === 'idle') {
      mouseMovedDist = 0;
      mode = 'window-detect';
    }
    mouseMovedDist += Math.abs(e.movementX) + Math.abs(e.movementY);

    if (mouseMovedDist > 10) {
      hideHighlight();
      const win = findWindowAt(e.clientX, e.clientY);
      if (win) {
        detectedWin = win;
        showHighlight(win);
      } else {
        detectedWin = null;
      }
    }
  } else if (mode === 'selecting') {
    const x = Math.min(startX, e.clientX);
    const y = Math.min(startY, e.clientY);
    const w = Math.abs(e.clientX - startX);
    const h = Math.abs(e.clientY - startY);
    selRect = { x, y, w, h };
    showSelection(x, y, w, h);
    clearOverlayArea(x, y, w, h);
  }
});

document.addEventListener('mousedown', (e) => {
  if (e.button !== 0) return;
  if (mode === 'idle' || mode === 'window-detect') {
    if (detectedWin && mouseMovedDist <= 10) {
      // Click on detected window without moving → do nothing, wait for dblclick
      return;
    }
    if (mouseMovedDist > 10) {
      // Mouse moved → switch to free selection mode
      mode = 'selecting';
      startX = e.clientX;
      startY = e.clientY;
      hideHighlight();
      hideToolbar();
      hideSelection();
    }
  }
});

document.addEventListener('mouseup', () => {
  if (mode === 'selecting') {
    if (selRect.w > 5 && selRect.h > 5) {
      mode = 'selected';
      showToolbar(selRect.x, selRect.y, selRect.w, selRect.h);
    } else {
      mode = 'idle';
      resetOverlay();
      hideSelection();
    }
  }
});

document.addEventListener('dblclick', (e) => {
  if (mode === 'window-detect' && detectedWin) {
    e.preventDefault();
    const b = detectedWin;
    selRect = { x: b.x, y: b.y, w: b.w, h: b.h };
    mode = 'selected';
    hideHighlight();
    showSelection(b.x, b.y, b.w, b.h);
    clearOverlayArea(b.x, b.y, b.w, b.h);
    // Immediately capture
    const tempCanvas = document.createElement('canvas');
    tempCanvas.width = b.w;
    tempCanvas.height = b.h;
    const tempCtx = tempCanvas.getContext('2d');
    tempCtx.drawImage(bgImg, b.x, b.y, b.w, b.h, 0, 0, b.w, b.h);
    const dataUrl = tempCanvas.toDataURL('image/png');
    window.sman.completeCapture(dataUrl);
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    window.sman.cancelCapture();
  } else if (e.key === 'Enter' && mode === 'selected') {
    captureSelection();
  }
});

document.getElementById('btn-confirm').addEventListener('click', (e) => {
  e.stopPropagation();
  captureSelection();
});
document.getElementById('btn-cancel').addEventListener('click', (e) => {
  e.stopPropagation();
  window.electronAPI.cancelCapture();
});
</script>
</body>
</html>`;
}

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

// Propagate to process.env so server-side code (loaded at runtime) can also read them.
if (INJECTED_UPDATE_URL) process.env.SMAN_UPDATE_URL = INJECTED_UPDATE_URL;
if (INJECTED_HUB_URL) process.env.SMAN_HUB_URL = INJECTED_HUB_URL;
if (INJECTED_PSK) process.env.SMAN_PSK = INJECTED_PSK;

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

  // Screenshot capture
  let screenshotOverlay: BrowserWindow | null = null;

  ipcMain.handle('screen:startCapture', async (_event, options: { hideWindow?: boolean }) => {
    if (screenshotOverlay) return null;

    const hideWindow = options?.hideWindow === true;
    const wasVisible = mainWindow?.isVisible() ?? false;

    if (hideWindow && mainWindow) {
      mainWindow.hide();
      await new Promise(r => setTimeout(r, 200));
    }

    try {
      const primaryDisplay = screen.getPrimaryDisplay();
      const { width, height } = primaryDisplay.size;
      const scaleFactor = primaryDisplay.scaleFactor;

      const sources = await desktopCapturer.getSources({
        types: ['screen', 'window'],
        thumbnailSize: { width: Math.round(width * scaleFactor), height: Math.round(height * scaleFactor) },
        fetchWindowIcons: false,
      });

      const screenSource = sources.find(s => s.id.startsWith('screen:'));
      if (!screenSource) return null;

      const screenPng = screenSource.thumbnail.toDataURL('image/png');

      const windows = sources
        .filter(s => s.id.startsWith('window:') && s.name && s.name !== 'Sman')
        .map(s => ({
          name: s.name,
          id: s.id,
          thumbnailDataUrl: s.thumbnail.toDataURL('image/png'),
          thumbnailWidth: s.thumbnail.getSize().width,
          thumbnailHeight: s.thumbnail.getSize().height,
        }));

      const displays = screen.getAllDisplays().map(d => ({
        bounds: d.bounds,
        scaleFactor: d.scaleFactor,
        isPrimary: d.id === primaryDisplay.id,
      }));

      screenshotOverlay = new BrowserWindow({
        width,
        height,
        x: 0,
        y: 0,
        frame: false,
        transparent: true,
        resizable: false,
        movable: false,
        alwaysOnTop: true,
        skipTaskbar: true,
        hasShadow: false,
        show: false,
        webPreferences: {
          nodeIntegration: false,
          contextIsolation: true,
          preload: path.join(__dirname, '../preload/preload.cjs'),
        },
      });

      screenshotOverlay.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
      if (process.platform === 'darwin') {
        screenshotOverlay.setFullScreen(true);
      }

      screenshotOverlay.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(buildScreenshotHtml(screenPng, width, height, windows, displays))}`);
      screenshotOverlay.once('ready-to-show', () => {
        screenshotOverlay?.show();
        screenshotOverlay?.focus();
      });

      screenshotOverlay.on('closed', () => {
        screenshotOverlay = null;
        if (hideWindow && wasVisible && mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.show();
          mainWindow.focus();
        }
      });

      return { started: true };
    } catch (err) {
      console.error('[Screenshot] Failed:', err);
      if (hideWindow && wasVisible && mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.show();
      }
      return null;
    }
  });

  ipcMain.handle('screen:captureComplete', (_event, dataUrl: string) => {
    if (screenshotOverlay && !screenshotOverlay.isDestroyed()) {
      screenshotOverlay.close();
      screenshotOverlay = null;
    }
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
      mainWindow.focus();
      mainWindow.webContents.send('screen:captureResult', dataUrl);
    }
    return true;
  });

  ipcMain.handle('screen:captureCancel', () => {
    if (screenshotOverlay && !screenshotOverlay.isDestroyed()) {
      screenshotOverlay.close();
      screenshotOverlay = null;
    }
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
      mainWindow.focus();
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

  // Enterprise build: auto-enable hub when HUB_URL is injected (PSK has built-in fallback)
  if (INJECTED_HUB_URL) {
    if (!config.hub?.enabled) {
      config.hub = { ...config.hub, serverUrl: INJECTED_HUB_URL, enabled: true };
      await fs.writeFile(configPath, JSON.stringify(config, null, 2));
    }
  } else if (!config.hub || !config.hub.serverUrl) {
    config.hub = { serverUrl: '', updateUrl: '', enabled: false };
    await fs.writeFile(configPath, JSON.stringify(config, null, 2));
  }

  // Priority: build-time injection > config.hub.updateUrl > package.json default
  const updateUrl = INJECTED_UPDATE_URL || config.hub?.updateUrl;
  if (updateUrl) {
    autoUpdater.setFeedURL({ provider: 'generic', url: updateUrl });
  }
  if (INJECTED_UPDATE_URL) {
    autoUpdater.setFeedURL({ provider: 'generic', url: INJECTED_UPDATE_URL });
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
              serverModule.startHub();
              console.log('[Electron] Hub initialized');
            } catch (err) {
              console.error('[Electron] startHub failed:', err);
            }
          }
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
