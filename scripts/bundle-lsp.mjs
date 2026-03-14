#!/usr/bin/env zx

/**
 * bundle-lsp.mjs
 *
 * Downloads and bundles LSP servers for self-contained deployment.
 *
 * Supported LSPs:
 * - rust-analyzer (Rust)
 * - gopls (Go)
 * - typescript-language-server (TypeScript/JavaScript)
 * - jdtls (Java) - requires JDK 21+
 *
 * Platforms: darwin-arm64, darwin-x64, linux-x64
 */

import 'zx/globals';
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'bundled', 'lsp');

// Detect current platform
const platform = process.platform;
const arch = process.arch;
const TARGET = `${platform}-${arch}`;

echo`📦 Bundling LSP servers for ${TARGET}...`;

// Clean output
if (existsSync(OUTPUT)) {
  rmSync(OUTPUT, { recursive: true });
}
mkdirSync(OUTPUT, { recursive: true });

const downloaded = [];

// ============================================
// rust-analyzer
// ============================================
async function downloadRustAnalyzer() {
  const name = 'rust-analyzer';
  const version = '2026-03-09';
  const baseUrl = 'https://github.com/rust-lang/rust-analyzer/releases/download';

  let binName = name;
  if (platform === 'win32') binName = `${name}.exe`;

  let archSuffix = arch;
  if (arch === 'arm64') archSuffix = 'aarch64';

  const platformName = platform === 'darwin' ? 'apple-darwin' : 'unknown-linux-gnu';
  const url = `${baseUrl}/${version}/${name}-${archSuffix}-${platformName}`;

  const outDir = path.join(OUTPUT, name);
  mkdirSync(outDir, { recursive: true });
  const binPath = path.join(outDir, binName);

  echo`   Downloading ${name}...`;
  try {
    await $`curl -sL ${url} -o ${binPath}`;
    await $`chmod +x ${binPath}`;
    downloaded.push({ name, version, path: binPath });
    echo`   ✓ ${name} ${version}`;
  } catch (e) {
    echo`   ⚠ Failed to download ${name}: ${e.message}`;
  }
}

// ============================================
// typescript-language-server
// ============================================
async function downloadTypescriptLsp() {
  const name = 'typescript-language-server';
  // Use npm to install
  const outDir = path.join(OUTPUT, name);

  echo`   Installing ${name} via npm...`;
  try {
    mkdirSync(outDir, { recursive: true });
    await $`npm install --prefix ${outDir} ${name} typescript`;
    downloaded.push({ name, version: 'npm', path: path.join(outDir, 'node_modules/.bin/typescript-language-server') });
    echo`   ✓ ${name}`;
  } catch (e) {
    echo`   ⚠ Failed to install ${name}: ${e.message}`;
  }
}

// ============================================
// gopls
// ============================================
async function downloadGopls() {
  const name = 'gopls';
  const outDir = path.join(OUTPUT, name);

  echo`   Installing ${name}...`;
  try {
    mkdirSync(outDir, { recursive: true });
    // Check if go is available
    await $`which go`;
    await $`GOBIN=${outDir} go install golang.org/x/tools/gopls@latest`;
    downloaded.push({ name, version: 'latest', path: path.join(outDir, name) });
    echo`   ✓ ${name}`;
  } catch (e) {
    echo`   ⚠ Failed to install ${name}: ${e.message} (requires Go)`;
  }
}

// ============================================
// Main
// ============================================
async function main() {
  await downloadRustAnalyzer();
  await downloadTypescriptLsp();
  await downloadGopls();

  // Write manifest
  const manifest = {
    generatedAt: new Date().toISOString(),
    platform: TARGET,
    servers: downloaded,
  };
  writeFileSync(path.join(OUTPUT, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');

  echo``;
  echo`✅ LSP servers bundled: ${OUTPUT}`;
  echo`   Downloaded: ${downloaded.length}`;
  for (const item of downloaded) {
    echo`   - ${item.name} (${item.version})`;
  }
}

main().catch(console.error);
