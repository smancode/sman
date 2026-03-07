# Windows 下 SmanClaw Desktop 编译打包启动完整指南

## 环境信息

- **操作系统**: Windows 11 Pro
- **项目**: SmanClaw Desktop (Tauri + SvelteKit)
- **工作目录**: `C:\work\smanpath\smanclaw-desktop`
- **Rust 工具链**: stable-x86_64-pc-windows-msvc
- **当前分支**: claw

## 快速启动流程

### 1. 环境准备（首次或清理后）

```bash
# 设置正确的 PATH（添加到 ~/.bashrc 或 ~/.bash_profile）
export PATH="$HOME/.rustup/toolchains/stable-x86_64-pc-windows-msvc/bin:$HOME/.cargo/bin:$PATH"

# 验证工具链
cargo --version
rustc --version
node --version
npm --version
```

### 2. 更新代码

```bash
cd C:/work/smanpath/smanclaw-desktop
git fetch origin
git rebase origin/claw  # 或 git pull origin claw
```

### 3. 安装依赖

```bash
# 前端依赖
cd C:/work/smanpath/smanclaw-desktop/crates/smanclaw-desktop
npm install

# Web 前端依赖（如果需要）
cd C:/work/smanpath/web
npm install
```

### 4. 构建 Web 资源

```bash
cd C:/work/smanpath/web
npm run build
# 生成 C:/work/smanpath/web/dist/
```

### 5. 开发模式启动

```bash
cd C:/work/smanpath/smanclaw-desktop/crates/smanclaw-desktop
npm run tauri:dev
```

**注意**: 如果端口 5173 被占用，先关闭进程：
```bash
netstat -ano | findstr :5173
taskkill /F /PID <进程ID>
```

### 6. 生产模式打包

```bash
cd C:/work/smanpath/smanclaw-desktop/crates/smanclaw-desktop
npm run tauri:build
```

**生成位置**: `C:/work/smanpath/smanclaw-desktop/target/release/smanclaw-desktop-tauri.exe`

### 7. 运行生产版本

```bash
cd C:/work/smanpath/smanclaw-desktop
./target/release/smanclaw-desktop-tauri.exe
```

## 常见问题与解决方案

### 问题 1: cargo/rustc 找不到

**症状**: `cargo: program not found` 或 `error: no such subcommand: ...`

**原因**: PATH 中有错误版本的 cargo.exe（如 tools/cargo.exe）

**解决方案**:
```bash
# 删除错误的工具链
rm C:/work/smanpath/tools/cargo.exe
rm C:/work/smanpath/tools/rustc.exe

# 重启终端或重新加载 PATH
```

### 问题 2: web/dist/ 不存在

**症状**:
```
error: failed to run custom build command for `smanclaw-desktop-tauri v0.1.0`
Error: #[derive(RustEmbed)] folder 'web/dist/' does not exist
```

**解决方案**:
```bash
cd C:/work/smanpath/web
npm run build
```

### 问题 3: 端口 5173 被占用

**症状**: `Error: Port 5173 is already in use`

**解决方案**:
```bash
# 查找占用进程
netstat -ano | findstr :5173

# 强制关闭
taskkill /F /PID <进程ID>

# 或者使用 npx 命令
npx kill-port 5173
```

### 问题 4: Debug 模式运行时 DLL 错误

**症状**: `STATUS_ENTRYPOINT_NOT_FOUND (0xc0000139)`

**解决方案**: 使用生产模式
```bash
# 直接构建生产版本
npm run tauri:build

# 运行生产版本
./target/release/smanclaw-desktop-tauri.exe
```

## 性能优化建议

### 1. 首次全量编译（预计 1-2 小时）

首次编译需要编译 765+ 个 Rust 依赖包，建议：
- 使用稳定的网络环境
- 确保有足够的磁盘空间（至少 5GB）
- 可以先用 `cargo check` 快速检查错误

### 2. 增量编译（后续 5-15 分钟）

修改代码后重新编译，Rust 会只重新编译修改的部分。

### 3. 并行编译

在 `.cargo/config.toml` 中配置：
```toml
[build]
jobs = 8  # 根据 CPU 核心数调整
```

## 目录结构

```
C:/work/smanpath/
├── smanclaw-desktop/
│   ├── crates/
│   │   └── smanclaw-desktop/
│   │       ├── src-tauri/      # Rust 后端
│   │       └── package.json
│   └── target/
│       ├── debug/              # Debug 构建
│       └── release/            # Release 构建
├── web/
│   ├── dist/                   # Web 构建产物
│   └── package.json
└── tools/                      # ⚠️ 不要在这里放工具链
```

## 验证成功的标志

### 编译成功
```
    Finished `release` profile [optimized] target(s) in XXm XXs
```

### 运行成功
```
[INFO] Using config directory: "C:\Users\75260062\AppData\Roaming\smanclaw-desktop"
[INFO] SmanClaw Desktop initialized successfully
```

## 历史问题记录

### 2026-03-06 编译问题总结

**遇到的问题**:
1. 错误的 cargo.exe 在 tools/ 目录导致编译失败
2. Web 前端未构建导致 Rust 嵌入资源失败
3. Debug 模式运行时 DLL 入口点错误
4. Vite 开发服务器端口冲突

**解决方法**:
1. 删除 tools/ 下的错误工具链，使用正确的 rustup 工具链
2. 先构建 Web 前端生成 dist/ 目录
3. 使用生产模式构建避免运行时错误
4. 清理占用端口的进程

**最终结果**:
- 生产版本成功构建（50MB）
- 应用程序正常运行
- 所有功能正常启动
