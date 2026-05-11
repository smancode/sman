#!/bin/bash
# ============================================================================
# Sman macOS arm64 打包脚本
#
# 输出: release/Sman-<version>-arm64.dmg
#
# 前提:
#   - macOS (Apple Silicon)
#   - Node.js >= 22
#   - pnpm 已安装
#
# 用法:
#   bash build-mac.sh              # 完整打包
#   bash build-mac.sh --skip-deps  # 跳过依赖安装
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

# ── 加载构建环境变量（Hub 上报 + 在线更新）──
# 创建 .env.build 文件注入企业版地址，不提交到 git
#
#   SMAN_HUB_URL=http://你的sman-server:5882      ← Hub 上报地址
#   SMAN_UPDATE_URL=http://你的sman-server:5882/updates/sman  ← 在线更新地址
#   SMAN_PSK=你的密钥                              ← Hub 通信密钥
#
# 不设置则打包为开源版（无 Hub 上报、无在线更新）
if [[ -f ".env.build" ]]; then
  info "加载 .env.build 构建环境变量..."
  while IFS='=' read -r _key _val; do
    [[ -z "$_key" || "$_key" == \#* ]] && continue
    export "$_key=$_val"
    info "  ${_key}=${_val}"
  done < .env.build
  ok "构建环境变量加载完成"
else
  info "无 .env.build，打包开源版（无 Hub 上报、无在线更新）"
fi

# ── 自动设置日期版本号 ──
# 格式: YY.MMDD.HH （如 26.429.15）
_V_MAJOR=$(date +%y)
_V_MINOR=$((10#$(date +%m) * 100 + 10#$(date +%d)))
_V_PATCH=$((10#$(date +%H)))
DATE_VERSION="${_V_MAJOR}.${_V_MINOR}.${_V_PATCH}"
info "设置版本号: ${DATE_VERSION}"
node -e "const fs=require('fs');const p=JSON.parse(fs.readFileSync('package.json','utf8'));p.version='${DATE_VERSION}';fs.writeFileSync('package.json',JSON.stringify(p,null,2)+'\n')"
VERSION="${DATE_VERSION}"
info "Sman v${VERSION} — macOS arm64 打包开始"

# ── 检查操作系统 ──
if [[ "$(uname -s)" != "Darwin" ]]; then
  err "此脚本只能在 macOS 上执行"
  exit 1
fi

ARCH=$(uname -m)
if [[ "$ARCH" != "arm64" ]]; then
  warn "当前架构为 ${ARCH}，预期 arm64 (Apple Silicon)"
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

# ── Step 3.5: 生成 macOS 图标 (.icns) ──
generate_icon() {
  local ICO_FILE="build/icon.ico"
  local ICNS_FILE="build/icon.icns"

  if [[ -f "$ICNS_FILE" ]]; then
    ok "macOS 图标已存在: ${ICNS_FILE}"
    return
  fi

  if [[ ! -f "$ICO_FILE" ]]; then
    err "未找到图标文件: ${ICO_FILE}"
    exit 1
  fi

  info "从 icon.ico 生成 icon.icns..."

  # 创建临时 iconset 目录
  local ICONSET_DIR
  ICONSET_DIR=$(mktemp -d)/icon.iconset
  mkdir -p "$ICONSET_DIR"

  # 从 ico 提取最大尺寸的 png（256x256），放大到 512x512
  # macOS icns 需要: icon_16x16.png, icon_16x16@2x.png, icon_32x32.png,
  #   icon_32x32@2x.png, icon_128x128.png, icon_128x128@2x.png,
  #   icon_256x256.png, icon_256x256@2x.png, icon_512x512.png, icon_512x512@2x.png
  magick "$ICO_FILE[3]" -resize 1024x1024 "$ICONSET_DIR/icon_512x512@2x.png"
  magick "$ICO_FILE[3]" -resize 512x512   "$ICONSET_DIR/icon_512x512.png"
  magick "$ICO_FILE[3]" -resize 256x256   "$ICONSET_DIR/icon_256x256.png"
  magick "$ICO_FILE[3]" -resize 256x256   "$ICONSET_DIR/icon_128x128@2x.png"
  magick "$ICO_FILE[3]" -resize 128x128   "$ICONSET_DIR/icon_128x128.png"
  magick "$ICO_FILE[3]" -resize 64x64     "$ICONSET_DIR/icon_32x32@2x.png"
  magick "$ICO_FILE[3]" -resize 32x32     "$ICONSET_DIR/icon_32x32.png"
  magick "$ICO_FILE[3]" -resize 32x32     "$ICONSET_DIR/icon_16x16@2x.png"
  magick "$ICO_FILE[3]" -resize 16x16     "$ICONSET_DIR/icon_16x16.png"

  iconutil -c icns "$ICONSET_DIR" -o "$ICNS_FILE"
  rm -rf "$(dirname "$ICONSET_DIR")"

  ok "macOS 图标生成完成: ${ICNS_FILE}"
}

# ── Step 3.6: 重编译原生模块为 Electron ABI ──
rebuild_native() {
  info "重编译 better-sqlite3 为 Electron ABI..."

  local SQLITE_DIR
  SQLITE_DIR=$(find node_modules/.pnpm -path "*/better-sqlite3@*/better-sqlite3" -maxdepth 6 -type d 2>/dev/null | head -1)

  if [[ -z "$SQLITE_DIR" ]]; then
    err "未找到 better-sqlite3 模块目录"
    exit 1
  fi

  # 获取 Electron 版本
  local ELECTRON_VERSION
  ELECTRON_VERSION=$(node -e "console.log(require('electron/package.json').version)")
  info "Electron 版本: ${ELECTRON_VERSION}"

  # 用 electron-rebuild 重编译
  npx electron-rebuild -v "$ELECTRON_VERSION" -m "$SQLITE_DIR" -w better-sqlite3

  local NODE_FILE="$SQLITE_DIR/build/Release/better_sqlite3.node"
  if [[ ! -f "$NODE_FILE" ]]; then
    err "重编译失败: better_sqlite3.node 不存在"
    exit 1
  fi

  ok "better-sqlite3 原生模块重编译完成 ($(du -sh "$NODE_FILE" | cut -f1))"
}

# ── Step 4: electron-builder 打包 ──
package_mac() {
  info "开始 electron-builder 打包 (macOS arm64 DMG)..."

  # 禁用代码签名（无 Apple Developer 证书时）
  export CSC_IDENTITY_AUTO_DISCOVERY=false

  # 清理旧产物
  rm -rf release/Sman-*.dmg release/Sman-*.dmg.blockmap release/mac release/*.yaml 2>/dev/null || true

  # electron-builder 会根据 mac.target 配置自动打包 DMG
  # npmRebuild=false 因为我们已手动 rebuild
  npx electron-builder --mac dmg --arm64

  ok "macOS 打包完成!"
}

# ── Step 5: 验证输出 ──
verify_output() {
  info "验证打包产物..."

  local installer=""
  # electron-builder DMG 默认命名: Sman-{version}-arm64.dmg 或 Sman-{version}.dmg
  for f in release/Sman-*.dmg; do
    if [ -f "$f" ]; then
      installer="$f"
      break
    fi
  done

  if [[ -z "$installer" ]]; then
    err "未找到 DMG 安装包!"
    ls -la release/ 2>/dev/null || echo "(release 目录不存在)"
    exit 1
  fi

  local dmg_size
  dmg_size=$(du -sh "$installer" | cut -f1)

  echo ""
  echo "============================================================"
  echo -e "  ${GREEN}Sman v${VERSION} macOS arm64 打包成功!${NC}"
  echo "============================================================"
  echo ""
  echo "  安装包: ${installer} (${dmg_size})"
  echo ""
  echo "  安装方式:"
  echo "  1. 双击 DMG 文件"
  echo "  2. 将 Sman 拖入 Applications 文件夹"
  echo "  3. 首次打开需右键 → 打开（绕过 Gatekeeper）"
  echo ""
  echo "  用户数据目录: ~/Library/Application Support/smanbase/"
  echo "============================================================"
}

# ── 主流程 ──
main() {
  echo ""
  echo "============================================================"
  echo "  Sman v${VERSION} — macOS arm64 打包"
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
  generate_icon
  rebuild_native
  package_mac
  verify_output

  # 打包完成后恢复 better-sqlite3 为 Node.js ABI（rebuild_native 改成了 Electron ABI）
  info "恢复 better-sqlite3 为 Node.js ABI..."
  npm rebuild better-sqlite3
  ok "better-sqlite3 已恢复为 Node.js ABI，dev.sh 可正常使用"
}

main "$@"
