/**
 * Initialize capabilities registry — scans plugins/ and generates ~/.sman/capabilities.json
 *
 * Run during server startup to ensure the capability catalog is up to date.
 */
import fs from 'node:fs';
import path from 'node:path';
const CAPABILITIES = [
    {
        id: 'office-skills',
        name: 'Office 文档处理',
        description: '创建和编辑 PowerPoint、Word、Excel、PDF 文档，支持模板、OOXML 编辑、表单填充等',
        executionMode: 'mcp-dynamic',
        triggers: ['PPT', 'PowerPoint', 'Word', 'docx', 'Excel', 'xlsx', 'PDF', '文档', '演示文稿', '表格', '报告'],
        runnerModule: './office-skills-runner.js',
        pluginPath: 'office-skills',
        version: '1.0.0',
    },
    {
        id: 'frontend-slides',
        name: 'HTML 幻灯片创建',
        description: '创建动画丰富的 HTML 演示文稿，支持风格发现、PPT 转换、PDF 导出、Vercel 部署',
        executionMode: 'instruction-inject',
        triggers: ['演示', '幻灯片', 'slides', 'HTML presentation', '创建演示', '动画演示'],
        runnerModule: './frontend-slides-runner.js',
        pluginPath: 'frontend-slides',
        version: '1.0.0',
    },
];
export function initCapabilities(homeDir, pluginsDir) {
    const registryPath = path.join(homeDir, 'capabilities.json');
    // Load existing manifest to preserve user preferences (enabled/disabled)
    let existing = null;
    if (fs.existsSync(registryPath)) {
        try {
            existing = JSON.parse(fs.readFileSync(registryPath, 'utf-8'));
        }
        catch {
            // Corrupted file, regenerate from scratch
        }
    }
    const capabilities = {};
    for (const cap of CAPABILITIES) {
        // Verify the plugin directory exists
        const pluginFullPath = path.join(pluginsDir, cap.pluginPath);
        const exists = fs.existsSync(pluginFullPath)
            || fs.existsSync(path.join(pluginsDir, cap.pluginPath, 'SKILL.md'))
            || fs.existsSync(path.join(pluginsDir, cap.pluginPath, 'CLAUDE.md'));
        if (!exists) {
            continue; // Skip capabilities whose plugins are not installed
        }
        // Preserve user's enabled/disabled preference if they previously set it
        const existingEntry = existing?.capabilities[cap.id];
        capabilities[cap.id] = {
            ...cap,
            enabled: existingEntry?.enabled ?? true,
        };
    }
    const manifest = {
        version: '1.0',
        capabilities,
    };
    // Ensure homeDir exists
    if (!fs.existsSync(homeDir)) {
        fs.mkdirSync(homeDir, { recursive: true });
    }
    fs.writeFileSync(registryPath, JSON.stringify(manifest, null, 2), 'utf-8');
    return manifest;
}
