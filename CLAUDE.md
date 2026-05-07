# Sman - 智能业务系统平台

> 此文件给 Claude Code 提供项目上下文

## 项目定位

Sman 是一个简化的智能业务平台，用户只需选择项目目录即可开始对话，无需预先配置业务系统。支持桌面端（Electron）、企业微信 Bot、飞书 Bot、微信 Bot 四端交互。

**设计理念**：越简单越好，不要让用户看不懂。

## 核心架构

```
用户 (桌面端 / 企业微信 / 飞书 / 微信)
         ↓
    Sman 后端 (Express + WebSocket)
         ↓
    Claude Agent SDK (V2 Session)
         ↓
    项目目录 (用户选择) + MCP Servers + Plugins + Capabilities
         ↕
    星域 (多 Agent 协作网络)
```

## 核心功能（侧边栏入口）

1. **新建会话** → 选择项目目录 → 开始对话 → 按目录分组显示会话
2. **协作星图** → 多 Agent 协作网络（仪表盘 + 像素世界）
3. **定时任务** → Cron 表达式驱动的自动化任务
4. **地球路径** → 多步骤自动化工作流（逐步骤执行，前一步结果作为下一步上下文）
5. **设置** → LLM、Web 搜索、Chatbot、用户画像等配置
6. **代码查看器** → 集成在聊天界面右侧，文件树浏览、代码读取、符号搜索
7. **Git 面板** → 集成在聊天界面右侧，状态查看、Diff 对比、提交推送

## 快速开始

### 开发模式

```bash
# 一键启动 (后端 + 前端 + Electron)
./dev.sh

# 或分别启动
pnpm dev           # 前端 (5881)
pnpm dev:server    # 后端 (5880)
```

### 生产构建

```bash
pnpm build         # 构建前端 + 后端
pnpm build:electron # 编译 Electron 主进程
pnpm electron:build # 一键构建+打包 (build + build:electron + electron-builder)
```

### 运行测试

```bash
pnpm test          # 运行所有测试
pnpm test:watch    # 监视模式
```

### 环境要求

- **Node.js 22 LTS** (better-sqlite3 预编译二进制，无需本地编译)
- **pnpm** 包管理器

## 端口使用

| 端口 | 用途 |
|------|------|
| 5880 | HTTP 服务 + WebSocket (生产模式固定) |
| 5881 | Vite 开发服务器 (仅开发模式) |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | 5880 | HTTP 服务端口 |
| `SMANBASE_HOME` | `~/.sman` | 用户数据目录 |

## 技术栈

- **前端**: React 19 + TypeScript + TailwindCSS + Radix UI + Zustand + CodeMirror 6
- **后端**: Node.js + TypeScript + Express + WebSocket (ws)
- **桌面**: Electron + electron-vite
- **数据库**: SQLite (better-sqlite3)
- **AI**: Claude Agent SDK (`@anthropic-ai/claude-agent-sdk` v0.2.110 + `@anthropic-ai/claude-code` v2.1.110)
- **渲染**: Shiki + Streamdown
- **Schema 校验**: Zod

## 用户数据目录 (`~/.sman/`)

```
~/.sman/
├── config.json          # LLM + WebSearch + Chatbot + Auth 配置
├── registry.json        # Skills 注册表
├── sman.db              # SQLite 数据库
├── claude-config/       # 隔离的 Claude CLI 配置目录
├── skills/              # 全局 Skills（预制通用技能）
└── logs/                # 日志文件
```

## 项目工作区目录 (`{workspace}/.sman/`)

```
{workspace}/.sman/
├── INIT.md              # 初始化结果
├── knowledge/           # 团队知识（每人独立文件，git push 共享）
│   ├── business-{username}.md
│   ├── conventions-{username}.md
│   └── technical-{username}.md
└── paths/               # 地球路径（文件存储）
    └── {pathId}/
        ├── path.md
        ├── runs/
        ├── reports/
        └── references/
```

## 关键技术点

1. **CJS/ESM 兼容**: 服务端编译为 ESM (`module: "ES2022"`)，`dist/server/package.json` 声明 `"type": "module"`
2. **ASAR 已禁用**: `better-sqlite3` 原生模块在 ASAR 内路径解析失败
3. **ESM __dirname**: 服务端用 `path.dirname(fileURLToPath(import.meta.url))` 替代 `__dirname`
4. **Windows GPU**: VDI 环境需 `app.disableHardwareAcceleration()` 防白屏
5. **Auth 边界**: 只有 `/api/` 路径需要 Bearer auth，静态文件直接放行
6. **环境隔离**: `getCleanEnv()` 清除 `ANTHROPIC_*/OPENAI_*/CLAUDE_*` 环境变量，使用隔离的 `CLAUDE_CONFIG_DIR`
7. **消息排队**: SDK 不支持打断正在执行的 turn，后端通过 `await streamDone` 排队

## 核心规则（必须遵守）

### UI 响应性优先

所有用户交互必须立即得到 UI 反馈，不允许同步阻塞：
- **输入框打字零联动**: `handleInputChange` 里不做任何资源操作
- **发送不卡 UI**: 先清空输入框，再用 `setTimeout(0)` 推到下一帧
- **避免不必要的 re-render**: ChatInput 不要订阅 `messages` 数组

### 参数校验严格

- 用户要求什么参数就什么参数，不擅自增减
- 不添加默认值，不擅自做参数转换
- 参数不满足时直接抛异常，不返回空结果兜底

详见: `~/.claude/rules/CODING_RULES.md`

### 多语言 (i18n) 强制规范

Sman 是多语言项目（中文/英文），**所有用户可见文本禁止硬编码**。

#### 基本规则

- 使用 `import { t } from '@/locales'` 的 `t('key')` 函数获取翻译文本
- 翻译 key 定义在 `src/locales/zh-CN.json` 和 `src/locales/en-US.json`
- **禁止**在 JSX 中直接写中文字符串（如 `>确定<`、`placeholder="请输入"`）
- 注释中的中文不受限制

#### 错误示例（不要这样）

```tsx
// ❌ 直接硬编码中文
<button>确定</button>
<input placeholder="请输入用户名" />

// ❌ 在模块顶层调用 t()（会导致 t() 返回固定语言）
const MENU_ITEMS = [
  { label: t('menu.new'), key: 'new' },
  { label: t('menu.open'), key: 'open' }
];
```

#### 正确示例

```tsx
// ✅ 使用 t() 函数
import { t } from '@/locales';

<button>{t('common.confirm')}</button>
<input placeholder={t('auth.usernamePlaceholder')} />

// ✅ 常量数组用 labelKey 模式
const MENU_ITEMS = [
  { labelKey: 'menu.new', key: 'new' },
  { labelKey: 'menu.open', key: 'open' }
];

// 在组件内映射
{MENU_ITEMS.map(item => (
  <MenuItem key={item.key}>{t(item.labelKey)}</MenuItem>
))}
```

#### 特殊场景处理

| 场景 | 方案 |
|-----|------|
| 常量数组中的 label | 用 `labelKey` 存储 key，组件内调用 `t(labelKey)` |
| 动态拼接文本 | 翻译文件中用完整句子，用参数插值（如 `t('file.count', { count: 5 })`） |
| 第三方库 props | 检查是否支持 i18n，不支持则用 `aria-label` 等可访问性属性 |
| 表单校验错误 | 错误消息定义在翻译文件中，校验函数返回错误 key |
| 日志/调试信息 | 可硬编码（开发者可见，非用户界面） |

### 文件行数限制

- 单个文件不得超过 **500 行**（TypeScript/React）
- 超过时必须拆分：提取子组件、拆分工具函数、按职责分文件

## 详细文档索引

### 项目结构

- **目录结构和模块组织** → 使用 `project-structure` skill
- **API 端点目录** → 使用 `project-apis` skill
- **外部依赖** → 使用 `project-external-calls` skill

### 知识库

- **业务知识** → 使用 `knowledge-business` skill
- **开发规范** → 使用 `knowledge-conventions` skill
- **技术知识** → 使用 `knowledge-technical` skill
- **数据库 Schema** → 使用 `database-schema` skill

### WebSocket API

- **会话管理**: `session.create/list/delete/history/updateLabel`
- **聊天**: `chat.send/abort/start/delta/tool_start/done/abort/error/ask_user`
- **设置**: `settings.get/update`, `skills.list`
- **Cron/Batch**: `cron.*`, `batch.*`
- **星域**: `stardom.*`
- **地球路径**: `smartpath.*`
- **代码查看器**: `code.*`（listDir, readFile, searchSymbols, saveFile, searchFiles）
- **Git 操作**: `git.*`（status, diff, commit, push, log, checkout, fetch 等）

详细 API 文档 → 使用 `project-apis` skill

### Chatbot 命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `//cd <项目名或路径>` | - | 切换工作目录 |
| `//pwd` | - | 显示当前工作目录 |
| `//workspaces` | `//wss` | 列出桌面端已打开的项目 |
| `//status` | `//sts` | 显示连接状态 |
| `//help` | - | 显示帮助信息 |

### MCP 工具

- **Web Access**: `web_access_navigate/snapshot/screenshot/click/fill/press_key/evaluate/list_tabs/close_tab`
- **Web Search**: Brave/Tavily/Bing/Baidu 搜索 MCP

## 常见问题

**Q: Windows 打包慢？**
A: NSIS 安装包启动快（推荐）；Portable 每次启动要解压，VDI 环境较慢。详见 `docs/windows-packaging.md`

**Q: 离线部署？**
A: 设置页配置内网模型 URL + API Key + Model 即可使用，支持 `ANTHROPIC_BASE_URL`

**Q: 知识提取不生效？**
A: 每 10 分钟空闲时自动提取，或手动触发 `skill-auto-updater`

**Q: 会话初始化失败？**
A: 检查 `server/init/` 流程：workspace-scanner → skill-injector → capability-matcher → claude-init-runner
