# Windows 打包指南

## 环境要求

- **Node.js 22 LTS**（better-sqlite3 有预编译二进制，无需本地编译）
- **pnpm** 包管理器
- **fnm**（可选，用于管理 Node 版本）
- **Python 3.x**（仅当需要本地编译 native 模块时）
- **VS C++ Build Tools**（仅当需要本地编译 native 模块时）

```bash
# 安装 fnm + Node 22
winget install Schniz.fnm
fnm install 22
fnm use 22
node -v  # 确认 v22.x
```

## 构建步骤

```bash
# 1. 安装依赖
pnpm install

# 2. 构建前端 + 后端 + Electron
pnpm build              # vite build + tsc + 生成 dist/server/package.json
pnpm build:electron     # electron-vite 编译主进程和预加载脚本

# 3. 打包
npx electron-builder --win nsis --x64    # NSIS 安装包
npx electron-builder --win portable --x64 # 便携版 exe
```

输出目录：`release/`

## 关键配置说明

### 1. CJS/ESM 兼容性

项目 `package.json` 声明 `"type": "module"`，但后端需要特殊处理：

- **server/tsconfig.json** 设置 `"module": "ES2022"` 输出 ESM
- **构建脚本** 自动在 `dist/server/` 下生成 `package.json: {"type": "module"}`
- **Electron 主进程** 使用 `import()` 动态加载服务端（不能用 `require()` 加载 ESM）
- **服务端代码** 用 `fileURLToPath(import.meta.url)` 替代 `__dirname`

```typescript
// electron/main.ts — 加载服务端
const serverPath = path.join(process.resourcesPath, 'app', 'dist', 'server', 'index.js');
const serverUrl = 'file:///' + serverPath.replace(/\\/g, '/');  // Windows 必须 file:// URL
serverModule = await import(serverUrl);
```

```typescript
// server/index.ts — ESM __dirname 替代
import { fileURLToPath } from 'url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
```

### 2. ASAR 已禁用

`package.json` 中 `"asar": false`。原因是 `better-sqlite3` 等原生模块在 ASAR 内无法正确 resolve 依赖路径。禁用 ASAR 虽然包体积稍大，但避免了 CJS/ESM 模块解析问题。

### 3. Windows GPU 兼容性

VDI/虚拟化环境通常没有 GPU，需要禁用硬件加速避免白屏：

```typescript
// electron/main.ts — 在 app.ready 之前
if (process.platform === 'win32') {
  app.disableHardwareAcceleration();
  app.commandLine.appendSwitch('disable-gpu');
}
```

### 4. 静态文件服务 Auth

后端 HTTP 服务同时提供 API 和前端静态文件。Auth 中间件只对 `/api/` 路径生效，静态文件（`/`、`/assets/*`）不需要 Bearer token：

```typescript
// server/index.ts
if (req.url?.startsWith('/api/') && !verifyHttpAuth(req)) {
  res.writeHead(401, ...);
  return;
}
```

**不要**对所有请求都做 auth 校验，否则 Electron 窗口 `loadURL()` 加载页面会被 401 拦截。

### 5. 安装包类型选择

| 类型 | 启动速度 | 适用场景 |
|------|---------|---------|
| **NSIS 安装包** | 快（直接从安装目录运行） | 企业部署、长期使用 |
| **Portable exe** | 慢（每次启动要解压临时文件） | 临时测试、U盘运行 |

VDI 环境推荐 NSIS 安装包。

## 踩坑记录

### better-sqlite3 编译失败

- Node 25/ARM64 没有预编译二进制
- 解决：用 fnm 切换到 Node 22 LTS

### `ERR_REQUIRE_ESM`

- CJS `require()` 无法加载 `@anthropic-ai/claude-agent-sdk`（纯 ESM 包）
- 解决：服务端编译为 ESM，Electron 用 `import()` 加载

### `ERR_UNSUPPORTED_ESM_URL_SCHEME`

- Windows 上 `import()` 不接受文件路径，需要 `file:///` URL
- 解决：`'file:///' + path.replace(/\\/g, '/')`

### better-sqlite3 在 ASAR 内找不到 bindings

- ASAR 内的原生模块路径解析失败
- 解决：禁用 ASAR

### `__dirname is not defined`

- ESM 模块没有 `__dirname` 全局变量
- 解决：`path.dirname(fileURLToPath(import.meta.url))`

### Electron 窗口白屏

- Auth 中间件拦截了静态文件请求（返回 401）
- 解决：auth 只对 `/api/` 路径生效

### Windows GPU 缓存错误

- 日志 `Unable to move the cache: 拒绝访问` / `Gpu Cache Creation failed`
- 无害警告，不影响功能（禁用硬件加速后可忽略）

### NSIS 安装时提示"无法关闭应用"

- 之前版本的 Sman 进程仍在运行或文件被占用
- 解决：先在任务管理器结束所有 Sman 进程，或使用 oneClick 安装模式

## 参考：hello-halo 项目的做法

hello-halo（`../hello-halo`）是同类型的 Electron + Claude Agent SDK 项目，其打包策略：

| 项目 | hello-halo | Sman |
|------|-----------|------|
| 前端加载 | `loadFile()` 本地文件 | `loadURL()` HTTP 服务 |
| ASAR | 启用，unpack 原生模块 | 禁用 |
| 安装方式 | NSIS（非一键） | NSIS（一键） |
| 原生模块处理 | 预编译下载 + afterPack 交换 | 依赖预编译二进制 |
| GPU | `disableHardwareAcceleration()` | 同 |
| 主进程/渲染通信 | IPC | HTTP/WebSocket |
| CJS/ESM | ESM (.mjs)，externalizeDeps | ESM，import() 加载 |

### hello-halo 值得借鉴的点

1. **`loadFile()` 替代 `loadURL()`** — 不依赖 HTTP 服务器提供前端，启动更快更稳
2. **ASAR + asarUnpack** — 包体积更小，只解包原生模块
3. **分阶段启动** — essential 先初始化，extended 延迟加载，提升首屏速度
4. **afterPack 钩子** — 自动清理非目标平台的原生模块，减小包体积
5. **prepare-binaries.mjs** — 预下载所有平台的二进制，支持交叉编译
