# Windows 打包指南

## 环境要求

- **Node.js 22 LTS**（better-sqlite3 有预编译二进制，无需本地编译）
- **pnpm** 包管理器
- **fnm**（可选，用于管理 Node 版本）

```bash
# 安装 fnm + Node 22
winget install Schniz.fnm
fnm install 22
fnm use 22
node -v  # 确认 v22.x
```

**不需要** Python、Visual Studio Build Tools 或 node-gyp。better-sqlite3 v11.10.0 提供了预编译二进制，直接可用。

## 一键打包

```bash
# 确保用 Node 22
eval "$(fnm env)" && fnm use 22

# 完整打包（含依赖安装）
bash build-win.sh

# 跳过依赖安装（代码没变时更快）
bash build-win.sh --skip-deps
```

输出：`release/Sman-Setup-<version>.exe`（NSIS 安装包，约 150MB）

## 手动步骤

```bash
# 0. 切换 Node 版本
eval "$(fnm env)" && fnm use 22

# 1. 更新版本号（package.json 的 version 字段）
#    "version": "0.1.4"

# 2. 安装依赖
pnpm install

# 3. 构建前端 + 后端
pnpm build

# 4. 构建 Electron 主进程
pnpm build:electron

# 5. 打包
npx electron-builder --win nsis --x64
```

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

### 6. pnpm + rollup 无效符号链接

pnpm 安装后，`node_modules/.pnpm/node_modules/@rollup/` 下可能有指向不存在平台的符号链接（如 `@rollup/rollup-linux-x64-gnu`）。electron-builder 内部的 `@electron/rebuild` 遍历 node_modules 时遇到这些无效链接会报错。

`build-win.sh` 已内置清理逻辑。如果手动打包时遇到 `@electron/rebuild` 报错，运行：

```bash
# 清理无效的 rollup 平台包符号链接
for link in node_modules/.pnpm/node_modules/@rollup/*; do
  if [[ -L "$link" && ! -e "$link" ]]; then
    rm "$link"
  fi
done
```

## 踩坑记录

### 1. better-sqlite3 MODULE_VERSION 不匹配（最常见、最坑的问题）

**现象**：安装后启动报错 `was compiled against a different Node.js version using NODE_MODULE_VERSION 127. This version of Node.js requires NODE_MODULE_VERSION 130.`

**根因分析**：

better-sqlite3 是 C++ 原生模块，编译时绑定到特定 Node.js ABI 版本。系统 Node 22 对应 MODULE_VERSION 127，Electron 33 对应 130。

但经过实际验证（2026-04-02），**better-sqlite3 v11.10.0 的预编译二进制在 Node 22 和 Electron 33 下是同一个文件**（MD5 完全一致）。这意味着 electron-builder 打包时会原样复制这个二进制，不会出问题。

之前出现的 MODULE_VERSION 错误可能是以下原因之一：
- 旧版本 better-sqlite3 确实有不兼容的 prebuild
- electron-rebuild 在 pnpm 的 `.pnpm` 目录结构下错误地替换了二进制
- 手动运行了 node-gyp rebuild 破坏了原有的预编译二进制

**正确做法**：

1. **不要运行 `electron-rebuild`** — 它在 pnpm symlink 结构下行为不可预测
2. **不要运行 `node-gyp rebuild`** — 会覆盖掉可用的预编译二进制
3. **不要删除 `build/Release/` 目录** — 删除后 `prebuild-install` 下载的文件可能无法正确替换
4. **只用 `pnpm install` 安装依赖** — 它会自动下载正确的预编译二进制
5. **让 electron-builder 原样打包** — 它会直接复制 node_modules，不需要额外 rebuild

**验证方法**：

```bash
# 用 Electron 直接测试 better-sqlite3 是否可加载
node -e "const e = require('electron'); console.log(e)"  # 获取 electron 路径
# 然后用 electron.exe 运行测试脚本
echo "try { const b = require('./node_modules/better-sqlite3'); console.log('OK'); } catch(e) { console.log('FAIL:', e.message); }" | path/to/electron.exe -
```

### 2. Node 版本必须是 22

- Node 25 的 better-sqlite3 预编译二进制在某些平台上不可用
- 系统默认可能是 Node 25，需要用 fnm 切换

```bash
eval "$(fnm env)" && fnm use 22
```

### 3. `ERR_REQUIRE_ESM`

- CJS `require()` 无法加载 `@anthropic-ai/claude-agent-sdk`（纯 ESM 包）
- 解决：服务端编译为 ESM，Electron 用 `import()` 加载

### 4. `ERR_UNSUPPORTED_ESM_URL_SCHEME`

- Windows 上 `import()` 不接受文件路径，需要 `file:///` URL
- 解决：`'file:///' + path.replace(/\\/g, '/')`

### 5. better-sqlite3 在 ASAR 内找不到 bindings

- ASAR 内的原生模块路径解析失败
- 解决：禁用 ASAR（`"asar": false`）

### 6. `__dirname is not defined`

- ESM 模块没有 `__dirname` 全局变量
- 解决：`path.dirname(fileURLToPath(import.meta.url))`

### 7. Electron 窗口白屏

- Auth 中间件拦截了静态文件请求（返回 401）
- 解决：auth 只对 `/api/` 路径生效

### 8. Windows GPU 缓存错误

- 日志 `Unable to move the cache: 拒绝访问` / `Gpu Cache Creation failed`
- 无害警告，不影响功能（禁用硬件加速后可忽略）

### 9. NSIS 安装时提示"无法关闭应用"

- 之前版本的 Sman 进程仍在运行或文件被占用
- 解决：先在任务管理器结束所有 Sman 进程，或使用 oneClick 安装模式

### 10. 端口冲突导致开发服务器启动失败

- 5880/5881 端口被之前未退出的进程占用
- 解决：`taskkill /PID <pid> /F` 或重启电脑

### 11. electron-rebuild 在 pnpm 下不工作

- pnpm 使用 `.pnpm/better-sqlite3@11.10.0/node_modules/better-sqlite3/` 的 symlink 结构
- electron-rebuild 报 "Rebuild Complete" 但实际没有修改文件
- `prebuild-install --runtime=electron --target=33.4.11` 也报告成功但文件不变（因为 Electron prebuild 和 Node prebuild 是同一个文件）
- **结论**：不要用 electron-rebuild，直接用 pnpm install 的原始二进制即可

## 打包后验证

```bash
# 检查打包产物中的 better-sqlite3 是否正确
md5sum node_modules/.pnpm/better-sqlite3@11.10.0/node_modules/better-sqlite3/build/Release/better_sqlite3.node
md5sum release/win-unpacked/resources/app/node_modules/better-sqlite3/build/Release/better_sqlite3.node
# 两个 MD5 应该一致

# 启动打包后的应用验证数据库初始化
release/win-unpacked/Sman.exe
# 日志应显示: SessionStore: Database initialized
```

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
