#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

echo "📦 准备 OpenClaw 运行资源..."
pnpm run prepare:openclaw

echo "🏗️ 开始打包 SMAN..."
pnpm run tauri:build

echo "✅ 打包完成"
echo "app: src-tauri/target/release/bundle/macos/sman.app"
echo "dmg: src-tauri/target/release/bundle/dmg/"
