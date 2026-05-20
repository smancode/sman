# Sman 成就系统设计文档

> 日期: 2026-05-20
> 状态: 已确认，待实现
> Review: v2 - 已修复 4 Critical + 5 Important + 补充 4 Suggestion
> v3 - 补充 Bot 维度、历史数据回填、sman-server 排行榜服务端

## 1. 目标

为 Sman 增加成就系统，同时满足两个核心目标：

1. **激励活跃 + 留存**：连续使用天数、日常互动等激励用户持续使用
2. **成长里程碑记录**：记录用户达成的关键节点，提供成就感和可回顾的成长轨迹

额外增加**多维度排行榜**，通过社交对比驱动用户活跃。

## 2. 架构方案：事件驱动型

```
业务动作 → emitAchievementEvent() → AchievementEngine.handleEvent()
  → 更新 achievement_stats / achievement_streaks 表
  → 检查相关成就条件
  → 满足则解锁 + 写入 achievement_progress
  → WebSocket broadcast achievement.unlocked
  → 前端 Toast 通知
```

### 2.1 事件总线

复用 Node.js 原生 `EventEmitter`，单例挂在 server 主实例上。

```typescript
// server/achievement-events.ts
import { EventEmitter } from 'events';

const achievementEmitter = new EventEmitter();

type AchievementEvent = {
  type: 'session_created' | 'message_sent' | 'message_done'
      | 'cron_executed' | 'batch_item_completed' | 'batch_completed'
      | 'smartpath_run' | 'stardom_collab'
      | 'token_used' | 'workspace_added' | 'skill_used'
      | 'code_viewed' | 'git_operation'
      | 'error_occurred' | 'day_active'
      | 'bot_session_created' | 'bot_message_sent'
  data: Record<string, any>;
};

export function emitAchievementEvent(event: AchievementEvent): void {
  achievementEmitter.emit('achievement', event);
}

export function onAchievementEvent(handler: (event: AchievementEvent) => void): void {
  achievementEmitter.on('achievement', handler);
}
```

### 2.2 成就引擎

```typescript
// server/achievement-engine.ts
class AchievementEngine {
  private store: AchievementStore;
  private definitions: AchievementDef[];
  private metricIndex: Map<string, AchievementDef[]>;  // metric → definitions

  start(): void;           // 加载定义，订阅事件
  handleEvent(event: AchievementEvent): void;  // 核心处理逻辑（内部 try-catch，错误记日志不冒泡）
  getAll(): AchievemenView[];                    // 全部成就 + 进度
  getUnlocked(): AchievementProgress[];          // 已解锁列表
  getStats(): Record<string, number>;            // 统计数据
  getLeaderboard(dimension: string): LeaderboardEntry[];  // 排行榜
  recalcStatsFromDB(): void;                     // 启动时从现有数据重建统计（回填历史成就）
}
```

**错误隔离**：`handleEvent()` 内部用 try-catch 包裹所有逻辑，异常只写日志（`console.error('[achievement]', err)`），不影响主业务流程。成就丢失的风险通过 `recalcStatsFromDB()` 启动时补偿。

### 2.3 事件埋点位置

| 事件 | 埋点位置 | 触发时机 | 备注 |
|------|---------|---------|------|
| `session_created` | `SessionStore.create()` | 新会话创建 | |
| `workspace_added` | `SessionStore.create()` 内部 | 新 workspace 首次出现 | 先查 `getActiveWorkspaces()` 判断是否首次，仅首次 emit |
| `message_sent` | `ClaudeSessionManager.sendMessage()` | 用户发消息 | |
| `message_done` | `ClaudeSessionManager` streamDone | AI 回复完成 | |
| `cron_executed` | `CronExecutor.execute()` | cron 执行成功 | |
| `batch_item_completed` | `BatchEngine` | 单个 item 完成 | |
| `batch_completed` | `BatchEngine` | batch 全部完成 | |
| `smartpath_run` | `SmartPathEngine` | 地球路径执行完成 | |
| `stardom_collab` | `stardom-session.ts` `completeCollaboration()` | 协作任务完成 | |
| `token_used` | token 统计更新时 | token 累积 | |
| `skill_used` | SDK `tool_use` 回调中，匹配 skill 名称模式 | skill 被调用 | 见下方说明 |
| `code_viewed` | WS handler `code.readFile` / `code.searchSymbols` | 查看代码文件 | |
| `git_operation` | WS handler `git.commit` / `git.push` | git 操作 | |
| `error_occurred` | WS handler `chat.error` | SDK 执行错误 | 仅限白名单错误类型 |
| `day_active` | server 启动 + `message_sent` 去重触发 | 活跃天数 | 见下方去重机制 |
| `bot_session_created` | `ChatbotSessionManager` 创建会话时 | Bot 会话创建 | data 含 platform |
| `bot_message_sent` | `ChatbotSessionManager` 发消息时 | Bot 对话消息 | data 含 platform |

**`skill_used` 说明**：服务端不直接感知 skill 调用。通过 SDK `tool_use` 回调捕获：当 tool name 匹配 skill 注册表中的名称时 emit。如果 SDK 不暴露 tool_use 回调，则降级为在 `SessionStore` 中通过 session 级别的 skill 使用记录触发。

**Bot 维度说明**：企业微信 Bot（`wecom`）、飞书 Bot（`feishu`）、微信 Bot（`weixin`）三类平台分别统计。通过 `chatbot_sessions.user_key` 前缀区分平台。`bot_session_created` 事件由 `ChatbotSessionManager` 创建/复用会话时触发，`bot_message_sent` 由 Bot 收到用户消息时触发。

**`day_active` 去重机制**：
- `achievement_stats` 表存储 `last_active_date`（格式 `YYYY-MM-DD`）
- 每次 `message_sent` 时比对今天是否已记录，仅首次记录时 emit `day_active`
- server 启动时也检查一次，确保零点后的首次启动也能记录

**`error_occurred` 白名单**：只统计以下类型：
- SDK 调用失败（`chat.error` 中 `type === 'sdk_error'`）
- 模型超时 / 限流
- 会话恢复失败
不统计：参数校验错误、session 不存在（用户输入问题）、权限错误。

**`workspace_added` 首次判断**：在 `SessionStore.create()` 内部，插入前查询 `SELECT COUNT(DISTINCT workspace) FROM sessions WHERE workspace = ?`，结果为 0 时 emit。

## 3. 数据模型

### 3.1 成就定义（内置代码 + `~/.sman/achievements.json` 覆盖）

```typescript
interface AchievementDef {
  id: string;
  category: 'conversation' | 'advanced' | 'exploration' | 'collaboration' | 'bot' | 'hidden';
  tier: 'bronze' | 'silver' | 'gold' | 'platinum' | 'diamond' | 'star' | 'king' | 'legend' | 'epic' | 'eternal';
  nameKey: string;         // i18n key
  descKey: string;         // i18n key
  icon: {
    source: 'emoji' | 'asset';
    value: string;         // emoji: "💬" | asset: "achievements/first_chat.svg"
  };
  // badge 样式由 tier 自动推导，不需要显式定义
  // 渲染时根据 tier 查内置 badge SVG 映射
  hidden: boolean;         // 彩蛋成就
  condition: {
    metric: string;
    threshold: number;
  };
}
```

**Badge 映射（内置，由 tier 推导）**：

```typescript
const TIER_BADGE_MAP: Record<Tier, string> = {
  bronze:   'badges/bronze.svg',    // #CD7F32
  silver:   'badges/silver.svg',    // #C0C0C0
  gold:     'badges/gold.svg',      // #FFD700
  platinum: 'badges/platinum.svg',  // #E5E4E2
  diamond:  'badges/diamond.svg',   // #B9F2FF
  star:     'badges/star.svg',      // #FF6B9D
  king:     'badges/king.svg',      // #FF4444
  legend:   'badges/legend.svg',    // #9B59B6
  epic:     'badges/epic.svg',      // #FF8C00
  eternal:  'badges/eternal.svg',   // #00FFAA
};
```

用户可在 `~/.sman/achievements/assets/` 放同名文件覆盖内置资源。

### 3.2 成就进度（SQLite）

```sql
CREATE TABLE achievement_progress (
  achievement_id TEXT PRIMARY KEY,
  current_value INTEGER DEFAULT 0,
  unlocked_at TEXT,
  notified_at TEXT
);

CREATE TABLE achievement_stats (
  key TEXT PRIMARY KEY,           -- "total_sessions", "total_messages", "last_active_date"
  value TEXT,                     -- 统一用 TEXT，数字类型 parseInt
  updated_at TEXT
);

CREATE TABLE achievement_streaks (
  id INTEGER PRIMARY KEY DEFAULT 1,  -- 单行表
  current_streak INTEGER DEFAULT 0,
  longest_streak INTEGER DEFAULT 0,
  last_active_date TEXT,              -- 'YYYY-MM-DD'
  CHECK (id = 1)
);
```

**`achievement_stats` 设计**：
- `last_active_date`：存储为 TEXT 类型 `YYYY-MM-DD`，用于 `day_active` 去重
- 数字类型的 stat（如 `total_sessions`）存储为 TEXT，读取时 parseInt

**`achievement_streaks` 设计**：
- 单行表，存储当前连续天数和历史最长连续天数
- 每次 `day_active` 事件时：
  - 如果 `last_active_date` 是昨天 → `current_streak++`
  - 如果 `last_active_date` 是今天 → 不更新（去重）
  - 如果 `last_active_date` 更早 → `current_streak = 1`（断连后重新计数）
  - 更新 `longest_streak = MAX(longest_streak, current_streak)`

### 3.3 排行榜数据

```sql
CREATE TABLE achievement_board (
  agent_id TEXT PRIMARY KEY,
  agent_name TEXT,
  total_unlocked INTEGER DEFAULT 0,
  total_points INTEGER DEFAULT 0,
  tier_counts TEXT,           -- JSON
  dimension_scores TEXT,      -- JSON: {"conversation":50, "advanced":30, ...}
  last_synced TEXT
);
```

**Offline fallback**：
- 排行榜始终写入本地自己的数据（agent_id = 'local'）
- Stardom 连接时：上传本地数据 + 下载全局数据合并到 `achievement_board`
- Stardom 未连接时：排行榜页面只显示本地数据，UI 提示"排行榜仅显示本地数据，连接星域网络可查看全局排名"
- 前端排行榜 store 区分 `isOnline: boolean` 状态

### 3.4 等级计分

```
青铜: 1 | 白银: 3 | 黄金: 5 | 铂金: 8 | 钻石: 12
星耀: 16 | 王者: 20 | 传说: 25 | 史诗: 30 | 永恒: 50
```

## 4. 成就体系（~90 个成就）

### 4.1 对话维度

**会话数线：** 1 → 10 → 50 → 200 → 500 → 1k → 2k → 5k → 10k → 99,999

**消息数线：** 10 → 100 → 500 → 2k → 5k → 10k → 50k → 100k → 500k → 1,000,000

**连续天数线：** 3 → 7 → 14 → 30 → 60 → 100 → 180 → 365 → 500 → 999

### 4.2 高级功能维度

| 线 | 阈值梯度 |
|----|---------|
| Cron 执行 | 1 → 10 → 50 → 200 → 500 → 1,000 |
| Batch item | 1 → 50 → 200 → 1k → 5k → 20k |
| 地球路径 | 1 → 10 → 50 → 100 → 500 → 1k |
| Skill 使用 | 1 → 5 → 20 → 50 → 100 → 500 |

### 4.3 资源/广度维度

| 线 | 阈值梯度 |
|----|---------|
| Workspace | 2 → 5 → 10 → 20 → 50 → 100 |
| Token | 1w → 10w → 50w → 100w → 500w → 1000w → 5000w → 1亿 |
| 代码查看 | 10 → 50 → 200 → 1k → 5k |
| Git 操作 | 10 → 50 → 200 → 1k → 5k |

### 4.4 协作维度

| 线 | 阈值梯度 |
|----|---------|
| 协作任务 | 1 → 10 → 50 → 200 → 1k |
| 声望值 | 5 → 10 → 25 → 50 → 100 → 200 → 500 → 1k |
| 帮助他人 | 1 → 10 → 50 → 200 → 500 |

### 4.5 Bot 维度（按三类平台分别统计）

**三类 Bot 平台**：`wecom`（企业微信）/ `feishu`（飞书）/ `weixin`（微信）

**识别方式**：`chatbot_sessions.user_key` 前缀（如 `wecom:bot-sales:zhangsan`），SUBSTR 到第一个 `:` 即为平台标识。

| 线 | 阈值梯度 | 说明 |
|----|---------|------|
| Bot 数量（单平台） | 1 → 3 → 5 → 10 → 20 | 某平台的活跃 Bot 数（`COUNT(DISTINCT user_key)` WHERE platform = X） |
| Bot 数量（全平台） | 1 → 5 → 10 → 20 → 50 → 100 | 全平台活跃 Bot 总数 |
| Bot 会话数（单平台） | 1 → 10 → 50 → 200 → 1000 | 某平台的会话创建总数 |
| Bot 会话数（全平台） | 1 → 10 → 50 → 200 → 1000 → 5000 → 10000 | 全平台 Bot 会话总数 |
| Bot 对话数（单平台） | 10 → 100 → 500 → 2000 → 10000 | 某平台的消息总数 |
| Bot 对话数（全平台） | 10 → 100 → 500 → 2000 → 10000 → 50000 → 100000 | 全平台 Bot 对话总数 |
| 多平台运营 | 2 → 3 | 同时使用 2 个 / 3 个平台 |

### 4.6 彩蛋成就（hidden）

| ID | 触发条件 | 实现说明 |
|----|---------|---------|
| `midnight_warrior` | 凌晨 0-5 点发消息 | `message_sent` 时检查 `new Date().getHours()` |
| `early_bird` | 早上 5-7 点发消息 | 同上 |
| `marathon_50` | 单会话 50 轮 | `message_sent` 时统计 session 内消息计数 |
| `marathon_200` | 单会话 200 轮 | 同上 |
| `speed_demon` | 1 分钟内发 5 条消息 | 内存滑动窗口（`Map<sessionId, number[]>`），记录最近 1 分钟的时间戳。server 重启后窗口清空，不影响用户体验（只是暂时无法触发该成就） |
| `weekend_warrior` | 连续 4 个周末都使用 | `day_active` 时检查，周末日与 `achievement_streaks` 交叉计算 |
| `first_error` | 第一次遇到错误 | `error_occurred` 白名单内首次触发 |
| `error_100` | 累计遇到 100 次错误 | 统计表计数 |
| `comeback` | 7 天未登录后回归 | 启动时比对 `achievement_streaks.last_active_date` |
| `new_year` | 在 1 月 1 日使用 | `day_active` 时检查月份 |

### 4.6 分期交付建议

**P0（首版核心，约 30 个）**：对话维度 3 条线的前 3-4 级 + 连续天数前 3 级 + 首次触发型成就（cron_first, batch_first, path_first, skill_first, stardom_first）
**P1（完善期，约 40 个）**：高级功能/资源/协作维度完整线 + 高级别成就
**P2（社交期）**：排行榜 + 彩蛋成就

## 5. UI 设计

### 5.1 入口

侧边栏独立入口，路径 `/achievements`，图标 `<Trophy>`。

### 5.2 页面结构

两个主 Tab：**成就** | **排行榜**

**成就 Tab：**
- 顶部：总进度条（已解锁/总数 + 百分比）
- 分类筛选 Tab：全部 / 对话 / 高级功能 / 探索 / 协作 / Bot / 彩蛋
- 卡片网格（3-4 列）

**排行榜 Tab：**
- 维度 Tab：总榜 / 数量 / 对话 / 功能 / 协作 / Bot / 周榜
- 列表展示，自己高亮
- 离线提示：未连接星域时显示"仅显示本地数据"

### 5.3 卡片样式

- **已解锁**：完整颜色 + badge 边框（由 tier 推导） + 解锁日期
- **进度中**：图标可见 + 进度条 + 当前值/目标值
- **未开始**：图标灰度 + "0/阈值"
- **彩蛋（hidden）**：锁定图标 + "???"，解锁后翻转揭示

### 5.4 Badge 颜色（由 tier 自动映射）

青铜 `#CD7F32` | 白银 `#C0C0C0` | 黄金 `#FFD700` | 铂金 `#E5E4E2` | 钻石 `#B9F2FF`
星耀 `#FF6B9D` | 王者 `#FF4444` | 传说 `#9B59B6` | 史诗 `#FF8C00` | 永恒 `#00FFAA`

### 5.5 解锁 Toast（全局组件）

- **位置**：`src/components/layout/AchievementToast.tsx`（全局 layout 层，非 feature 目录）
- 右下角弹出，3 秒自动消失
- 显示：庆祝动画 + 成就图标 + 名称 + 描述
- 按钮：查看成就（跳转页面）/ 关闭
- 任何页面都可能触发，注册在 App 顶层

## 6. 文件结构

### 新增

```
server/
├── achievement-store.ts          # SQLite 表 + CRUD
├── achievement-engine.ts         # 核心引擎
├── achievement-events.ts         # 事件总线
├── achievement-definitions.ts    # 成就定义数据
└── achievement-ws-handler.ts     # WS 路由（独立文件，从 index.ts 调用入口函数）

src/
├── stores/
│   └── achievement.ts            # Zustand store
├── components/
│   └── layout/
│       └── AchievementToast.tsx  # 全局解锁通知组件
├── features/
│   └── achievements/
│       ├── index.tsx             # 主页面
│       ├── AchievementCard.tsx   # 卡片组件
│       ├── AchievementGrid.tsx   # 网格布局
│       └── assets/               # 内置 badge SVG (10 个等级)

sman-server/src/
├── db-achievements.ts            # AchievementDB 类（hub.db 新表）
└── routes/
    └── achievement-api.ts        # 上报 + 排行榜路由（PSK 加密）
```

### 修改

| 文件 | 改动 |
|------|------|
| `server/index.ts` | 初始化 Engine + 调用 `handleAchievementMessage()` 入口 |
| `server/session-store.ts` | emit `session_created`, `workspace_added`（含首次判断） |
| `server/claude-session.ts` | emit `message_sent`, `message_done` |
| `server/cron-executor.ts` | emit `cron_executed` |
| `server/batch-engine.ts` | emit `batch_item_completed`, `batch_completed` |
| `server/smart-path-engine.ts` | emit `smartpath_run` |
| `server/token-counter.ts` | emit `token_used` |
| `server/chatbot/chatbot-session-manager.ts` | emit `bot_session_created`, `bot_message_sent` |
| `src/components/layout/Sidebar.tsx` | 添加 NavLink |
| `src/app/routes.tsx` | 添加路由 |
| `src/locales/zh-CN.json` | i18n |
| `src/locales/en-US.json` | i18n |

## 7. WebSocket API

| 消息类型 | 方向 | 用途 |
|---------|------|------|
| `achievement.list` | C→S | 请求所有成就 + 进度 |
| `achievement.stats` | C→S | 请求统计数据 |
| `achievement.leaderboard` | C→S | 请求排行榜（带 `dimension` 参数） |
| `achievement.data` | S→C | 返回成就列表（响应 `achievement.list`） |
| `achievement.unlocked` | S→C | 实时推送解锁通知 |
| `achievement.progress` | S→C | 实时推送进度更新 |

### Payload 格式

**`achievement.data`**:
```typescript
{
  type: 'achievement.data',
  achievements: Array<{
    id: string;
    category: string;
    tier: string;
    nameKey: string;
    descKey: string;
    icon: { source: string; value: string };
    hidden: boolean;
    currentValue: number;
    threshold: number;
    unlockedAt: string | null;
  }>;
  stats: Record<string, string>;
  streak: { current: number; longest: number };
}
```

**`achievement.unlocked`**:
```typescript
{
  type: 'achievement.unlocked',
  achievement: {
    id: string;
    nameKey: string;
    descKey: string;
    tier: string;
    icon: { source: string; value: string };
  };
  totalPoints: number;
  totalUnlocked: number;
}
```

**`achievement.progress`**:
```typescript
{
  type: 'achievement.progress',
  achievementId: string;
  currentValue: number;
  threshold: number;
  justUnlocked: boolean;
}
```

**`achievement.leaderboard`**:
```typescript
{
  type: 'achievement.leaderboard',
  dimension: string;
  entries: Array<{
    rank: number;
    agentId: string;
    agentName: string;
    score: number;
    unlocked: number;
    isMe: boolean;
  }>;
  isOnline: boolean;  // 是否连接了 Stardom
}
```

## 8. 排行榜系统

### 同步机制

- 本地成就变更后，通过 Stardom WebSocket 上报到 Stardom Server
- Stardom Server 维护全局排行榜
- 单机模式：写入本地 `achievement_board`（agent_id = 'local'），排行榜只显示自己 + 提示
- 前端排行榜 store 区分 `isOnline` 状态

### 排行榜维度

| 排行榜 | 排序依据 |
|--------|---------|
| 总成就榜 | total_points |
| 成就数量榜 | total_unlocked |
| 对话榜 | conversation 维度分 |
| 高级功能榜 | advanced 维度分 |
| 协作榜 | collaboration 维度分 |
| 本周之星 | 本周新增成就分（周维度重置） |

## 9. 历史数据回填（`recalcStatsFromDB()`）

成就系统上线时，用户已有大量历史数据。`AchievementEngine` 启动时必须从现有 SQLite 表重建统计，一次性解锁所有历史成就。

### 回填数据源

| 统计 key | 数据源 SQL | 说明 |
|---------|-----------|------|
| `total_sessions` | `SELECT COUNT(*) FROM sessions` | **包含**软删除的会话（不排除 `deleted_at`） |
| `total_messages` | `SELECT COUNT(*) FROM messages WHERE role = 'user'` | 所有用户消息 |
| `total_workspaces` | `SELECT COUNT(DISTINCT workspace) FROM sessions` | **包含**所有 workspace（含软删除会话的） |
| `total_input_tokens` | `SELECT COALESCE(SUM(input_tokens), 0) FROM sessions` | 累计输入 token |
| `total_output_tokens` | `SELECT COALESCE(SUM(output_tokens), 0) FROM sessions` | 累计输出 token |
| `total_cron_runs` | `SELECT COUNT(*) FROM cron_runs WHERE status = 'success'` | 成功的 cron 执行 |
| `total_batch_items` | `SELECT COUNT(*) FROM batch_items WHERE status IN ('completed','partial')` | 成功的 batch items |
| `total_smartpath_runs` | `SELECT COUNT(*) FROM smart_path_runs WHERE status = 'completed'` | 成功的路径执行 |
| `bot_sessions_{platform}` | `SELECT COUNT(DISTINCT cs.session_id) FROM chatbot_sessions cs JOIN sessions s ON s.id = cs.session_id WHERE SUBSTR(cs.user_key, 1, INSTR(cs.user_key, ':') - 1) = '{platform}'` | 按 Bot 平台统计会话数 |
| `bot_messages_{platform}` | 同上 + JOIN messages | 按 Bot 平台统计消息数 |
| `bot_count_{platform}` | `SELECT COUNT(DISTINCT user_key) FROM chatbot_sessions WHERE SUBSTR(user_key, 1, INSTR(user_key, ':') - 1) = '{platform}'` | 按平台活跃 Bot 数 |
| `bot_platforms_used` | `SELECT COUNT(DISTINCT SUBSTR(user_key, 1, INSTR(user_key, ':') - 1)) FROM chatbot_sessions` | 使用的平台数 |

### 活跃天数回填

```sql
-- 统计所有有用户消息的不同日期
SELECT COUNT(DISTINCT DATE(m.created_at)) AS active_days
FROM messages m
WHERE m.role = 'user';
```

连续天数无法从历史数据精确恢复（中间可能有中断），所以 `streak_current` 设为 0，只回填 `total_active_days` 到 `achievement_stats`。

### 回填流程

```
1. 启动 AchievementEngine
2. 检查 achievement_stats 是否已有数据（key = '_initialized'）
3. 如果没有 → 执行 recalcStatsFromDB()
   a. 上述所有 SQL 查询写入 achievement_stats
   b. 遍历所有成就定义，根据 stat 值判断是否解锁
   c. 解锁的写入 achievement_progress（unlocked_at = NOW）
   d. 设置 _initialized = 'true'
4. 如果已有 → 跳过（增量事件会继续更新）
```

## 10. sman-server 排行榜服务端

排行榜需要 sman-server 提供全局聚合和排名能力。

### 10.1 新增数据库表（sman-server `hub.db`）

```sql
CREATE TABLE IF NOT EXISTS achievement_leaderboard (
  client_id TEXT NOT NULL,
  total_points INTEGER DEFAULT 0,
  total_unlocked INTEGER DEFAULT 0,
  dimension_scores TEXT,         -- JSON: {"conversation":50, "advanced":30, ...}
  tier_counts TEXT,              -- JSON: {"bronze":5, "silver":3, ...}
  weekly_points INTEGER DEFAULT 0,
  weekly_reset_at TEXT,
  updated_at TEXT,
  PRIMARY KEY (client_id)
);

CREATE INDEX IF NOT EXISTS idx_achievement_leaderboard_points
  ON achievement_leaderboard(total_points DESC);
```

### 10.2 新增 HTTP API（sman-server）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/hub/achievement-report` | 客户端上报成就数据（PSK 加密） |
| POST | `/api/hub/achievement-leaderboard` | 查询排行榜（PSK 加密，支持 dimension 参数） |

**`/api/hub/achievement-report`** 请求体（解密后）：
```typescript
{
  clientId: string;
  totalPoints: number;
  totalUnlocked: number;
  dimensionScores: Record<string, number>;
  tierCounts: Record<string, number>;
}
```
行为：`INSERT OR REPLACE` 到 `achievement_leaderboard`，更新 `weekly_points`（如果超过 `weekly_reset_at` 则重置为 0 并更新 `weekly_reset_at` 为本周结束时间）。

**`/api/hub/achievement-leaderboard`** 请求体（解密后）：
```typescript
{
  clientId: string;
  dimension?: string;  // 'total' | 'unlocked' | 'conversation' | 'advanced' | 'collaboration' | 'weekly'
}
```
返回：排序后的 leaderboard entries（TOP 100），请求者标记 `isMe`。

### 10.3 新增文件（sman-server）

```
sman-server/src/
├── db-achievements.ts          # AchievementDB 类（hub.db 新表）
└── routes/
    └── achievement-api.ts      # 上报 + 排行榜路由
```

### 10.4 同步流程

1. Sman 客户端每次成就变更时，通过 `POST /api/hub/achievement-report` 上报
2. 上报复用现有的 PSK 加密通道和 `/api/report` 的 clientId 机制
3. 排行榜查询通过 `POST /api/hub/achievement-leaderboard` 获取
4. 如果 sman-server 不可达，降级为本地 `achievement_board` 表（仅显示自己）

## 11. 性能考量

- 事件处理用 `setImmediate` 异步，不阻塞主流程
- 统计表 `INSERT OR REPLACE` 原子操作
- 内存 `Map<metric, definitions[]>` 索引，O(1) 查找
- 前端 Zustand store 持有成就列表，仅初始化和推送时更新
- `speed_demon` 彩蛋用内存滑动窗口，不查数据库
- 启动时 `recalcStatsFromDB()` 从现有 session/message/cron 等表重建统计，补偿历史数据
