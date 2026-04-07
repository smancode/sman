#!/usr/bin/env bash
set -e

# Sman 后端 Linux x86_64 打包脚本
# 产物: release/sman-backend-linux-x64.tar.gz
# 部署: 解压后运行 ./start.sh

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

VERSION=$(node -e "console.log(require('./package.json').version)")
OUTPUT_DIR="release"
PKG_NAME="sman-backend-${VERSION}-linux-x64"
PKG_DIR="${OUTPUT_DIR}/${PKG_NAME}"

echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  Sman Backend Linux x86_64 Packager ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"
echo ""

# ── 1. 环境检查 ──────────────────────────────────────────

if ! command -v pnpm &> /dev/null; then
  echo -e "${RED}pnpm not found. Install: npm install -g pnpm${NC}"
  exit 1
fi

echo -e "${CYAN}Version: ${VERSION}${NC}"

# ── 2. 安装依赖 ──────────────────────────────────────────

if [ ! -d "node_modules" ]; then
  echo -e "${YELLOW}Installing dependencies...${NC}"
  pnpm install
fi

# ── 3. 编译后端 TypeScript ────────────────────────────────

echo -e "${CYAN}[1/4] Compiling backend TypeScript...${NC}"
rm -rf dist/server
npx tsc -p server/tsconfig.json

# 写 package.json 声明 ESM
node -e "require('fs').writeFileSync('dist/server/package.json',JSON.stringify({type:'module'},null,2))"

if [ ! -f "dist/server/index.js" ]; then
  echo -e "${RED}Backend compilation failed!${NC}"
  exit 1
fi
echo -e "${GREEN}Backend compiled OK.${NC}"

# ── 4. 准备打包目录 ──────────────────────────────────────

echo -e "${CYAN}[2/4] Preparing package directory...${NC}"
rm -rf "${PKG_DIR}"
mkdir -p "${PKG_DIR}"

# 复制编译产物
cp -r dist/server/* "${PKG_DIR}/"

# ── 5. 复制生产依赖 ──────────────────────────────────────

echo -e "${CYAN}[3/4] Copying production dependencies...${NC}"

# 后端实际用到的顶层依赖（排除前端 UI 包）
BACKEND_DEPS=(
  "@anthropic-ai/claude-agent-sdk"
  "@anthropic-ai/claude-code"
  "@larksuiteoapi/node-sdk"
  "better-sqlite3"
  "cron-parser"
  "express"
  "node-cron"
  "qrcode"
  "uuid"
  "ws"
  "yaml"
  "zod"
)

# 复制每个生产依赖到打包目录
mkdir -p "${PKG_DIR}/node_modules"

for dep in "${BACKEND_DEPS[@]}"; do
  if [ -d "node_modules/${dep}" ]; then
    echo "  ${dep}"
    # 确保目标目录结构存在（处理 @scope/name 的情况）
    target_dir="${PKG_DIR}/node_modules/${dep}"
    mkdir -p "$(dirname "${target_dir}")"
    # 用 cp -rL 解析符号链接为实际文件
    cp -rL "node_modules/${dep}" "${target_dir}" 2>/dev/null || \
      cp -r "node_modules/${dep}" "${target_dir}"
  else
    echo -e "  ${YELLOW}Warning: ${dep} not found in node_modules${NC}"
  fi
done

# 一些包有嵌套的 peer deps 或子依赖，需要也复制
# 通过扫描已复制的 node_modules 内的依赖
echo -e "${CYAN}  Scanning sub-dependencies...${NC}"
cd "${PKG_DIR}"
node -e "
const fs = require('fs');
const path = require('path');

function collectDeps(dir, rootDir) {
  const pkgPath = path.join(dir, 'package.json');
  if (!fs.existsSync(pkgPath)) return;
  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    const allDeps = { ...(pkg.dependencies || {}), ...(pkg.peerDependencies || {}), ...(pkg.optionalDependencies || {}) };
    for (const dep of Object.keys(allDeps)) {
      const dest = path.join(rootDir, 'node_modules', dep);
      if (fs.existsSync(dest)) continue;  // already copied
      const src = path.join('$ROOT_DIR', 'node_modules', dep);
      if (fs.existsSync(src)) {
        console.log('    ' + dep);
        cpSync(src, dest);
        // recurse into this dep's deps
        collectDeps(dest, rootDir);
      }
    }
  } catch {}
}

function cpSync(src, dest) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  try {
    fs.cpSync(src, dest, { recursive: true, verbatimSymlinks: false, dereference: true });
  } catch {
    fs.cpSync(src, dest, { recursive: true });
  }
}

collectDeps('.', '.');
"
cd "$ROOT_DIR"

# ── 6. 创建启动脚本 ──────────────────────────────────────

cat > "${PKG_DIR}/start.sh" << 'STARTSH'
#!/usr/bin/env bash
set -e

# Sman Backend 启动脚本 (Linux)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# 环境变量
export PORT="${PORT:-5880}"
export SMANBASE_HOME="${SMANBASE_HOME:-$HOME/.sman}"
export NODE_ENV="${NODE_ENV:-production}"

mkdir -p "$SMANBASE_HOME"

# 检查 better-sqlite3 原生模块
if ! node -e "require('better-sqlite3')" 2>/dev/null; then
  echo -e "${YELLOW}better-sqlite3 native module needs rebuild for this platform...${NC}"
  if command -v npm &> /dev/null; then
    echo -e "${YELLOW}Running npm rebuild better-sqlite3...${NC}"
    npm rebuild better-sqlite3 --napi-build-from-source 2>&1 || {
      echo -e "${RED}Failed to rebuild better-sqlite3!${NC}"
      echo -e "${RED}Try: npm install better-sqlite3 --build-from-source${NC}"
      exit 1
    }
    echo -e "${GREEN}Rebuild OK.${NC}"
  else
    echo -e "${RED}npm not found. Cannot rebuild better-sqlite3.${NC}"
    echo -e "${RED}Install Node.js 22 LTS and npm first.${NC}"
    exit 1
  fi
fi

echo -e "${GREEN}Starting Sman backend on port ${PORT}...${NC}"
echo -e "${GREEN}Home: ${SMANBASE_HOME}${NC}"
echo ""

node index.js
STARTSH

chmod +x "${PKG_DIR}/start.sh"

# ── 7. 打包 ──────────────────────────────────────────────

echo -e "${CYAN}[4/4] Creating tarball...${NC}"
mkdir -p "${OUTPUT_DIR}"
cd "${OUTPUT_DIR}"

tar -czf "${PKG_NAME}.tar.gz" "${PKG_NAME}"

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  Package created:${NC}"
echo -e "${GREEN}  ${OUTPUT_DIR}/${PKG_NAME}.tar.gz${NC}"
echo ""
echo -e "${YELLOW}  Deploy to Linux server:${NC}"
echo -e "  scp ${OUTPUT_DIR}/${PKG_NAME}.tar.gz user@server:~/"
echo -e "  ssh user@server"
echo -e "  tar xzf ${PKG_NAME}.tar.gz"
echo -e "  cd ${PKG_NAME}"
echo -e "  ./start.sh"
echo ""
echo -e "${YELLOW}  Requirements on target:${NC}"
echo -e "  - Node.js 22 LTS"
echo -e "  - C++ build tools (for better-sqlite3 rebuild: gcc, make, python3)"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

cd "$ROOT_DIR"
