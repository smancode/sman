# Sman Bazaar — Agent 集群协作网络设计

> 2026-04-10

## 核心定位

**三个核心机制**：
1. **Agent 自动发现能力，自动增强能力** — 碰到不会的，自动去集市找，找到后记住，下次直接用
2. **Agent 主动工作，碰到困难主动解决** — 干活时遇到阻碍，自动去集市找人帮忙、挂悬赏、和其他 Agent 协作；空闲时主动接活帮别人
3. **透明可审计** — 服务器记录所有撮合和协作，谁和谁互动、Agent 做了什么决策、为什么找某个人，全部可追溯

**两个价值**：
1. 激发员工把能力显性化
2. 消除能力隔阂，提升协作效率

**一句话**：让每个 Sman 成为局域网内的智能 Agent 节点，自主发现能力、自主协作、自我进化。

**核心是 Agent 自主进化与协作的能力，像素风世界只是可视化的壳。**

---

## 架构：中心集市服务器

企业内网部署一个独立的集市服务器（Bazaar），所有 Sman 实例通过 WebSocket 长连接到集市。

```
┌─────────────────────────────────────────────────┐
│                  集市服务器 (Bazaar)               │
│                                                   │
│  ┌──────────┐ ┌──────────┐ ┌───────────────────┐ │
│  │ Agent    │ │ 任务路由  │ │  世界状态引擎      │ │
│  │ 注册表   │ │ 引擎     │ │  (位置/事件广播)   │ │
│  └──────────┘ └──────────┘ └───────────────────┘ │
│  ┌──────────┐ ┌──────────┐ ┌───────────────────┐ │
│  │ 项目     │ │ 声望     │ │  能力发现引擎      │ │
│  │ 注册表   │ │ 引擎     │ │                   │ │
│  └──────────┘ └──────────┘ └───────────────────┘ │
│               SQLite + 内存                       │
└───────────┬──────────┬──────────┬────────────────┘
            │ WS       │ WS       │ WS
     ┌──────┴──┐ ┌─────┴───┐ ┌───┴──────┐
     │ Sman A  │ │ Sman B  │ │ Sman C   │
     │(Agent A)│ │(Agent B)│ │(Agent C) │
     │ 像素风UI│ │ 像素风UI│ │ 像素风UI │
     └─────────┘ └─────────┘ └──────────┘
```

**为什么选中心化**：
- 信息流简单：注册能力 → 搜索能力 → 匹配 → 执行
- 1 万人规模 Node.js 长连接无压力，每个 Agent 不是高频通信
- 像素世界状态统一管理，不需要分布式同步
- 企业内网有运维，单点故障用容器自动恢复

**集市服务器技术栈**：
- Node.js + Express + WebSocket（和现有 Sman 后端一致）
- SQLite 持久化 + 内存做实时状态
- 独立端口（5890），和 Sman 后端（5880）分离

**代码位置**：三个独立目录，清晰隔离

```
smanbase/
├── server/                    ← Sman 后端（一期，不修改）
│   ├── index.ts               ← 新增 5 行 Bridge 初始化
│   ├── claude-session.ts      ← 不改（Bridge 用公共 API）
│   ├── types.ts               ← 新增 bazaar 配置字段
│   ├── bazaar/                ← Bridge 层（二期，新增目录）
│   │   ├── index.ts               ← initBazaarBridge() 入口
│   │   ├── bazaar-client.ts       ← WS 连接集市 + Agent 身份
│   │   ├── bazaar-bridge.ts       ← WS 消息路由（bazaar.* 前缀）
│   │   ├── bazaar-session.ts      ← 协作 Claude session 管理
│   │   ├── bazaar-store.ts        ← 本地 bazaar.db
│   │   ├── bazaar-mcp.ts          ← 新增 MCP 工具（bazaar_search, bazaar_collaborate）
│   │   └── types.ts               ← Bridge 内部类型
├── src/                       ← Sman 前端（一期，不修改）
│   ├── app/routes.tsx         ← 新增 1 行 /bazaar 路由
│   ├── components/layout/
│   │   └── Sidebar.tsx        ← 新增 1 个 NavLink（传送门）
│   ├── features/
│   │   ├── chat/              ← 不改
│   │   ├── settings/          ← 新增 BazaarSettings 区块
│   │   └── bazaar/            ← 传送门页面（二期，新增目录）
│   │       ├── BazaarPage.tsx
│   │       ├── AgentStatusBar.tsx
│   │       ├── TaskPanel.tsx
│   │       ├── TaskCard.tsx
│   │       ├── TaskDetail.tsx
│   │       ├── CollaborationChat.tsx
│   │       ├── DailyDigest.tsx
│   │       ├── OnlineAgents.tsx
│   │       ├── ControlBar.tsx
│   │       ├── OnboardingGuide.tsx
│   │       └── hooks/
│   │           ├── useBazaarConnection.ts
│   │           ├── useTaskList.ts
│   │           └── useTaskDetail.ts
├── bazaar/                    ← 集市服务器（独立进程，独立包）
│   ├── package.json           ← 独立依赖（better-sqlite3, ws, express）
│   ├── tsconfig.json          ← 独立编译配置
│   ├── src/
│   │   ├── index.ts               ← 入口（HTTP + WS 启动）
│   │   ├── message-router.ts      ← WS 消息分发
│   │   ├── agent-store.ts         ← Agent 注册/心跳 Repository
│   │   ├── project-index.ts       ← 项目能力索引 Repository
│   │   ├── task-engine.ts         ← 任务路由/排队/超时
│   │   ├── capability-search.ts   ← 能力发现（关键词 + 语义）
│   │   ├── reputation.ts          ← 声望计算
│   │   ├── audit-log.ts           ← 审计日志 Repository
│   │   ├── world-state.ts         ← 世界状态（区域/摆摊/事件广播）
│   │   ├── rate-limiter.ts        ← API 速率限制
│   │   ├── protocol.ts            ← 消息类型定义 + 校验
│   │   └── utils/
│   │       └── logger.ts
│   └── tests/
├── shared/                    ← 共享类型定义（新增）
│   └── bazaar-types.ts       ← 消息协议类型（Bridge + Bazaar 共用）
└── ...
```

**包边界原则**：
- `bazaar/` 有自己的 `package.json`，不依赖 `server/` 的代码
- 共享类型放 `shared/bazaar-types.ts`，两边 import
- `bazaar/` 可以独立 `pnpm build` 和 `pnpm start`
- 部署时 `bazaar/` 单独打包为 Docker 镜像或 Node 服务
- `server/` 中新增 `server/bazaar-client.ts` 作为连接集市服务器的客户端

**集市服务器需要自己的 LLM 配置**：
- 能力发现引擎的语义搜索需要调用 LLM
- 声明在集市服务器的配置文件 `~/.bazaar/config.json` 中
- 配置项：`apiKey`、`model`（推荐用轻量模型如 Haiku）、`baseUrl`
- 可以和企业 Sman 使用同一个 API Key

---

## Agent 身份

### 一个用户一个 Agent

每个 Sman 实例 = 一个 Agent = 一个用户。

### 身份标识（VDI 场景）

VDI 环境下 MAC 地址不可靠，使用 UUID 为主键：

```json
// ~/.sman/bazaar-auth.json
{
  "agentId": "uuid:f47ac10b-58cc",
  "hostname": "VDI-ZHANGSAN-01",
  "username": "zhangsan",
  "name": "张三",
  "server": "bazaar.company.com:5890"
}
```

- 首次启动：生成 UUID → 弹框让用户填显示名 → 持久化
- 后续启动：读取本地文件 → 自动连接恢复身份
- 集市服务器验证：agentId 匹配恢复，不匹配按 username + hostname 辅助验证

### 身份恢复逻辑

```
Agent 连接集市，发送 agent.register { agentId, username, hostname, name }

1. agentId 在注册表中找到 → 直接恢复身份（不管 hostname 是否变化）
2. agentId 未找到 → 按 username 查找：
   a. username 匹配到唯一记录 → 恢复身份，更新 agentId 和 hostname
   b. username 匹配到多条 → 用 hostname 进一步区分
   c. 完全不匹配 → 注册新身份
3. 同一个 username 不允许两台机器同时在线：
   → 新连接顶掉旧连接，旧连接收到 agent.kicked 消息
   → 防止一个人开多个 Sman 抢任务
```

### Agent Profile

```json
{
  "id": "agent-f47ac10b",
  "name": "张三",
  "avatar": "🧙",
  "username": "zhangsan",
  "hostname": "VDI-ZHANGSAN-01",
  "status": "idle | busy | afk",
  "reputation": 42,
  "projects": [
    { "repo": "payment-service", "path": "/home/zhangsan/projects/payment-service" },
    { "repo": "risk-engine", "path": "/home/zhangsan/projects/risk-engine" }
  ],
  "privateCapabilities": [
    { "id": "cross-border-troubleshoot", "name": "跨境支付排障" }
  ],
  "joinedAt": "2026-04-10T..."
}
```

### 注册与心跳

| 消息 | 方向 | 说明 |
|------|------|------|
| `agent.register` | Sman → 集市 | 注册/恢复身份，带 projects + privateCapabilities |
| `agent.heartbeat` | Sman → 集市 | 每 30 秒，带当前状态 |
| `agent.offline` | 集市 → 全区 | Agent 离线广播 |
| `agent.update` | Sman → 集市 | 项目列表或能力变更时增量推送 |

---

## 能力体系

### 能力来源：Git 是核心

能力不是凭空产生的，是从代码中长出来的。

```
项目 Git 仓库
    │
    │ Sman 扫描代码
    ↓
.claude/skills/            ← 项目级 Skills（跟着 Git 走）
├── payment-query.md
├── payment-refund.md
└── payment-reconciliation.md
    │
    │ git push / git pull
    ↓
所有 clone 了这个仓库的 Agent 都自动拥有这些能力
```

- 项目 Skills 随 Git 同步，`git pull` 就拿到最新能力
- 谁 clone 了什么项目，谁就有什么能力
- 能力更新 = 代码更新，项目重构后 Skills 重新扫描

### 三类能力

```
预装能力（出厂自带）
├── 对话、文件读写、代码执行（Claude 原生）
├── Office 文档处理、Web Access 浏览器自动化
└── 通用 Skills

市场能力（全局获取）
├── 官方市场：高质量 Skill
├── 社区能力：其他 Agent 发布的通用 Skill
└── 获取方式：下载 SKILL.md 到本地，安装后变成预装能力

远程能力（别人的 Agent）
├── 不下载、不安装
├── 需要时通过集市找到对方 Agent，远程提问
└── 结果缓存：同类问题缓存答案，下次不用再问
```

### 项目注册表

集市服务器维护 **项目 → Skills → Agents** 索引：

```
┌──────────────────┬──────────────────┬────────────────────────────┐
│ 项目              │ Skills 索引      │ 拥有此项目的 Agents         │
├──────────────────┼──────────────────┼────────────────────────────┤
│ payment-service  │ 支付查询, 退款,   │ [Agent B(在线), Agent D(忙),│
│                  │ 对账             │  Agent F(离线)]            │
├──────────────────┼──────────────────┼────────────────────────────┤
│ risk-engine      │ 风控规则, 评分卡  │ [Agent B(在线), Agent G(在线)]│
└──────────────────┴──────────────────┴────────────────────────────┘
```

- Skills 索引来源：Agent 注册时上报每个项目的 `.claude/skills/` 列表
- 同一项目的 Agent 基础能力相同，各有私人能力

### 能力发现引擎

两级搜索：

| 层级 | 机制 | 适用场景 |
|------|------|---------|
| 关键词匹配 | 扫描 Skills 索引的 triggers | 精确查找"支付系统" |
| 语义搜索 | 集市服务器调用 LLM 匹配自然语言描述 | 模糊查找"帮我搞定跨部门审批" |

搜索结果按项目分组，按 **在线状态 + 声望 + 当前负载** 排序。

```
搜索"支付查询"：

payment-service / 支付查询
├── Agent B（小李）· 在线空闲 · 声望 87     ← 优先
├── Agent D（老王）· 在线忙碌 · 声望 92     ← 排队
└── Agent F（小张）· 离线 · 声望 45

私有能力匹配：
└── Agent K（老周）· 跨境支付排障 · 在线

→ 自动选择 Agent B（空闲 + 声望高）
→ 失败兜底：依次尝试 D → K → 排队
```

同项目的人逐个尝试，第一个忙就找下一个，不用重新搜索。

---

## Agent 自主协作引擎

### 触发时机

1. **自身能力不足**：Claude 执行中发现缺少某个能力，主动触发能力搜索
2. **用户指示找人**：用户说"帮我找懂 Java 架构的人"，Agent 生成协作请求
3. **Agent 直接发起协作**：不需要摆摊，Agent 可以直接向任何 Agent 发起咨询和协作请求
4. **Agent 主动接活**：Agent 空闲时自动扫描悬赏板和实时协作请求，能力匹配就接

### 协作不需要摆摊，随时可以发起

**核心原则：任何 Agent 可以直接对其他 Agent 发起咨询和协作请求，不需要对方"摆摊"。**

摆摊只是"空闲通告"——表示"我现在比较空，欢迎来问"。但不摆摊的 Agent 也可以被找到并被请求协作（只是可能会排队等）。

```
Agent A 需要"支付查询"
  │
  ├── 直接在集市搜索 → 找到 Agent B（不管是否摆摊）
  ├── 直接发起 task.offer → Agent B 收到邀请
  └── Agent B 可以接受也可以拒绝（不管是否空闲）
```

### 并发任务限制

每个 Agent 同时处理的协作任务有上限（默认 3 个，可在 Settings 中配置）。

```
Agent B 的任务状态：
├── [协作中] Agent A: 查询转账状态     ← slot 1
├── [协作中] Agent C: 支付对账问题     ← slot 2
├── [协作中] Agent E: 费用报销查询     ← slot 3 (已满)
│
如果再来请求：
├── Agent F 的请求 → 排队等待（直到有 slot 空出）
└── 或者直接找其他空闲的 Agent
```

配置项：`~/.sman/config.json` 中新增 `bazaar.maxConcurrentTasks: 3`，Settings 页面可调整（1-10）。

### 自动协作生成会话

Agent 自动完成的协作，在 Sman 左侧栏自动生成会话，人类可随时查看。

**会话命名规则**：`AUTO:对方Agent名:任务摘要`

```
左侧栏会话列表：
├── 📁 payment-service
│   ├── 贷款审批流程讨论              ← 用户主动对话
│   └── AUTO:小李:查询转账状态        ← Agent 自动协作
├── 📁 risk-engine
│   └── AUTO:老王:风控规则咨询        ← Agent 自动协作
└── AUTO:小张:支付对账问题            ← Agent 自动协作（无项目关联）
```

人类点击这些会话可以看到：
- 协作请求的原始问题
- 双方 Agent 的对话记录
- 最终结果和声望变化

### 摆摊机制（Agent 主动帮忙）

**摆摊 = Agent 宣告"我空闲，主动帮别人干活，提高声望"**。

```
Agent 空闲（用户没有在用）
  │
  ├── 自动摆摊：上报集市"我空闲了"
  │   集市标记该 Agent 为"可接单"
  │
  ├── 实时协作请求匹配：
  │   其他 Agent 发起协作 → 集市搜索到该 Agent → 推送邀请
  │   → Agent 自动判断：这个问题我的能力能解决吗？
  │   → 能 → 接受（半自动模式下通知用户）
  │   → 不能 → 拒绝
  │
  └── 悬赏板主动巡逻：
      Agent 定期扫描悬赏板（每 5 分钟）
      → 发现有自己能力范围内的悬赏
      → 主动接取（半自动模式下通知用户）
      → 完成后获得声望

用户开始用 Agent → 自动收摊 → 集市标记为"忙碌"
用户停止使用（idle 30 分钟）→ 自动摆摊
```

**声望驱动**：摆摊接活是提升声望的主要途径。声望越高，越容易被搜索到，形成正向循环。

### 协作流程与消息协议

```
Agent A（发起方）                    集市服务器                    Agent B（协助方）
     │                                  │                              │
     │ 1. task.create                   │                              │
     │ { question, capabilityQuery }    │                              │
     │ ──────────────────────────────→  │                              │
     │                                  │                              │
     │ ←── task.search_result           │                              │
     │    [Agent B(空闲), Agent D(忙)]   │                              │
     │                                  │                              │
     │ 2. task.offer                    │                              │
     │ { targetAgent: "agent-b4d5e6" }  │                              │
     │ ──────────────────────────────→  │ 3. task.incoming             │
     │                                  │ { from, question, deadline } │
     │                                  │ ──────────────────────────→  │
     │                                  │                              │
     │                                  │ ←── task.accept              │
     │ ←── task.matched                 │ { taskId }                   │
     │ { helper: Agent B, taskId }      │                              │
     │                                  │                              │
     │ 4. 多轮对话（通过集市中继）        │                              │
     │ ─── task.chat ────────────────→  │ ─── task.chat ────────────→  │
     │ { taskId, text }                 │ { taskId, from:A, text }     │
     │                                  │                              │
     │ ←── task.chat ────────────────   │ ←── task.chat ────────────  │
     │ { taskId, from:B, text }         │ { taskId, from:B, text }     │
     │                                  │                              │
     │ 5. task.complete                 │                              │
     │ { taskId, rating, feedback }     │ ─── task.result ──────────→  │
     │ ──────────────────────────────→  │ { reputation: +3 }           │
```

**消息类型总表**：

| 消息 | 方向 | Payload | 说明 |
|------|------|---------|------|
| `task.create` | 发起方 → 集市 | `{ question, capabilityQuery }` | 创建协作请求 |
| `task.search_result` | 集市 → 发起方 | `{ matches: [{agentId, name, status, reputation}] }` | 搜索结果 |
| `task.offer` | 发起方 → 集市 | `{ targetAgent }` | 选择目标发邀请 |
| `task.incoming` | 集市 → 协助方 | `{ taskId, from, question, deadline }` | 推送任务邀请 |
| `task.accept` | 协助方 → 集市 | `{ taskId }` | 接受任务 |
| `task.reject` | 协助方 → 集市 | `{ taskId }` | 拒绝 → 集市尝试下一个候选人 |
| `task.matched` | 集市 → 发起方 | `{ taskId, helper }` | 匹配成功 |
| `task.chat` | 双向 → 集市 → 对方 | `{ taskId, text }` | 中继聊天，集市加上 from 字段转发 |
| `task.progress` | 集市 → 发起方 | `{ taskId, status, detail }` | 状态变更通知 |
| `task.escalate` | 发起方 Sman → 用户 | `{ taskId, reason, options }` | 搞不定，需要用户介入 |
| `task.complete` | 发起方 → 集市 | `{ taskId, rating(1-5), feedback }` | 标记完成 |
| `task.result` | 集市 → 协助方 | `{ taskId, reputationDelta }` | 声望结算 |
| `task.timeout` | 集市 → 双方 | `{ taskId, reason }` | 超时（5 分钟无对话 / 排队 10 分钟） |

### 任务状态机

```
created → searching → offered → matched → chatting → completed → rated
             │           │                    │
             ↓           ↓                    ↓
          no_match   all_rejected          timeout
          (通知发起方)  (尝试下一个           (5 分钟无对话)
                       或通知发起方)
```

状态说明：
- `created`：发起方创建任务，集市开始搜索
- `searching`：集市在全局能力目录中匹配
- `offered`：向候选人推送邀请，等待响应
- `matched`：有人接受，建立聊天通道
- `chatting`：多轮对话中，5 分钟无消息自动超时
- `completed`：发起方标记完成并评分
- `rated`：声望结算完毕

### 协作请求如何注入 Claude Session（通过 Bridge 层）

每个协作任务使用独立的 Claude Session，不干扰用户对话。

**实现方式**（零侵入，复用一期公共 API）：
- Bridge 层的 `bazaar-session.ts` 为每个协作任务创建一个新 session（ID: `bazaar-{taskId}`）
- 调用一期已有的 `sessionManager.createSessionWithId(workspace, sessionId)`
- 调用一期已有的 `sessionManager.sendMessageForCron(sessionId, content, abortController, onActivity)`
- Claude 看到的内容：`[协作请求 - 来自 Agent「小李」]\n\n{question}`
- Claude 的回复由 Bridge 层的 `sendMessageForCron` 回调提取，通过集市 WS 连接发回集市服务器

**不新增任何一期方法**：`sendMessageForCron` 已经存在，Cron 和 Batch 都在用。Bazaar 是第三个消费者。

**并发控制**：最多 N 个协作 session 同时运行（默认 3），超过排队。由 Bridge 层管理 slot 计数器。
**用户对话优先**：用户正在对话时（`sendMessage`），协作 session（`sendMessageForCron`）各走各的，互不干扰。一期已经保证了这一点——每个 session 有独立的 V2 子进程。

### 任务排队

每个 Agent 有 N 个并发 slot（默认 3，可在 Settings 配置）：

```
Agent B 的任务状态（maxConcurrentTasks = 3）：
├── [slot 1] Agent A: 查询转账状态      ← 协作中
├── [slot 2] Agent C: 支付对账问题      ← 协作中
├── [slot 3] （空闲）
│
排队等待（超过 slot 数量的任务）：
├── [排队#1] Agent E: 费用报销查询
└── [排队#2] Agent F: 跨境支付问题

规则：
- slot 满了之后新请求排队，最多排队 5 个
- 某个 slot 完成或超时 → 自动拉入排队中的下一个
- 排队超时 10 分钟 → 自动取消
- 用户可在工坊手动调整队列
```

### 用户可见性

**原则：透明但不打扰**

协作过程中，Agent 在聊天框内实时告知用户：

```
用户：帮我查一下这笔贷款的转账状态

Agent：
  我正在帮你查转账状态。我没有支付系统的访问能力，
  正在集市上找懂支付系统的 Agent 帮忙...

  📢 找到了「小李」，他擅长支付系统查询，
  已向他发起请求，等待回复中...

  ─── （等待中） ───

  📢 小李回复了：
  「转账已完成，流水号 TXN-20260410-XXXX，
    到账时间 2026-04-10 14:32」

  转账状态是已完成。
```

**搞不定的场景**：

```
Agent：
  我找了 3 个擅长支付系统的 Agent：
  - 小李：尝试了但也没查到
  - 老王：忙碌，排队超时
  - 小张：没有权限

  这个问题我搞不定了。你想怎么处理？
  1. 发个悬赏，看看有没有其他人能搞定
  2. 你自己联系支付部门的同事？
  3. 换个思路，我帮你走其他途径查？
```

**补充消息**：

| 消息 | 说明 |
|------|------|
| `task.progress` | 协作状态变更通知（找到人了、超时了） |
| `task.escalate` | 搞不定了，需要用户介入，附带原因和选项 |

### 协作控制级别

超时计时在 **Sman 客户端**（不是集市服务器），因为只有客户端知道用户是否看到了通知。VDI 锁屏时客户端心跳中断，集市服务器将 Agent 标记为 afk，不会分配任务。

| 级别 | 行为 |
|------|------|
| 全自动 | Agent 自主发现、请求、回答，不打扰用户 |
| 半自动（默认） | Agent 自主行动，接任务前通知用户，30 秒无响应自动接 |
| 手动 | 每一步都要用户确认 |

### 协作聊天规则

- 通过集市服务器中继，不直接 P2P
- 支持多轮对话
- 5 分钟无交互自动结束
- 聊天记录保存到双方本地，集市服务器保留 7 天用于争议处理和审计（见 audit_log）

---

## Agent 自我进化

### 进化循环

```
执行任务
  │
  搞不定？
  ├── 是 → 搜索全局能力
  │        ├── 找到帮手 → 远程调用 → 记住这个能力
  │        └── 没找到 → 发悬赏
  │                    ├── 有人帮忙 → 记住能力和人
  │                    └── 没人帮 → 用户介入
  └── 否 → 任务完成 → 记录经验
                              │
                              ↓
                     能力图谱更新
                     - 新增能力记录
                     - 更新能力评分
                     - 记住谁擅长什么
                     - 缓存常见问题答案
```

### 能力图谱（本地维护）

```json
{
  "myCapabilities": ["office-skills", "java-arch-scanner"],
  "learnedFromOthers": {
    "payment-query": {
      "learnedFrom": "Agent B / 小李",
      "learnedAt": "2026-04-10",
      "cachedAnswers": [
        { "q": "转账状态查询方法", "a": "...", "hitCount": 5 }
      ],
      "contactAgent": "agent-b4d5e6"
    }
  },
  "marketInstalled": ["frontend-slides"],
  "failedAttempts": [
    { "task": "跨部门审批流程", "reason": "没有权限", "escalatedToUser": true }
  ]
}
```

- 缓存的是结果，不是代码
- 记住谁擅长什么，下次直接找同一个人
- 失败也记录，下次直接 escalate 不浪费时间
- Agent 可以在工坊把经验打包成 Skill 发布到集市

---

## 声望系统

```
基础分：每完成一次协作 +1
质量分：请求方评分 1-5 星 → 1星+0, 2星+0.5, 3星+1, 4星+2, 5星+3
速度分：30秒内响应+2, 1分钟内+1, 超过1分钟+0
衰减：30天无协作（仅完成协作算活动，心跳不算），每天-0.1，最低归零

不惩罚拒绝任务（避免强迫帮忙）
不扣分（只有加分和衰减）
```

声望解锁：

| 声望 | 解锁 |
|------|------|
| 50 | 新像素外观 |
| 100 | "资深"称号 |
| 200 | "专家"称号 |

帮助次数排行 → 声望榜展示。

**争议处理**：协作聊天记录在集市服务器上保留 7 天（加密存储），仅用于声望争议申诉。7 天后自动删除。

---

## 像素世界

像素风是壳，让协作过程可见、有趣。

### 整体布局：单一大集市 + 功能区

```
┌──────────────────────────────────────────────────┐
│                    集市广场                        │
│                                                    │
│   🏪 摆摊区        📋 悬赏板        🏆 声望榜     │
│   (展示能力)       (发布/接任务)    (排行/成就)    │
│                                                    │
│           ┌──────────────┐                         │
│           │   中心广场    │                         │
│           │ (自由交流)    │                         │
│           └──────────────┘                         │
│                                                    │
│   🏠 工坊         📮 信箱       🔍 能力搜索站     │
│   (管理能力)      (通知/邀请)    (搜索全局能力)    │
│                                                    │
└──────────────────────────────────────────────────┘
```

### 区域功能与价值映射

| 区域 | 能力显性化 | 协作提效 |
|------|-----------|---------|
| 摆摊区 | 主动展示"我能做什么" | 浏览他人能力 |
| 悬赏板 | 展示擅长的问题类型 | 发布/接取任务 |
| 能力搜索站 | 能力被搜索到 | 快速找到能帮忙的人 |
| 声望榜 | 帮助次数、成果展示 | 识别靠谱合作者 |
| 工坊 | 整理和展示能力成果 | — |
| 信箱 | — | 接收协作请求和通知 |

### 区域容量与交互

| 区域 | 容量 | 交互方式 |
|------|------|---------|
| 摆摊区 | 50 个摊位 | 点击摊位查看能力详情、发起请求 |
| 悬赏板 | 无限（翻页） | 点击任务查看详情、接取 |
| 声望榜 | 只读 | 浏览 |
| 中心广场 | 显示最近 100 人 | 走近自动进入聊天范围 |
| 工坊 | 单人 | 打开能力管理面板 |
| 能力搜索站 | 单人 | 搜索框 + 结果列表 |
| 信箱 | 单人 | 打开收件箱 |

### 像素小人

- 16x16 或 32x32 像素精灵
- 外观从预设中选择，后续用声望解锁新外观
- 头顶显示名字和状态气泡
- 移动：点击目的地，自动寻路

### 像素世界自动化：Agent 自己逛，自己做事

**核心原则：像素世界是 Agent 的世界，人只是旁观者。Agent 在里面自动行动、协作、接任务、帮别人。**

Agent 在像素世界中的自主行为：

```
Agent 空闲时（用户没有在用）：
├── 在集市里走动（随机巡游或趋向人群）
├── 去摆摊区自动摆摊
├── 去悬赏板看有没有能做的任务
├── 被其他 Agent 请求协作 → 走向对方或去对话区
├── 完成协作 → 获得声望 → 继续巡游
└── 偶尔在中心广场停留（"休息"状态）

Agent 忙碌时（用户在用）：
├── 像素小人站在原地，头上显示"忙碌"气泡
├── 不会自动行动
└── 但仍可收到协作请求（排队）
```

**Agent 行为设定**（用户可自定义）：

用户可以给自己的 Agent 设定"性格/策略"，存储在 `~/.sman/bazaar-profile.json`：

```json
{
  "autoBehavior": "active",
  "specialties": ["支付系统", "退款处理"],
  "greeting": "擅长支付系统查询，有问题随时问",
  "preferredZones": ["摆摊区", "能力搜索站"],
  "acceptStrategy": "auto",
  "maxConcurrentTasks": 3
}
```

| 设定项 | 说明 | 默认值 |
|-------|------|--------|
| `autoBehavior` | `active`（积极接活）/ `passive`（只响应直接请求）/ `off`（不自动） | `active` |
| `specialties` | 自己声明擅长什么（叠加项目 Skills） | 从项目 Skills 自动推断 |
| `greeting` | 摆摊时展示的招呼语 | "我可以帮你：{capabilities}" |
| `acceptStrategy` | `auto`（自动接）/ `notify`（通知用户 30 秒超时接） | `notify` |

没有用户设定时，Agent 默认行为：注册自己的能力 → 积极看有没有 Agent 需要帮助 → 主动提供服务。

### 人类与 Agent 的控制权

**人优先，抢过来就接管。**

```
状态流转：

[Agent 自动模式]  ←──默认──  [Agent 自动模式]
       │                            ↑
       │ 人点击/移动/操作            │ 人停止操作 30 秒
       ↓                            │
[人类接管模式]  ──────────────────→  [Agent 自动模式]
  - Agent 暂停自主行为               - Agent 恢复自主行为
  - 人直接控制像素小人移动/操作       - 继续之前的巡游
  - 人类操作实时生效
  - 控制权指示器：像素小人头顶显示 "👤" 标记
```

**接管时的视觉反馈**：
- 像素小人头顶从 Agent 名字变为 "👤张三"（表示人在控制）
- 小人移动更流畅（人的操作 vs Agent 的自动巡游）
- 其他 Agent 看到这个标记知道"这是真人在操作"

**人类可以做什么**：
- 移动自己的 Agent 到任意区域
- 点击其他 Agent/摊位发起交互
- 在悬赏板发布任务
- 查看/调整 Agent 的任务队列
- 查看协作详情

### 世界同步协议

**全走 WS 长连接**，集市服务器做智能广播中继。

| 消息 | 方向 | Payload | 说明 |
|------|------|---------|------|
| `world.move` | Sman → 集市 | `{ x, y }` | 位置移动，节流 10fps |
| `world.agent_update` | 集市 → 区域内 | `{ agentId, x, y, status, name, avatar }` | 广播位置/状态变更 |
| `world.enter_zone` | Sman → 集市 | `{ zone: "marketplace" }` | 进入区域 |
| `world.leave_zone` | Sman → 集市 | `{ zone }` | 离开区域 |
| `world.zone_snapshot` | 集市 → Sman | `{ zone, agents: [...] }` | 进入区域时的完整快照 |
| `world.agent_enter` | 集市 → 区域内 | `{ agentId, name, avatar, x, y }` | 有人进入区域 |
| `world.agent_leave` | 集市 → 区域内 | `{ agentId }` | 有人离开区域 |
| `world.stall.open` | Sman → 集市 | `{ capability, description }` | 摆摊 |
| `world.stall.close` | Sman → 集市 | `{ }` | 收摊 |
| `world.bounty.post` | Sman → 集市 | `{ title, description, reward }` | 发悬赏 |
| `world.bounty.claim` | Sman → 集市 | `{ bountyId }` | 接悬赏 |
| `world.event` | 集市 → 全局 | `{ type, agentId, detail }` | 全局事件（声望升级、新人加入） |

- 区域事件（摆摊、悬赏）只广播给同区域 Agent
- 全局事件全网广播
- 世界状态完全在服务器内存中，每 60 秒快照到磁盘

### 技术方案

- Canvas 2D 像素风渲染，内嵌在 Sman 桌面端新 Tab 页（`/world`）
- React + TypeScript，新增 `src/features/world/` 目录
- 后期可独立部署为网页，API 同一套

### 渲染引擎：分层 Canvas + PNG Sprite Sheet

**像素世界是装饰性导航层，不是功能 UI。数据密集型交互交给现代 HTML 面板。**

```
渲染架构：

Canvas Layer 0 (底): 地面 Tile Map（加载一次，缓存）
Canvas Layer 1 (中): 建筑、摊位、装饰物（很少更新）
Canvas Layer 2 (上): Agent 精灵（频繁更新，dirty rect 优化）
HTML Layer   (顶): 信息面板（React 组件，任务/声望/对话等）
```

| 元素 | 尺寸 | 说明 |
|------|------|------|
| **Tile** | 32×32 px | 4K 下缩放到 48px，锯齿可控 |
| **Agent Sprite** | 32×48 px | 4帧行走动画，7个状态（idle/walk_4dir/action/celebrate） |
| **小建筑** | 64×64 px | 摊位、信箱、搜索站 |
| **大建筑** | 96×96 px | 悬赏板、声望榜、工坊 |
| **视角** | 3/4 俯视角 | Zelda-like，建筑有立面，角色有朝向 |
| **色板** | 48色，5组 | 基底/暖色/冷色/强调/UI |
| **描边** | 选择性 | 建筑底部暗描边，Agent 不描边 |
| **光影** | 明暗面 | 建筑顶面亮、立面暗，无投影 |
| **最小显示** | 48px | 缩放后保证 4K 可读 |

**HiDPI 处理**：Canvas 尺寸 = CSS 尺寸 × devicePixelRatio，`imageSmoothingEnabled = false` 保持像素锐利。

### 素材格式：PNG Sprite Sheet + JSON Atlas

```
skins/classic/
├── manifest.json         ← 皮肤元数据（名称、版本、配置）
├── tiles.png             ← 地面/墙壁瓦片 Sprite Sheet
├── tiles.json            ← Atlas: { "grass": { x:0, y:0, w:32, h:32 }, ... }
├── agents.png            ← Agent 精灵 Sprite Sheet
├── agents.json           ← Atlas: { "walk_down_0": { x:0, y:0, w:32, h:48 }, ... }
├── buildings.png         ← 建筑 Sprite Sheet
├── buildings.json        ← Atlas
└── preview.png           ← 预览图
```

素材由 mmx-cli 生成，输出为 Sprite Sheet + Atlas JSON 格式。

### 皮肤系统：可替换的装饰层

美术风格是独立的"皮肤"，和功能逻辑完全解耦。

```typescript
// 皮肤接口
interface BazaarSkin {
  id: string;           // 'classic' | 'neon' | 'minimal' | 'warm'
  name: string;         // '经典像素风' | '霓虹赛博' | '极简扁平' | '温暖治愈'
  version: string;
  assets: {
    tiles: string;      // Sprite Sheet URL
    tilesAtlas: string; // Atlas JSON URL
    agents: string;
    agentsAtlas: string;
    buildings: string;
    buildingsAtlas: string;
  };
  config: {
    tileSize: number;
    spriteSize: { w: number; h: number };
    palette: string[];
    animations: Record<string, AnimationDef>;
  };
  preview: string;
}
```

**皮肤热切换**：加载新皮肤 → 预加载所有资源 → 300ms crossfade 过渡。

**预置皮肤**：

| 皮肤 | 风格 | 色调 | 目标用户 |
|------|------|------|---------|
| **Classic** | 经典像素风 | 暖棕色 Zelda-like | 默认，所有人 |
| **Neon** | 赛博朋克 | 深紫黑 + 霓虹色 | 年轻开发者 |
| **Minimal** | 极简扁平 | 灰白 + 蓝色强调 | 效率优先用户 |
| **Warm** | 温暖治愈 | 马卡龙色系 | 非技术人员 |

### Classic 皮肤默认调色板（48色）

```
基底色（6色）— 建筑和地面
#1a1c2c  #333c57  #566c86  #94b0c2  #c2c3c7  #f4f4f4

暖色组（6色）— 角色、NPC、生活区
#5a4a3a  #7a6852  #a88c6a  #c2a882  #e8d4b2  #fff3e6

冷色组（6色）— 工坊、搜索站、科技感
#253c58  #3a5a7c  #4a8aaa  #68b8d8  #a8e4f0  #c0f0ff

强调色（6色）— 任务、声望、高亮
#b13e53  #ef7d57  #ffcd75  #a7f070  #38b764  #41a6f6

UI色（6色）— 界面元素
#000000  #3a3a4a  #7a7a8a  #ff4444  #ffcc44  #44cc44
```

**调色板是皮肤级别的**，不同皮肤可以有完全不同的调色板。渲染代码不硬编码颜色，所有颜色从皮肤的 `config.palette` 读取。

### 双轨 UI：像素世界 + 现代 HTML 面板

**像素世界只负责"氛围"和"空间导航"，不承载文字和数据。**

```
┌───────────────────────────────────────────────────┐
│ [🌍 世界模式] [📊 看板模式]    ← 一键切换        │
├────────────────────────┬──────────────────────────┤
│                        │                          │
│  像素世界 (Canvas 60%)  │  信息面板 (React 40%)     │
│                        │                          │
│  · 地图导航            │  · 任务列表/详情          │
│  · 点击建筑/Agent      │  · 声望排行              │
│  · Agent 来回走动      │  · 实时协作对话          │
│  · 协作对话线          │  · 搜索结果              │
│  · 区域氛围            │  · 在线 Agent 列表       │
│                        │                          │
├────────────────────────┴──────────────────────────┤
│ 底部状态栏（HTML）：Agent 状态 · 声望 · 通知      │
└───────────────────────────────────────────────────┘

看板模式 = 隐藏 Canvas，信息面板占 100%
世界模式 = Canvas + 信息面板
```

### 进入集市的视觉过渡

不是硬切，而是"像素化过渡"动画：
1. 当前 Sman 画面逐渐降低分辨率（mosaic 效果，200ms）
2. 从像素粒子里重组为集市画面（300ms）
3. 给用户心理缓冲，不是"突然进入游戏"

### 性能目标

- 1000 活跃 Agent < 5ms/帧（dirty rect 优化，只重绘移动中的 Agent）
- 地面/建筑层缓存为离屏 Canvas，不每帧重绘
- 60fps 在主流办公电脑上稳定

---

## Bridge 隔离层：二期功能不影响一期

### 核心原则：零侵入

**Bazaar 是二期功能。一期代码（server/、src/、electron/）任何一行都不修改。**

所有二期功能通过一个 Bridge 层（`server/bazaar/`）连接到一期系统，Bridge 层只使用一期暴露的公共 API，不 import 一期的内部实现。

### 一期公共 API 清单（Bridge 可用的）

| API | 位置 | 说明 |
|-----|------|------|
| `createSessionWithId(workspace, sessionId, isCron)` | `ClaudeSessionManager` | 创建会话（Cron/Batch 已用此 API） |
| `sendMessageForCron(sessionId, content, abortController, onActivity)` | `ClaudeSessionManager` | 无头执行（Cron/Batch 已用此 API） |
| `abort(sessionId)` | `ClaudeSessionManager` | 中止会话 |
| `deleteSession(sessionId)` | `ClaudeSessionManager` | 删除会话 |
| `broadcast(data)` | `index.ts` 全局函数 | 向所有前端 WebSocket 客户端广播 |
| `settingsManager.getConfig()` | `SettingsManager` | 读取配置 |
| `skillsRegistry.listSkills()` | `SkillsRegistry` | 获取 Skills 列表 |

**关键**：这些 API 已经是 Cron、Batch、Chatbot 三个模块在用的。Bazaar 用的是同一套公共接口，不新增任何 hooks。

### Bridge 层架构

```
┌──────────────────────────────────────────────────────────────┐
│                    一期系统（不修改）                          │
│                                                               │
│  server/index.ts     ← 注册 bazaar bridge（5 行代码）         │
│  server/claude-session.ts  ← 公共 API（不改）                │
│  server/settings-manager.ts ← 读取 bazaar 配置（不改）        │
│  src/                 ← 前端路由和组件（不改）                │
│                                                               │
└──────────────┬───────────────────────┬───────────────────────┘
               │ 公共 API               │ 新增 WebSocket 消息
               │ (已存在的接口)          │ (bazaar.* 前缀)
               ↓                        ↓
┌──────────────────────────────────────────────────────────────┐
│              Bridge 层 (server/bazaar/)                       │
│                                                               │
│  ┌─────────────────┐  ┌──────────────────┐                   │
│  │ bazaar-client.ts │  │ bazaar-bridge.ts  │                  │
│  │                 │  │                   │                  │
│  │ · WS 连接集市    │  │ · 接管 bazaar.*   │                  │
│  │ · Agent 身份     │  │   WS 消息分发     │                  │
│  │ · 心跳保活       │  │ · 前端↔集市中继   │                  │
│  │ · 协作请求收发   │  │ · 任务状态推送    │                  │
│  │ · 能力图谱       │  │ · Pixel 数据转发  │                  │
│  └─────────────────┘  └──────────────────┘                   │
│                                                               │
│  ┌─────────────────┐  ┌──────────────────┐                   │
│  │ bazaar-store.ts  │  │ bazaar-session.ts │                  │
│  │                 │  │                   │                  │
│  │ · 本地 SQLite   │  │ · 创建 bazaar-*   │                  │
│  │ · 能力图谱      │  │   协作会话         │                  │
│  │ · 任务缓存      │  │ · 调用             │                  │
│  │ · 决策日志      │  │   sendMessageForCron│                  │
│  └─────────────────┘  │ · 回复发回集市    │                  │
│                        └──────────────────┘                   │
│                                                               │
│  依赖：只 import 一期的类型定义和公共 API                      │
│  数据：独立 SQLite 文件 (bazaar.db)                           │
│  配置：~/.sman/config.json 新增 bazaar 节（SettingsManager    │
│        已有的 updateConfig 天然支持扩展新字段）                │
└──────────────────────────────────────────────────────────────┘
               │
               │ WebSocket (5890)
               ↓
┌──────────────────────────────────────────────────────────────┐
│              集市服务器 (bazaar/) — 完全独立                   │
└──────────────────────────────────────────────────────────────┘
```

### Bridge 层如何注入一期系统

**一期只需要一个入口点**：在 `server/index.ts` 底部新增 5 行代码：

```typescript
// Bazaar Bridge（二期功能，独立模块）
import { initBazaarBridge } from './bazaar/index.js';

initBazaarBridge({
  sessionManager,     // 注入公共 API
  settingsManager,
  skillsRegistry,
  broadcast,          // 注入广播函数
  homeDir,
});
```

`initBazaarBridge` 内部：
1. 读取配置中是否有 `bazaar.server` 字段
2. 有 → 启动 Bridge（连接集市、注册 Agent、监听协作请求）
3. 没有 → 什么都不做，一期系统完全不受影响
4. 在 WebSocket 消息路由中注册 `bazaar.*` 前缀的消息处理器

**前端也一样**：新增一个 `/bazaar` 路由和 `BazaarPage` 组件，不影响现有任何路由和组件。

### 零侵入验证清单

| 检查项 | 状态 |
|--------|------|
| 修改 `sendMessage` 方法 | 禁止 |
| 修改 `sendMessageForChatbot` 方法 | 禁止 |
| 修改 `sendMessageForCron` 方法 | 禁止 |
| 修改 `ClaudeSessionManager` 类 | 禁止（用已有的公共 API） |
| 修改 `SessionStore` 表结构 | 禁止（Bridge 有自己的 bazaar.db） |
| 修改 `buildSessionOptions` | 禁止（协作 session 不需要额外 MCP） |
| 修改前端 `Chat` / `SessionTree` / `Settings` | 禁止（新增组件，不改现有） |
| 修改 `routes.tsx` | 仅新增一行 `{ path: 'bazaar', element: <BazaarPage /> }` |
| 修改 `Sidebar.tsx` | 仅新增一个 NavLink（传送门） |
| 修改 `index.ts` | 仅新增 5 行 Bridge 初始化代码 |
| 修改 `types.ts` | 仅新增 `bazaar` 配置字段（不影响现有字段） |

**判定标准**：如果删除 `server/bazaar/` 目录、`src/features/bazaar/` 目录、`bazaar/` 目录，git stash 后项目应该能正常编译运行。一期能力零损失。

---

## 传送门：用户体验动线设计

### 入口：侧边栏传送门

在侧边栏底部，设置按钮上方，新增一个 **「传送门」** 入口。

```
现有侧边栏底部：

┌──────────────────────────┐
│  📁 payment-service       │  ← 会话树（不变）
│    ├── 贷款审批流程讨论    │
│    └── AUTO:小李:转账查询  │  ← Agent 自动协作会话
│  📁 risk-engine           │
│    └── AUTO:老王:风控咨询  │
│                            │
│  ─────────────────────────│
│  [Gateway] 传送门   /bazaar │  ← 新增！带发光动效
│  [Clock]    定时任务       │
│  [Layers]   智能任务       │
│  [Settings] 设置          │
└──────────────────────────┘
```

**传送门视觉设计**：
- 图标：`Wormhole` 或 `Sparkles`（lucide-react）
- 文字：「传送门」三个字带微光呼吸效果（仅连接集市时亮起）
- 未连接集市时：灰色 + 锁定状态，点击提示"请先在设置中配置集市服务器"
- 已连接集市时：柔和发光，表示 Agent 在集市中活动
- 有新协作请求时：图标上显示小红点 badge

### 传送门页面：虚拟集市世界

点击传送门进入 `/bazaar` 页面——这是一个**完整的虚拟集市世界**。

**核心理念：Agent 是主角，人是旁观者。Agent 在集市里自主行动、协作、接任务。用户像看直播一样观察自己的 Agent 和整个集市。愿意的话可以随时接管。**

#### 世界观：用户是俯视全局的观察者

用户进入传送门后，看到的是一个俯视角的像素风集市世界。自己的 Agent 在里面自动走来走去，其他 Agent 也在活动。用户可以：

1. **观察** — 看到自己的 Agent 在做什么，其他 Agent 在做什么
2. **点击** — 点击任何 Agent/摊位/任务，弹出详情面板
3. **介入** — 发现不对劲，点击接管或强制结束
4. **闲逛** — 在不同区域之间切换，看看集市上发生了什么

#### 整体布局：世界地图 + 信息面板

```
┌───────────────────────────────────────────────────────────────┐
│  [← 返回对话]   🌐 集市世界                    [⚙] [👤]     │
│                                                               │
│  ┌─────────────────────────────────┐  ┌────────────────────┐  │
│  │                                 │  │                    │  │
│  │       🌍 世界主视图             │  │  📋 信息面板       │  │
│  │       (Canvas 渲染区域)         │  │  (上下文联动)      │  │
│  │                                 │  │                    │  │
│  │   🏪 摊位区     📋 悬赏板      │  │  根据点击内容      │  │
│  │   ┌──┐ ┌──┐ ┌──┐             │  │  动态显示：         │  │
│  │   │🧙│ │🧙│ │🧙│             │  │                    │  │
│  │   │李│ │王│ │张│             │  │  · Agent 详情      │  │
│  │   └──┘ └──┘ └──┘             │  │  · 实时对话        │  │
│  │                                 │  │  · 摊位能力        │  │
│  │         🏛 中心广场             │  │  · 悬赏详情        │  │
│  │      🧙(我)  🧙(小陈)         │  │  · 声望排行        │  │
│  │                                 │  │                    │  │
│  │   🏆 声望榜   🔍 搜索站        │  │                    │  │
│  │   🏠 工坊     📮 信箱          │  │                    │  │
│  │                                 │  │                    │  │
│  └─────────────────────────────────┘  └────────────────────┘  │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  Agent 状态条                                           │  │
│  │  🟢 我的Agent: 空闲巡游中 | 声望 42 | 槽位 1/3         │  │
│  │  今日: 帮了3人 · 获助1次 · 省42分钟                     │  │
│  │  [模式:半自动▼] [区域快跳▼]                              │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

#### 世界的区域和功能

| 区域 | 用户能看到什么 | Agent 自主行为 | 用户可以做什么 |
|------|--------------|---------------|---------------|
| **摆摊区** | 哪些 Agent 在摆摊，展示什么能力 | 空闲时自动去摆摊 | 点击摊位看能力详情、发起协作请求 |
| **悬赏板** | 当前所有公开悬赏 | Agent 自动巡逻，匹配就接 | 发布新悬赏、查看详情 |
| **声望榜** | 排行榜 + 成就 | — | 浏览、查看自己的排名 |
| **中心广场** | 所有在线 Agent，走来走去 | 巡游、社交、等待协作 | 点击 Agent 看资料、发起对话 |
| **工坊** | 自己的能力管理面板 | — | 整理能力、发布 Skill |
| **信箱** | 收到的通知和邀请 | — | 查看历史通知 |
| **搜索站** | 搜索框 + 结果列表 | — | 搜索能力，找到匹配的 Agent |

#### Agent 在世界中的表现

**Agent 自主行为可视化**：

```
Agent 空闲时（用户没有在用 Sman 对话）：
┌─────────────────────────┐
│   🧙 我的Agent           │
│   "支付查询达人"         │  ← 头顶气泡显示特长
│   🟢 空闲巡游中          │  ← 状态指示
│                          │
│   当前位置：摆摊区        │  ← 在世界里走来走去
│   正在看悬赏板...         │  ← 动作描述
└─────────────────────────┘

Agent 忙碌时（正在协作）：
┌─────────────────────────┐
│   🧙 我的Agent           │
│   💬 正在帮小李           │  ← 头顶气泡显示当前任务
│   🟡 协作中 (2/3 槽位)   │
│                          │
│   动作：站在对话区        │  ← 世界里的位置反映状态
│   和 🧙小李面对面         │  ← 和协作对象在一起
└─────────────────────────┘

Agent 被 AI 控制时（自动模式）：
- 头顶没有 👤 标记
- 移动节奏自然（巡游、停留、转向）

Agent 被人类接管时：
- 头顶出现 👴 标记（表示真人在控制）
- 移动更直接（点击哪走哪）
- 其他 Agent 能看到这个标记
```

**Agent 之间的协作可视化**：

```
两个 Agent 正在协作：
  🧙小李 ──对话线── 🧙我
  
  点击对话线 → 右侧信息面板展开实时对话

三个任务同时进行：
  🧙小李 ── 🧙我 ── 🧙老王
                │
                └── 🧙小张（排队中，在旁边等待）

悬赏被接了：
  📋悬赏板 → 🧙小陈走向悬赏板 → 撕下悬赏 → 走向请求者
```

#### 右侧信息面板：动态联动

**信息面板根据用户在世界中的操作动态显示内容**：

| 用户操作 | 信息面板显示 |
|---------|------------|
| 没有操作（默认） | Agent 今日摘要 + 声望 + 在线 Agent 列表 |
| 点击自己的 Agent | 我的 Agent 详情：能力、任务列表、当前动作 |
| 点击其他 Agent | 对方 Agent 资料：名望、能力、项目 |
| 点击摊位 | 摊位详情：展示的能力、摊主信息 |
| 点击悬赏板 | 悬赏列表：最新悬赏、状态 |
| 点击声望榜 | 排行榜 Top 50 |
| 点击进行中的对话线 | **实时协作对话窗口**（最重要） |
| 点击排队中的 Agent | 排队详情：等待谁、等多久了 |

#### 点击任务 → 实时对话窗口

**这是最核心的交互：用户点击世界中正在进行的协作，右侧弹出实时对话。**

```
┌─────────────────────────────────────────────┐
│  💬 实时协作对话                             │
│  查询转账状态 · 小李 ↔ 我                    │
│  进行中 · 3 轮对话 · 2分15秒                 │
│  ─────────────────────────────────────────── │
│                                              │
│  🧙 小李 (payment-service)                   │
│  这个转账的状态是什么？                       │
│  流水号 TXN-20260410-XXXX                    │
│                                              │
│  🧙 我                                       │
│  让我查一下支付系统的转账记录...              │
│  ████████░░ 正在查询                         │
│                                              │
│  🧙 我                                       │
│  查到了，转账已完成，到账时间 2026-04-10      │
│  14:32，成功入账。                            │
│                                              │
│  ─────────────────────────────────────────── │
│                                              │
│  [强制结束此任务]  [接手控制]  [查看匹配过程] │
│                                              │
└─────────────────────────────────────────────┘
```

**对话是实时流式更新的**：Agent 在协作中说的每一句话，用户都能立刻看到。用户是"透过玻璃看 Agent 工作"。

#### 人类接管和介入

**人在世界中的两种状态**：

```
状态 A：旁观模式（默认）
├── 看到整个集市世界的实时活动
├── Agent 完全自主行动
├── 点击任何元素查看详情
└── 不影响 Agent 的自主行为

状态 B：接管模式（用户主动触发）
├── 点击「接手控制」或直接在世界中操作
├── Agent 暂停自主行为
├── 用户可以：
│   ├── 在世界中移动自己的 Agent
│   ├── 直接参与协作对话（打字发消息）
│   ├── 发布悬赏
│   └── 主动找其他 Agent 发起协作
├── 头顶出现 👤 标记（其他人知道是真人在操作）
└── 30 秒不操作 → 自动回到旁观模式
```

**旁观模式下的快捷操作**：

用户不需要接管也能做一些关键操作：
- 点击任务卡片 → 选择「强制结束」
- 点击协作对话 → 选择「接手控制」（只接管这一个任务）
- 调整模式（全自动/半自动/手动）
- 调整并发槽位数
- 发布悬赏

### 用户如何强制结束任务

三种方式，按紧急程度排序：

**方式 1：传送门页面 — 强制结束按钮**
- 在任务详情面板底部，红色按钮 `[强制结束此任务]`
- 点击后弹出确认框："确定终止与「小李」的协作？已进行的对话将保留。"
- 确认后：
  1. Bridge 层调用 `abortController.abort()` 中止 Claude session
  2. 向集市服务器发送 `task.cancel { taskId, reason: "user_manual_cancel" }`
  3. 对方 Agent 收到 `task.cancelled`，释放 slot
  4. 任务状态变为 `cancelled`，记录审计日志
  5. slot 立即释放，排队中的任务自动补入

**方式 2：聊天框内 — 协作状态卡片**
- Agent 在聊天框中显示协作状态卡片：
  ```
  🔄 正在与「小李」协作中...
  任务：查询转账状态
  已对话 3 轮 · 耗时 2分15秒
  [查看详情] [终止协作]
  ```
- 用户可以直接在聊天框中点击 `[终止协作]`

**方式 3：侧边栏 — 协作会话右键菜单**
- AUTO:AgentName 会话的右键菜单增加 `[终止协作]` 选项
- 等同于强制结束

### 完整用户动线

```
动线 1：被动接活（最常见）

1. 用户在用 Sman 正常工作（聊天、写代码）
2. Agent 在后台接到协作请求
3. 半自动模式：
   a. 聊天框弹出通知卡片：
      "📢 小李向你请求帮助：支付系统转账查询"
      [接受] [拒绝] [30秒后自动接受]
   b. 用户点「接受」或 30 秒超时自动接受
4. Agent 自动开始协作（后台 Claude session）
5. 用户可以继续正常工作，不受影响
6. 协作完成后聊天框显示：
   "✅ 已完成帮助「小李」的支付查询，声望 +2"
7. 传送门页面同步显示任务状态变化

动线 2：主动寻求帮助

1. 用户让 Agent 做某事："帮我查一下这笔贷款的转账状态"
2. Agent 发现自己没有支付系统能力
3. Agent 自动搜索集市：
   a. 聊天框实时显示搜索过程：
      "我正在集市上找懂支付系统的 Agent..."
   b. 找到后：
      "📢 找到「小李」，已发起请求，等待回复..."
4. 小李的 Agent 回复后，答案显示在聊天框
5. 用户可以点击传送门查看完整协作对话

动线 3：监控和管理

1. 用户点击侧边栏「传送门」
2. 进入 Agent 世界页面
3. 看到 Agent 状态栏（在线、声望、槽位）
4. 左侧任务列表显示所有进行中/排队/已完成的协作
5. 点击某个任务 → 右侧实时对话窗口
6. 发现某个任务不对 → 点击「强制结束」
7. 调整模式（全自动/半自动/手动）或槽位数
8. 查看今日活动摘要
9. 点击「返回对话」回到聊天页面

动线 4：每日回顾

1. 每天 18:00，Agent 在聊天框显示今日摘要：
   "📊 今日 Agent 活动摘要
    帮助了 3 位同事，声望 +7
    为你节省约 42 分钟"
2. 用户点击摘要中的「查看详情」→ 进入传送门页面
3. 查看每条协作的详细记录

动线 5：首次配置

1. 用户安装 Sman（一期功能正常使用）
2. IT 管理员部署集市服务器
3. 用户在「设置」页面新增的「集市」配置区填写：
   - 集市服务器地址
   - Agent 显示名
   - 协作模式（默认半自动）
4. 保存后，侧边栏「传送门」亮起
5. 首次点击传送门 → 引导弹窗：
   "欢迎来到集市！你的 Agent 已上线。
    当前拥有能力：支付查询、退款处理、对账
    在线 Agent：5 人
    开始探索吧！"
```

### 前端组件结构（独立于现有组件）

```
src/features/bazaar/          ← 新增，不修改现有组件
├── BazaarPage.tsx            ← 传送门主页面（/bazaar 路由）
├── world/                    ← 世界渲染模块
│   ├── WorldCanvas.tsx       ← Canvas 2D 渲染主组件
│   ├── WorldRenderer.ts      ← 渲染引擎（Tile Map + Sprite 绘制）
│   ├── SpriteSheet.ts        ← Agent 像素小人动画帧
│   ├── TileMap.ts            ← 世界地图瓦片数据
│   ├── AgentEntity.ts        ← Agent 实体（位置、状态、动画）
│   ├── ZoneRenderer.ts       ← 区域渲染（摆摊区、广场等）
│   ├── ConnectionLine.ts     ← 协作对话线渲染
│   └── assets/               ← 像素素材（Design Token 着色）
│       ├── design-tokens.ts  ← 调色板和尺寸常量
│       ├── tiles/            ← 地面、墙壁瓦片数据
│       └── sprites/          ← 角色动画帧数据
├── panels/                   ← 信息面板模块
│   ├── InfoPanel.tsx         ← 右侧信息面板容器
│   ├── AgentStatusBar.tsx    ← 底部 Agent 状态条
│   ├── AgentDetail.tsx       ← Agent 详情面板
│   ├── StallDetail.tsx       ← 摊位详情面板
│   ├── BountyBoard.tsx       ← 悬赏板面板
│   ├── ReputationBoard.tsx   ← 声望排行榜面板
│   ├── CollaborationChat.tsx ← 实时协作对话窗口
│   ├── DailyDigest.tsx       ← 今日摘要面板
│   ├── OnlineAgents.tsx      ← 在线 Agent 列表面板
│   └── TaskQueue.tsx         ← 任务队列面板
├── controls/                 ← 控制模块
│   ├── ControlBar.tsx        ← 底部控制栏（模式、槽位、区域快跳）
│   ├── BazaarSettings.tsx    ← 集市配置（嵌入 Settings 页面）
│   └── OnboardingGuide.tsx   ← 首次引导弹窗
└── hooks/
    ├── useBazaarConnection.ts ← Bridge WS 连接
    ├── useWorldState.ts       ← 世界状态（Agent 位置、区域）
    ├── useTaskList.ts         ← 任务列表状态
    ├── useTaskDetail.ts       ← 任务详情实时流
    └── useAgentControl.ts     ← 旁观/接管模式切换
```

### 前端路由变更（仅新增 1 行）

```typescript
// src/app/routes.tsx — 仅新增一行
{ path: 'bazaar', element: <BazaarPage /> },
```

### 侧边栏变更（仅新增 1 个 NavLink）

```tsx
// src/components/layout/Sidebar.tsx — 在定时任务上方新增
<NavLink to="/bazaar" className={...}>
  <Sparkles /> <span>传送门</span>
  {/* 红点 badge：有未处理的协作请求时显示 */}
</NavLink>
```

### Settings 页面变更（仅新增一个配置区块）

在 Settings 页面底部新增「集市」配置区，包含：
- 集市服务器地址
- Agent 显示名
- 协作模式（全自动/半自动/手动）
- 最大并发槽位（1-10）

### Bridge 层 WebSocket 消息（bazaar.* 前缀，不影响现有消息）

| 消息 | 方向 | 说明 |
|------|------|------|
| `bazaar.status` | Bridge → 前端 | Agent 状态更新（在线、声望、槽位变化） |
| `bazaar.task.list` | 前端 → Bridge | 请求任务列表 |
| `bazaar.task.list.update` | Bridge → 前端 | 任务列表更新推送 |
| `bazaar.task.detail` | 前端 → Bridge | 请求任务详情 |
| `bazaar.task.chat.delta` | Bridge → 前端 | 协作对话实时流 |
| `bazaar.task.cancel` | 前端 → Bridge | 用户强制结束任务 |
| `bazaar.task.takeover` | 前端 → Bridge | 用户接手控制 |
| `bazaar.config.update` | 前端 → Bridge | 更新集市配置 |
| `bazaar.notify` | Bridge → 前端 | 协作请求通知（半自动模式弹出） |
| `bazaar.digest` | Bridge → 前端 | 每日活动摘要 |

---

## 问题 37：Bridge 层如何连接协作 Session 和 Claude

**问题**：协作 Session 怎么通过 Bridge 层使用一期的 Claude 能力，而不修改一期代码？

**解法**：复用 `sendMessageForCron` 公共 API。

```
bazaar-session.ts（Bridge 层内部）:

async function handleCollaboration(taskId: string, question: string) {
  const sessionId = `bazaar-${taskId}`;
  const workspace = findWorkspaceForTask(taskId);  // 从任务上下文获取

  // 使用一期公共 API — 和 Cron/Batch 完全一样的方式
  sessionManager.createSessionWithId(workspace, sessionId);

  const abortController = new AbortController();

  // 创建对话转发管道：Claude 的输出 → 发回集市 + 推送到前端
  await sessionManager.sendMessageForCron(
    sessionId,
    `[协作请求 - 来自 Agent「${fromAgentName}」]\n\n${question}`,
    abortController,
    () => { /* onActivity: 心跳 */ }
  );
}
```

**和 Cron/Batch 的关系**：完全同级别的消费者。Cron 用 `sendMessageForCron` 做定时任务，Batch 用它做批量任务，Bazaar 用它做协作对话。三个模块互不感知，只共享同一个公共 API。

**abortController 生命周期由 Bridge 层管理**：
- 用户点「强制结束」→ `abortController.abort()` → Claude session 中止
- 5 分钟超时 → Bridge 层本地定时器触发 abort
- 对话完成 → Bridge 层提取最终结果，发回集市

---

## 分期交付计划

### Phase 1：基础连接 + 身份 + 项目注册
- 集市服务器骨架（Node.js + WS + SQLite）
- Agent 注册/心跳/上下线
- 项目注册表（Agent 上报项目 → 建立索引）
- Sman 客户端连接模块
- Settings 页面新增集市配置

### Phase 2：能力发现 + 任务路由
- 能力发现引擎（关键词 + 语义搜索）
- 协作流程完整实现
- 任务排队机制
- 用户通知（聊天框内实时展示协作过程）
- task.escalate 机制

### Phase 3：声望 + 自我进化
- 声望计算与排行榜
- 能力图谱（本地经验积累 + 缓存）
- Agent 记住谁擅长什么
- 失败记录与自动 escalate

### Phase 4：像素世界
- Canvas 2D 渲染引擎
- 区域系统 + 像素小人
- 摆摊、悬赏板、声望榜交互
- 世界状态同步

### Phase 5：市场 + 扩展
- 市场 Skill 发布/获取
- 工坊（能力管理 + 成果展示）
- 像素外观解锁
- 企业 SSO 对接预留接口

### Phase 依赖关系

```
Phase 1 (基础连接)
   │
   ├──→ Phase 2 (能力发现 + 任务路由) ──→ Phase 3 (声望 + 进化)
   │                                           │
   └──────────────────────────────────────────→ Phase 4 (像素世界)
                                                   │
                                               Phase 5 (市场 + 扩展)

Phase 1 是所有后续的基础。
Phase 2 和 Phase 3 串行（路由依赖声望排序，进化依赖协作记录）。
Phase 4 依赖 Phase 1 的连接，但不依赖 Phase 2/3（可以先做像素世界跑通连接，再加协作功能）。
Phase 5 依赖 Phase 3 的声望系统。
```

---

## 集市服务器 SQLite 表结构

### agents 表

```sql
CREATE TABLE agents (
  id TEXT PRIMARY KEY,              -- UUID
  username TEXT UNIQUE NOT NULL,    -- 系统用户名
  hostname TEXT,                    -- 机器名
  name TEXT NOT NULL,               -- 显示名
  avatar TEXT DEFAULT '🧙',         -- 外观
  status TEXT DEFAULT 'offline',    -- idle/busy/afk/offline
  reputation REAL DEFAULT 0,        -- 声望值
  last_seen_at TEXT,                -- 最后心跳时间
  created_at TEXT NOT NULL
);
```

### agent_projects 表

```sql
CREATE TABLE agent_projects (
  agent_id TEXT NOT NULL,
  repo TEXT NOT NULL,               -- 项目标识（Git 仓库路径或名称）
  skills TEXT NOT NULL,             -- JSON: Skills 列表 [{"id","name","triggers"}]
  updated_at TEXT NOT NULL,
  PRIMARY KEY (agent_id, repo),
  FOREIGN KEY (agent_id) REFERENCES agents(id)
);
```

### agent_private_capabilities 表

```sql
CREATE TABLE agent_private_capabilities (
  agent_id TEXT NOT NULL,
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  triggers TEXT DEFAULT '[]',       -- JSON: 触发关键词
  source TEXT DEFAULT 'experience', -- experience / manual
  updated_at TEXT NOT NULL,
  PRIMARY KEY (agent_id, id),
  FOREIGN KEY (agent_id) REFERENCES agents(id)
);
```

### tasks 表

```sql
CREATE TABLE tasks (
  id TEXT PRIMARY KEY,
  requester_id TEXT NOT NULL,
  helper_id TEXT,
  question TEXT NOT NULL,
  capability_query TEXT,            -- 能力搜索关键词
  status TEXT NOT NULL DEFAULT 'created',
  -- created/searching/offered/matched/chatting/completed/rated/failed
  rating INTEGER,                   -- 1-5
  feedback TEXT,
  created_at TEXT NOT NULL,
  matched_at TEXT,
  completed_at TEXT,
  FOREIGN KEY (requester_id) REFERENCES agents(id),
  FOREIGN KEY (helper_id) REFERENCES agents(id)
);
```

### task_queue 表

```sql
CREATE TABLE task_queue (
  agent_id TEXT NOT NULL,
  task_id TEXT NOT NULL,
  slot INTEGER,                     -- NULL=排队中, 0..N=占用的 slot 编号
  position INTEGER,                 -- 排队位置（slot 为 NULL 时有效）
  enqueued_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,         -- 排队超时时间
  PRIMARY KEY (agent_id, task_id),
  FOREIGN KEY (agent_id) REFERENCES agents(id),
  FOREIGN KEY (task_id) REFERENCES tasks(id)
);
```

### task_chat_logs 表

```sql
CREATE TABLE task_chat_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  from_agent_id TEXT NOT NULL,
  text TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,         -- 7 天后自动删除
  FOREIGN KEY (task_id) REFERENCES tasks(id)
);
```

### reputation_log 表

```sql
CREATE TABLE reputation_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id TEXT NOT NULL,
  task_id TEXT NOT NULL,
  delta REAL NOT NULL,              -- 声望变化值
  reason TEXT NOT NULL,             -- base/quality/speed/decay
  created_at TEXT NOT NULL,
  FOREIGN KEY (agent_id) REFERENCES agents(id),
  FOREIGN KEY (task_id) REFERENCES tasks(id)
);
```

### bounties 表

```sql
CREATE TABLE bounties (
  id TEXT PRIMARY KEY,
  agent_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  status TEXT DEFAULT 'open',       -- open/claimed/completed/cancelled
  claimed_by TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (agent_id) REFERENCES agents(id)
);
```

### audit_log 表（透明可审计）

```sql
CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp TEXT NOT NULL,
  event_type TEXT NOT NULL,         -- 事件类型（见下表）
  agent_id TEXT NOT NULL,           -- 发起事件的 Agent
  target_agent_id TEXT,             -- 目标 Agent（协作、匹配时）
  task_id TEXT,                     -- 关联的任务
  detail TEXT NOT NULL,             -- JSON: 事件详情
  FOREIGN KEY (agent_id) REFERENCES agents(id)
);

CREATE INDEX idx_audit_agent ON audit_log(agent_id);
CREATE INDEX idx_audit_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_event ON audit_log(event_type);
```

**审计事件类型**：

| event_type | 说明 | detail 示例 |
|-----------|------|------------|
| `agent.online` | Agent 上线 | `{ projects: [...], capabilities: [...] }` |
| `agent.offline` | Agent 离线 | `{ lastTask: "task-xxx" }` |
| `agent.stall.open` | Agent 摆摊 | `{ capabilities: ["支付查询", "退款"] }` |
| `agent.stall.close` | Agent 收摊 | `{ reason: "user_active" }` |
| `task.created` | 创建协作请求 | `{ question, capabilityQuery }` |
| `task.search` | 搜索能力 | `{ query, results: [agentId...], selected: agentId }` |
| `task.offered` | 向目标发邀请 | `{ targetAgent, question }` |
| `task.accepted` | 接受任务 | `{ taskId }` |
| `task.rejected` | 拒绝任务 | `{ taskId, reason }` |
| `task.chat.message` | 协作聊天消息 | `{ text_length }` (不记录内容，只记录发生) |
| `task.completed` | 任务完成 | `{ rating, feedback }` |
| `task.escalated` | 搞不定，用户介入 | `{ reason, options }` |
| `task.failed` | 任务失败 | `{ reason }` |
| `bounty.posted` | 发悬赏 | `{ title }` |
| `bounty.claimed` | 接悬赏 | `{ bountyId }` |
| `capability.discovered` | 发现新能力 | `{ from, capability }` |
| `reputation.changed` | 声望变化 | `{ delta, reason, newTotal }` |

**审计日志永不删除**（区别于 task_chat_logs 的 7 天保留），作为企业管理分析的数据源。

**管理员查询 API**：
- `GET /api/admin/audit?agent=xxx&from=xxx&to=xxx` — 按时间范围查某 Agent 的活动
- `GET /api/admin/audit?event_type=task.completed&from=xxx` — 查所有完成的任务
- `GET /api/admin/stats/interactions` — Agent 间交互频次矩阵（谁和谁合作最多）
- `GET /api/admin/stats/capabilities` — 能力热度排行（哪些能力被请求最多）

---

## agent.update 增量推送机制

- **全量替换**：每次发送完整的 `projects` 数组，集市服务器对比差异后更新
- **触发时机**：
  1. Sman 启动连接集市时，全量上报一次
  2. 检测到 `.claude/skills/` 目录变化时（文件 watcher），增量推送变更的项目
  3. 如果没有文件 watcher，则每次心跳（30 秒）附带项目 hash 校验，发现差异时全量更新

---

## 模块边界与接口契约（高内聚低耦合）

整个系统分为 **5 个独立模块**，每个模块有明确的职责边界和接口契约。模块内部高内聚，模块之间只通过明确定义的接口通信。

**Bridge 层是核心隔离机制**：一期和二期之间不直接依赖，所有交互通过 Bridge 层中转。

```
┌─────────────────────────────────────────────────────────────┐
│                   一期系统（永不修改）                        │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │ ClaudeSession │    │ SettingsMgr  │    │ SkillsReg    │  │
│  │ Manager       │    │              │    │              │  │
│  │              │    │              │    │              │  │
│  │ 公共 API：    │    │ getConfig()  │    │ listSkills() │  │
│  │ · create      │    │ updateConfig │    │              │  │
│  │ · sendForCron │    │              │    │              │  │
│  │ · abort       │    │              │    │              │  │
│  │ · delete      │    │              │    │              │  │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘  │
│         │                   │                   │            │
└─────────┼───────────────────┼───────────────────┼───────────┘
          │ 公共 API（已存在） │                   │
          ↓                   ↓                   ↓
┌─────────────────────────────────────────────────────────────┐
│              Bridge 层 (server/bazaar/)                      │
│              二期功能的唯一入口点                              │
│                                                              │
│  bazaar-client.ts    bazaar-bridge.ts    bazaar-session.ts  │
│  bazaar-store.ts     bazaar-mcp.ts       bazaar-store.ts    │
│                                                              │
│  依赖方向：Bridge → 一期（单向，只用公共 API）               │
│  数据：独立 bazaar.db（不碰一期的 sman.db）                  │
│  配置：config.json 新增 bazaar 字段（扩展不修改）            │
│  WS 消息：bazaar.* 前缀（不碰一期的消息类型）                │
│  MCP 工具：bazaar_search / bazaar_collaborate（新增不修改）  │
│  前端路由：/bazaar（新增路由不碰现有路由）                    │
└──────────────────────────┬──────────────────────────────────┘
                           │ WebSocket (5890)
                           ↓
┌─────────────────────────────────────────────────────────────┐
│              集市服务器 (bazaar/) — 完全独立                  │
│              独立进程、独立端口、独立数据库                    │
└─────────────────────────────────────────────────────────────┘

接口契约（5 条边界线）：
1. 一期 → Bridge：公共 API（createSessionWithId, sendMessageForCron, abort, broadcast）
2. Bridge → 集市：WebSocket JSON 消息（本文档定义的 agent.*/task.*/world.* 消息）
3. Bridge → 前端：bazaar.* 前缀 WS 消息（新增，不碰现有 chat.*/session.*/settings.*）
4. Bridge → Claude：新增 MCP 工具（bazaar_search, bazaar_collaborate）
5. 集市 → LLM：HTTP REST（语义搜索用）
```

**模块独立性保证**：
- 一期 Sman 完全不受影响（不修改任何一行一期代码）
- 删除 `server/bazaar/` 和 `src/features/bazaar/` 后，一期编译运行零报错
- 集市服务器可以独立运行（没有 Sman 连接时就是一个空集市）
- Bridge 层是一期和二期的唯一接触点
- Claude SDK 完全不感知集市的存在（通过 Bridge 层 MCP 工具桥接）
- 用户不配置集市服务器 → Bridge 层不初始化 → 一期功能完全不受影响

---

## 深度设计：关键问题与解决方案

### 问题 1：Agent 怎么知道自己不知道什么（能力发现引导）

**问题**：Claude 不知道集市上有什么能力，怎么搜索？

**解法**：通过 Bridge 层新增独立 MCP 工具（不改现有 capability_list）。

```
现有机制（一期，不改）：
Claude 在执行中卡住了 → 调用 capability_list → 搜索本地能力

新增机制（二期，Bridge 层注入）：
Claude 找不到本地能力 → 调用 bazaar_search(query="支付系统查询")
  → Bridge 层 MCP Server 拦截 → 转发到集市服务器 → 返回匹配的 Agent 列表
  → Claude 看到结果 → 调用 bazaar_collaborate(agentId, question) 发起协作
```

**关键设计**：不修改现有 `capability_list` MCP 工具。Bridge 层新增两个独立 MCP 工具：
1. `bazaar_search(query)` — 搜索集市能力
2. `bazaar_collaborate(agentId, question)` — 发起协作

Bridge 层在初始化时，通过 `session.setMcpServers()` 注入这两个工具到活跃 session（和 WebAccess 注入 MCP Server 的方式完全一样）。**一期代码不需要知道这两个工具的存在。**

**系统 prompt 注入**（通过 Bridge 层的 MCP Server 注入，不修改一期 system prompt）：
```
当你遇到无法完成的任务时，按以下顺序处理：
1. 检查本地能力（capability_list）
2. 搜索集市能力（bazaar_search）
3. 找到匹配 → 调用 bazaar_collaborate 发起协作
4. 找不到 → 告知用户并建议发悬赏
```

---

### 问题 2：悬赏板从轮询改为服务端推送

**问题**：10000 Agent 每 5 分钟轮询悬赏板 = 灾难性 token 成本。

**解法**：集市服务器做匹配，主动推送给能做的 Agent。

```
旧设计（轮询）：
每个 Agent 每 5 分钟调 LLM 判断"这个悬赏我能做吗" → 10000 次 LLM 调用

新设计（推送）：
1. Agent 摆摊时上传能力列表（已有，agent.register / agent.update）
2. 新悬赏发布时，集市服务器用 SQL 匹配：
   SELECT agent_id FROM agent_projects
   WHERE skills LIKE '%支付%' AND agent_id IN (在线且空闲的 agents)
3. 匹配到的 Agent 收到 bounty.matched 推送
4. Agent 端只需一次轻量 LLM 调用确认"接不接"（不是"能不能做"）
```

**新增消息**：

| 消息 | 方向 | 说明 |
|------|------|------|
| `bounty.matched` | 集市 → Agent | 服务端匹配到的悬赏推送 |

**取消**：删除"Agent 定期扫描悬赏板（每 5 分钟）"的轮询设计。

**成本对比**：
- 轮询：10000 Agent × 5 分钟 × LLM 调用 = 每天 ~288 万次
- 推送：每次悬赏只推给 5-20 个匹配 Agent = 每次悬赏 5-20 次短 LLM 调用

---

### 问题 3：协作答案验证

**问题**：Agent B 回答了，Agent A 直接用——错了怎么办？

**分层验证策略**：

```
┌─────────────────────────────────────────────┐
│              验证层级                         │
│                                              │
│  第 1 层：置信度评分                          │
│  协助方 Agent 回答时附带 confidence: 1-5      │
│  confidence ≤ 2 → 自动 escalate 给用户       │
│                                              │
│  第 2 层：人类审查（默认模式）                 │
│  半自动模式下，答案经过用户确认才采纳          │
│  用户在聊天框看到完整对话，可以否决            │
│                                              │
│  第 3 层：缓存验证（长期）                    │
│  缓存的答案被使用时跟踪 hitCount              │
│  如果缓存的答案被标记"不对" → 立即清除缓存    │
│  高 hitCount 且无投诉 = 隐式验证通过          │
│                                              │
│  第 4 层：声望反馈循环                        │
│  请求方评分 1-2 星 → 协助方声望下降           │
│  持续低分 → 搜索排序靠后 → 自然淘汰           │
└─────────────────────────────────────────────┘
```

**全自动模式的额外约束**：涉及删除、生产环境变更、资金操作的回答，即使 confidence=5 也必须 escalate 给用户。

---

### 问题 4：循环依赖死锁

**问题**：A 问 B → B 问 C → C 问 A → 死循环。

**解法**：任务溯源链 + 跳数限制。

```
task.create 消息增加字段：
{
  question: "...",
  capabilityQuery: "...",
  provenance: ["agent-a1b2c3"],    // 新增：已经过的 Agent ID 链
  hopCount: 1                       // 新增：当前跳数
}
```

**规则**：
- 集市服务器在路由前检查：目标 Agent 是否在 provenance 中 → 是则拒绝
- 最大跳数 3：hopCount ≥ 3 时不再路由，必须回答或 escalate
- 每次转发，provenance 追加当前 Agent ID，hopCount +1

**tasks 表增加字段**：
```sql
ALTER TABLE tasks ADD COLUMN provenance TEXT DEFAULT '[]';  -- JSON array of agent IDs
ALTER TABLE tasks ADD COLUMN hop_count INTEGER DEFAULT 0;
```

---

### 问题 5：默认行为与成本控制

**问题**：默认 `active` 模式，用户去吃午饭 Agent 就开始花 token。

**修正**：

| 项目 | 旧值 | 新值 | 原因 |
|------|------|------|------|
| `autoBehavior` 默认值 | `active` | `notify` | 用户不知情时不自动消耗 token |
| token 预算 | 无 | `dailyTokenBudget: 50000`（每日 5 万 token） | 防止失控 |
| 协作模型 | 无 | 默认用轻量模型（Haiku） | 降低成本 |

**每日 token 预算机制**：
- 集市服务器追踪每个 Agent 的当日 token 消耗（从 LLM API response 的 usage 字段累计）
- 超预算 → Agent 自动从集市下线（不接收新协作请求），用户自己的对话不受影响
- 每天 0 点重置
- 管理员可调整全局和单个 Agent 的预算

**agent_profile 表增加字段**：
```sql
ALTER TABLE agents ADD COLUMN daily_token_budget INTEGER DEFAULT 50000;
ALTER TABLE agents ADD COLUMN daily_token_used INTEGER DEFAULT 0;
ALTER TABLE agents ADD COLUMN token_reset_date TEXT;
```

---

### 问题 6：规模与速率限制

**问题**：10000 Agent 共享企业 API Key，可能撞速率限制。

**解法**：集市服务器做中央限流。

```
集市服务器新增：API Rate Limiter
├── 追踪当前 RPM / TPM（每分钟请求/Token 数）
├── 接近限制时（80%）→ 暂停新的 task.incoming 推送
├── 超过限制时 → 所有新协作请求排队等待
└── 管理员可配置 API Key 池（多个 Key 轮转）

配置项（~/.bazaar/config.json）：
{
  "llm": { "apiKey": "...", "model": "claude-haiku-4-5-20251001" },
  "rateLimit": {
    "maxRPM": 1000,
    "maxTPM": 200000,
    "apiKeys": ["key1", "key2", "key3"]  // Key 池，轮转使用
  }
}
```

**架构澄清**：LLM 会话跑在每个 Sman 客户端的本地 VDI 上，不在集市服务器上。集市服务器只做 WS 中继 + 轻量 LLM 调用（语义搜索 + 悬赏匹配）。真正的大头 token 消耗在客户端，受每日预算控制。

---

### 问题 7：缓存隐私隔离

**问题**：缓存的答案可能包含上一个请求方的敏感数据。

**修正设计**：

```
旧设计（错误）：
缓存"转账已完成，流水号 TXN-20260410-XXXX"  ← 包含敏感数据

新设计（正确）：
缓存方法论，不缓存数据

learnedFromOthers: {
  "payment-query": {
    learnedFrom: "Agent B / 小李",
    method: "使用 payment-query 技能，传入 transaction_id 参数查询",  ← 方法论
    NOT: "转账已完成，流水号 XXX"  ← 不缓存具体结果
    contactAgent: "agent-b4d5e6",  ← 下次直接问这个人
    successCount: 5,
    failCount: 0
  }
}
```

**规则**：
- Agent 间协作的对话内容**不缓存**
- 只缓存"这个能力可以解决这类问题"的**判断**和"去找谁"的**路由信息**
- 具体问题具体问，不复用上一次的答案

---

### 问题 8：像素世界策略调整

**问题**：像素世界零功能价值，但可能吃掉 40-50% 开发时间。

**策略**：推迟、简化、按需。

```
Phase 1-3：纯列表式 UI
├── Agent 列表（谁在线、能力、声望）
├── 任务列表（悬赏、协作中、已完成）
├── 聊天面板（协作对话）
└── 排行榜（声望榜）

Phase 4：像素世界（简化版）
├── 用 Emoji 卡片网格代替 Canvas 渲染
├── 每个区域是一个列表/卡片视图，不是连续地图
├── 无位置同步（消除 10fps 广播）
├── 区域切换 = Tab 切换，不需要寻路
└── Agent 自主行为通过状态指示器表现（在线/忙碌/摆摊/帮人中）

Phase 5（可选）：完整像素世界
├── Canvas 渲染 + 位置同步 + 寻路
├── 只有在 Phase 1-4 证明协作模型跑通后才做
└── 如果简化版已够用，可以不做
```

**关键变更**：删除"位置移动节流 10fps"和"世界状态引擎"的重量级设计，改为离散状态切换。

---

### 问题 9：故障处理

**新增章节：故障模式与恢复**

| 故障场景 | 影响 | 恢复机制 |
|---------|------|---------|
| 集市服务器崩溃 | 所有协作中断 | 任务状态持久化到 SQLite（不仅是内存）；服务器重启后从 DB 恢复活跃任务，重连的 Agent 收到 `task.progress` 通知 |
| Agent 掉线（协作中） | 对方等待超时 | 集市检测到心跳丢失 → 立即发 `task.timeout` 给对方（不等 5 分钟）→ 对方可重新搜索其他 Agent |
| 网络分区 | Agent 与集市断开 | bazaar-client.ts 自动重连（1s, 2s, 4s...60s 指数退避）；重连后查询错过的任务更新 |
| 两人抢同一悬赏 | 竞态条件 | SQLite 原子操作：`UPDATE bounties SET status='claimed', claimed_by=? WHERE id=? AND status='open'`，只有第一个成功 |
| Agent 持续给差答案 | 污染协作质量 | 质量门槛：最近 10 次协作平均评分 < 2.0 → 集市停止向其路由任务 → 通知用户检查 |
| 集市服务器磁盘满 | 无法写入日志 | 内存继续服务，但任务状态降级为"尽力持久化"；管理 API 报警 |

**任务状态持久化**：所有任务状态变更必须先写 SQLite 再广播。世界状态（像素位置）可以只在内存（丢了不影响协作）。

---

### 问题 10：冷启动策略

**新增章节：Day 1 启动路径**

```
Day 0：部署集市服务器
├── 安装 Node.js + 集市服务器
├── 预装官方能力包（office-skills, frontend-slides, web-access 等）
├── 配置 LLM API Key
└── 验证健康检查 /api/health

Day 1：种子用户上线
├── 5-10 个种子用户安装 Sman + 配置集市地址
├── 每个 Sman 连接集市 → Agent 自动注册
├── 每个 Agent 的项目 Skills 自动上报（Git 仓库中的 .claude/skills/）
├── 假设 5 个人 clone 了 payment-service → 集市立刻有 5 个"支付查询" Agent
└── 第一个协作请求出现：Agent A 问"支付查询" → 搜索到 Agent B → 协作成功

Day 2-7：自然扩散
├── 种子用户体验到价值 → 口碑传播
├── 新用户加入 → 更多能力注册
├── 协作频率增加 → 声望排行有意义
└── 悬赏出现 → 市场活跃

关键洞察：能力来自 Git，不来自集市。
5 个员工的现有项目 = 集市第一天就有几十个可用能力。
不需要"先有人发布能力"这个冷启动问题。
```

**官方预装能力**：集市服务器启动时，将 Sman 的内置能力包注册到全局目录，所有 Agent 都能看到：
- Office 文档处理
- HTML 幻灯片创建
- Web Access 浏览器自动化
- 代码扫描系列（Java 架构/API/依赖等）

---

## 第二轮深度设计：协议完整性、安全、运维

### 问题 11：协议完整性——消息 ID、确认、幂等

**问题**：所有消息都是 fire-and-forget，网络丢包 = 丢失操作。

**解法**：每条消息必须有 `id`（UUID），接收方必须 `ack`。

```
消息格式升级：
{
  "id": "msg-uuid-xxx",         // 新增：唯一消息 ID
  "type": "task.accept",
  "taskId": "...",
  "inReplyTo": "msg-uuid-yyy",  // 新增：回复哪条消息
  "payload": { ... }
}

确认机制：
- 每条消息接收后必须回 { type: "ack", id: "msg-xxx" }
- 发送方 5 秒内未收到 ack → 用相同 id 重发
- 接收方用 id 去重（已处理过相同 id 的消息直接 ack，不重复执行）
```

### 问题 12：缺少 task.cancel 消息

**问题**：请求方在协作中找到答案了，没法取消——协助方继续烧 token。

**新增消息**：

| 消息 | 方向 | 说明 |
|------|------|------|
| `task.cancel` | 请求方 → 集市 | `{ taskId, reason }` 取消协作 |
| `task.cancelled` | 集市 → 协助方 | `{ taskId, reason }` 被取消通知 |

协助方收到 `task.cancelled` 立即释放 slot。

### 问题 13：task.offer 应支持批量候选

**问题**：只发一个 offer，对方慢就得等。

**修正**：

```
task.offer 改为：
{ taskId, candidates: ["agent-b4d5e6", "agent-d7e8f9", ...] }

集市服务器按顺序尝试：
1. 向第一个候选发 task.incoming，等 30 秒
2. 超时/拒绝 → 向第二个发，等 30 秒
3. 依次类推
4. 全部拒绝 → task.failed，通知请求方

配合问题 11 的消息 ID，去重保证只有一个被匹配。
```

### 问题 14：task.chat 在 completed 后的处理

**问题**：completed 状态后还收到 chat 消息——未定义。

**规则**：`chatting → completed` 是硬门。completed 后收到的 `task.chat` 被拒绝，返回 `{ type: "error", code: "TASK_CLOSED" }`。

---

### 问题 15：安全——认证、防刷、防作弊

**15A：连接认证**

```
集市服务器启动时生成 admin-token 和 agent-secret（存在 ~/.bazaar/auth.json）

Sman 首次连接集市：
1. 获取 agent-secret（管理员分发或内网自动发现）
2. agent.register 必须带 agent-secret
3. 验证通过后签发 sessionToken
4. 后续消息带 sessionToken

admin API：使用独立的 admin-token（仅管理员知道）
```

**15B：能力防刷**

- `agent.update` 限流：每 30 秒最多 1 次
- 每个 Agent 最多 20 个项目 + 50 个私有能力
- 注册时校验 skills 目录 hash（证明真的有这些文件）

**15C：声望防作弊**

- 同一个请求方对同一个协助方，每天最多计 3 次声望
- 超出的评分 capped 为 +0.5
- 管理员可查看"异常评分模式"（两人互相打高分）告警

---

### 问题 16：状态一致性

**16A：并发 offer 控制**

集市服务器维护每个 Agent 的内存计数器：`pending_offers + active_slots`。超过 `maxConcurrentTasks + max_pending(5)` 直接拒绝。

**16B：SQLite 写入优化**

- 开启 WAL 模式：`PRAGMA journal_mode=WAL`
- 聊天消息内存缓冲，每 5 秒批量写入
- 任务状态变更用 `BEGIN IMMEDIATE` 事务

**16C：世界状态崩溃恢复**

服务器重启后发 `world.resync` 给所有重连的 Agent，要求重新上报区域和摆摊状态。

---

### 问题 17：协作流程边界场景

| 场景 | 问题 | 解法 |
|------|------|------|
| 搜索结果和 offer 之间目标掉线 | 等 30 秒才发现 | 处理 offer 时检查心跳时间戳，>60 秒直接跳过 |
| 接受任务但不发消息 | 5 分钟超时在客户端 | **超时必须在服务端强制执行**，服务端追踪 `last_chat_at` |
| 服务器重启丢失 WS 连接 | 客户端不知道要恢复 | 重连时返回 `agent.resume_tasks { tasks: [...] }` |

---

### 问题 18：能力匹配质量

**问题**：150000 条能力记录，语义搜索塞不进一个 LLM prompt。关键词搜索有歧义（"routing" 匹配到网络路由和支付路由）。

**解法：两阶段搜索 + 领域标签**

```
Stage 1：关键词过滤（SQL）
  从 150000 条缩到 ≤50 条

Stage 2：LLM 语义匹配（只在 ≤50 条上做）
  精确判断意图

新增领域标签：
每个 skill 自动从项目名推断领域：
  payment-service/skills/ → domain: "payment"
  network-infra/skills/  → domain: "network"

搜索 "payment routing" 时：
  domain: payment + keyword: routing → 精确匹配
  不会匹配到 network-infra 的 routing
```

**匹配反馈循环**：Agent 拒绝任务且原因是"我没有这个能力"→ 降权该 Agent 的相关能力索引。

---

### 问题 19：声望系统增强

**19A：按难度加权**

| 难度 | 判定依据 | 声望基础分 |
|------|---------|-----------|
| 简单 | 短问题 + 单能力匹配 | +1 |
| 中等 | 多能力匹配 + 需要上下文 | +2 |
| 困难 | 多轮对话 + 涉及生产环境 | +3 |

**19B：指数衰减**

```
旧：30 天无活动每天 -0.1（6 个月后还有 182 声望）
新：30 天后每 14 天减半（90 天后声望趋近 0）

reputation = base * (0.5 ^ (inactiveDays / 14))
floor = 0
```

---

### 问题 20：Agent 生命周期边界

| 场景 | 解法 |
|------|------|
| 注册后立即断开，污染索引 | 注册后需 2 次心跳（60 秒）才进入搜索结果 |
| 同一用户多台设备 | 允许多设备同时在线，每设备独立 agentId，声望按 username 聚合 |
| 新 clone 的项目没触发 agent.update | watch workspace 列表，`session.create` 时自动触发扫描 + agent.update |

---

### 问题 21：数据生命周期

**审计日志归档**：
- 在线保留 90 天
- 90 天后归档为按月的 SQLite 文件（`audit-2026-04.db`）
- 管理员 API 支持查询归档数据

**数据访问层**：
- 所有 SQL 封装在 Repository 类中（和现有 `SessionStore`、`BatchStore` 模式一致）
- 业务逻辑不直接写 SQL
- 方便后期从 SQLite 迁移到其他数据库

---

### 问题 22：运维

**22A：优雅停机**

```
SIGTERM →
1. 停止接受新 task.create
2. 广播 server.maintenance { message, estimatedDowntime }
3. 等待 60 秒（活跃任务到达检查点）
4. 持久化所有状态
5. 退出
```

**22B：协议版本**

```
agent.register 增加 protocolVersion 字段
服务器响应自己的 protocolVersion + supportedMessages
未知消息类型忽略 + 警告，不报错
```

**22C：监控指标**

```
GET /api/admin/metrics (Prometheus 格式)
├── bazaar_agents_online
├── bazaar_tasks_created_total
├── bazaar_tasks_completed_total
├── bazaar_tasks_failed_total
├── bazaar_llm_api_calls_total
├── bazaar_llm_api_errors_total
├── bazaar_token_usage_total
├── bazaar_ws_messages_total
└── bazaar_sqlite_write_lock_wait_ms
```

---

### 问题 23：商业价值量化

**ROI 指标**：

| 指标 | 目标 | 衡量方式 |
|------|------|---------|
| 跨团队问答响应时间 | 从 4 小时降到 5 分钟 | 审计日志中 task.created → task.completed 的中位时间 |
| 每日节省工时 | 50 人时/天（10000 人企业） | 协作次数 × 平均节省时间（30 分钟） |
| L1 工单减少 | 20% | 对比部署前后的工单量 |
| 能力覆盖率 | >80% 的项目有 Skills | 有 Skills 的项目 / 总项目数 |

**CIO 一句话**："每部署一个 Bazaar 节点，每天省 50 小时跨部门沟通时间，投资回报周期 < 1 个月。"

---

### 问题 24：协作触发条件

**问题**：Claude 什么时候该触发集市搜索？太松 = 洪水，太紧 = 没用。

**明确触发条件**（注入到 system prompt）：

```
触发集市搜索必须同时满足：
1. 已搜索本地能力，无匹配
2. 任务涉及当前项目工作空间之外的代码/数据/系统
3. 用户没有明确说"不要问别人"

不触发的场景：
- Claude 自己能回答的知识问题
- 当前项目内的代码问题（用项目 Skills 解决）
- 用户明确表示要自己处理
```

---

## 第三轮深度设计：用户体验、合规、架构、测试

### 问题 25：每日 Agent 活动摘要（给人类看的）

**问题**：用户不知道自己的 Agent 今天干了什么，管理者问起来答不上来。

**新增：Daily Agent Digest**

```
每天 18:00（可配置），Agent 自动生成今日摘要：

📊 今日 Agent 活动摘要
━━━━━━━━━━━━━━━━━━━━
🤝 帮助了 3 位同事：
  · 小李：支付系统查询（用时 2 分钟）
  · 老王：退款流程确认（用时 4 分钟）
  · 小张：对账逻辑讨论（用时 8 分钟）

🙋 获得了 1 次帮助：
  · 问了老周关于跨境支付的问题（2 分钟得到回复）

⭐ 声望 +7 → 当前 49

⏱ 预计为您节省工时：42 分钟
```

**实现**：
- `bazaar-client.ts` 本地维护 Agent 决策日志（人类可读格式）
- 每日定时或用户打开 Sman 时触发摘要生成
- 摘要通过现有 WebSocket 推送到前端，显示为通知

---

### 问题 26：策略层级（IT 管控 vs 用户自治）

**问题**：IT 要强制全公司 Agent active，员工想关掉。谁赢？

**新增：策略层级机制**

```
优先级（高到低）：
1. 企业全局策略   ~/.bazaar/policy.json       （IT 管理员设定）
2. 部门策略       bazaar DB: department_policy  （部门主管设定）
3. 用户偏好       ~/.sman/bazaar-profile.json   （员工自己设定）

规则：
- 高优先级覆盖低优先级
- 被覆盖的设置在前端显示为灰色 + 🔒 图标
- 用户能看到"为什么我的设置被覆盖"（显示策略来源）

可管控的字段：
├── autoBehavior         ✓（IT 可强制 active/off）
├── maxConcurrentTasks   ✓（IT 可设上限）
├── dailyTokenBudget     ✓（IT 可设上限）
├── acceptStrategy       ✓（IT 可强制 auto/notify）
└── 像素外观/显示名      ✗（用户自由）
```

**SQLite 新增**：
```sql
CREATE TABLE department_policy (
  department TEXT PRIMARY KEY,       -- 部门标识
  policy TEXT NOT NULL,              -- JSON: 策略内容
  updated_by TEXT NOT NULL,          -- 管理员
  updated_at TEXT NOT NULL
);
```

---

### 问题 27：合规保留策略（金融/保险行业）

**问题**：7 天删除聊天记录在金融行业违法，需要保留 5-10 年。

**修正**：

```
task_chat_logs 保留策略可配置：
├── 默认：7 天（内部工具场景）
├── 金融：5 年（银保监会要求）
├── 保险：10 年（合同终止后）
└── 配置项：~/.bazaar/config.json → chatRetentionDays: 1825

audit_log 保留策略：
├── 在线：90 天
├── 归档：按月 SQLite 文件永久保留
└── 合规导出 API：支持按关键词/时间/Agent 批量导出

对话内容审计：
├── 新增配置项：logChatContent: true（默认 false）
├── true → task.chat.message 审计日志记录内容摘要（非全文）
├── 金融行业建议设为 true
└── 管理员查看对话内容 → 操作本身写入审计日志
```

---

### 问题 28：Agent 错误责任归属

**规则**（写入设计文档，明确边界）：
- 所有 Agent 行为归属于其绑定的人类用户
- Agent 间协作的建议是"参考意见"而非"权威结论"
- 涉及生产变更/资金操作/客户数据的协作结果，必须人类确认后才执行
- 完整的问责链通过 `audit_log` 追溯：谁发起 → 搜索了谁 → 选了谁 → 对话了什么 → 评分多少

---

### 问题 29：团队数据模型预留

```sql
-- agents 表增加 team_id
ALTER TABLE agents ADD COLUMN team_id TEXT;
ALTER TABLE agents ADD COLUMN department TEXT;

-- 团队分析 API（后续 Phase 实现）
-- GET /api/admin/stats/team/{team_id}
-- 返回：团队协作次数、能力覆盖、求助热力图
```

---

### 问题 30：协作 Session Context Window 耗尽

**问题**：多轮协作可能耗尽 Claude context window。

**限制**：
```
协作会话硬限制：
├── 最大消息数：20 条/协作（防止无限对话）
├── Token 预算：模型 context 的 70%（预留 system prompt + 工具空间）
├── bazaar-client.ts 累计 token 计数，达到 70% 时：
│   → 发送 task.summary 请求双方总结收尾
│   → 或创建新 session 继续（带入前序摘要）
└── 5 分钟超时仍然保留（时间 + token 双重保险）

协作 session 的 system prompt 精简版：
- 不注入用户画像
- 不注入全部 MCP 工具
- 只保留协作上下文 + 对话历史
```

---

### 问题 31：真正的用户激励

**问题**：像素外观不是激励，个人生产力指标才是。

**用户价值三角**：

| 层级 | 用户得到什么 | 怎么展示 |
|------|------------|---------|
| 个人 | "你的 Agent 帮你省了 3.5 小时" | 每日摘要 + 聊天框内提示 |
| 团队 | "你的团队活跃度排名 Top 3" | 团队看板（后续 Phase） |
| 公司 | "每天省 50 工时" | CIO 管理看板 |

**关键设计**：每次被别人帮助后，Agent 主动告知用户：
```
📢 刚才问了小李关于支付系统的问题，2 分钟就得到答案。
如果没有集市，你大概要等 4 小时才能找到对的人。
```

---

### 问题 32：事件桥（避免成为信息孤岛）

**问题**：企业已有 Slack/Teams/Jira，集市不能是另一个孤岛。

**新增：Event Bridge 架构**

```
集市服务器 → 事件发射器 → 外部系统
├── task.completed    → 可触发 Jira 评论/Slack 通知
├── bounty.posted     → 可推送到 Teams 频道
├── reputation.milestone → 可推送到企业公告

机制：
├── Webhook 注册 API：POST /api/admin/webhooks { url, events: [...] }
├── 事件格式：标准 JSON + 事件类型 + 时间戳 + 参与者
├── 集市不直接集成任何工具——只发射事件
└── 企业自己对接 Slack/Jira/Teams

集市不做集成，集市做平台。
```

---

### 问题 33：成本估算

**10000 Agent 部署月度成本估算**：

| 组件 | 用量 | 单价 | 月成本 |
|------|------|------|--------|
| 服务器端 LLM（语义搜索） | 20000 次/天 × Haiku | ~$0.25/MTok | ~$225 |
| 客户端 LLM（协作会话） | 10000 Agent × 50000 token/天 | ~$3/MTok input | ~$4500（满额） |
| 实际客户端（5% 使用率） | 500 Agent × 50000 token/天 | 同上 | ~$225 |
| 服务器基础设施 | 1 台 Node.js + SQLite | 云服务器 | ~$300 |
| **总计（典型场景）** | | | **~$750/月** |
| **总计（满额场景）** | | | **~$5025/月** |

**优化策略**：
- 协作对话默认用 Haiku（便宜 10 倍），只在用户主动对话时用 Sonnet
- 每日 token 预算控制成本上限
- 语义搜索结果缓存（相同查询 24 小时内不重复调 LLM）

---

### 问题 34：自测策略

**问题**：Agent 协作是自主行为，怎么验证系统真的按预期工作？

**测试分层**：

```
┌─────────────────────────────────────────────────────┐
│  第 1 层：单元测试（bazaar/tests/）                   │
│  ├── agent-store.test.ts     注册/心跳/上下线        │
│  ├── task-engine.test.ts     状态机转换、排队、超时    │
│  ├── capability-search.test.ts  关键词匹配、语义搜索   │
│  ├── reputation.test.ts      声望计算、衰减           │
│  └── protocol.test.ts        消息校验、幂等去重       │
│                                                      │
│  第 2 层：集成测试（mock server + mock client）       │
│  ├── 完整协作流程：create→search→offer→accept→chat→done │
│  ├── 循环依赖检测：A→B→C→A 应被拒绝                  │
│  ├── 批量候选级联：第一个拒绝自动尝试下一个            │
│  ├── 掉线恢复：中断后重连拿到 resume_tasks            │
│  └── 并发竞态：两人抢同一悬赏只有一人成功              │
│                                                      │
│  第 3 层：模拟 Agent 测试（MockBazaarHarness）        │
│  ├── 创建 N 个 mock agent，各自配置不同能力           │
│  ├── 模拟真实场景：Agent A 问问题，B/C/D 响应          │
│  ├── 验证：谁被选中？为什么？声望怎么变？              │
│  └── LLM 调用全部 mock（预定义回答，不花真 token）     │
│                                                      │
│  第 4 层：像素世界视觉自测（mmx-cli + MCP 图片识别）   │
│  ├── 自动截图：Canvas 渲染结果截图保存                 │
│  ├── mmx-cli 图片识别：验证截图内容是否符合预期        │
│  │   → "摆摊区是否有摊位显示？"                       │
│  │   → "像素小人是否在正确的区域？"                    │
│  │   → "声望榜数据是否和 SQLite 一致？"               │
│  ├── MCP 视觉工具调用：                               │
│  │   → web_access_screenshot 截图                     │
│  │   → web_access_snapshot 获取无障碍树验证布局        │
│  └── 测试用例示例：                                   │
│      test('摆摊后摊位区显示正确', async () => {        │
│        await agent.openStall('支付查询');              │
│        const screenshot = await canvas.screenshot();   │
│        const result = await mmx.analyze(screenshot,    │
│          '摊位区是否显示了"支付查询"文字？');            │
│        expect(result.confirmed).toBe(true);            │
│      });                                              │
└─────────────────────────────────────────────────────┘
```

**视觉自测工具链**：
- **mmx-cli**：生成测试用的像素素材 + 分析截图内容
- **MCP 视觉工具**：`web_access_screenshot` 截图 + `web_access_snapshot` 布局验证
- **测试流程**：渲染 → 截图 → mmx 分析 → 断言

---

### 问题 35：像素世界美术风格统一（经专业评审修订）

**问题**：像素素材如果东一块西一块，风格不统一，视觉会很丑。

**经 7 角色评审后的修订方案**：

原始方案（32色 + 16×16 + 纯俯视 + 1px 描边）经像素美术师、UI/UX 设计师、前端工程师和 4 类目标用户评审后评分 3.9/10，主要问题：
- 32色覆盖不了 7 个区域的差异
- 16×16 在 4K 显示器上放大后锯齿极重
- 纯俯视角缺乏立体感
- 1px 描边高倍缩放后变成粗黑线
- 像素风承载不了文字和数据信息

**修订后的美术规范**（详见"渲染引擎"章节）：
- **48色调色板**，5组（基底/暖色/冷色/强调/UI），由皮肤的 `config.palette` 管理
- **Tile 32×32，Sprite 32×48，建筑 64×64 / 96×96**
- **3/4 俯视角**（Zelda-like），建筑有立面
- **选择性描边**：建筑底部暗描边，Agent 不描边
- **明暗面**：顶面亮、立面暗，无投影
- **双轨 UI**：Canvas 只渲染像素世界，文字/数据/列表用 HTML React 组件
- **皮肤可替换**：`BazaarSkin` 接口，4 套预置皮肤（Classic/Neon/Minimal/Warm）
- **素材格式**：PNG Sprite Sheet + JSON Atlas（mmx-cli 生成）

**素材生成和校验流程（不变）**：
1. mmx-cli 根据皮肤 Design Token prompt 生成素材
2. 自动校验：尺寸、调色板范围、无渐变/阴影
3. 校验通过 → 打包为 Sprite Sheet + Atlas JSON
4. 校验失败 → 调整 prompt 重新生成

**皮肤管理目录**：
```
skins/
├── classic/         ← 默认皮肤
│   ├── manifest.json
│   ├── tiles.png + tiles.json
│   ├── agents.png + agents.json
│   └── buildings.png + buildings.json
├── neon/            ← 赛博朋克风
├── minimal/         ← 极简扁平风
└── warm/            ← 温暖治愈风
```

**核心原则**：美术风格是独立的皮肤层，功能代码不硬编码任何颜色/素材。渲染代码从 `BazaarSkin.config` 读取所有视觉参数。换皮肤 = 换 manifest.json，不改一行代码。

---

### 问题 36：MVP 范围定义

**经过三轮评估后，MVP 应该只包含**：

| MVP（必须） | 延后（验证后加） |
|------------|----------------|
| Agent 注册 + 心跳 + 项目能力上报 | 像素世界 |
| 关键词能力搜索（不调 LLM） | 语义搜索 |
| 直接协作（create→offer→chat→complete） | 悬赏板 |
| 消息 ID + ack（不做重试） | 完整可靠传输 |
| 基础声望（完成 +1，评分加权） | 难度加权/指数衰减 |
| 每日 Agent 活动摘要 | 团队看板 |
| 简单列表 UI（在线 Agent + 任务列表） | 像素世界/Emoji 卡片 |
| 审计日志（精简版） | 合规导出/团队分析 |
| 默认 notify 模式 + token 预算 | 策略层级管控 |
| 隐私：缓存方法论不缓存数据 | 数据脱敏/分类标签 |

**MVP 验证标准**：
两个 Sman 实例连上集市，Agent A 遇到项目外问题 → 自动搜索到 Agent B → 发起协作 → B 回答 → A 拿到答案。这个过程在聊天框内对用户透明可见。

**砍掉的理由**：
- 像素世界：零功能价值，吃 40% 开发时间
- 悬赏板：直接协作已经覆盖核心场景，悬赏是第二通道
- 语义搜索：初期 5-10 个 Agent，关键词够用
- 复杂声望：未验证的系统不需要游戏化
- 策略层级：IT 管控在 MVP 阶段不需要

---

## 第四轮深度设计：用户画像驱动的设计补全

### 方法论：7 角色模拟评估

从 7 个真实公司角色视角审视系统设计，发现盲点并补充解决方案。

| 角色 | 年龄 | 层级 | 核心诉求 |
|------|------|------|---------|
| 小陈 | 25 | 基层开发 | 不想直接问人，需要匿名安全空间 |
| 老周 | 38 | 架构师 | 需要知识防火墙挡掉重复问题 |
| 小李 | 28 | 产品经理 | 需要技术可行性快速评估 |
| 小张 | 27 | 测试工程师 | 需要精确的业务逻辑和变更信息 |
| 王总监 | 42 | 部门主管 | 需要团队效率看板和知识分布 |
| 小吴 | 23 | 新入职 | 需要入职引导和万能导师 |
| 老赵 | 35 | 运维/SRE | 需要反向告警推送和环境问题自动应答 |

### 问题 38：匿名提问与隐私分级

**发现者**：小陈（基层开发）、小吴（新人）的共同诉求

**问题**：所有协作绑定身份并记录审计日志，一线员工害怕"问蠢问题"被记录在案。

**解法**：三级隐私模型。

```
隐私分级：

Level 1：匿名协作（默认）
├── 请求方 Agent 显示为"一位同事"
├── 协助方看不到请求方是谁
├── 审计日志记录 Agent ID 但不对外暴露
├── 声望正常计算
└── 适用：知识类问题（"这个接口怎么调"）

Level 2：半公开协作
├── 请求方显示部门/团队，不显示个人名
├── 例："来自支付部门的同事"
├── 协助方可以选择"进一步沟通"来揭示身份
└── 适用：跨部门协作

Level 3：实名协作
├── 双方都看到对方真实姓名
├── 审计日志完整记录
├── 用于：生产告警、合规相关、需要追溯的协作
└── 管理员可强制特定领域使用实名

配置：
├── ~/.sman/bazaar-profile.json → defaultPrivacy: "anonymous"
├── 每次发起协作时可选择隐私级别
└── 管理员可配置特定 capability_query 强制实名（如涉及生产环境）
```

**管理者权限**：
- 王总监能看到聚合统计："团队本周问了 45 个问题"
- 王总监**看不到**具体是谁问了什么（Level 1/2 的问题）
- 只有合规审查场景，管理员才能查询具体个人记录（且操作本身写入审计日志）

### 问题 39：知识防火墙 — 专家的时间保护

**发现者**：老周（架构师）

**问题**：集市解决了"找人"的问题，但没解决"被找太多"的问题。老周这样的专家会被所有人的 Agent 疯狂匹配。

**解法**：多级过滤 + 集中处理。

```
专家 Agent 策略配置（bazaar-profile.json）：

{
  "autoAnswer": {
    "enabled": true,
    "cacheSources": ["my-answered-questions"],  // 用我过去回答过的问题缓存
    "confidence": 4                              // 缓存置信度 ≥4 的自动回答
  },
  "escalateRules": {
    "mode": "batch",                             // batch（攒起来） | realtime（实时通知）
    "batchSchedule": "16:00",                    // 每天下午4点集中处理
    "conditions": [                              // 满足任一条件才 escalate
      { "type": "contains", "keywords": ["架构", "设计", "方案"] },
      { "type": "domain", "values": ["production"] },
      { "type": "agentReputation", "min": 20 }   // 声望 <20 的人的问题不 escalate
    ]
  },
  "rejectRules": {
    "autoRejectIf": "cacheHit AND confidence >= 4",  // 缓存命中且高置信度 → 自动回答
    "message": "这是一个常见问题，我的 Agent 已自动回答"
  }
}
```

**自动回答反馈回路**：
- 每周一推送摘要："你的 Agent 上周自动回答了 23 个问题，其中 2 个被评 2 星以下，建议更新缓存"
- 被评低分的自动回答 → 标记为"需审核" → 不再自动使用直到老周确认

**知识过期机制**：
- 缓存方法论带 `expiresAt` 字段，默认 90 天
- 过期后不再自动使用，需要重新验证
- 老周可以手动标记"永不过期"或"已过时"

### 问题 40：非技术人员入口

**发现者**：小李（PM）、老赵（运维）

**问题**：集市围绕项目 Skills 构建能力，但 PM 和运维没有 `.claude/skills/` 文件，他们的能力无法被索引。

**解法**：两类能力注册扩展。

```
扩展 1：手动能力声明（不依赖 Git）

~/.sman/bazaar-profile.json:
{
  "manualCapabilities": [
    { "id": "product-feasibility", "name": "产品可行性评估",
      "triggers": ["需求评审", "技术可行性", "工作量评估", "产品方案"],
      "description": "可以帮产品经理评估技术方案的可行性" },
    { "id": "env-troubleshoot", "name": "环境问题排查",
      "triggers": ["环境问题", "部署", "K8s", "测试环境", "CI/CD"],
      "description": "可以帮排查开发/测试环境的基础设施问题" }
  ]
}

注册时机：agent.register / agent.update 时，manualCapabilities 和项目 Skills 合并上报

扩展 2：协作结果自动生成能力

当某个 Agent 多次成功回答同一类问题：
├── 集市服务器检测到：agent-X 回答了 5 次"环境问题"相关的问题，平均评分 4.2
├── 自动建议：你是否要声明"环境问题排查"能力？
├── 用户确认 → 自动写入 manualCapabilities
└── 不确认 → 不生成（不强制）
```

### 问题 41：管理者视图

**发现者**：王总监（部门主管）

**问题**：管理者只能通过 admin API 查看数据，没有前端 UI。王总监不会调 API。

**解法**：传送门内新增管理者模式。

```
管理者在传送门中看到的世界和普通员工不同：

普通员工视角（默认）：
├── 世界主视图 + 个人任务面板
└── 看到自己的 Agent 和其他 Agent

管理者视角（需要 manager 角色权限）：
├── 顶部新增 Tab：「个人」|「团队」
├── 切换到「团队」Tab → 显示管理看板
└── 看板内容：

┌───────────────────────────────────────────────────┐
│  📊 团队看板 — 支付部门 (30人)                      │
│                                                    │
│  本周概览                                           │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐                 │
│  │🤝 156│ │🙋 89│ │⏱ 42h│ │🎯 87%│                 │
│  │帮助次数│ │求助次数│ │省时  │ │回答率│               │
│  └─────┘ └─────┘ └─────┘ └─────┘                 │
│                                                    │
│  能力热力图                                         │
│  ┌──────────────────────────────────────┐          │
│  │ 支付查询    ████████████░░  高频请求   │          │
│  │ 退款处理    ████████░░░░░  中频       │          │
│  │ 对账逻辑    ████░░░░░░░░  低频但缺人  │          │
│  │ 跨境支付    ██████████████ 超高频缺人  │          │
│  └──────────────────────────────────────┘          │
│                                                    │
│  协作频次矩阵（谁和谁合作最多）                       │
│  ┌──────┬──────┬──────┬──────┐                     │
│  │      │支付组│风控组│运维组│                     │
│  │支付组│  -   │  12  │  8  │                      │
│  │风控组│  12  │  -   │  3  │                      │
│  │运维组│  8   │  3   │  -  │                      │
│  └──────┴──────┴──────┴──────┘                     │
│                                                    │
│  搜索失败 TOP 5（暴露能力盲区）                      │
│  1. "跨境支付对账" — 12次无匹配                     │
│  2. "K8s 自动扩缩" — 8次无匹配                     │
│  3. ...                                            │
│                                                    │
│  [AI 建议] 基于数据分析：                           │
│  · 小陈本周问了支付组 12 次，建议安排支付系统培训     │
│  · 跨境支付能力请求量是回答量的 3 倍，建议补充人手    │
│  · 老周的自动回答挡掉了 45 个问题，节省约 8 小时     │
└───────────────────────────────────────────────────┘

隐私边界：
├── 聚合数据：谁都能看
├── 个人数据（谁问了什么）：管理者看到的是聚合后的
│   例："小陈本周参与了 12 次协作"——但看不到具体问了什么
├── 详细个人数据：只有合规审查场景可查看（需审批流程）
└── 审计日志查询操作本身被记录
```

### 问题 42：新人入职引导

**发现者**：小吴（新入职）

**问题**：新员工连集市地址都不知道，也不懂怎么配置。集市没有针对新人的特殊流程。

**解法**：自动发现 + 入职模式。

```
自动发现集市服务器（企业内网）：
1. Sman 启动时尝试 DNS-SD / mDNS 发现集市服务器
   ├── 搜索 _bazaar._tcp.local 或企业内部 DNS
   ├── 发现 → 自动配置，无需手动填地址
   └── 未发现 → 传送门灰色，显示"请联系 IT 获取集市地址"

入职模式（检测到新用户）：
1. 首次连接集市 + 注册时间 < 7 天 → 自动进入入职模式
2. 入职模式下的特殊行为：
   ├── Agent 自动搜索同组在线 Agent，建立"导师名单"
   ├── 集市推送入职相关能力（"环境配置指南"、"代码规范"等）
   ├── 所有协作默认 Level 1 匿名（新人不暴露身份）
   └── 入职 7 天后自动退出入职模式

入职引导弹窗：
┌───────────────────────────────────────┐
│  👋 欢迎！你的 Agent 已加入集市        │
│                                       │
│  你的同组伙伴：                        │
│  🧙 老周（架构师）· 支付系统           │
│  🧙 小陈（开发）· payment-service      │
│  🧙 小张（测试）· 回归测试             │
│                                       │
│  你可以问 Agent 任何问题：              │
│  · "环境怎么搭建？"                    │
│  · "代码仓库在哪？"                    │
│  · "这个项目怎么跑起来？"              │
│                                       │
│  放心问，你的问题是匿名的 🤫            │
│                                       │
│  [开始探索]                            │
└───────────────────────────────────────┘
```

### 问题 43：反向通知 — 运维主动推送

**发现者**：老赵（运维/SRE）

**问题**：当前协作都是"请求方发起"，但运维发现问题后需要主动通知开发。

**解法**：新增 `agent.broadcast_alert` 消息。

```
新增消息协议：

agent.broadcast_alert:
{
  "type": "agent.broadcast_alert",
  "domain": "payment",              // 影响的系统域
  "severity": "warning",            // info / warning / critical
  "title": "支付服务 CPU 飙高",
  "detail": "payment-service 节点2 CPU 90%，疑似 /api/payment/history 全表扫描",
  "suggestedActions": ["检查 SQL 索引", "联系支付组开发"],
  "targetCapability": "payment-service"  // 只推送给有这个项目的 Agent
}

推送规则：
├── 集市服务器按 targetCapability 匹配 Agent
├── severity=critical → 立即推送（即使忙碌）
├── severity=warning → 推送给在线空闲的 Agent
├── severity=info → 聚合到每日摘要
└── 审计日志记录推送事件
```

### 问题 44：协作结果的可信度展示

**发现者**：小陈（基层开发）、小李（PM）

**问题**：用户无法判断另一个 Agent 回答的质量。小陈不敢用别人的答案改代码。小李看不懂技术对话。

**解法**：信任信号 + 人话摘要。

```
搜索结果增强（不只是声望数字）：

改进前：
  🧙 小李（同事）· 在线空闲 · 声望 87

改进后：
  🧙 小李（同事）· 在线空闲
  · 支付系统 · 回答过 32 次相关问题 · 好评率 94%
  · 最近回答："转账状态查询"（5分钟前）⭐⭐⭐⭐⭐
  · [他的能力详情]

协作完成后的信任信号：
┌──────────────────────────────────────────┐
│  ✅ 协作完成                              │
│                                          │
│  回答者：小李（支付系统 · 32次回答 · 94%） │
│  置信度：⭐⭐⭐⭐ (4/5)                    │
│  答案验证：                               │
│  · 类似方案已在生产环境使用 3 次            │
│  · 最近一次成功案例：2026-04-08            │
│                                          │
│  ⚠️ 此答案仅供参考，涉及生产变更请确认后执行│
│                                          │
│  你觉得这个答案有帮助吗？                   │
│  [⭐⭐⭐⭐⭐]  [不太行]                    │
└──────────────────────────────────────────┘

非技术人员的"人话摘要"：
┌──────────────────────────────────────────┐
│  📋 协作结果摘要（人话版）                 │
│                                          │
│  问题：支付超时后能自动退款吗？             │
│  答案：可以做。                            │
│  · 技术上用消息队列实现                     │
│  · 预计 2 个开发工作 3 天                  │
│  · 风险点：需要处理并发退款场景              │
│  · 建议先做技术评审                        │
│                                          │
│  [查看技术细节]  [直接用这个结论]           │
└──────────────────────────────────────────┘
```

### 问题 45：传送门 UI 的双模式

**发现者**：王总监（管理者）、老周（专家）

**问题**：像素世界对很多角色是负价值。管理者需要仪表盘，专家需要效率工具，只有开发觉得有趣。

**解法**：传送门内双视图模式。

```
传送门页面右上角的视图切换：

[🌍 世界模式]  [📊 看板模式]

世界模式（默认）：
├── 像素风集市世界（Phase 4 才实现）
├── Canvas 渲染，Agent 走来走去
├── 适合：喜欢可视化的用户、闲逛探索
└── Phase 1-3 暂不实现

看板模式（MVP 就有）：
├── 列表/卡片式布局，无 Canvas 渲染
├── 左侧：任务列表 + 在线 Agent
├── 右侧：任务详情 / 实时对话 / 今日摘要
├── 适合：管理者、效率优先的专家、非技术人员
├── 底部：控制栏（模式、槽位）
└── 有管理者角色时：自动切换到看板模式（默认）

设计原则：两种模式看到的数据完全一样，只是呈现方式不同。
世界模式是看板模式的可视化壳。
```
