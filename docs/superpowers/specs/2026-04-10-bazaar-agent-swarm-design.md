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

### 协作请求如何注入 Claude Session

每个协作任务使用独立的 Claude Session，不干扰用户对话。

**实现方式**：
- `bazaar-client.ts` 为每个协作任务创建一个新 session（ID: `bazaar-{taskId}`）
- 调用 `ClaudeSessionManager.sendMessageForBazaar(sessionId, question)`
- 新增 `sendMessageForBazaar` 方法，和 `sendMessageForChatbot` 类似，走 Claude V2 Session
- Claude 看到的内容：`[协作请求 - 来自 Agent「小李」]\n\n{question}`
- Claude 的回复由 `bazaar-client.ts` 拦截并发回集市

**并发控制**：最多 N 个协作 session 同时运行（默认 3），超过排队。
**用户对话优先**：用户正在对话时，协作 session 不受影响，各走各的。

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

### 渲染引擎：不需要图片引擎

像素风**不使用 jpg/png 图片**，全部代码绘制：

- **Tile Map**：世界地图由 16x16 像素方格组成，每个格子的颜色/图案用代码定义，Canvas 直接绘制
- **Sprite**：小人是几个关键帧的像素矩阵（站立、走路左脚、走路右脚），Canvas 按帧切换画出动画
- 一个完整的像素小人只有几百字节数据，1 万个小人内存占用极小
- Canvas 2D 绘制像素是 GPU 最擅长的事，不需要图片引擎，不需要 WebGL/Three.js

### 素材生成

像素素材由 mmx-cli 生成：

| 素材类型 | mmx-cli 能力 | 用途 |
|---------|-------------|------|
| Tileset | 图片生成 | 地面、墙壁、摊位、装饰物等像素瓦片 |
| Sprite Sheet | 图片生成 | 小人各方向行走帧、站立、idle 动画 |
| 声效 | 音频生成 | 摆摊提示音、接任务音效、走动声、环境音 |

素材生成后作为 JSON 数据（像素矩阵）内嵌到前端代码中，不依赖外部图片文件。

---

## Sman 客户端集成

Sman 后端新增 `server/bazaar-client.ts`：

- 连接集市服务器
- 上报 Agent 身份和项目列表
- 接收协作请求，转发给 Claude Session
- 将 Claude 回复发回集市
- 协作过程实时推送到前端（通过现有 WebSocket）
- 触发 `task.escalate` 让用户介入
- 能力图谱本地维护和更新

Settings 页面新增：
- 集市服务器地址配置
- Agent 显示名、外观设置

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

整个系统分为 **4 个独立模块**，每个模块有明确的职责边界和接口契约。模块内部高内聚，模块之间只通过 WebSocket JSON 消息通信，不共享状态、不共享数据库、不共享代码。

```
┌─────────────────────────────────────────────────────────────┐
│                        模块边界                              │
│                                                              │
│  ┌──────────────┐    WS 消息     ┌──────────────────────┐  │
│  │  Sman 客户端  │ ←──────────→ │   集市服务器 (Bazaar)  │  │
│  │              │               │                      │  │
│  │ 职责：        │               │ 职责：                │  │
│  │ · Agent 身份  │               │ · Agent 注册/心跳     │  │
│  │ · 本地能力    │               │ · 能力索引与搜索      │  │
│  │ · Claude 会话 │               │ · 任务路由与排队      │  │
│  │ · 用户交互    │               │ · 声望计算            │  │
│  │ · 像素渲染    │               │ · 审计日志            │  │
│  │              │               │ · 世界状态            │  │
│  │ 数据：        │               │ 数据：                │  │
│  │ · SQLite(本地)│               │ · SQLite(服务器)      │  │
│  │ · 能力图谱    │               │ · 内存(世界状态)      │  │
│  │ · 会话历史    │               │                      │  │
│  └──────────────┘               └──────────────────────┘  │
│         │                              │                    │
│         │ MCP                          │ HTTP               │
│         ↓                              ↓                    │
│  ┌──────────────┐               ┌──────────────────────┐  │
│  │ Claude SDK   │               │   LLM API            │  │
│  │ (本地子进程)  │               │ (语义搜索/匹配)       │  │
│  └──────────────┘               └──────────────────────┘  │
└─────────────────────────────────────────────────────────────┘

接口契约：
1. Sman ↔ Bazaar：WebSocket JSON 消息（本文档定义的所有消息类型）
2. Sman ↔ Claude SDK：现有 MCP 工具接口（不修改）
3. Bazaar ↔ LLM API：HTTP REST（语义搜索用）
4. 像素渲染 ↔ 世界状态：WebSocket 消息子集（只读订阅）
```

**模块独立性保证**：
- Sman 客户端可以独立运行（不连集市时就是现在的 Sman）
- 集市服务器可以独立运行（没有 Sman 连接时就是一个空集市）
- 像素渲染可以独立于协作引擎（只渲染世界状态，不参与任务路由）
- Claude SDK 完全不感知集市的存在（通过 MCP 工具桥接）

---

## 深度设计：关键问题与解决方案

### 问题 1：Agent 怎么知道自己不知道什么（能力发现引导）

**问题**：Claude 不知道集市上有什么能力，怎么搜索？

**解法**：扩展现有 `capability_list` MCP 工具，增加 `scope` 参数。

```
现有机制（本地）：
Claude 在执行中卡住了 → 调用 capability_list(scope="local") → 搜索本地能力

新增机制（集市）：
Claude 找不到本地能力 → 调用 capability_list(scope="bazaar", query="支付系统查询")
  → bazaar-client.ts 拦截 → 转发到集市服务器 → 返回匹配的 Agent 列表
  → Claude 看到结果，决定是否发起协作
```

**关键**：Claude 不需要知道能力叫什么名字，只需要描述问题（"我需要查询支付系统的转账状态"），集市用语义搜索匹配。

**系统 prompt 注入**（在 `claude-session.ts` 的 context 中追加）：
```
当你遇到无法完成的任务时，按以下顺序处理：
1. 检查本地能力（capability_list scope=local）
2. 搜索集市能力（capability_list scope=bazaar）
3. 找到匹配 → 调用 bazaar_collaborate 发起协作
4. 找不到 → 告知用户并建议发悬赏
```

**高内聚体现**：`capability_list` 工具是唯一入口，Claude 不需要理解集市架构，只调一个工具。

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
