# SMAN 架构设计

## 核心定位

**SMAN 是"上下文管理者"，OpenClaw 是"执行者"**

---

## 1. 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    SMAN.app / SMAN.exe                  │
├─────────────────────────────────────────────────────────┤
│  Tauri 桌面壳（~10M）                                    │
│  • 窗口管理                                              │
│  • 文件系统                                              │
│  • 系统托盘                                              │
│  • 调用 Sidecar                                          │
├─────────────────────────────────────────────────────────┤
│  Sidecar: openclaw-server（~50-100M）                   │
│  • Bun 运行时（编译进去）                                │
│  • OpenClaw 代码（编译进去）                             │
│  • 所有依赖（编译进去）                                  │
├─────────────────────────────────────────────────────────┤
│  SvelteKit 前端（~5M）                                   │
│  • Chat UI                                               │
│  • 项目管理                                              │
│  • 设置页面                                              │
├─────────────────────────────────────────────────────────┤
│  SMAN Core（薄封装层）                                   │
│  • 上下文加载（.sman/ + .smanlocal/）                   │
│  • Skill 渐进式选择                                      │
│  • 学习提示词生成                                        │
│  • System Prompt 拼接                                    │
└─────────────────────────────────────────────────────────┘
```

**打包大小**：~100-150M（使用 Bun compile + Tauri Sidecar）

---

## 2. 上下文架构

### 2.1 目录结构

```
~/.smanlocal/                    # 个人配置（不提交 git）
├── preferences.md               # 使用偏好
├── habits.md                    # 操作习惯
├── skills/                      # 个人通用技能
└── openclaw.yaml               # OpenClaw 配置

项目/.sman/                      # 团队共享（提交 git）
├── SOUL.md                      # 项目使命
├── AGENTS.md                    # 角色定义
├── skills/                      # 领域技能
│   ├── api-design.md
│   └── testing.md
├── memory/                      # 项目知识
│   └── lessons-learned.md
└── openclaw.yaml               # 项目级 OpenClaw 配置

项目/.claude/                    # ClaudeCode 兼容（可选）
└── skills/                      # 复用 ClaudeCode 的 skill
```

### 2.2 优先级

```
个人习惯 > 项目配置 > OpenClaw 默认
~/.smanlocal/ > 项目/.sman/ > OpenClaw 内置
```

### 2.3 上下文拼接

```typescript
function buildSystemPrompt(project) {
  const parts = [];

  // 1. OpenClaw 基础（可被覆盖）
  parts.push(openclawDefaultPrompts.base);

  // 2. 项目使命
  if (fs.exists(project.path + '/.sman/SOUL.md')) {
    parts.push('## 项目使命\n' + fs.read(project.path + '/.sman/SOUL.md'));
  }

  // 3. 项目角色
  if (fs.exists(project.path + '/.sman/AGENTS.md')) {
    parts.push('## 角色定义\n' + fs.read(project.path + '/.sman/AGENTS.md'));
  }

  // 4. 选中技能（渐进式披露）
  const selectedSkills = selectSkills(userInput, allSkills, userHabits);
  parts.push('## 可用技能\n' + formatSkills(selectedSkills));

  // 5. 项目知识
  const memory = fs.readdir(project.path + '/.sman/memory/');
  parts.push('## 项目知识\n' + memory.map(read).join('\n'));

  // 6. 个人习惯（最高优先级）
  const habits = fs.read(homeDir + '/.smanlocal/habits.md');
  parts.push('## 用户习惯\n' + habits);

  return parts.join('\n\n');
}
```

---

## 3. Skill 系统

### 3.1 Skill 加载路径

OpenClaw 原生支持多 skill 目录，SMAN 配置：

```yaml
# .sman/openclaw.yaml
skills:
  load:
    extraDirs:
      - ~/.smanlocal/skills      # 个人技能
      - ./skills                  # OpenClaw 默认
      - ./.sman/skills            # 项目技能
      - ./.claude/skills          # ClaudeCode 兼容
```

### 3.2 渐进式披露

**问题**：skill 太多会爆 token

**策略**：根据用户输入智能选择

```
┌─────────────────────────────────────────────────────────┐
│  用户输入："帮我写个 API"                                 │
├─────────────────────────────────────────────────────────┤
│  SMAN 分析：                                             │
│  1. 关键词匹配：api, rest, http                          │
│  2. 结合用户习惯加权                                      │
│  3. 按优先级排序                                          │
│  4. 截断到 token 限制                                     │
│                                                          │
│  选择结果：                                               │
│  ✓ api-design.md     （匹配度高）                        │
│  ✓ testing.md        （用户习惯：写 API 后会测试）        │
│  ✗ database.md       （本次不相关，跳过）                 │
└─────────────────────────────────────────────────────────┘
```

---

## 4. 自我学习机制

### 4.1 学习流程

```
┌─────────────────────────────────────────────────────────┐
│  对话结束                                                 │
├─────────────────────────────────────────────────────────┤
│  SMAN 构造学习提示词：                                    │
│                                                          │
│  "你刚才和用户完成了一次对话，请分析：                      │
│                                                          │
│  1. 有没有值得记录的用户习惯？                             │
│     → 写入 ~/.smanlocal/habits.md                        │
│                                                          │
│  2. 有没有学到的业务知识？                                 │
│     → 写入 .sman/memory/xxx.md                           │
│                                                          │
│  3. 有没有可复用的技能？                                   │
│     → 写入 .sman/skills/xxx.md                           │
│                                                          │
│  如果没有值得记录的，输出：NO_UPDATE"                     │
├─────────────────────────────────────────────────────────┤
│  OpenClaw 执行 → 返回更新建议                            │
├─────────────────────────────────────────────────────────┤
│  SMAN 执行文件写入（用 SMAN 自己的文件工具）              │
└─────────────────────────────────────────────────────────┘
```

### 4.2 学习分类

| 内容类型 | 存储位置 | 是否提交 git |
|---------|---------|-------------|
| 用户偏好（中文回复、编辑器偏好） | ~/.smanlocal/habits.md | 否 |
| 项目知识（API 规范、业务逻辑） | .sman/memory/ | 是 |
| 项目技能（特定领域知识） | .sman/skills/ | 是 |

---

## 5. 职责划分

| 职责 | SMAN | OpenClaw |
|---|---|---|
| 桌面壳 | ✅ Tauri | ❌ |
| UI 界面 | ✅ SvelteKit | ❌ |
| 上下文加载 | ✅ 读 .sman/ + .smanlocal/ | ❌ |
| Skill 选择 | ✅ 渐进式披露 | ❌ |
| System Prompt 拼接 | ✅ | ❌ |
| Skill 加载执行 | ❌ | ✅ |
| 对话执行（React Loop） | ❌ | ✅ |
| LLM 调用 | ❌ | ✅ |
| 学习分析 | ✅ 构造提示词 | ✅ 执行分析 |
| 文件写入 | ✅ SMAN 写工具 | ❌ |

---

## 6. 打包方案

### 6.1 技术栈

- **Tauri 2.x**：桌面壳
- **Bun compile**：把 OpenClaw 编译成单二进制
- **Tauri Sidecar**：把 OpenClaw 二进制打包进 app

### 6.2 打包命令

```bash
# 1. 编译 OpenClaw 为单二进制
bun build ./src/server.ts --compile --outfile binaries/openclaw-server

# 2. Tauri 打包（自动包含 sidecar）
npm run tauri:build
```

### 6.3 产物大小估算

| 组件 | 大小 |
|---|---|
| Tauri 桌面壳 | ~10M |
| Bun 运行时 | ~30M |
| OpenClaw + 依赖 | ~50M |
| SvelteKit 前端 | ~5M |
| **总计** | **~100M** |

---

## 7. 差异化价值

| 能力 | ClaudeCode | SMAN |
|---|---|---|
| 零门槛启动 | ❌ 需要命令行 | ✅ 双击启动 |
| 自动上下文 | ❌ 每次描述 | ✅ 自动加载 .sman/ |
| 团队知识共享 | ❌ 个人配置 | ✅ .sman/ 可 git 提交 |
| 自我学习 | ❌ | ✅ 对话后自动学习 |
| 可视化 | ❌ CLI | ✅ 桌面 UI |
| 完全可控 | ❌ 黑盒 | ✅ 所有文件可读可改 |

---

## 8. 下一步

1. **精简 Tauri**：只保留窗口/文件/托盘命令
2. **集成 OpenClaw**：作为 Sidecar 运行
3. **实现 SMAN Core**：上下文管理 + 学习机制
4. **打包测试**：验证 Bun compile + Tauri Sidecar 方案
