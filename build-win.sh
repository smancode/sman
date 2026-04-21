#!/bin/bash
# ============================================================================
# Sman Windows x64 打包脚本
#
# 输出: release/Sman-Setup-<version>.exe (NSIS 安装包)
#
# 前提:
#   - Windows 10/11 开发机
#   - Node.js >= 22
#   - pnpm 已安装
#
# 用法:
#   bash build-win.sh              # 完整打包
#   bash build-win.sh --skip-deps  # 跳过依赖安装
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

# ── 自动设置日期版本号 ──
# 格式: YY.M.DD （如 26.4.21）
DATE_VERSION=$(date '+%y.%-m.%-d')
info "设置版本号: ${DATE_VERSION}"
node -e "const fs=require('fs');const p=JSON.parse(fs.readFileSync('package.json','utf8'));p.version='${DATE_VERSION}';fs.writeFileSync('package.json',JSON.stringify(p,null,2)+'\n')"
VERSION="${DATE_VERSION}"
info "Sman v${VERSION} — Windows x64 打包开始"

# ── 检查操作系统 ──
if [[ "$(uname -s)" != "MINGW"* && "$(uname -s)" != "MSYS"* && "$(uname -s)" != "CYGWIN"* && "$(uname -s)" != "Windows_NT" ]]; then
  err "此脚本只能在 Windows 上执行"
  err "Mac/Linux 请使用对应的构建脚本"
  exit 1
fi

# ── 检查 Node 版本 (>= 22) ──
NODE_MAJOR=$(node -e "console.log(process.version.split('.')[0].replace('v',''))")
if [[ "$NODE_MAJOR" -lt 22 ]]; then
  err "需要 Node.js >= 22，当前: $(node -v)"
  exit 1
fi

# ── Step 1: 安装依赖 ──
install_deps() {
  info "安装依赖..."
  pnpm install
  ok "依赖安装完成"

  # 清理 pnpm store 中无效的 rollup 平台包符号链接
  # (electron-builder 的 @electron/rebuild 会遍历这些目录，无效链接会导致打包失败)
  clean_rollup_links
}

# ── 清理无效的 rollup 平台包 ──
clean_rollup_links() {
  local rollup_dir="node_modules/.pnpm/node_modules/@rollup"
  if [[ ! -d "$rollup_dir" ]]; then
    return
  fi

  local cleaned=0
  for link in "$rollup_dir"/*; do
    if [[ -L "$link" && ! -e "$link" ]]; then
      warn "移除无效符号链接: $(basename "$link")"
      rm "$link"
      cleaned=$((cleaned + 1))
    fi
  done

  if [[ "$cleaned" -gt 0 ]]; then
    ok "清理了 ${cleaned} 个无效的 rollup 符号链接"
  fi
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

# ── Step 3.5: 确保原生模块兼容 Electron ABI ──
rebuild_native() {
  info "检查 better-sqlite3 原生模块..."

  local SQLITE_DIR
  SQLITE_DIR=$(find node_modules/.pnpm -path "*/better-sqlite3@*/better-sqlite3" -maxdepth 6 -type d 2>/dev/null | head -1)

  if [[ -z "$SQLITE_DIR" ]]; then
    warn "未找到 better-sqlite3 模块目录，跳过"
    return
  fi

  local NODE_FILE="$SQLITE_DIR/build/Release/better_sqlite3.node"
  if [[ ! -f "$NODE_FILE" ]]; then
    err "better_sqlite3.node 不存在！请运行 pnpm install"
    exit 1
  fi

  ok "better-sqlite3 原生模块就绪 ($(du -sh "$NODE_FILE" | cut -f1))"
}

# ── Step 4: electron-builder 打包 ──
package_win() {
  info "开始 electron-builder 打包 (Windows x64 NSIS)..."

  # 禁用代码签名（内网使用无需签名）
  export CSC_IDENTITY_AUTO_DISCOVERY=false

  # 清理旧产物
  rm -f release/Sman-Setup-*.exe release/*.blockmap 2>/dev/null || true

  npx electron-builder --win nsis --x64

  ok "Windows 打包完成!"
}

# ── Step 5: 验证输出 ──
verify_output() {
  info "验证打包产物..."

  local installer=""
  for f in release/Sman-Setup-*.exe; do
    if [ -f "$f" ]; then
      installer="$f"
      break
    fi
  done

  if [[ -z "$installer" ]]; then
    err "未找到 NSIS 安装包!"
    ls -la release/ 2>/dev/null || echo "(release 目录不存在)"
    exit 1
  fi

  local exe_size=$(du -sh "$installer" | cut -f1)

  echo ""
  echo "============================================================"
  echo -e "  ${GREEN}Sman v${VERSION} Windows x64 打包成功!${NC}"
  echo "============================================================"
  echo ""
  echo "  安装包: ${installer} (${exe_size})"
  echo ""
  echo "  内网部署说明:"
  echo "  1. 将安装包复制到 Windows 机器"
  echo "  2. 双击安装 (一键安装，无需管理员权限)"
  echo "  3. 安装完成后从桌面或开始菜单启动 Sman"
  echo "  4. 打开 Sman → 设置 → 配置本地大模型:"
  echo "     - Base URL: http://<模型地址>:<端口>/v1"
  echo "     - API Key:  <你的 key>"
  echo "     - Model:    <模型名称>"
  echo ""
  echo "  用户数据目录: C:\\Users\\<用户名>\\AppData\\Local\\smanbase\\"
  echo "  (卸载不会删除用户数据，升级安装会保留所有记录)"
  echo "============================================================"
}

# ── 主流程 ──
main() {
  echo ""
  echo "============================================================"
  echo "  Sman v${VERSION} — Windows x64 打包"
  echo "  $(date '+%Y-%m-%d %H:%M:%S')"
  echo "============================================================"
  echo ""

  local SKIP_DEPS=false
  if [[ "${1:-}" == "--skip-deps" ]]; then
    SKIP_DEPS=true
  fi

  if [[ "$SKIP_DEPS" == "false" ]]; then
    install_deps
  else
    info "跳过依赖安装 (--skip-deps)"
  fi

  build_app
  build_electron
  rebuild_native
  package_win
  verify_output
}

main "$@"
