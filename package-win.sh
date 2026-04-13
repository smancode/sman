#!/bin/bash
# ============================================================================
# 快速打包脚本（跳过依赖安装和构建，只做 electron-builder 打包）
#
# 用法:
#   bash package-win.sh           # 直接打包
#   bash package-win.sh --full    # 完整构建 + 打包
# ============================================================================
set -euo pipefail

eval "$(fnm env)" && fnm use 22

cd "$(dirname "$0")"

# 清理无效符号链接（@electron/rebuild 遍历时会报错）
find node_modules/.pnpm/node_modules -maxdepth 2 -type l ! -exec test -e {} \; -exec rm {} \; -print 2>/dev/null || true

if [[ "${1:-}" == "--full" ]]; then
  bash build-win.sh
else
  bash build-win.sh --skip-deps
fi
