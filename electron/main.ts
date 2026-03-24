import { app, BrowserWindow, Menu } from 'electron';
import path from 'path';
import { spawn, ChildProcess } from 'child_process';
import http from 'http';

let mainWindow: BrowserWindow | null = null;
let serverProcess: ChildProcess | null = null;

// 开发模式用 Vite (5881)，生产模式用后端 serve (5880)
const isDev = !app.isPackaged;
const BACKEND_PORT = isDev ? 5880 : 5880;
const FRONTEND_URL = isDev ? 'http://localhost:5881' : `http://localhost:${BACKEND_PORT}`;

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 600,
    title: 'SmanBase',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.loadURL(FRONTEND_URL);

  if (isDev) {
    mainWindow.webContents.openDevTools();
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  buildMenu();
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
        { role: 'toggleDevTools', label: '开发者工具' },
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
        {
          label: '关于 SmanBase',
          click: () => {
            if (mainWindow) {
              mainWindow.webContents.send('show-about');
            }
          },
        },
      ],
    },
  ];

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

function startServer(): void {
  if (isDev) {
    // 开发模式：后端用 tsx watch 启动，前端用 Vite 启动
    // 两者都由 dev.sh 管理，Electron 只负责等它们就绪
    console.log('[Electron] Dev mode — waiting for backend and frontend...');
    return;
  }

  // 生产模式：Electron 自己启动后端
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

    // timeout 30s
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
