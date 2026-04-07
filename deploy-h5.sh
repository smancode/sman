#!/usr/bin/env bash
set -e

# sman + H5 一键部署脚本
# 编译 sman 后端 + 同步 H5 代码 → 打包 → 上传服务器 → 远程安装
#
# 用法:
#   ./deploy-h5.sh              # 完整流程
#   ./deploy-h5.sh --build-only # 只编译打包，不上传
#   ./deploy-h5.sh --upload-only # 只上传安装（跳过编译）

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

VERSION=$(node -e "console.log(require('./package.json').version)")
H5_DIR="/Users/nasakim/projects/cowork/aipro/H5"
SSH_ALIAS="h5"
REMOTE_BASE="/root"
STAGING="release/deploy-sman-h5"
SERVER_IP="59.110.164.212"
SERVER_PORT="5880"

BUILD_ONLY=false
UPLOAD_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --build-only) BUILD_ONLY=true ;;
    --upload-only) UPLOAD_ONLY=true ;;
  esac
done

echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║    sman + H5 Deployer                ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"
echo ""

if ! command -v pnpm &> /dev/null; then
  echo -e "${RED}pnpm not found. Install: npm install -g pnpm${NC}"
  exit 1
fi

# ── 1. 安装依赖 ──────────────────────────────────────────

if [ ! -d "node_modules" ]; then
  echo -e "${YELLOW}Installing dependencies...${NC}"
  pnpm install
fi

# ── 2. 编译后端 ──────────────────────────────────────────

if [ "$UPLOAD_ONLY" = false ]; then
  echo -e "${CYAN}[1/5] Compiling sman backend...${NC}"
  rm -rf dist/server
  npx tsc -p server/tsconfig.json
  node -e "require('fs').writeFileSync('dist/server/package.json',JSON.stringify({type:'module'},null,2))"

  if [ ! -f "dist/server/index.js" ]; then
    echo -e "${RED}Compilation failed!${NC}"
    exit 1
  fi
  echo -e "${GREEN}Backend compiled OK.${NC}"

  # ── 3. 准备 staging 目录 ────────────────────────────────

  echo -e "${CYAN}[2/5] Preparing staging directory...${NC}"
  rm -rf "${STAGING}"
  mkdir -p "${STAGING}/sman/app" "${STAGING}/sman/plugins" "${STAGING}/H5"

  # sman 编译产物
  cp -r dist/server/* "${STAGING}/sman/app/"

  # sman 前端静态文件
  if [ -f "dist/index.html" ]; then
    mkdir -p "${STAGING}/sman/app/public"
    cp dist/index.html dist/favicon* "${STAGING}/sman/app/" 2>/dev/null || true
    cp -r dist/assets "${STAGING}/sman/app/assets" 2>/dev/null || true
  fi

  # ── 4. 复制后端依赖 ──────────────────────────────────────

  echo -e "${CYAN}[3/5] Installing backend dependencies...${NC}"

  BACKEND_DEPS='@anthropic-ai/claude-agent-sdk @anthropic-ai/claude-code @larksuiteoapi/node-sdk better-sqlite3 cron-parser express node-cron qrcode uuid ws yaml zod'

  # 在 staging 目录创建 package.json 并用 npm install 安装扁平依赖
  node -e "
    const fs = require('fs');
    const rootPkg = require('./package.json');
    const names = '${BACKEND_DEPS}'.trim().split(/\\s+/);
    const deps = {};
    for (const d of names) {
      const ver = rootPkg.dependencies?.[d];
      if (ver) deps[d] = ver;
      else console.error('Missing dep: ' + d);
    }
    const pkg = { name: 'sman-backend', private: true, type: 'module', dependencies: deps };
    fs.writeFileSync('release/deploy-sman-h5/sman/app/package.json', JSON.stringify(pkg, null, 2));
    console.log('  package.json written with ' + Object.keys(deps).length + ' deps');
  "

  cd "${STAGING}/sman/app"
  echo -e "${YELLOW}  Running npm install (production only)...${NC}"
  npm install --omit=dev --no-package-lock 2>&1 | tail -5
  cd "$ROOT_DIR"

  if [ ! -d "${STAGING}/sman/app/node_modules" ]; then
    echo -e "${RED}npm install failed!${NC}"
    exit 1
  fi
  echo -e "${GREEN}  Dependencies installed.${NC}"
  cd "$ROOT_DIR"

  # 复制插件（排除 office-skills 411MB、gstack 符号链接）
  echo -e "${CYAN}  Copying plugins...${NC}"
  for plugin in plugins/*/; do
    name=$(basename "$plugin")
    if [ "$name" = "office-skills" ] || [ "$name" = "gstack" ]; then
      echo -e "    ${YELLOW}Skip ${name}${NC}"
      continue
    fi
    # 跳过符号链接指向不存在的目录
    if [ -L "plugins/${name}" ]; then
      continue
    fi
    echo "    ${name}"
    cp -r "plugins/${name}" "${STAGING}/sman/plugins/"
  done

  # SDK patch
  if [ -f "scripts/patch-sdk.mjs" ]; then
    cp "scripts/patch-sdk.mjs" "${STAGING}/sman/app/patch-sdk.mjs"
  fi

  # ── 复制 H5 项目 ──────────────────────────────────────

  echo -e "${CYAN}  Copying H5 project...${NC}"
  if [ -d "${H5_DIR}" ]; then
    rsync -a \
      --exclude='frontend-vue/' \
      --exclude='.git/' \
      --exclude='logs/' \
      --exclude='__pycache__/' \
      --exclude='*.pyc' \
      --exclude='venv/' \
      --exclude='.venv/' \
      --exclude='node_modules/' \
      --exclude='deploy/' \
      --exclude='doc/' \
      --exclude='docs/' \
      --exclude='tmp/' \
      --exclude='state/' \
      --exclude='memory/' \
      --exclude='.DS_Store' \
      --exclude='backend.log' \
      "${H5_DIR}/" "${STAGING}/H5/"
    echo -e "${GREEN}  H5 project copied.${NC}"
  else
    echo -e "${YELLOW}  Warning: H5 project not found at ${H5_DIR}${NC}"
  fi

  # ── 创建 sman 启动脚本 ────────────────────────────────

  cat > "${STAGING}/sman/sman.sh" << 'SMANSH'
#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

export HOST="${HOST:-0.0.0.0}"
export PORT="${PORT:-5880}"
export SMANBASE_HOME="${SMANBASE_HOME:-$HOME/.sman}"
export CORS_ORIGINS="${CORS_ORIGINS:-}"
export NODE_ENV="${NODE_ENV:-production}"

# Resolve node path via nvm or system
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
NODE_BIN="$(which node)"

mkdir -p "$SMANBASE_HOME" "$SMANBASE_HOME/logs"

echo "[sman] Starting on ${HOST}:${PORT}..."
exec "$NODE_BIN" --dns-result-order=ipv4first app/index.js "$@"
SMANSH
  chmod +x "${STAGING}/sman/sman.sh"

  # ── 创建 install.sh ───────────────────────────────────

  cat > "${STAGING}/install.sh" << 'INSTALLSH'
#!/usr/bin/env bash
set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Source nvm to get node
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"

mkdir -p /root/.sman/logs

echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║    sman + H5 Installer               ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"

# 1. 同步 sman 文件
echo -e "${CYAN}[1/4] Installing sman...${NC}"
rsync -a --delete "${SCRIPT_DIR}/sman/" /root/sman/
echo -e "${GREEN}sman files synced.${NC}"

# 2. Rebuild better-sqlite3 (darwin binary won't work on linux)
echo -e "${CYAN}[2/4] Installing better-sqlite3 native module...${NC}"
cd /root/sman/app
if node -e "require('better-sqlite3')" 2>/dev/null; then
  echo -e "${GREEN}better-sqlite3 OK (already working).${NC}"
else
  echo -e "${YELLOW}  Reinstalling better-sqlite3 for Linux...${NC}"
  npm install better-sqlite3 --no-save 2>&1 || {
    echo -e "${RED}better-sqlite3 install failed!${NC}"
    exit 1
  }
  echo -e "${GREEN}better-sqlite3 installed.${NC}"
fi

# 3. 同步 H5 文件（不删除已有文件）
echo -e "${CYAN}[3/4] Syncing H5 project...${NC}"
if [ -d "${SCRIPT_DIR}/H5/backend" ]; then
  rsync -a --update "${SCRIPT_DIR}/H5/" /root/H5/
  echo -e "${GREEN}H5 files synced.${NC}"
else
  echo -e "${YELLOW}No H5 files to sync.${NC}"
fi

# 4. 启动 sman
echo -e "${CYAN}[4/4] Starting sman...${NC}"

# 先停旧的
pkill -f "node.*sman/app/index.js" 2>/dev/null || true
sleep 1

cd /root/sman
export HOST=0.0.0.0
export PORT=5880
export CORS_ORIGINS="http://59.110.164.212:5880"
export SMANBASE_HOME=/root/.sman
export NODE_ENV=production

nohup bash sman.sh > /root/.sman/sman.log 2>&1 &
NODE_BIN=$(which node)
SMAN_PID=$!
sleep 2

# 验证
if curl -sf http://127.0.0.1:5880/api/health > /dev/null 2>&1; then
  echo -e "${GREEN}sman started (PID: ${SMAN_PID}).${NC}"
else
  echo -e "${RED}sman failed to start. Check /root/.sman/sman.log${NC}"
  tail -20 /root/.sman/sman.log
  exit 1
fi

# 打印 auth token
echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  sman deployed successfully!${NC}"
echo ""
sleep 1
if [ -f /root/.sman/config.json ]; then
  TOKEN=$(node -e "const c=JSON.parse(require('fs').readFileSync('/root/.sman/config.json','utf8'));console.log(c.authToken||'')" 2>/dev/null)
  if [ -n "$TOKEN" ]; then
    echo -e "${CYAN}  Auth Token: ${TOKEN}${NC}"
    echo ""
    echo -e "${YELLOW}  Electron 后端设置:${NC}"
    echo -e "  URL: ws://59.110.164.212:5880/ws"
    echo -e "  Token: ${TOKEN}"
  fi
fi
echo ""
echo -e "${YELLOW}  Commands:${NC}"
echo -e "  Start:  cd /root/sman && bash sman.sh"
echo -e "  Stop:   pkill -f 'node.*sman/app/index.js'"
echo -e "  Log:    tail -f /root/.sman/sman.log"
echo -e "  Status: curl http://127.0.0.1:5880/api/health"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
INSTALLSH
  chmod +x "${STAGING}/install.sh"

  # ── 打包 ──────────────────────────────────────────────

  echo -e "${CYAN}[4/5] Creating tarball...${NC}"
  mkdir -p release
  tar -czf "release/deploy-sman-h5.tar.gz" -C release "deploy-sman-h5"

  PKG_SIZE=$(ls -lh release/deploy-sman-h5.tar.gz | awk '{print $5}')
  echo -e "${GREEN}Package: release/deploy-sman-h5.tar.gz (${PKG_SIZE})${NC}"
fi

# ── 上传 + 安装 ─────────────────────────────────────────

if [ "$BUILD_ONLY" = true ]; then
  echo ""
  echo -e "${YELLOW}Build only mode. Upload manually:${NC}"
  echo -e "  scp release/deploy-sman-h5.tar.gz ${SSH_ALIAS}:/root/"
  echo -e "  ssh ${SSH_ALIAS} 'cd /root && tar xzf deploy-sman-h5.tar.gz && bash deploy-sman-h5/install.sh'"
  exit 0
fi

echo -e "${CYAN}[5/5] Uploading to ${SSH_ALIAS}...${NC}"

# 上传
scp release/deploy-sman-h5.tar.gz "${SSH_ALIAS}:/root/"

# 解压并安装
echo -e "${CYAN}Installing on remote server...${NC}"
ssh "${SSH_ALIAS}" "cd /root && tar xzf deploy-sman-h5.tar.gz && bash deploy-sman-h5/install.sh"

echo ""
echo -e "${GREEN}Deploy complete!${NC}"
