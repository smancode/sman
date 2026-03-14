#!/usr/bin/env zx

/**
 * bundle-lsp.mjs
 *
 * Downloads and bundles LSP servers for self-contained deployment.
 *
 * Supported LSPs:
 * - jdtls (Java) - Eclipse JDT Language Server
 * - pyright (Python) - Microsoft Python Language Server
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
// jdtls (Java)
// ============================================
async function downloadJdtls() {
  const name = 'jdtls';
  const version = '1.40.0';
  const url = `https://download.eclipse.org/jdtls/milestones/${version}/jdt-language-server-${version}-202411271555.tar.gz`;

  const outDir = path.join(OUTPUT, name);
  mkdirSync(outDir, { recursive: true });

  echo`   Downloading ${name} ${version}...`;
  try {
    const tmpFile = '/tmp/jdtls.tar.gz';
    await $`curl -sL ${url} -o ${tmpFile}`;
    await $`tar -xzf ${tmpFile} -C ${outDir} --strip-components=1`;
    downloaded.push({ name, version, path: outDir });
    echo`   ✓ ${name} ${version}`;
  } catch (e) {
    echo`   ⚠ Failed to download ${name}: ${e.message}`;
  }
}

// ============================================
// pyright (Python)
// ============================================
async function downloadPyright() {
  const name = 'pyright';
  const outDir = path.join(OUTPUT, name);

  echo`   Installing ${name} via npm...`;
  try {
    mkdirSync(outDir, { recursive: true });
    await $`npm install --prefix ${outDir} pyright`;
    downloaded.push({ name, version: 'npm', path: path.join(outDir, 'node_modules/.bin/pyright') });
    echo`   ✓ ${name}`;
  } catch (e) {
    echo`   ⚠ Failed to install ${name}: ${e.message}`;
  }
}

// ============================================
// Main
// ============================================
async function main() {
  await downloadJdtls();
  await downloadPyright();

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
