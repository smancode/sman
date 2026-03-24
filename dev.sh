#!/usr/bin/env bash
set -e

# SmanBase 一键开发启动脚本
# 同时启动前端(Vite)和后端(Node.js)

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║       SmanBase Dev Environment       ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"

# 检查依赖
if ! command -v pnpm &> /dev/null; then
  echo -e "${YELLOW}pnpm not found, installing...${NC}"
  npm install -g pnpm
fi

if [ ! -d "node_modules" ]; then
  echo -e "${YELLOW}Installing dependencies...${NC}"
  pnpm install
fi

# 确保 home 目录存在
HOME_DIR="${SMANBASE_HOME:-$HOME/.smanbase}"
mkdir -p "$HOME_DIR"

# 清理函数
cleanup() {
  echo -e "\n${YELLOW}Shutting down...${NC}"
  kill $(jobs -p) 2>/dev/null
  wait 2>/dev/null
  echo -e "${GREEN}Done.${NC}"
}
trap cleanup EXIT INT TERM

# 启动后端
echo -e "${GREEN}[1/2] Starting backend on port 5880...${NC}"
pnpm dev:server &
SERVER_PID=$!

# 等待后端启动
echo -e "${YELLOW}Waiting for backend...${NC}"
for i in $(seq 1 30); do
  if curl -s http://localhost:5880/api/health > /dev/null 2>&1; then
    echo -e "${GREEN}Backend is ready!${NC}"
    break
  fi
  if [ $i -eq 30 ]; then
    echo -e "${YELLOW}Backend not responding after 15s, but continuing...${NC}"
  fi
  sleep 0.5
done

# 启动前端
echo -e "${GREEN}[2/2] Starting frontend on port 5881...${NC}"
pnpm dev &
FRONTEND_PID=$!

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  Frontend: http://localhost:5881${NC}"
echo -e "${GREEN}  Backend:  http://localhost:5880${NC}"
echo -e "${GREEN}  Home:     $HOME_DIR${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "Press ${YELLOW}Ctrl+C${NC} to stop"

# 等待
wait
