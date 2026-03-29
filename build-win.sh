#!/bin/bash
# ============================================================================
# Sman Windows x64 打包脚本 (从 macOS 交叉编译)
#
# 输出: release/Sman 0.1.0.exe (portable 绿色免安装)
#
# 前提:
#   - macOS 开发机，已安装 pnpm
#   - 内网 Windows 目标机器 (x64)，无需联网
#   - 用户需配置本地大模型的 baseUrl + apiKey
#
# 用法:
#   ./build-win.sh              # 完整打包
#   ./build-win.sh --skip-deps  # 跳过依赖安装 (已在之前装过)
# ============================================================================

set -euo pipefail

# ── 颜色输出 ──
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── 项目根目录 ──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── 读取版本号 ──
VERSION=$(node -e "console.log(require('./package.json').version)")
info "Sman v${VERSION} — Windows x64 打包开始 (portable)"

# ── 检查前置工具 ──
check_prerequisites() {
  info "检查前置工具..."
  command -v pnpm >/dev/null 2>&1 || { err "pnpm 未安装，请先: npm install -g pnpm"; exit 1; }
  command -v node >/dev/null 2>&1 || { err "node 未安装"; exit 1; }
  ok "前置工具就绪"
}

# ── Step 1: 安装依赖 ──
install_deps() {
  info "安装依赖..."
  pnpm install
  ok "依赖安装完成"
}

# ── Step 2: 构建前端 + 后端 ──
build_app() {
  info "构建前端 (Vite) + 后端 (tsc)..."
  pnpm build
  ok "前端 + 后端构建完成"
}

# ── Step 3: 构建 Electron 主进程 ──
build_electron() {
  info "构建 Electron 主进程 + 预加载脚本..."
  pnpm build:electron
  ok "Electron 构建完成"
}

# ── Step 4: 检查 plugins 目录 ──
prepare_plugins() {
  info "检查 plugins 目录..."
  if [ -d "plugins" ]; then
    ok "plugins 目录存在 (superpowers, gstack)"
  else
    warn "plugins 目录不存在，打包将不包含插件"
  fi
}

# ── Step 5: electron-builder 打包 Windows portable ──
package_win() {
  info "开始 electron-builder 打包 (Windows x64 portable)..."

  # 禁用代码签名（内网使用无需签名）
  export CSC_IDENTITY_AUTO_DISCOVERY=false

  npx electron-builder --win --x64

  ok "Windows 打包完成!"
}

# ── Step 6: 恢复开发环境的 native 模块 ──
restore_native_modules() {
  info "恢复开发环境的 native 模块 (better-sqlite3)..."
  # electron-builder 打包时会 rebuild native 模块为 Electron 版本
  # 打包完成后需要恢复为系统 Node 版本，否则 dev 模式启动会报错
  pnpm rebuild better-sqlite3 2>&1 | tail -3
  ok "native 模块已恢复为系统 Node 版本"
}

# ── Step 7: 验证输出 ──
verify_output() {
  info "验证打包产物..."

  # portable 格式输出一个 .exe 文件（非 Setup 安装包）
  local found=false
  for f in release/*.exe; do
    if [ -f "$f" ] && [[ "$f" != *"Setup"* ]] && [[ "$f" != *"uninstaller"* ]]; then
      local exe_size=$(du -sh "$f" | cut -f1)
      ok "便携包: ${f} (${exe_size})"
      found=true
    fi
  done

  if [ "$found" = false ]; then
    # Fallback: check win-unpacked directory
    if [ -d "release/win-unpacked" ]; then
      ok "解压即用目录: release/win-unpacked/"
      found=true
    fi
  fi

  if [ "$found" = false ]; then
    err "未找到 Windows 打包产物!"
    info "release/ 目录内容:"
    ls -la release/ 2>/dev/null || echo "(目录不存在)"
    exit 1
  fi

  echo ""
  echo "============================================================"
  echo -e "  ${GREEN}Sman v${VERSION} Windows x64 打包成功!${NC}"
  echo "============================================================"
  echo ""
  echo "  输出位置: release/"
  echo ""
  echo "  内网部署说明:"
  echo "  1. 将整个 release/ 目录（或 .exe 文件）复制到 Windows 机器"
  echo "  2. 双击 Sman.exe 即可运行（绿色免安装）"
  echo "  3. 打开 Sman → 设置 → 配置本地大模型:"
  echo "     - Base URL: http://<你的模型地址>:<端口>/v1"
  echo "     - API Key:  <你的 key>"
  echo "     - Model:    <模型名称>"
  echo ""
  echo "  注意事项:"
  echo "  - 首次启动会比较慢（需要初始化数据库）"
  echo "  - 数据目录: C:\\Users\\<用户名>\\.sman\\"
  echo "  - 如遇问题查看: C:\\Users\\<用户名>\\.sman\\logs\\"
  echo "============================================================"
}

# ── 主流程 ──
main() {
  echo ""
  echo "============================================================"
  echo "  Sman v${VERSION} — Windows x64 打包 (portable)"
  echo "  $(date '+%Y-%m-%d %H:%M:%S')"
  echo "============================================================"
  echo ""

  local SKIP_DEPS=false
  if [[ "${1:-}" == "--skip-deps" ]]; then
    SKIP_DEPS=true
  fi

  check_prerequisites

  if [[ "$SKIP_DEPS" == "false" ]]; then
    install_deps
  else
    info "跳过依赖安装 (--skip-deps)"
  fi

  build_app
  build_electron
  prepare_plugins
  package_win
  restore_native_modules
  verify_output
}

main "$@"
