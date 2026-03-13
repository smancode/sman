#!/usr/bin/env zx

/**
 * bundle-openclaw.mjs
 *
 * Bundles OpenClaw with all dependencies for self-contained deployment.
 * Follows the same approach as ClawX's bundle-openclaw.mjs using BFS
 * through pnpm's virtual store to collect all transitive dependencies.
 */

import 'zx/globals';
import { realpathSync, existsSync, mkdirSync, rmSync, cpSync, statSync, readdirSync, lstatSync, writeFileSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'bundled', 'openclaw');
const NODE_MODULES = path.join(ROOT, 'node_modules');

// On Windows, pnpm virtual store paths can exceed MAX_PATH (260 chars).
function normWin(p) {
  if (process.platform !== 'win32') return p;
  if (p.startsWith('\\\\?\\')) return p;
  return '\\\\?\\' + p.replace(/\//g, '\\');
}

echo`📦 Bundling OpenClaw for SmanWeb...`;

// 1. Resolve openclaw package (follows pnpm symlink)
const openclawLink = path.join(NODE_MODULES, 'openclaw');
if (!existsSync(openclawLink)) {
  echo`❌ node_modules/openclaw not found. Run pnpm install first.`;
  process.exit(1);
}

const openclawReal = realpathSync(openclawLink);
echo`   openclaw resolved: ${openclawReal}`;

// 2. Clean and create output directory
if (existsSync(OUTPUT)) {
  rmSync(OUTPUT, { recursive: true });
}
mkdirSync(OUTPUT, { recursive: true });

// 3. Copy openclaw package itself to OUTPUT root
echo`   Copying openclaw package...`;
cpSync(openclawReal, OUTPUT, { recursive: true, dereference: true });

// 4. Recursively collect ALL transitive dependencies via pnpm virtual store BFS
//
// pnpm structure example:
//   .pnpm/openclaw@ver/node_modules/
//     openclaw/          <- real files
//     chalk/             <- symlink -> .pnpm/chalk@ver/node_modules/chalk
//     @clack/prompts/    <- symlink -> .pnpm/@clack+prompts@ver/node_modules/@clack/prompts
//
//   .pnpm/@clack+prompts@ver/node_modules/
//     @clack/prompts/    <- real files
//     @clack/core/       <- symlink (transitive dep, NOT in openclaw's siblings!)
//
// We BFS from openclaw's virtual store node_modules, following each symlink
// to discover the target's own virtual store node_modules and its deps.

const collected = new Map(); // realPath -> packageName (for deduplication)
const queue = []; // BFS queue of virtual-store node_modules dirs to visit

/**
 * Given a real path of a package, find the containing virtual-store node_modules.
 * e.g. .pnpm/chalk@5.4.1/node_modules/chalk -> .pnpm/chalk@5.4.1/node_modules
 * e.g. .pnpm/@clack+core@0.4.1/node_modules/@clack/core -> .pnpm/@clack+core@0.4.1/node_modules
 */
function getVirtualStoreNodeModules(realPkgPath) {
  let dir = realPkgPath;
  while (dir !== path.dirname(dir)) {
    if (path.basename(dir) === 'node_modules') {
      return dir;
    }
    dir = path.dirname(dir);
  }
  return null;
}

/**
 * List all package entries in a virtual-store node_modules directory.
 * Handles both regular packages (chalk) and scoped packages (@clack/prompts).
 * Returns array of { name, fullPath }.
 */
function listPackages(nodeModulesDir) {
  const result = [];
  const nDir = normWin(nodeModulesDir);
  if (!existsSync(nDir)) return result;

  for (const entry of readdirSync(nDir)) {
    if (entry === '.bin') continue;
    // Use original (non-normWin) path so callers can call
    // getVirtualStoreNodeModules() on fullPath correctly.
    const entryPath = path.join(nodeModulesDir, entry);

    if (entry.startsWith('@')) {
      try {
        const scopeEntries = readdirSync(normWin(entryPath));
        for (const sub of scopeEntries) {
          result.push({
            name: `${entry}/${sub}`,
            fullPath: path.join(entryPath, sub),
          });
        }
      } catch {
        // Not a directory, skip
      }
    } else {
      result.push({ name: entry, fullPath: entryPath });
    }
  }
  return result;
}

// Start BFS from openclaw's virtual store node_modules
const openclawVirtualNM = getVirtualStoreNodeModules(openclawReal);
if (!openclawVirtualNM) {
  echo`❌ Could not determine pnpm virtual store for openclaw`;
  process.exit(1);
}

echo`   Virtual store root: ${openclawVirtualNM}`;
queue.push({ nodeModulesDir: openclawVirtualNM, skipPkg: 'openclaw' });

const SKIP_PACKAGES = new Set([
  'typescript',
  '@playwright/test',
]);
const SKIP_SCOPES = ['@types/'];
let skippedDevCount = 0;

while (queue.length > 0) {
  const { nodeModulesDir, skipPkg } = queue.shift();
  const packages = listPackages(nodeModulesDir);

  for (const { name, fullPath } of packages) {
    // Skip the package that owns this virtual store entry (it's the package itself, not a dep)
    if (name === skipPkg) continue;

    if (SKIP_PACKAGES.has(name) || SKIP_SCOPES.some(s => name.startsWith(s))) {
      skippedDevCount++;
      continue;
    }

    let realPath;
    try {
      realPath = realpathSync(fullPath);
    } catch {
      continue; // broken symlink, skip
    }

    if (collected.has(realPath)) continue; // already visited
    collected.set(realPath, name);

    // Find this package's own virtual store node_modules to discover ITS deps
    const depVirtualNM = getVirtualStoreNodeModules(realPath);
    if (depVirtualNM && depVirtualNM !== nodeModulesDir) {
      // Determine the package's "self name" in its own virtual store
      // For scoped: @clack/core -> skip "@clack/core" when scanning
      queue.push({ nodeModulesDir: depVirtualNM, skipPkg: name });
    }
  }
}

echo`   Found ${collected.size} total packages (direct + transitive)`;
echo`   Skipped ${skippedDevCount} dev-only package references`;

// 5. Copy all collected packages into OUTPUT/node_modules/ (flat structure)
//
// IMPORTANT: BFS guarantees direct deps are encountered before transitive deps.
// When the same package name appears at different versions (e.g. chalk@5 from
// openclaw directly, chalk@4 from a transitive dep), we keep the FIRST one
// (direct dep version) and skip later duplicates. This prevents version
// conflicts like CJS chalk@4 overwriting ESM chalk@5.
const outputNodeModules = path.join(OUTPUT, 'node_modules');
mkdirSync(outputNodeModules, { recursive: true });

const copiedNames = new Set(); // Track package names already copied
let copiedCount = 0;
let skippedDupes = 0;

for (const [realPath, pkgName] of collected) {
  if (copiedNames.has(pkgName)) {
    skippedDupes++;
    continue; // Keep the first version (closer to openclaw in dep tree)
  }
  copiedNames.add(pkgName);

  const dest = path.join(outputNodeModules, pkgName);

  try {
    mkdirSync(normWin(path.dirname(dest)), { recursive: true });
    cpSync(normWin(realPath), normWin(dest), { recursive: true, dereference: true });
    copiedCount++;
  } catch (err) {
    echo`   ⚠️  Skipped ${pkgName}: ${err.message}`;
  }
}

// 6. Clean up the bundle to reduce package size
//
// This removes platform-agnostic waste: dev artifacts, docs, source maps,
// type definitions, test directories, and known large unused subdirectories.

function getDirSize(dir) {
  let total = 0;
  try {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, entry.name);
      if (entry.isDirectory()) total += getDirSize(p);
      else if (entry.isFile()) total += statSync(p).size;
    }
  } catch { /* ignore */ }
  return total;
}

function formatSize(bytes) {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)}G`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}M`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)}K`;
  return `${bytes}B`;
}

function rmSafe(target) {
  try {
    const stat = lstatSync(target);
    if (stat.isDirectory()) rmSync(target, { recursive: true, force: true });
    else rmSync(target, { force: true });
    return true;
  } catch { return false; }
}

function cleanupBundle(outputDir) {
  let removedCount = 0;
  const nm = path.join(outputDir, 'node_modules');

  // --- openclaw root junk ---
  for (const name of ['CHANGELOG.md', 'README.md']) {
    if (rmSafe(path.join(outputDir, name))) removedCount++;
  }

  // docs/ is kept — contains prompt templates and other runtime-used prompts

  // --- node_modules: remove unnecessary file types and directories ---
  if (existsSync(nm)) {
    const REMOVE_DIRS = new Set([
      'test', 'tests', '__tests__', '.github', 'docs', 'examples', 'example',
    ]);
    const REMOVE_FILE_EXTS = ['.d.ts', '.d.ts.map', '.js.map', '.mjs.map', '.ts.map', '.markdown'];
    const REMOVE_FILE_NAMES = new Set([
      '.DS_Store', 'README.md', 'CHANGELOG.md', 'LICENSE.md', 'CONTRIBUTING.md',
      'tsconfig.json', '.npmignore', '.eslintrc', '.prettierrc', '.editorconfig',
    ]);

    function walkClean(dir) {
      let entries;
      try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (REMOVE_DIRS.has(entry.name)) {
            if (rmSafe(full)) removedCount++;
          } else {
            walkClean(full);
          }
        } else if (entry.isFile()) {
          const name = entry.name;
          if (REMOVE_FILE_NAMES.has(name) || REMOVE_FILE_EXTS.some(e => name.endsWith(e))) {
            if (rmSafe(full)) removedCount++;
          }
        }
      }
    }
    walkClean(nm);
  }

  // --- known large unused subdirectories ---
  const LARGE_REMOVALS = [
    'node_modules/pdfjs-dist/legacy',
    'node_modules/pdfjs-dist/types',
    'node_modules/node-llama-cpp/llama',
    'node_modules/koffi/src',
    'node_modules/koffi/vendor',
    'node_modules/koffi/doc',
  ];
  for (const rel of LARGE_REMOVALS) {
    if (rmSafe(path.join(outputDir, rel))) removedCount++;
  }

  return removedCount;
}

echo``;
echo`🧹 Cleaning up bundle (removing dev artifacts, docs, source maps, type defs)...`;
const sizeBefore = getDirSize(OUTPUT);
const cleanedCount = cleanupBundle(OUTPUT);
const sizeAfter = getDirSize(OUTPUT);
echo`   Removed ${cleanedCount} files/directories`;
echo`   Size: ${formatSize(sizeBefore)} → ${formatSize(sizeAfter)} (saved ${formatSize(sizeBefore - sizeAfter)})`;

// 7. Patch known broken packages
//
// Some packages in the ecosystem have transpiled CJS output that sets
// `module.exports = exports.default` without ever assigning `exports.default`,
// resulting in `module.exports = undefined`.  This causes a TypeError in
// Node.js 22+ ESM interop when the translators try to call hasOwnProperty on
// the undefined exports object.
function patchBrokenModules(nodeModulesDir) {
  const rewritePatches = {
    // node-domexception@1.0.0: transpiled index.js leaves module.exports = undefined.
    // Node.js 18+ ships DOMException as a built-in global, so a simple shim works.
    'node-domexception/index.js': [
      `'use strict';`,
      `// Shim: the original transpiled file sets module.exports = exports.default`,
      `// (which is undefined), causing TypeError in Node.js 22+ ESM interop.`,
      `// Node.js 18+ has DOMException as a built-in global.`,
      `const dom = globalThis.DOMException ||`,
      `  class DOMException extends Error {`,
      `    constructor(msg, name) { super(msg); this.name = name || 'Error'; }`,
      `  };`,
      `module.exports = dom;`,
      `module.exports.DOMException = dom;`,
      `module.exports.default = dom;`,
    ].join('\n'),
  };

  // Packages whose package.json "exports" field causes issues when bundled.
  // We add a "main" field if missing, pointing to the CJS entry.
  const EXPORTS_FIX_PACKAGES = [
    'https-proxy-agent',
    'socks-proxy-agent',
    'proxy-agent',
    'agent-base',
  ];

  let count = 0;

  // Apply rewrite patches
  for (const [rel, content] of Object.entries(rewritePatches)) {
    const target = path.join(nodeModulesDir, rel);
    if (existsSync(target)) {
      writeFileSync(target, content + '\n', 'utf8');
      count++;
    }
  }

  // Fix exports field for problematic packages
  for (const pkgName of EXPORTS_FIX_PACKAGES) {
    const pkgDir = path.join(nodeModulesDir, pkgName);
    const pkgJsonPath = path.join(pkgDir, 'package.json');
    if (!existsSync(pkgJsonPath)) continue;

    try {
      const pkgJson = JSON.parse(readFileSync(pkgJsonPath, 'utf8'));

      // If package has exports but no main, add main and remove exports
      // This allows CJS require() to work properly
      if (pkgJson.exports && !pkgJson.main) {
        // Check for common entry points
        const candidates = ['index.js', 'src/index.js', 'dist/index.js'];
        for (const entry of candidates) {
          if (existsSync(path.join(pkgDir, entry))) {
            pkgJson.main = entry;
            delete pkgJson.exports;  // Remove exports to allow CJS resolution
            writeFileSync(pkgJsonPath, JSON.stringify(pkgJson, null, 2) + '\n', 'utf8');
            count++;
            break;
          }
        }
      }
    } catch {
      // Ignore parse errors
    }
  }

  if (count > 0) {
    echo`   🩹 Patched ${count} broken module(s) in node_modules`;
  }
}

patchBrokenModules(outputNodeModules);

// 8. Verify the bundle
const entryExists = existsSync(path.join(OUTPUT, 'openclaw.mjs'));
const distExists = existsSync(path.join(OUTPUT, 'dist', 'entry.js'));

echo``;
echo`✅ Bundle complete: ${OUTPUT}`;
echo`   Unique packages copied: ${copiedCount}`;
echo`   Dev-only packages skipped: ${skippedDevCount}`;
echo`   Duplicate versions skipped: ${skippedDupes}`;
echo`   Total discovered: ${collected.size}`;
echo`   openclaw.mjs: ${entryExists ? '✓' : '✗'}`;
echo`   dist/entry.js: ${distExists ? '✓' : '✗'}`;

if (!entryExists) {
  echo`❌ Bundle verification failed!`;
  process.exit(1);
}
