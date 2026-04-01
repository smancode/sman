#!/usr/bin/env bash
# Setup script for office-skills plugin dependencies
# Run this once after installing Sman to enable Word/Excel/PPT/PDF capabilities
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OFFICE_DIR="$SCRIPT_DIR/../plugins/office-skills"

echo "=== Office Skills Setup ==="

# --- System dependencies ---
check_tool() {
  if command -v "$1" &>/dev/null; then
    echo "  [OK] $1"
    return 0
  else
    echo "  [MISSING] $1"
    return 1
  fi
}

echo ""
echo "Checking system tools..."

MISSING=()
check_tool soffice || MISSING+=("libreoffice")
check_tool pdftoppm || MISSING+=("poppler")
check_tool pandoc || MISSING+=("pandoc")
check_tool python3 || MISSING+=("python3")
check_tool node || MISSING+=("node")

if [ ${#MISSING[@]} -gt 0 ]; then
  echo ""
  echo "Missing system dependencies: ${MISSING[*]}"
  echo ""
  echo "Install with:"
  if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "  brew install poppler pandoc"
    echo "  brew install --cask libreoffice"
  elif [[ "$OSTYPE" == "linux"* ]]; then
    echo "  sudo apt-get install -y libreoffice poppler-utils pandoc python3 python3-venv"
  elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
    echo "  choco install libreoffice poppler pandoc"
    echo "  # Or download from https://www.libreoffice.org/download/"
  fi
  echo ""
  read -p "Continue anyway? (y/N) " -n 1 -r
  echo
  [[ ! $REPLY =~ ^[Yy]$ ]] && exit 1
fi

# --- Python venv ---
echo ""
echo "Setting up Python virtual environment..."
cd "$OFFICE_DIR"

if [ ! -d "venv" ]; then
  python3 -m venv venv
  echo "  Created venv/"
fi

echo "  Installing Python packages..."
venv/bin/pip install -q -r requirements.txt
echo "  [OK] Python packages installed"

# --- Node dependencies ---
echo ""
echo "Setting up Node.js dependencies..."

if [ ! -d "node_modules" ]; then
  echo "  Installing npm packages (including Playwright Chromium)..."
  npm install
else
  echo "  [OK] node_modules already exists"
fi

echo ""
echo "=== Setup Complete ==="
echo "Office skills ready: PPTX, DOCX, XLSX, PDF"
