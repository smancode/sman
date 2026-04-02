/**
 * URL Experience Store — 持久化记录 URL 匹配经验。
 *
 * 用户告诉 Claude 一个 URL 后，记录 description → url 映射。
 * 下次 web_access_find_url 会读取这些经验，全量返回给 Claude 做语义匹配。
 *
 * 存储路径：~/.sman/web-access-experiences.json
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createLogger } from '../utils/logger.js';

const log = createLogger('UrlExperienceStore');

export interface ExperienceEntry {
  description: string;
  url: string;
  createdAt: string;
}

interface ExperienceStore {
  entries: ExperienceEntry[];
}

function getExperiencePath(): string {
  const home = process.env.SMANBASE_HOME || path.join(os.homedir(), '.sman');
  return path.join(home, 'web-access-experiences.json');
}

/** 读取经验文档，不存在返回空 */
export function loadExperiences(): ExperienceStore {
  const filePath = getExperiencePath();
  try {
    if (!fs.existsSync(filePath)) return { entries: [] };
    const raw = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(raw);
  } catch (e: any) {
    log.warn(`Failed to load experiences: ${e.message}`);
    return { entries: [] };
  }
}

/** 追加一条经验，按 url 去重 */
export function addExperience(entry: ExperienceEntry): void {
  const store = loadExperiences();
  // 按 url 去重：更新已有条目的 description
  const existingIdx = store.entries.findIndex(e => e.url === entry.url);
  if (existingIdx >= 0) {
    store.entries[existingIdx].description = entry.description;
    store.entries[existingIdx].createdAt = entry.createdAt;
  } else {
    store.entries.push(entry);
  }

  const filePath = getExperiencePath();
  const dir = path.dirname(filePath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  fs.writeFileSync(filePath, JSON.stringify(store, null, 2), 'utf-8');
  log.info(`Experience saved: ${entry.description} -> ${entry.url}`);
}
