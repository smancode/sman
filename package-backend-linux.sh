#!/usr/bin/env bash
set -e

# Sman 后端 Linux x86_64 自包含打包脚本
# 产物包含 Node.js runtime，目标机器零依赖
# 产物: release/sman-backend-{version}-linux-x64.tar.gz
# 部署: 解压后运行 ./sman

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

VERSION=$(node -e "console.log(require('./package.json').version)")
NODE_VERSION="v22.14.0"
NODE_DIST="node-${NODE_VERSION}-linux-x64"
# 国内镜像优先，失败回退官方
NODE_MIRROR="${NODE_MIRROR:-https://npmmirror.com/mirrors/node}"
NODE_URL="${NODE_MIRROR}/${NODE_VERSION}/${NODE_DIST}.tar.xz"
NODE_URL_FALLBACK="https://nodejs.org/dist/${NODE_VERSION}/${NODE_DIST}.tar.xz"
OUTPUT_DIR="release"
PKG_NAME="sman-backend-${VERSION}-linux-x64"
PKG_DIR="${OUTPUT_DIR}/${PKG_NAME}"
NODE_CACHE="${OUTPUT_DIR}/.cache/${NODE_DIST}"

echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  Sman Backend Linux x86_64 Packager ║${NC}"
echo -e "${CYAN}║  (Self-contained, zero deps)        ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"
echo ""

if ! command -v pnpm &> /dev/null; then
  echo -e "${RED}pnpm not found. Install: npm install -g pnpm${NC}"
  exit 1
fi

echo -e "${CYAN}Version: ${VERSION} | Node: ${NODE_VERSION}${NC}"

# ── 1. 安装依赖 ──────────────────────────────────────────

if [ ! -d "node_modules" ]; then
  echo -e "${YELLOW}Installing dependencies...${NC}"
  pnpm install
fi

# ── 2. 编译后端 TypeScript ────────────────────────────────

echo -e "${CYAN}[1/5] Compiling backend TypeScript...${NC}"
rm -rf dist/server
npx tsc -p server/tsconfig.json

node -e "require('fs').writeFileSync('dist/server/package.json',JSON.stringify({type:'module'},null,2))"

if [ ! -f "dist/server/index.js" ]; then
  echo -e "${RED}Backend compilation failed!${NC}"
  exit 1
fi
echo -e "${GREEN}Backend compiled OK.${NC}"

# ── 3. 下载 Node.js Linux runtime ────────────────────────

echo -e "${CYAN}[2/5] Preparing Node.js ${NODE_VERSION} runtime...${NC}"
mkdir -p "${OUTPUT_DIR}/.cache"

if [ ! -d "${NODE_CACHE}" ]; then
  echo -e "${YELLOW}Downloading ${NODE_URL}...${NC}"
  curl -fSL -o "${OUTPUT_DIR}/.cache/${NODE_DIST}.tar.xz" "${NODE_URL}" || {
    echo -e "${YELLOW}Mirror failed, trying official...${NC}"
    curl -fSL -o "${OUTPUT_DIR}/.cache/${NODE_DIST}.tar.xz" "${NODE_URL_FALLBACK}"
  }
  echo -e "${YELLOW}Extracting...${NC}"
  tar -xf "${OUTPUT_DIR}/.cache/${NODE_DIST}.tar.xz" -C "${OUTPUT_DIR}/.cache/"
  echo -e "${GREEN}Node.js runtime cached.${NC}"
else
  echo -e "${GREEN}Using cached Node.js runtime.${NC}"
fi

# ── 4. 组装打包目录 ──────────────────────────────────────

echo -e "${CYAN}[3/5] Assembling package...${NC}"
rm -rf "${PKG_DIR}"
mkdir -p "${PKG_DIR}/runtime" "${PKG_DIR}/app" "${PKG_DIR}/app/node_modules"

# 复制 Node.js runtime（只保留 node 二进制 + 必要的 lib）
cp "${NODE_CACHE}/bin/node" "${PKG_DIR}/runtime/node"
chmod +x "${PKG_DIR}/runtime/node"
cp -r "${NODE_CACHE}/lib" "${PKG_DIR}/runtime/" 2>/dev/null || true

# 复制编译产物
cp -r dist/server/* "${PKG_DIR}/app/"

# ── 5. 复制后端依赖 ──────────────────────────────────────

echo -e "${CYAN}[4/5] Copying backend dependencies...${NC}"

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

for dep in "${BACKEND_DEPS[@]}"; do
  if [ -d "node_modules/${dep}" ]; then
    echo "  ${dep}"
    target_dir="${PKG_DIR}/app/node_modules/${dep}"
    mkdir -p "$(dirname "${target_dir}")"
    cp -rL "node_modules/${dep}" "${target_dir}" 2>/dev/null || \
      cp -r "node_modules/${dep}" "${target_dir}"
  else
    echo -e "  ${YELLOW}Warning: ${dep} not found in node_modules${NC}"
  fi
done

# 扫描子依赖
cd "${PKG_DIR}/app"
node -e "
const fs = require('fs');
const path = require('path');
const rootDir = '$ROOT_DIR';

function collectDeps(dir, rootDir) {
  const pkgPath = path.join(dir, 'package.json');
  if (!fs.existsSync(pkgPath)) return;
  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    const allDeps = { ...(pkg.dependencies || {}), ...(pkg.peerDependencies || {}), ...(pkg.optionalDependencies || {}) };
    for (const dep of Object.keys(allDeps)) {
      const dest = path.join(rootDir, 'node_modules', dep);
      if (fs.existsSync(dest)) continue;
      const src = path.join(rootDir, dep);
      if (fs.existsSync(src)) {
        console.log('    ' + dep);
        cpDeps(src, dest);
        collectDeps(dest, rootDir);
      }
    }
  } catch {}
}

function cpDeps(src, dest) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  try { fs.cpSync(src, dest, { recursive: true, verbatimSymlinks: false, dereference: true }); }
  catch { fs.cpSync(src, dest, { recursive: true }); }
}

collectDeps('.', '.');
"
cd "$ROOT_DIR"

# ── 6. 创建启动入口 ──────────────────────────────────────

cat > "${PKG_DIR}/sman" << 'ENTRY'
#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NODE="$SCRIPT_DIR/runtime/node"
cd "$SCRIPT_DIR/app"

export PORT="${PORT:-5880}"
export SMANBASE_HOME="${SMANBASE_HOME:-$HOME/.sman}"
export NODE_ENV="${NODE_ENV:-production}"

mkdir -p "$SMANBASE_HOME"

# 自动 rebuild better-sqlite3 原生模块（darwin -> linux 首次需要）
if ! "$NODE" -e "require('better-sqlite3')" 2>/dev/null; then
  echo "[sman] better-sqlite3 native module mismatch, rebuilding..."
  if command -v npm &> /dev/null; then
    npm rebuild better-sqlite3 --napi-build-from-source 2>&1 || {
      echo "[sman] ERROR: rebuild failed. Install: gcc make python3" >&2
      exit 1
    }
    echo "[sman] Rebuild OK."
  else
    echo "[sman] ERROR: npm not found. Install Node.js 22 LTS first, or run: npm rebuild better-sqlite3" >&2
    exit 1
  fi
fi

exec "$NODE" --dns-result-order=ipv4first index.js "$@"
ENTRY

chmod +x "${PKG_DIR}/sman"

# ── 7. 打包 ──────────────────────────────────────────────

echo -e "${CYAN}[5/5] Creating tarball...${NC}"
cd "${OUTPUT_DIR}"
tar -czf "${PKG_NAME}.tar.gz" "${PKG_NAME}"

PKG_SIZE=$(ls -lh "${PKG_NAME}.tar.gz" | awk '{print $5}')

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  Package: ${OUTPUT_DIR}/${PKG_NAME}.tar.gz (${PKG_SIZE})${NC}"
echo ""
echo -e "${YELLOW}  Deploy:${NC}"
echo -e "  scp ${OUTPUT_DIR}/${PKG_NAME}.tar.gz root@server:~/"
echo -e "  ssh root@server"
echo -e "  tar xzf ${PKG_NAME}.tar.gz"
echo -e "  cd ${PKG_NAME}"
echo -e "  ./sman                    # foreground"
echo -e "  nohup ./sman &            # background"
echo ""
echo -e "${YELLOW}  Target: 零依赖，无需 Node.js / gcc${NC}"
echo -e "${YELLOW}  注意: better-sqlite3 是 darwin 编译的 .node，需要在 Linux 上 rebuild:${NC}"
echo -e "${YELLOW}  首次部署需在目标机器执行: ${PKG_DIR}/runtime/node -e \"require('better-sqlite3')\" || cd ${PKG_DIR}/app && npm rebuild better-sqlite3${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

cd "$ROOT_DIR"
