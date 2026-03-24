#!/usr/bin/env bash
set -e

# SmanBase Electron 开发模式启动脚本
# 启动后端 + 前端 + Electron 桌面窗口

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     SmanBase Electron Dev Mode       ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"

# 检查依赖
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

# 清理
cleanup() {
  echo -e "\n${YELLOW}Shutting down...${NC}"
  # 杀掉所有子进程
  jobs -p | xargs kill 2>/dev/null
  wait 2>/dev/null
  echo -e "${GREEN}Done.${NC}"
}
trap cleanup EXIT INT TERM

# 启动后端 (5880)
echo -e "${GREEN}[1/3] Starting backend on port 5880...${NC}"
pnpm dev:server &
BACKEND_PID=$!

# 启动前端 Vite (5881)
echo -e "${GREEN}[2/3] Starting frontend on port 5881...${NC}"
pnpm dev &
FRONTEND_PID=$!

# 等后端就绪
echo -e "${YELLOW}Waiting for backend...${NC}"
for i in $(seq 1 30); do
  if curl -s http://localhost:5880/api/health > /dev/null 2>&1; then
    echo -e "${GREEN}Backend ready.${NC}"
    break
  fi
  if [ $i -eq 30 ]; then
    echo -e "${RED}Backend failed to start!${NC}"
    exit 1
  fi
  sleep 0.5
done

# 等前端就绪
echo -e "${YELLOW}Waiting for frontend...${NC}"
for i in $(seq 1 30); do
  if curl -s http://localhost:5881 > /dev/null 2>&1; then
    echo -e "${GREEN}Frontend ready.${NC}"
    break
  fi
  if [ $i -eq 30 ]; then
    echo -e "${RED}Frontend failed to start!${NC}"
    exit 1
  fi
  sleep 0.5
done

# 启动 Electron
echo -e "${GREEN}[3/3] Starting Electron...${NC}"
npx electron . &
ELECTRON_PID=$!

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  Electron desktop app launched${NC}"
echo -e "${GREEN}  Frontend: http://localhost:5881${NC}"
echo -e "${GREEN}  Backend:  http://localhost:5880${NC}"
echo -e "${GREEN}  Home:     $HOME_DIR${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "Press ${YELLOW}Ctrl+C${NC} to stop all"

wait
