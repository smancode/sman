/**
 * Office-Skills Runner — exposes PPT/Word/Excel/PDF capabilities as dynamic MCP tools.
 *
 * Each tool reads the corresponding SKILL.md on demand and returns the instructions
 * for Claude to follow. Claude then uses Bash/Write/Edit tools to execute the workflow.
 *
 * This approach preserves the rich multi-step workflow guidance from the original plugin
 * while eliminating the upfront context cost of loading all 4 SKILL.md files (~52KB).
 */

import { z } from 'zod';
import { tool } from '@anthropic-ai/claude-agent-sdk';
import fs from 'node:fs';
import path from 'node:path';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

/**
 * Create MCP tools for office skills.
 * Called when capability_load('office-skills') is invoked.
 */
export function createOfficeSkillsMcpTools(pluginsDir: string): any[] {
  const officeBase = path.join(pluginsDir, 'office-skills');

  // Read SKILL.md files on-demand (not at module load)
  function readSkill(format: string): string {
    const skillPath = path.join(officeBase, 'public', format, 'SKILL.md');
    if (!fs.existsSync(skillPath)) {
      throw new Error(`SKILL.md not found for ${format}: ${skillPath}`);
    }
    return fs.readFileSync(skillPath, 'utf-8');
  }

  function readSkillsSystem(): string {
    const sysPath = path.join(officeBase, 'skills-system.md');
    if (!fs.existsSync(sysPath)) {
      return '';
    }
    return fs.readFileSync(sysPath, 'utf-8');
  }

  const pptxTool = tool(
    'office_pptx',
    'PowerPoint creation, editing, and analysis. '
    + 'Provides complete workflow instructions for creating presentations from scratch, '
    + 'from templates, or editing existing .pptx files. '
    + 'Supports HTML-to-PPTX conversion, template-based creation, and OOXML editing.',
    {
      task: z.string().describe('What you want to do: "create", "edit", "analyze", or "template"'),
    },
    async (args: any) => {
      try {
        const skillMd = readSkill('pptx');
        const systemMd = readSkillsSystem();
        return textResult(
          `## Skills System\n${systemMd}\n\n## PPTX Workflow Instructions\n${skillMd}`,
        );
      } catch (e: any) {
        return errorResult(`Failed to load PPTX instructions: ${e.message}`);
      }
    },
  );

  const docxTool = tool(
    'office_docx',
    'Word document creation, editing, and analysis. '
    + 'Provides workflow instructions for creating new .docx files, editing existing ones, '
    + 'tracked changes (redlining), and OOXML manipulation.',
    {
      task: z.string().describe('What you want to do: "create", "edit", "analyze", or "redline"'),
    },
    async (args: any) => {
      try {
        const skillMd = readSkill('docx');
        const systemMd = readSkillsSystem();
        return textResult(
          `## Skills System\n${systemMd}\n\n## DOCX Workflow Instructions\n${skillMd}`,
        );
      } catch (e: any) {
        return errorResult(`Failed to load DOCX instructions: ${e.message}`);
      }
    },
  );

  const xlsxTool = tool(
    'office_xlsx',
    'Excel spreadsheet creation and editing. '
    + 'Provides workflow instructions for creating financial models, data analysis, '
    + 'and professional formatting with formula standards.',
    {
      task: z.string().describe('What you want to do: "create", "edit", or "analyze"'),
    },
    async (args: any) => {
      try {
        const skillMd = readSkill('xlsx');
        const systemMd = readSkillsSystem();
        return textResult(
          `## Skills System\n${systemMd}\n\n## XLSX Workflow Instructions\n${skillMd}`,
        );
      } catch (e: any) {
        return errorResult(`Failed to load XLSX instructions: ${e.message}`);
      }
    },
  );

  const pdfTool = tool(
    'office_pdf',
    'PDF manipulation and processing. '
    + 'Provides workflow instructions for merging, splitting, OCR, form filling, '
    + 'watermarking, and data extraction from PDF files.',
    {
      task: z.string().describe('What you want to do: "merge", "split", "ocr", "forms", or "extract"'),
    },
    async (args: any) => {
      try {
        const skillMd = readSkill('pdf');
        const systemMd = readSkillsSystem();
        return textResult(
          `## Skills System\n${systemMd}\n\n## PDF Workflow Instructions\n${skillMd}`,
        );
      } catch (e: any) {
        return errorResult(`Failed to load PDF instructions: ${e.message}`);
      }
    },
  );

  return [pptxTool, docxTool, xlsxTool, pdfTool];
}
