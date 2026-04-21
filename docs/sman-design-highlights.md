# Sman 核心设计亮点

> 基于代码分析的架构特色总结

---

## 1. Claude Session 持久化 + 进程保活

V2 Session 不是一次性 API 调用，而是**长期驻留的 Claude CLI 子进程**。

- Session ID 持久化到 SQLite，**进程重启后能恢复会话上下文**
- 用 `process.kill(pid, 0)` 探测进程存活，死进程透明重建
- **预热机制**：用户开始输入时就提前创建 session，消除冷启动延迟
- 中止时保存已流式的部分消息，不丢失已生成内容
- 两级卡死检测：3 分钟无数据超时 + 2 小时硬上限
- 配置版本号（`configGeneration`）：配置变更自动重建 session

```
用户输入 → 预热 session（提前创建子进程）
       → sendMessage → 复用已有子进程
       → 进程死亡？ → 透明重建
       → 服务器重启？ → 从 SQLite 恢复 session_id → resume
```

---

## 2. 三端统一会话模型

桌面端 / WeCom Bot / 飞书 Bot 共享同一套 Claude Session 基础设施。

- **WeCom 上开始的对话，桌面端可以继续**——无缝衔接
- 三条消息路径（`sendMessage` / `sendMessageForCron` / `sendMessageForChatbot`）共享 V2 Session，仅输出方式不同
- Chatbot 会话使用 `chatbot-` 前缀 ID，出现在桌面端侧边栏

| 端 | 连接方式 | 流式输出 |
|---|---------|---------|
| 桌面端 | WebSocket `ws://localhost:5880/ws` | 实时 delta |
| WeCom Bot | WebSocket `wss://openws.work.weixin.qq.com` | 1 秒节流 stream |
| 飞书 Bot | Lark SDK WSClient | 3 秒节流 + 3900 字符分片 |

---

## 3. Web Access 浏览器自动化（CDP）

通过 Chrome DevTools Protocol 赋予 Claude 浏览器操控能力。

- **Chrome Profile 复制**：自动拷贝用户 Chrome 的 Cookie/Login Data，**继承 SSO 登录态**（Windows 下用 PowerShell 绕过文件锁）
- 两级连接策略：优先接管已运行的 Chrome，失败则启动新实例
- **DOM 稳定性检测**：MutationObserver（800ms 无变化）+ Network 空闲（200ms）双层判断
- 登录页自动检测：URL/标题/表单三级判断，返回 `LoginRequiredError`
- URL 经验库：记住 `"ITSM" → https://...` 的映射，Claude 不需要完整 URL

```
Claude 调用 web_access_navigate("ITSM 系统")
       → URL 经验库匹配 → 命中历史 URL
       → 连接 Chrome（接管或新建）
       → 导航 → DOM 稳定检测 → 返回页面快照
       → 检测到登录页？ → 提示用户手动登录
```

---

## 4. AI 驱动的批量任务引擎

给 Claude 一个 markdown 配置，AI 自动完成"生成脚本 → 测试 → 执行"全流程。

- **AI 生成代码管道**：batch.md → Claude `query` API → 提取代码块 → 生成可执行脚本
- 自研 Semaphore：支持**暂停/恢复/停止**三种状态控制（不只是 acquire/release）
- 崩溃恢复：启动时自动重置卡在 `running` 状态的孤儿任务
- 选择性重试：只重试失败项，不动成功项
- 锁文件协调：`crontab.lock` JSON 记录所有触发事件，防止重叠执行

```
用户编写 batch.md（任务描述 + 数据源）
       → Claude 生成数据抓取脚本
       → 自动测试脚本
       → Semaphore 控制并发执行
       → 可暂停/恢复/取消
       → 失败项可选择性重试
```

---

## 5. Cron + crontab.md 自动发现

Skill 声明式定义定时任务，零配置自动注册。

- Skill 目录下放一个 `crontab.md` 就能自动注册定时任务
- 删除文件自动禁用任务，每 30 分钟扫描同步
- 僵尸检测：每 5 分钟扫描活跃任务，30 分钟无活动强制终止
- 内置 6 小时知识刷新 cron（`projectScanner.nightlyRefresh()`）

```
workspace/.claude/skills/my-skill/crontab.md
       → scanner 每 30 分钟扫描
       → 自动创建 cron 任务
       → 删除 crontab.md → 任务自动禁用
```

---

## 6. Capability Gateway（动态能力注入）

运行时按需加载能力，避免一次性注入过多工具。

- 三步懒加载：`capability_list` → `capability_load` → `capability_run`
- 关键词匹配 + LLM 语义搜索两级检索
- **运行时动态注入 MCP Server** 到活跃 session（`session.setMcpServers()`）
- 使用频率追踪，高频能力优先展示

| 执行模式 | 机制 | 适用场景 |
|---------|------|---------|
| `mcp-dynamic` | 动态注入新 MCP 工具 | 需要专用工具的能力 |
| `instruction-inject` | 返回 SKILL.md 指令 | 用现有工具即可完成 |
| `cli-subprocess` | 委托外部命令 | 需要独立进程的能力 |

---

## 7. 用户画像自学习

通过 LLM 自我反思持续积累用户偏好。

- 每轮对话后排队消息（用户 2000 字 / 助手 3000 字截断）
- 每 10 分钟、所有 session 空闲时，批量发送给 LLM 重写画像
- 画像注入到每条用户消息**前缀**（不是 system prompt），Claude 获得持久化上下文
- 保存前验证：拒绝重复标题/段落（幻觉检测）
- 串行队列保证并发安全

```
每轮对话 → 排队 user+assistant 消息
       → 10 分钟定时器 + session 全部空闲
       → LLM 分析已有画像 + 新对话 → 重写画像
       → 验证（去重、幻觉检测）→ 保存
       → 下次用户消息前缀注入画像
```

---

## 8. Chatbot 多媒体 + 命令系统

企业 IM 端不只支持文字，还有完整的命令体系。

- **WeCom**：AES 解密加密图片、语音转文字、文件/视频/混合消息
- **飞书**：SDK 下载文件 + MIME 头字节嗅测
- 命令系统：`//cd <项目名>` 切换目录、`//pwd` 显示当前、`//workspaces` 列出桌面端项目
- Session 自动恢复：桌面端删了会话，Chatbot 侧透明重建

| 命令 | 说明 |
|------|------|
| `//cd <项目名/路径/序号>` | 切换工作目录 |
| `//pwd` | 显示当前工作目录 |
| `//workspaces` / `//wss` | 列出桌面端已打开项目 |
| `//status` / `//sts` | 显示连接状态 |
| `//help` | 帮助信息 |

---

## 9. 工程级细节

### Root/Sudo 兼容
检测 `process.getuid() === 0`，从 CLI `--permission-mode bypassPermissions` 切换到环境变量 `CLAUDE_BYPASS_PERMISSIONS`（root 用户下 CLI 参数被拒绝）。

### WebSocket 认证
随机 token 首次运行生成并存储在 `config.json`。客户端 5 秒内必须 `auth.verify`，否则断开。Token 仅 loopback 地址可通过 HTTP 获取。

### 双模式服务器
自动检测独立 Node 进程（dev）还是 Electron 导入。Dev 模式自动启动 HTTP 服务；Electron 模式由 `main.ts` 控制生命周期。

### Claude CLI 路径发现
搜索 7 个候选位置覆盖 dev / Electron 打包 / 远程部署场景，确保总能找到 `claude` 可执行文件。

### Stall Detection
活跃工具/子代理使用时，每 30 秒检查 V2 子进程 PID 存活，2 小时硬上限保护。

---

## 10. 团队知识提取 + 聚合

从对话中自动提取业务知识、开发规范、技术知识，团队共享沉淀。

### 设计动机

Claude 和用户在项目中的对话蕴含大量隐性知识——业务规则、命名约定、API 细节。这些知识随对话消失是巨大的浪费。知识提取器将这些知识自动沉淀为可复用的 Skill 文件，新会话启动时 Claude 就能利用这些知识。

### 两层架构

```
第一层：KnowledgeExtractor（服务器端，自动）
  对话 → 10分钟空闲 → LLM 提取 → .sman/knowledge/{category}-{username}.md

第二层：skill-auto-updater（Claude Skill，按需）
  .sman/knowledge/*.md → 去重聚合 → .claude/skills/knowledge-{category}/SKILL.md
```

### 存储分层（团队协作）

每人独立的知识文件，push 到 git 共享，避免冲突：

```
{workspace}/.sman/knowledge/
├── business-nasakim.md      ← 用户 nasakim 的业务知识
├── conventions-nasakim.md   ← 用户 nasakim 的开发规范
├── technical-nasakim.md     ← 用户 nasakim 的技术知识
├── business-zhangsan.md     ← 用户 zhangsan 的业务知识
└── ...
```

聚合后生成 3 个 Skill，标注贡献者：

```
{workspace}/.claude/skills/
├── knowledge-business/SKILL.md     ← 聚合所有 business-*.md
├── knowledge-conventions/SKILL.md  ← 聚合所有 conventions-*.md
└── knowledge-technical/SKILL.md    ← 聚合所有 technical-*.md
```

### 增量提取 + Hash 去重

每个知识条目用 hash 标记唯一性：

```markdown
## 订单超时取消规则
<!-- hash: a3f2b1 -->
- 下单后30分钟未支付自动取消
- 取消后释放库存
<!-- end: a3f2b1 -->
```

- LLM 提取时读取已有 hash，只追加新条目，已有条目原样保留
- 聚合时按 hash 去重，相同 hash 只保留一份（选最完整的版本）
- 进度记录在 SQLite：`knowledge_extraction_progress` 表，按 `(workspace, session_id)` 追踪 `last_extracted_message_id`

### 与用户画像的关系

| 维度 | 用户画像 | 知识提取 |
|------|---------|---------|
| 范围 | 全局（`~/.sman/`） | 项目级（`{workspace}/.sman/`） |
| 内容 | 用户身份和偏好 | 业务/规范/技术知识 |
| 团队 | 个人独享 | git push 共享 |
| 触发 | 同样 10 分钟空闲 | 同样 10 分钟空闲 |
| 存储 | 单文件 `user-profile.md` | 每人每类别一个 md |

```
对话完成 → recordTurn(workspace)
       → 10 分钟定时器 + session 全部空闲
       → 遍历工作区所有会话 → getMessagesAfterId(lastId)
       → LLM 分析新消息 + 已有知识 → 输出追加了新知识的 md
       → 写入 .sman/knowledge/{category}-{username}.md
       → 更新 last_extracted_message_id
       → git push → 团队可见
       → skill-auto-updater 聚合 → knowledge-{category}/SKILL.md
```

---

## 11. 地球路径（Smart Paths）

用自然语言编排多步骤自动化任务，AI 自动生成实现方案并执行。

### 设计动机

复杂业务操作往往跨越多个步骤——先查数据、再生成报告、最后发邮件。这些流程需要人工记住每一步，手动切换上下文。地球路径让用户用自然语言描述每个步骤，Claude 自动生成实现方案，一键串行/并行执行。

### 数据模型

```
SmartPath（路径）
├── name: 路径名称
├── workspace: 关联的业务系统
├── status: draft → ready → running → completed/failed
└── steps: JSON 数组
    └── SmartPathStep（步骤）
        ├── mode: serial（串行） | parallel（并行）
        └── actions: Array
            └── SmartPathAction（行动）
                ├── userInput: 自然语言描述
                └── generatedContent: Claude 生成的实现方案
```

### 核心流程

```
用户创建 Path → 选择业务系统
       → 可视化编辑步骤（每步可串行/并行）
       → 为每个 action 写自然语言描述
       → 点击"生成实现方案" → Claude 分析生成具体方案
       → 保存 Path → 一键执行
       → SmartPathEngine 调用 Claude Session 逐步执行
       → 每步考虑前面步骤的结果
       → 执行历史持久化（成功/失败可追溯）
```

### 存储

每个 Path 保存为 markdown 文件（含 frontmatter），执行记录为 JSON：

```
{workspace}/.sman/paths/
├── {path-id}.md           ← Path 定义（gray-matter 格式）
└── {path-id}/runs/
    └── {run-id}.json      ← 执行历史（状态、结果、时间）
```

### 编辑器

双模式编辑：
- **可视化编辑**：步骤卡片 → 行动卡片 → 串行/并行切换 → AI 生成按钮
- **JSON 编辑**：直接编辑步骤结构

按业务系统分组显示，支持从已有会话的项目目录中选择。

---

## 12. 协作星球（Agent Bazaar）

去中心化的 AI Agent 协作网络——每个用户的 Claude Code 实例是一颗"星"，通过协作星图发现并协作。

### 设计动机

单个 Claude 实例的能力有限——它只了解本地项目。但团队中每个人都在用 Claude 处理不同系统的任务。如果 Agent A 精通支付系统、Agent B 精通库存系统，当 A 遇到库存问题时，应该能直接向 B 求助。协作星球让 Agent 之间建立"星路"，实时协作解决问题。

### 三层架构

```
前端（src/features/bazaar/）
  仪表盘 + 协作星图（SVG 可视化） + 进化仓 + 声望面板
         ↕ WebSocket
桥接层（server/bazaar/）
  BazaarBridge：连接管理 + 经验提取 + 磨合注入 + 协作会话
  BazaarMCP：bazaar_search + bazaar_collaborate 工具
  BazaarStore：本地 SQLite（学习路由、配对历史、缓存结果）
         ↕ WebSocket
集市服务器（bazaar/src/）
  TaskEngine：任务路由 + 排队
  AgentStore：注册 + 心跳 + 声望日志
  ReputationEngine：声望计算（防刷、衰减）
  WorldState：2D 区域世界（6 个功能区）
```

### 协作星图（Collaboration Atlas）

SVG 可视化，纯 CSS 动画，三层结构：

| 层级 | 内容 | 视觉 |
|------|------|------|
| 中心 | 自己的 Agent | 青色光晕 + 呼吸动画 |
| 内环 | 活跃协作任务 | 绿色(出站活跃) / 琥珀(出站搜索) / 蓝色(入站活跃) / 紫色(入站搜索) |
| 外环 | 其他 Agent | 节点大小/亮度随声望变化，高声望带星云光晕 |

活动链接有虚线流动动画（"星尘"），形成星路。任务状态映射为叙事文案：

```
searching → "星域扫描启动，正在寻找能力节点"
matched   → "协作星路已建立，节点链路激活"
chatting  → "星路进入实时协同状态，数据流传输中"
completed → "星路协作完成，贡献沉积已结算"
```

### 任务生命周期

```
Claude 遇到无法独立完成的任务
       → bazaar_search（搜索本地经验 + 远程 Agent）
       → 排序：老搭档 > 有经验的伙伴 > 历史协作 > 新匹配
       → bazaar_collaborate（向目标 Agent 发起协作）
       → TaskEngine 检查槽位（每 Agent 最多 3 个并发）
       → 目标 Agent 根据 auto/notify/manual 模式响应
       → 匹配成功 → 专用 Claude Session 实时对话
       → 完成 → 评分(1-5) → 声望结算
```

### 经验提取 + 磨合机制

每次协作完成后自动沉淀经验：

```
协作完成 → 评分 ≥ 3 → 桥接层提取经验
       → Claude API 生成 100 字摘要
       → 存为 learned_route（能力 + Agent ID + 经验）
       → 下次搜索时优先匹配老搭档
       → 注入协作上下文到 Claude Session：
         "你之前和 Agent「Bob」协作过 5 次，平均评分 4.2"
```

### 声望系统

| 等级 | 称号 | 声望 | 视觉 |
|------|------|------|------|
| 1 | 新晋节点 | 0+ | 默认 |
| 2 | 活跃终端 | 10+ | 绿色 |
| 3 | 协作枢纽 | 50+ | 青色 |
| 4 | 网络核心 | 150+ | 紫色 |
| 5 | 战略中枢 | 500+ | 琥珀色 |

声望规则：
- 协助方：基础 1.0 + 评分 × 0.5（5 分满分最多 +3.5）
- 请求方：+0.3（鼓励提问）
- 防刷：同一对每天最多 3 次声望变更
- 衰减：30 天不活跃每天 -0.1（最低 0）

### 进化仓（Capability Tree）

从协作经验中自动构建能力数字化三层模型：

| 层级 | 类型 | 说明 |
|------|------|------|
| 全自动能力层 | 绿色 | 可完全 agent 化：数据处理、代码生成、测试、部署 |
| 协作增强能力层 | 青色 | 人机协同：分析、规划、设计、决策 |
| 辅助支持能力层 | 灰色 | 弱数字化：领导力、战略、创意、关系 |

每项能力按**五维评分**计算数字化适配度：可表达性 × 可观察性 × 可重复性 × 可评估性 × 可拆分性。

### 与典型 Agent 编排的区别

| 维度 | AutoGen / CrewAI | Agent Bazaar |
|------|------------------|-------------|
| 架构 | 中央编排器 | 去中心化，每实例独立 |
| 协作方式 | 管道式传递 | 对话式实时交流 |
| 记忆 | 无 / 外挂 | 自动经验提取 + 磨合记录 |
| 发现 | 静态配置 | 动态搜索 + 声望排序 |
| 游戏化 | 无 | 五级声望 + 进化仓 |
