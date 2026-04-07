/**
 * Frontend-Slides Runner — returns SKILL.md instructions on demand.
 *
 * Frontend-slides is a creative/exective capability where Claude needs to:
 * 1. Discover the user's intent (style, content, audience)
 * 2. Generate multi-phase output (style preview → full presentation)
 * 3. Use Bash/Write tools to create the final HTML file
 *
 * Instruction-inject mode is ideal because the workflow is conversational,
 * not tool-driven. Claude reads the instructions and orchestrates itself.
 */

import fs from 'node:fs';
import path from 'node:path';

/**
 * Read the full frontend-slides SKILL.md and supporting files.
 * Called when capability_load('frontend-slides') is invoked.
 */
export function createFrontendSlidesInstructions(pluginsDir: string): string | null {
  const slidesBase = path.join(pluginsDir, 'frontend-slides');
  const parts: string[] = [];

  // Main SKILL.md
  const skillPath = path.join(slidesBase, 'SKILL.md');
  if (fs.existsSync(skillPath)) {
    parts.push(fs.readFileSync(skillPath, 'utf-8'));
  }

  // Style presets
  const presetsPath = path.join(slidesBase, 'STYLE_PRESETS.md');
  if (fs.existsSync(presetsPath)) {
    parts.push('\n\n## Style Presets Reference\n');
    parts.push(fs.readFileSync(presetsPath, 'utf-8'));
  }

  // HTML template architecture
  const templatePath = path.join(slidesBase, 'html-template.md');
  if (fs.existsSync(templatePath)) {
    parts.push('\n\n## HTML Template Architecture\n');
    parts.push(fs.readFileSync(templatePath, 'utf-8'));
  }

  // Animation patterns
  const animationsPath = path.join(slidesBase, 'animation-patterns.md');
  if (fs.existsSync(animationsPath)) {
    parts.push('\n\n## Animation Patterns\n');
    parts.push(fs.readFileSync(animationsPath, 'utf-8'));
  }

  // Viewport base CSS (mandatory — inline in every presentation)
  const cssPath = path.join(slidesBase, 'viewport-base.css');
  if (fs.existsSync(cssPath)) {
    parts.push('\n\n## Mandatory Viewport Base CSS\n');
    parts.push('```css\n');
    parts.push(fs.readFileSync(cssPath, 'utf-8'));
    parts.push('\n```\n');
  }

  if (parts.length === 0) {
    throw new Error(`No frontend-slides files found at ${slidesBase}`);
  }

  return parts.join('');
}
