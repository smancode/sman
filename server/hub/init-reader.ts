import fs from 'node:fs';
import path from 'node:path';
import { createLogger } from '../utils/logger.js';

const log = createLogger('InitReader');

export interface InitMdInfo {
  summary: string;
  description: string;
  techStack: string[];
  projectType: string;
  skills: string[];
}

export function readInitMd(workspace: string): InitMdInfo | null {
  const initPath = path.join(workspace, '.sman', 'INIT.md');
  try {
    const content = fs.readFileSync(initPath, 'utf-8');

    const summary = content.match(/\*\*Summary:\*\* (.+)/)?.[1] || '';
    const description = content.match(/\*\*Description:\*\* (.+)/)?.[1] || '';
    const techStackRaw = content.match(/\*\*Tech Stack:\*\* (.+)/)?.[1] || '';
    const techStack = techStackRaw.split(/,\s*/).filter(Boolean);
    const projectType = content.match(/\*\*Type:\*\* (.+)/)?.[1] || '';
    const skills = [...content.matchAll(/^## Injected Skills\n([\s\S]*?)(?=\n##|$)/gm)]
      .flatMap(m => [...m[1].matchAll(/^- (.+)$/gm)].map(s => s[1]));

    return { summary, description, techStack, projectType, skills };
  } catch {
    return null;
  }
}
