/**
 * Initialize capabilities registry — scans plugins/ and generates ~/.sman/capabilities.json
 *
 * Run during server startup to ensure the capability catalog is up to date.
 */

import fs from 'node:fs';
import path from 'node:path';
import type { CapabilityManifest, CapabilityEntry } from './types.js';

const CAPABILITIES: Omit<CapabilityEntry, 'enabled'>[] = [
  {
    id: 'office-skills',
    name: 'Office 文档处理',
    description: '创建和编辑 PowerPoint、Word、Excel、PDF 文档，支持模板、OOXML 编辑、表单填充等',
    executionMode: 'mcp-dynamic',
    triggers: ['PPT', 'PowerPoint', 'Word', 'docx', 'Excel', 'xlsx', 'PDF', '文档', '演示文稿', '表格', '报告'] as string[],
    runnerModule: './office-skills-runner.js',
    pluginPath: 'office-skills',
    version: '1.0.0',
  },
  {
    id: 'frontend-slides',
    name: 'HTML 幻灯片创建',
    description: '创建动画丰富的 HTML 演示文稿，支持风格发现、PPT 转换、PDF 导出、Vercel 部署',
    executionMode: 'instruction-inject',
    triggers: ['演示', '幻灯片', 'slides', 'HTML presentation', '创建演示', '动画演示'] as string[],
    runnerModule: './frontend-slides-runner.js',
    pluginPath: 'frontend-slides',
    version: '1.0.0',
  },
  {
    id: 'changelog-generator',
    name: 'Git Changelog 生成',
    description: '从 git 提交历史自动生成用户友好的 changelog，将技术提交转化为清晰的发布说明',
    executionMode: 'instruction-inject',
    triggers: ['changelog', 'release notes', '发布说明', '更新日志', '版本记录', 'commit 历史'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'changelog-generator',
    version: '1.0.0',
  },
  {
    id: 'file-organizer',
    name: '文件智能整理',
    description: '智能整理文件和文件夹，分析上下文、查找重复、建议更优结构、自动化清理',
    executionMode: 'instruction-inject',
    triggers: ['文件整理', '整理文件', 'file organizer', 'cleanup', '去重', '归类', '文件夹结构'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'file-organizer',
    version: '1.0.0',
  },
  {
    id: 'invoice-organizer',
    name: '发票收据归类',
    description: '自动整理发票和收据，提取关键信息、统一命名、按日期/类别排序，生成 CSV 报告',
    executionMode: 'instruction-inject',
    triggers: ['发票', '收据', 'invoice', 'receipt', '报销', '税务', '账单整理'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'invoice-organizer',
    version: '1.0.0',
  },
  {
    id: 'meeting-insights-analyzer',
    name: '会议记录分析',
    description: '分析会议转录文件，识别沟通模式、行为洞察和改进建议',
    executionMode: 'instruction-inject',
    triggers: ['会议', 'meeting', '转录', 'transcript', '沟通分析', '会议总结'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'meeting-insights-analyzer',
    version: '1.0.0',
  },
  {
    id: 'internal-comms',
    name: '内部沟通文档',
    description: '生成各类内部沟通文档：状态报告、领导简报、项目更新、公司通讯、FAQ、事件报告',
    executionMode: 'instruction-inject',
    triggers: ['内部沟通', '状态报告', '项目更新', 'newsletter', '简报', 'FAQ', 'internal comms'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'internal-comms',
    version: '1.0.0',
  },
  {
    id: 'tailored-resume-generator',
    name: '定制简历生成',
    description: '分析职位描述，根据候选人经验生成定制简历，突出相关技能和成就',
    executionMode: 'instruction-inject',
    triggers: ['简历', 'resume', 'CV', '求职', '职位申请', '简历优化'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'tailored-resume-generator',
    version: '1.0.0',
  },
  {
    id: 'brand-guidelines',
    name: '品牌配色规范',
    description: '提供品牌颜色和字体规范，应用于需要统一视觉风格的设计和文档',
    executionMode: 'instruction-inject',
    triggers: ['品牌', 'brand', '配色', '颜色规范', '字体', '视觉风格', '设计规范'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'brand-guidelines',
    version: '1.0.0',
  },
  {
    id: 'theme-factory',
    name: '主题样式生成',
    description: '10 种预设主题样式（颜色+字体），可应用于幻灯片、文档、报告、HTML 页面等',
    executionMode: 'instruction-inject',
    triggers: ['主题', 'theme', '样式', '配色方案', 'color scheme', '设计主题'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'theme-factory',
    version: '1.0.0',
  },
  {
    id: 'twitter-algorithm-optimizer',
    name: '推文优化',
    description: '基于 Twitter 开源推荐算法分析推文，优化内容以提升触达和互动',
    executionMode: 'instruction-inject',
    triggers: ['推文', 'tweet', 'Twitter', '社交媒体', '内容优化', 'engagement'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'twitter-algorithm-optimizer',
    version: '1.0.0',
  },
  {
    id: 'skill-creator',
    name: 'Skill 创建器',
    description: '创建和打包新的 Claude Skill，包含初始化脚本、验证工具和最佳实践',
    executionMode: 'instruction-inject',
    triggers: ['创建 skill', 'skill creator', '新建技能', '打包 skill', 'skill 开发'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'skill-creator',
    version: '1.0.0',
  },
  // === gstack-skills: workflow & safety tools (browser features excluded) ===
  {
    id: 'autoplan',
    name: '自动审查流水线',
    description: '自动执行 CEO 审查、设计审查、工程审查三条流水线，基于 6 条决策原则自动判断',
    executionMode: 'instruction-inject',
    triggers: ['autoplan', 'auto review', '自动审查', '审查流水线'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/autoplan',
    version: '1.0.0',
  },
  {
    id: 'benchmark',
    name: '性能回归检测',
    description: '检测性能回归，建立基线、对比性能指标、定位性能下降的提交',
    executionMode: 'instruction-inject',
    triggers: ['benchmark', 'performance', '性能', '性能回归', '性能基准'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/benchmark',
    version: '1.0.0',
  },
  {
    id: 'canary',
    name: '金丝雀部署监控',
    description: '部署后金丝雀监控，观察线上应用的控制台错误、网络失败和视觉回归',
    executionMode: 'instruction-inject',
    triggers: ['canary', 'monitoring', '监控', '部署验证', '金丝雀'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/canary',
    version: '1.0.0',
  },
  {
    id: 'careful',
    name: '危险命令防护',
    description: '危险操作安全护栏，在执行 rm -rf、DROP TABLE、force-push 等破坏性命令前发出警告',
    executionMode: 'instruction-inject',
    triggers: ['careful', 'safety', '安全模式', '危险命令', '小心模式'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/careful',
    version: '1.0.0',
  },
  {
    id: 'codex',
    name: '多模型第二意见',
    description: 'OpenAI Codex CLI 封装，提供独立的代码审查、问题诊断和第二意见',
    executionMode: 'instruction-inject',
    triggers: ['codex', 'second opinion', '第二意见', '交叉验证'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/codex',
    version: '1.0.0',
  },
  {
    id: 'cso',
    name: '首席安全官审计',
    description: 'CSO 模式，执行 OWASP Top 10 审计、STRIDE 威胁建模和安全漏洞扫描',
    executionMode: 'instruction-inject',
    triggers: ['cso', 'security', '安全审计', 'OWASP', '威胁建模'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/cso',
    version: '1.0.0',
  },
  {
    id: 'design-consultation',
    name: '设计系统咨询',
    description: '设计咨询：理解产品、调研竞品、提出完整设计系统方案（美学、排版、配色、动效）',
    executionMode: 'instruction-inject',
    triggers: ['design consultation', '设计咨询', '设计系统', 'design system'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/design-consultation',
    version: '1.0.0',
  },
  {
    id: 'design-review',
    name: '视觉设计审查',
    description: '设计师视角 QA：发现视觉不一致、间距问题、层级问题、AI 模板痕迹，然后修复',
    executionMode: 'instruction-inject',
    triggers: ['design review', '设计审查', '视觉审查', 'UI 审计'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/design-review',
    version: '1.0.0',
  },
  {
    id: 'document-release',
    name: '文档发布更新',
    description: '发布后文档更新，读取所有项目文档、交叉引用最新变更、更新版本说明',
    executionMode: 'instruction-inject',
    triggers: ['document release', '文档更新', '发布文档', 'changelog 文档'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/document-release',
    version: '1.0.0',
  },
  {
    id: 'freeze',
    name: '文件编辑锁定',
    description: '限制文件编辑到指定目录，阻止对其他目录的修改操作',
    executionMode: 'instruction-inject',
    triggers: ['freeze', '锁定', '限制编辑', 'freeze 目录'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/freeze',
    version: '1.0.0',
  },
  {
    id: 'guard',
    name: '安全防护模式',
    description: '完整安全模式：危险命令警告 + 目录编辑范围限制的组合',
    executionMode: 'instruction-inject',
    triggers: ['guard', '安全防护', '防护模式'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/guard',
    version: '1.0.0',
  },
  {
    id: 'investigate',
    name: '系统化根因调查',
    description: '系统性根因调查调试，四阶段：根因定位 → 模式分析 → 假设验证 → 实施修复',
    executionMode: 'instruction-inject',
    triggers: ['investigate', 'debug', '调试', 'root cause', '根因分析'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/investigate',
    version: '1.0.0',
  },
  {
    id: 'land-and-deploy',
    name: '合并部署流程',
    description: '合并 PR、等待 CI 和部署完成、金丝雀验证的完整发布流程',
    executionMode: 'instruction-inject',
    triggers: ['land and deploy', 'merge', 'deploy', '合并部署', '发布上线'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/land-and-deploy',
    version: '1.0.0',
  },
  {
    id: 'office-hours',
    name: '创业门诊',
    description: 'YC Office Hours 模式：创业诊断六个核心问题 + 设计伙伴深度合作',
    executionMode: 'instruction-inject',
    triggers: ['office hours', '创业诊断', 'YC', '产品诊断'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/office-hours',
    version: '1.0.0',
  },
  {
    id: 'plan-ceo-review',
    name: 'CEO 视角规划审查',
    description: 'CEO/创始人视角审查规划方案，重新思考问题、寻找 10x 突破点',
    executionMode: 'instruction-inject',
    triggers: ['CEO review', 'CEO 审查', '规划审查', '战略审查'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/plan-ceo-review',
    version: '1.0.0',
  },
  {
    id: 'plan-design-review',
    name: '设计视角规划审查',
    description: '设计师视角的规划审查，关注用户体验和视觉一致性',
    executionMode: 'instruction-inject',
    triggers: ['plan design review', '设计规划审查', '设计评审'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/plan-design-review',
    version: '1.0.0',
  },
  {
    id: 'plan-eng-review',
    name: '工程视角规划审查',
    description: '工程经理视角的规划审查，锁定执行方案、架构和依赖分析',
    executionMode: 'instruction-inject',
    triggers: ['plan eng review', '工程审查', '技术审查', '架构审查'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/plan-eng-review',
    version: '1.0.0',
  },
  {
    id: 'qa',
    name: 'QA 测试与修复',
    description: '系统性 QA 测试 Web 应用，发现并修复 bug',
    executionMode: 'instruction-inject',
    triggers: ['qa', 'QA', 'testing', '质量测试', '测试'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/qa',
    version: '1.0.0',
  },
  {
    id: 'qa-only',
    name: 'QA 测试报告',
    description: '仅报告模式的 QA 测试，发现 bug 但不自动修复，只生成报告',
    executionMode: 'instruction-inject',
    triggers: ['qa only', 'QA 报告', '测试报告'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/qa-only',
    version: '1.0.0',
  },
  {
    id: 'retro',
    name: '工程周复盘',
    description: '每周工程复盘，分析提交历史、工作日志、PR 活动和团队协作模式',
    executionMode: 'instruction-inject',
    triggers: ['retro', 'retrospective', '回顾', '复盘', '周报'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/retro',
    version: '1.0.0',
  },
  {
    id: 'review',
    name: 'PR 代码审查',
    description: '预合并 PR 审查，分析 diff 检查 SQL 安全、LLM 信任边界、条件副作用等问题',
    executionMode: 'instruction-inject',
    triggers: ['review', 'code review', 'review PR', '代码审查', 'PR 审查'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/review',
    version: '1.0.0',
  },
  {
    id: 'setup-deploy',
    name: '部署配置',
    description: '配置部署设置，检测部署平台（Vercel/Netlify/Fly.io 等）并设置对应配置',
    executionMode: 'instruction-inject',
    triggers: ['setup deploy', 'deploy config', '部署配置', '部署设置'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/setup-deploy',
    version: '1.0.0',
  },
  {
    id: 'ship',
    name: '发布流程',
    description: '完整发布流程：合并基线、运行测试、审查 diff、更新版本、创建 PR',
    executionMode: 'instruction-inject',
    triggers: ['ship', 'deploy', '发布', '上线', 'deploy'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/ship',
    version: '1.0.0',
  },
  {
    id: 'unfreeze',
    name: '解除编辑锁定',
    description: '解除 /freeze 设置的编辑限制，允许编辑所有目录',
    executionMode: 'instruction-inject',
    triggers: ['unfreeze', '解除锁定', '解锁'] as string[],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/unfreeze',
    version: '1.0.0',
  },
];

export function initCapabilities(homeDir: string, pluginsDir: string): CapabilityManifest {
  const registryPath = path.join(homeDir, 'capabilities.json');

  // Load existing manifest to preserve user preferences (enabled/disabled)
  let existing: CapabilityManifest | null = null;
  if (fs.existsSync(registryPath)) {
    try {
      existing = JSON.parse(fs.readFileSync(registryPath, 'utf-8'));
    } catch {
      // Corrupted file, regenerate from scratch
    }
  }

  const capabilities: Record<string, CapabilityEntry> = {};

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

  const manifest: CapabilityManifest = {
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
