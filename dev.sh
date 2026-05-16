#!/usr/bin/env bash
set -e

# Sman Electron 开发模式启动脚本
# 一键启动：构建 Electron → 后端 → 前端 → Electron 桌面窗口

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     Sman Electron Dev Mode       ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"

# ── 1. 环境检查 ──────────────────────────────────────────

if ! command -v pnpm &> /dev/null; then
  echo -e "${RED}pnpm not found. Install: npm install -g pnpm${NC}"
  exit 1
fi

if [ ! -d "node_modules" ]; then
  echo -e "${YELLOW}Installing dependencies...${NC}"
  pnpm install
fi

# 确保 home 目录存在
HOME_DIR="${SMANBASE_HOME:-$HOME/.sman}"
mkdir -p "$HOME_DIR"

# ── 2. 端口检测 ──────────────────────────────────────────

# Kill only our own stale dev processes (by command name), not production Sman
kill_our_stale() {
  local port=$1
  shift
  local pids=$(lsof -i :"$port" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
  if [ -z "$pids" ]; then
    return
  fi
  for pid in $pids; do
    local cmd=$(ps -p "$pid" -o comm= 2>/dev/null || true)
    for match in "$@"; do
      if echo "$cmd" | grep -qi "$match"; then
        echo -e "${YELLOW}Killing stale dev process on port $port (pid=$pid, cmd=$cmd)${NC}"
        kill -15 "$pid" 2>/dev/null || true
        for i in $(seq 1 6); do
          if ! kill -0 "$pid" 2>/dev/null; then break; fi
          sleep 0.5
        done
        kill -9 "$pid" 2>/dev/null || true
        return
      fi
    done
  done
}

echo -e "${CYAN}Checking ports...${NC}"
kill_our_stale 5880 tsx node
kill_our_stale 5881 vite node
echo -e "${GREEN}Ports checked.${NC}"

# ── 3. 构建 ──────────────────────────────────────────

echo -e "${CYAN}[1/5] Building backend (TypeScript)...${NC}"
pnpm tsc -p server/tsconfig.json 2>&1 | tail -5

if [ $? -ne 0 ]; then
  echo -e "${RED}Backend build failed!${NC}"
  exit 1
fi
# Ensure dist/server/package.json exists (ESM marker)
if [ ! -f "dist/server/package.json" ]; then
  echo '{"type":"module"}' > dist/server/package.json
fi
echo -e "${GREEN}Backend build OK.${NC}"

echo -e "${CYAN}[2/5] Building Electron main/preload...${NC}"
pnpm build:electron 2>&1 | tail -5

if [ ! -f "electron/dist/main/main.js" ]; then
  echo -e "${RED}Electron build failed!${NC}"
  exit 1
fi
echo -e "${GREEN}Electron build OK.${NC}"

# ── 4. 启动服务 ──────────────────────────────────────────

cleanup() {
  echo -e "\n${YELLOW}Shutting down...${NC}"
  # 杀掉所有子进程
  jobs -p | xargs kill 2>/dev/null
  wait 2>/dev/null
  echo -e "${GREEN}Done.${NC}"
}
trap cleanup EXIT INT TERM

# 启动后端
echo -e "${CYAN}[3/5] Starting backend on port 5880...${NC}"
pnpm dev:server &
BACKEND_PID=$!

# 启动前端 Vite
echo -e "${CYAN}[4/5] Starting frontend on port 5881...${NC}"
pnpm dev &
FRONTEND_PID=$!

# ── 5. 等待服务就绪 ──────────────────────────────────────

echo -e "${YELLOW}Waiting for backend...${NC}"
for i in $(seq 1 30); do
  if curl -s --noproxy localhost http://localhost:5880/api/health > /dev/null 2>&1; then
    echo -e "${GREEN}Backend ready.${NC}"
    break
  fi
  if [ $i -eq 30 ]; then
    echo -e "${RED}Backend failed to start within 15s!${NC}"
    exit 1
  fi
  sleep 0.5
done

echo -e "${YELLOW}Waiting for frontend...${NC}"
for i in $(seq 1 30); do
  if curl -s --noproxy localhost http://localhost:5881 > /dev/null 2>&1; then
    echo -e "${GREEN}Frontend ready.${NC}"
    break
  fi
  if [ $i -eq 30 ]; then
    echo -e "${RED}Frontend failed to start within 15s!${NC}"
    exit 1
  fi
  sleep 0.5
done

# ── 6. 启动 Electron ─────────────────────────────────────

echo -e "${CYAN}[5/5] Starting Electron...${NC}"
# Clear proxy for Electron — localhost Vite/HMR must not go through proxy
unset http_proxy HTTP_PROXY https_proxy HTTPS_PROXY ALL_PROXY all_proxy
# Electron runs in FOREGROUND so macOS gives it proper GUI focus.
# Ctrl+C will trigger cleanup trap which kills backend + frontend.
exec npx electron .

echo ""
