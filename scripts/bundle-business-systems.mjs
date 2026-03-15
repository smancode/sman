#!/usr/bin/env zx

/**
 * bundle-business-systems.mjs
 *
 * Copies configured business systems to bundled/business-systems/
 */

import 'zx/globals';
import {
  existsSync,
  mkdirSync,
  rmSync,
  readFileSync,
  writeFileSync,
  cpSync,
  statSync,
  readdirSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import yaml from 'yaml';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'bundled', 'business-systems');
const CONFIG_FILE = path.join(ROOT, 'resources', 'business-systems.yaml');

echo`📦 Bundling business systems...`;

// Read configuration
if (!existsSync(CONFIG_FILE)) {
  echo`⚠️  No business-systems.yaml found, skipping.`;
  process.exit(0);
}

const configContent = readFileSync(CONFIG_FILE, 'utf8');
const config = yaml.parse(configContent);

if (!config.systems || config.systems.length === 0) {
  echo`⚠️  No business systems configured, skipping.`;
  process.exit(0);
}

// Clean output directory
if (existsSync(OUTPUT)) {
  rmSync(OUTPUT, { recursive: true });
}
mkdirSync(OUTPUT, { recursive: true });

// Helper: get directory size
function getDirSize(dirPath) {
  let size = 0;
  const files = readdirSync(dirPath);
  for (const file of files) {
    const filePath = path.join(dirPath, file);
    const stats = statSync(filePath);
    if (stats.isDirectory()) {
      size += getDirSize(filePath);
    } else {
      size += stats.size;
    }
  }
  return size;
}

// Helper: format size
function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// Copy each business system
let copiedCount = 0;
for (const system of config.systems) {
  const sourcePath = path.resolve(ROOT, system.path);
  const targetPath = path.join(OUTPUT, system.id);

  echo`   Copying ${system.id} from ${system.path}...`;

  if (!existsSync(sourcePath)) {
    echo`⚠️  Business system source not found: id=${system.id}, path=${system.path}`;
    continue;
  }

  cpSync(sourcePath, targetPath, { recursive: true });
  copiedCount++;
  echo`   ✓ ${system.name} (${system.id})`;
}

// Update config paths (打包后使用 bundled 路径)
const bundledConfig = {
  version: config.version,
  systems: config.systems.map((s) => ({
    id: s.id,
    name: s.name,
    description: s.description,
    techStack: s.techStack,
    path: `bundled/business-systems/${s.id}/`,
  })),
};

// Write bundled config to OpenClaw directory
const openclawConfigDir = path.join(ROOT, 'bundled', 'openclaw');
mkdirSync(openclawConfigDir, { recursive: true });
const openclawConfigFile = path.join(openclawConfigDir, 'business-systems.yaml');
writeFileSync(openclawConfigFile, yaml.stringify(bundledConfig));

echo``;
echo`✅ Business systems bundled: ${OUTPUT}`;
echo`   Systems: ${copiedCount}`;
if (copiedCount > 0) {
  echo`   Total size: ${formatSize(getDirSize(OUTPUT))}`;
}
for (const system of config.systems) {
  echo`     - ${system.name} (${system.id})`;
}
