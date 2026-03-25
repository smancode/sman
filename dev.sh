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
HOME_DIR="${SMANBASE_HOME:-$HOME/.smanbase}"
mkdir -p "$HOME_DIR"

# ── 2. 端口冲突检测 ──────────────────────────────────────

check_port() {
  local port=$1
  if lsof -i :"$port" -sTCP:LISTEN > /dev/null 2>&1; then
    echo -e "${RED}Port $port is already in use!${NC}"
    echo -e "${RED}  Please stop the process or change the port.${NC}"
    echo -e "${RED}  Current process: $(lsof -i :"$port" -sTCP:LISTEN -t | head -1 | xargs ps -p | tail -1 | awk '{print $4}')${NC}"
    return 1
  fi
  return 0
}

echo -e "${CYAN}Checking ports...${NC}"
check_port 5880 || exit 1
check_port 5881 || exit 1
echo -e "${GREEN}Ports 5880 and 5881 are available.${NC}"

# ── 3. 构建 Electron main/preload ─────────────────────────

echo -e "${CYAN}[1/4] Building Electron main/preload...${NC}"
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

# 启动后端 (5880)
echo -e "${CYAN}[2/4] Starting backend on port 5880...${NC}"
pnpm dev:server &
BACKEND_PID=$!

# 启动前端 Vite (5881)
echo -e "${CYAN}[3/4] Starting frontend on port 5881...${NC}"
pnpm dev &
FRONTEND_PID=$!

# ── 5. 等待服务就绪 ──────────────────────────────────────

echo -e "${YELLOW}Waiting for backend...${NC}"
for i in $(seq 1 30); do
  if curl -s http://localhost:5880/api/health > /dev/null 2>&1; then
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
  if curl -s http://localhost:5881 > /dev/null 2>&1; then
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

echo -e "${CYAN}[4/4] Starting Electron...${NC}"
npx electron . &
ELECTRON_PID=$!

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  Sman Electron launched${NC}"
echo -e "${GREEN}  Frontend: http://localhost:5881${NC}"
echo -e "${GREEN}  Backend:  http://localhost:5880${NC}"
echo -e "${GREEN}  Home:     $HOME_DIR${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "Press ${YELLOW}Ctrl+C${NC} to stop all"

wait
