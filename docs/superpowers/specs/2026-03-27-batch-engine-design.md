# 批量执行引擎（BatchEngine）设计文档

> 日期: 2026-03-27
> 状态: Draft (v2 - after review)

## 1. 背景与目标

### 问题

现有 Sman 的 Cron 系统只能定时执行**单个** skill（一个 crontab.md 对应一个周期性任务）。当用户需要对一个 skill 批量执行 N 个不同参数时（如分析 4000 只股票），无法满足。

用户需要一个通用的批量执行引擎，核心思路是 **MD 驱动 + Claude 代码生成**：
- 用户写 MD 描述数据获取方式
- Claude 生成获取数据的代码
- 测试验证 → 保存 → 并发执行

### 目标

1. 支持任何 skill 的批量执行（不限于 stock-ai-analyze）
2. MD 配置驱动，Claude 动态生成数据获取代码
3. 测试 → 修改 → 重试的迭代循环
4. 并发执行 + 实时进度追踪
5. 支持手动执行和定时执行

## 2. 用户工作流

```
1. 用户在 .claude/skills/<skill>/ 下创建 batch.md（描述数据获取方式）
2. Sman 设置页 → 批量任务 → 新建
3. 选择项目 + skill，系统加载 batch.md
4. 补充环境变量（如 MySQL 连接信息）
5. 点击"生成代码" → Claude 读 batch.md，生成数据获取脚本
6. 点击"测试" → 在项目目录执行生成的代码，返回 items 预览
7. 测试失败 → 修改 MD / 环境变量 → 重新生成
8. 测试通过 → 保存（代码 + 配置持久化）
9. 点击"执行" → 并发池批量跑，实时看进度
10. （可选）设置定时重复执行
```

## 3. batch.md 模板规范

### 文件位置

```
{workspace}/.claude/skills/{skillName}/batch.md
```

### 内容格式

```markdown
# Batch Configuration

## 数据获取
从 MySQL 数据库获取所有 A 股股票名称。

环境变量：
- DB_HOST: 数据库地址
- DB_PORT: 端口
- DB_USER: 用户名
- DB_PASSWORD: 密码
- DB_NAME: 数据库名

## 执行模板
每条数据执行以下命令：
/stock-ai-analyze ${name}

## 数据格式要求
输出 JSON 数组，每个元素包含 name 字段：
[{"name": "贵州茅台"}, {"name": "中国平安"}, ...]
```

### 模板要素

| 要素 | 必需 | 说明 |
|------|------|------|
| 数据获取 | 是 | 描述数据来源、查询逻辑 |
| 环境变量 | 视情况 | Claude 生成代码时需要的连接信息 |
| 执行模板 | 是 | `${field_name}` 占位符，JSON 对象字段名作为 key |
| 数据格式要求 | 是 | Claude 输出的 JSON 数组格式 |

### 执行模板替换规则

- `${field_name}` 语法，`field_name` 对应 item JSON 对象的字段名
- 支持多字段：`/stock-ai-analyze ${name} --code ${code}`
- 替换逻辑：遍历 item JSON 对象的所有 key，将 `${key}` 替换为对应 value
- 未匹配的占位符保留原样（不抛异常，方便调试）
- 示例：item = `{"name": "贵州茅台", "code": "600519"}`，模板 `/stock-ai-analyze ${name} --code ${code}` → `/stock-ai-analyze 贵州茅台 --code 600519`

## 4. 任务生命周期

```
draft      → 用户填写配置，未生成代码
generating → Claude 正在生成代码
generated  → 代码已生成，未测试
testing    → 正在执行测试
tested     → 测试通过，可保存
saved      → 代码和配置已持久化
queued     → 等待执行（已提交到并发池队列）
running    → 正在并发执行
paused     → 等待中的 item 暂停入队，已运行的 item 等待完成
completed  → 全部完成
failed     → 执行异常终止
```

### 生命周期转换规则

| 当前状态 | 触发 | 目标状态 |
|----------|------|----------|
| draft | 生成代码 | generating |
| generating | 生成完成 | generated |
| generating | 生成失败 | draft |
| generated | 测试 | testing |
| testing | 测试通过 | tested |
| testing | 测试失败 | generated |
| tested | 保存 | saved |
| saved | 执行 | queued |
| queued | 第一个 item 开始 | running |
| running | 全部完成 | completed |
| running | 全部失败 | failed |
| running | 用户暂停 | paused |
| paused | 用户恢复 | running |
| running | 用户终止 | failed |
| paused | 用户终止 | failed |

## 5. 后端架构

### 新增文件

```
server/
├── batch-engine.ts    # 核心引擎（生成、测试、执行）
├── batch-store.ts     # SQLite 持久化
├── semaphore.ts       # 并发池信号量（独立模块）
└── types.ts           # 新增 BatchTask 等类型（追加到现有文件）
```

### 数据库表设计

#### batch_tasks（批量任务主表）

```sql
CREATE TABLE IF NOT EXISTS batch_tasks (
  id TEXT PRIMARY KEY,
  workspace TEXT NOT NULL,
  skill_name TEXT NOT NULL,
  md_content TEXT NOT NULL,           -- batch.md 原始内容
  exec_template TEXT NOT NULL,        -- 执行模板（从 batch.md 提取）
  generated_code TEXT,                -- Claude 生成的数据获取代码
  env_vars TEXT NOT NULL DEFAULT '{}', -- JSON: 环境变量（AES-256-GCM 加密）
  concurrency INTEGER NOT NULL DEFAULT 10,
  retry_on_failure INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'draft'
    CHECK(status IN ('draft','generating','generated','testing','tested','saved','queued','running','paused','completed','failed')),
  total_items INTEGER DEFAULT 0,      -- 总条目数
  success_count INTEGER DEFAULT 0,
  failed_count INTEGER DEFAULT 0,
  total_cost REAL DEFAULT 0,          -- 累计花费（USD）
  started_at TEXT,                    -- 执行开始时间
  finished_at TEXT,                   -- 执行结束时间
  cron_enabled INTEGER DEFAULT 0,     -- 是否启用定时执行
  cron_interval_minutes INTEGER,      -- 定时间隔（分钟）
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

#### batch_items（每条执行记录）

```sql
CREATE TABLE IF NOT EXISTS batch_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  item_data TEXT NOT NULL,            -- JSON: 该条目的数据
  item_index INTEGER NOT NULL,        -- 原始序号（从 0 开始）
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK(status IN ('pending','queued','running','success','failed','skipped')),
  session_id TEXT,                    -- 执行该条目的 SDK session ID
  started_at TEXT,
  finished_at TEXT,
  error_message TEXT,
  cost REAL DEFAULT 0,                -- 该条目花费
  retries INTEGER DEFAULT 0,
  FOREIGN KEY (task_id) REFERENCES batch_tasks(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_batch_items_task ON batch_items(task_id);
CREATE INDEX IF NOT EXISTS idx_batch_items_status ON batch_items(task_id, status);
```

### WebSocket API

| 消息类型 | 方向 | 说明 |
|----------|------|------|
| `batch.create` | C→S | 创建批量任务 `{workspace, skillName, mdContent, envVars, concurrency, retryOnFailure}` |
| `batch.created` | S→C | 创建成功 `{task}` |
| `batch.list` | C→S | 列出所有批量任务 |
| `batch.list` | S→C | 任务列表 `{tasks: [...]}` |
| `batch.get` | C→S | 获取单个任务详情 `{taskId}` |
| `batch.get` | S→C | 任务详情 `{task}` |
| `batch.update` | C→S | 更新任务配置 |
| `batch.updated` | S→C | 更新成功 `{task}` |
| `batch.delete` | C→S | 删除任务 `{taskId}` |
| `batch.deleted` | S→C | 删除成功 |
| `batch.generate` | C→S | 调用 Claude 生成代码 `{taskId}` |
| `batch.generating` | S→C | 开始生成 |
| `batch.generated` | S→C | 生成完成 `{taskId, code}` |
| `batch.test` | C→S | 测试生成的代码 `{taskId}` |
| `batch.testing` | S→C | 开始测试 |
| `batch.tested` | S→C | 测试完成 `{taskId, items, total, preview}` |
| `batch.save` | C→S | 保存代码和配置 `{taskId}` |
| `batch.saved` | S→C | 保存成功 |
| `batch.execute` | C→S | 开始并发执行 `{taskId}` |
| `batch.executed` | S→C | 开始执行 `{taskId}` |
| `batch.pause` | C→S | 暂停执行 `{taskId}` |
| `batch.paused` | S→C | 已暂停 `{taskId}` |
| `batch.resume` | C→S | 恢复执行 `{taskId}` |
| `batch.resumed` | S→C | 已恢复 `{taskId}` |
| `batch.cancel` | C→S | 终止执行 `{taskId}` |
| `batch.cancelled` | S→C | 已终止 `{taskId}` |
| `batch.items` | C→S | 查询 items 列表 `{taskId, status?, offset?, limit?}` |
| `batch.items` | S→C | items 列表 `{taskId, items: [...], total, offset, limit}` |
| `batch.retry` | C→S | 重试失败的 items `{taskId}` |
| `batch.retried` | S→C | 重试已提交 `{taskId, retryCount}` |
| `batch.progress` | S→C | 进度推送（主动推送，每完成一个 item 时） |

### 核心类：BatchEngine

```typescript
class BatchEngine {
  // === 代码生成（独立 session，不污染执行历史） ===
  generateCode(task: BatchTask): Promise<string>

  // === 测试（沙箱执行） ===
  testCode(task: BatchTask): Promise<{ items: any[]; preview: string }>

  // === 并发执行 ===
  execute(taskId: string): Promise<void>

  // === 执行控制 ===
  pause(taskId: string): void
  resume(taskId: string): void
  cancel(taskId: string): void

  // === 重试 ===
  retryFailed(taskId: string): Promise<void>

  // === 定时集成 ===
  syncCronSchedule(taskId: string): void  // 注册/取消 CronScheduler

  // === 生命周期 ===
  start(): void   // 启动时恢复 orphaned running items
  stop(): void    // 优雅关闭
}
```

### 并发模型

**方案：Node.js async 并发池（带暂停功能的 Semaphore）**

```typescript
// semaphore.ts - 独立模块
class Semaphore {
  private waiters: Array<() => void> = [];
  private _paused = false;
  private _stopped = false;

  constructor(private max: number) {}

  async acquire(): Promise<void> {
    if (this._stopped) throw new Error('Semaphore stopped');
    if (this._paused) {
      await new Promise<void>(resolve => {
        const check = () => { if (!this._paused || this._stopped) resolve(); };
        this.waiters.push(check);
      });
      if (this._stopped) throw new Error('Semaphore stopped');
    }
    while (this.active >= this.max) {
      await new Promise<void>(resolve => this.waiters.push(resolve));
    }
    this.active++;
  }

  release(): void {
    this.active--;
    const next = this.waiters.shift();
    if (next) next();
  }

  pause(): void {
    this._paused = true;  // 新的 acquire 请求会等待
  }

  resume(): void {
    this._paused = false;
    // 唤醒所有等待的 pause waiters
    const pausedWaiters = this.waiters;
    this.waiters = [];
    pausedWaiters.forEach(fn => fn());
  }

  stop(): void {
    this._stopped = true;
    this.waiters.forEach(fn => fn());  // 唤醒所有等待者，让它们抛错退出
    this.waiters = [];
  }
}
```

**执行循环**（基于 for-loop 而非 Promise.all，支持暂停/终止）：

```typescript
async execute(taskId: string): Promise<void> {
  const items = this.store.getPendingItems(taskId);
  const semaphore = new Semaphore(task.concurrency);

  for (const item of items) {
    // 检查是否被暂停或终止
    if (this.isPaused(taskId) || this.isCancelled(taskId)) {
      // 标记剩余 items 为 skipped
      this.markRemainingSkipped(taskId, item.id);
      break;
    }

    // 标记为 queued
    this.store.updateItem(item.id, { status: 'queued' });

    await semaphore.acquire();
    this.processItem(taskId, item).finally(() => semaphore.release());
  }

  // 等待所有进行中的 item 完成
  await this.drainActiveItems(taskId);
}
```

**为什么 pause 等待当前 item 完成**：
- Claude Agent SDK 的 query() 是 HTTP 长连接，无法安全中断
- 强制中断会导致 session 状态不一致
- 等待当前 item 完成 + 阻止新 item 入队，是最安全的暂停方式
- 已运行的 10 个 item 自然完成，不会丢失数据

### 代码生成流程（独立 session）

代码生成**不使用** `sendMessageForCron()`，避免污染 session 历史。使用独立的 `query()` 调用：

```
BatchEngine.generateCode():
1. 读取 batch.md 内容
2. 读取用户配置的环境变量 key 列表（不含 value）
3. 构造生成 prompt：
   - system: 固定的代码生成 system prompt（内置在 BatchEngine 中）
   - user: batch.md 内容 + 环境变量 key 列表 + "输出格式必须是 JSON 数组"
4. 直接调用 query()（不创建持久 session，不保存到 session store）
5. 从响应中提取代码块（```python 或 ```js 代码块）
6. 存入 batch_tasks.generated_code
7. query 结束后所有临时状态自动释放
```

**为什么独立调用**：
- `sendMessageForCron()` 会创建持久 session、保存消息历史、管理 SDK session ID
- 代码生成是一次性操作，不需要这些
- 避免代码生成消息混入执行历史

### 测试流程（沙箱执行）

生成的代码作为**子进程**执行，带安全限制：

```typescript
async testCode(task: BatchTask): Promise<{ items: any[]; preview: string }> {
  // 1. 从 batch_tasks.generated_code 读取代码
  const code = store.getTask(taskId).generated_code;

  // 2. 确定解释器（根据代码语言后缀或 batch.md 中的语言声明）
  const interpreter = detectInterpreter(code); // 'python3' | 'node' | ...

  // 3. 创建临时脚本文件（在 workspace/tmp/ 目录下）
  const tmpDir = path.join(workspace, 'tmp', `batch-test-${Date.now()}`);
  fs.mkdirSync(tmpDir, { recursive: true });
  const scriptPath = path.join(tmpDir, 'fetch-data');
  fs.writeFileSync(scriptPath, code);

  // 4. 作为子进程执行，带安全限制
  const result = await execFile(interpreter, [scriptPath], {
    cwd: tmpDir,                     // 限制工作目录
    timeout: 30_000,                 // 30 秒超时
    env: { ...process.env, ...userEnvVars },  // 注入用户环境变量
    maxBuffer: 10 * 1024 * 1024,     // 10MB 输出限制
  });

  // 5. 解析 stdout 为 JSON 数组
  const items = JSON.parse(result.stdout.trim());

  // 6. 限制最大条目数
  if (items.length > 100_000) {
    throw new Error(`数据量过大: ${items.length} 条，上限 100,000 条`);
  }

  // 7. 清理临时文件
  fs.rmSync(tmpDir, { recursive: true, force: true });

  return {
    items,
    preview: items.slice(0, 10).map(i => JSON.stringify(i)).join('\n'),
  };
}
```

**安全限制**：
- `execFile()`（非 `exec()`），不经过 shell，防命令注入
- `cwd` 限制在 workspace/tmp/ 子目录
- 30 秒超时，防死循环
- 10MB 输出限制，防内存溢出
- 100,000 条上限，防数据爆炸
- 执行后立即清理临时文件

### 执行流程

```
BatchEngine.execute(taskId):
1. 状态校验：status 必须为 saved
2. 防重复：status 设为 running，利用状态机保证幂等
3. 重置计数器：success_count=0, failed_count=0, total_cost=0
4. 将所有 pending items 重置为 pending（支持重试场景）
5. 从 batch_items 表加载所有 pending items
6. 创建 Semaphore(concurrency)
7. for-loop 遍历每个 item：
   a. 检查 pause/cancel 状态
   b. semaphore.acquire()（等待令牌）
   c. 标记 item 为 running，记录 started_at
   d. 用执行模板替换 ${field_name} 占位符
   e. 调用 sendMessageForCron()（每个 item 独立 session）
   f. 成功 → 标记 success，累加 cost
   g. 失败 → 标记 failed，记录错误信息
   h. semaphore.release()
   i. 更新 batch_tasks 计数器 + 推送 batch.progress 给前端
8. drainActiveItems()：等待所有进行中的 item 完成
9. 判定最终状态：全部完成 → completed / 有失败 → running（标记 completed，但不改）
10. 记录 finished_at
```

### 模板替换实现

```typescript
function renderTemplate(template: string, item: Record<string, unknown>): string {
  return template.replace(/\$\{(\w+)\}/g, (match, field) => {
    const value = item[field];
    return value !== undefined ? String(value) : match;  // 未匹配保留原样
  });
}
```

### 进度推送策略

- 每个 item 完成（成功或失败）时，主动推送 `batch.progress` 给前端
- 前端通过 WebSocket 事件监听接收，无需轮询
- 推送格式：`{ taskId, successCount, failedCount, runningCount, totalItems, totalCost, lastItem? }`
- 对于列表页的进度条，前端收到的 progress 事件即可实时更新

### 费用追踪

- 每条 item 执行后，从 SDK result 中提取 `total_cost_usd`
- 累加到 `batch_tasks.total_cost`
- 记录到 `batch_items.cost`
- 前端进度页显示总费用

## 6. 前端设计

### 页面位置

设置页新增"批量任务"区块（放在 CronTaskSettings 下面）：

```
设置页面
├── 定时任务（现有 CronTaskSettings）
├── 批量任务（新增 BatchTaskSettings）  ← 新增
├── 模型配置
└── 网络搜索配置
```

### 新增文件

```
src/
├── features/settings/BatchTaskSettings.tsx  # 批量任务主组件
├── features/settings/BatchTaskDialog.tsx    # 新建/编辑 Dialog
├── features/settings/BatchProgress.tsx      # 执行进度组件
├── stores/batch.ts                          # Zustand store
└── types/settings.ts                        # 新增 BatchTask 类型（追加）
```

### UI 组件

#### 1. 任务列表

每个任务显示为卡片，包含：
- skill 名称 + 项目名称
- 进度条（已完成/总数）
- 计数器：成功 / 失败 / 运行中 / 等待
- 状态 badge
- 总费用
- 耗时
- 操作按钮：编辑 / 执行 / 暂停 / 终止 / 重试失败 / 删除

#### 2. 新建/编辑 Dialog

表单字段：
- 项目目录（下拉选择 workspace）
- Skill（下拉选择，依赖 workspace）
- batch.md 内容（textarea，自动加载已有文件）
- 执行模板（从 batch.md 自动提取，可编辑）
- 环境变量（动态 key-value 输入对，密码字段隐藏显示）
- 并发数（number input，默认 10）
- 失败重试次数（number input，默认 0）
- 操作按钮：生成代码 / 测试 / 保存

代码区域：
- 生成后显示代码（只读 textarea）
- 测试结果区域：
  - 成功时：显示 items 预览（前 10 条）+ 总数
  - 失败时：显示错误信息 + 重试按钮

#### 3. 执行进度

点击任务卡片展开进度详情：
- 进度条 + 百分比
- 计数器实时更新（通过 WebSocket `batch.progress` 推送）
- 总费用
- 最近失败列表（通过 `batch.items` API 拉取 status=failed 的 items）
- 操作按钮：暂停 / 终止 / 重试失败项

### WebSocket 订阅模式

```typescript
// stores/batch.ts
// 在 store 初始化时注册 progress 事件监听
wsClient.on('batch.progress', (data) => {
  set(state => {
    const task = state.tasks.find(t => t.id === data.taskId);
    if (task) {
      task.successCount = data.successCount;
      task.failedCount = data.failedCount;
      task.runningCount = data.runningCount;
      task.totalCost = data.totalCost;
    }
  });
});
```

## 7. 与现有系统的关系

### 复用

| 组件 | 复用方式 |
|------|---------|
| `ClaudeSessionManager.sendMessageForCron()` | 直接复用，每个 item 的执行走这个方法 |
| `ClaudeSessionManager.createSessionWithId()` | 直接复用，为每个 item 创建独立 session |
| `ClaudeSessionManager.abort()` | 直接复用，终止时 abort 运行中的 item |

### 区别

| 维度 | Cron | Batch |
|------|------|-------|
| 本质 | 定时重复执行 1 个任务 | 一次性/定时执行 N 个任务 |
| 数据来源 | crontab.md 固定内容 | batch.md + Claude 生成代码动态获取 |
| 并发 | 单次执行 1 个 | 多个并发执行 |
| 进度追踪 | 简单（成功/失败） | 详细（每条 item 状态 + 费用） |
| 用户交互 | 配置后自动跑 | 生成→测试→确认→执行 |

### 定时集成方案

Batch 任务的定时执行复用 CronScheduler，但使用**独立注册**：

- BatchEngine 在 `syncCronSchedule()` 中管理定时注册
- `cron_enabled=true` 时，调用 `CronScheduler` 注册一个 "batch" 类型的定时触发
- 触发时，CronScheduler 调用 `BatchEngine.execute(taskId)` 而不是 `CronExecutor.execute()`
- CronScheduler 需小幅改动：支持外部注册的回调函数，不再只依赖 cron_tasks 表

**实现方式**：CronScheduler 新增 `registerCallback(taskId, intervalMinutes, callback)` 方法，与现有的 cron_tasks 表调度并存。

## 8. 安全考虑

- **环境变量加密**：使用 Node.js 内置 `crypto.createCipheriv('aes-256-gcm', ...)` 加密 env_vars，密钥派生自机器唯一标识（mac address），存储在内存中，不持久化
- **代码沙箱执行**：生成的代码通过 `execFile()` 以子进程运行，限制 `cwd`、超时 30 秒、输出 10MB 上限
- **工作目录隔离**：临时脚本放在 `workspace/tmp/` 下，执行后立即清理
- **数据量上限**：单个批量任务最多 100,000 条 items
- **执行超时**：每个 item 的 SDK 调用受 30 分钟假死检测保护（复用现有机制）
- **幂等保护**：execute 操作通过状态机保证幂等（只有 saved 状态可以执行）

## 9. 优雅关闭与崩溃恢复

### 优雅关闭

```
进程收到 SIGTERM/SIGINT:
1. BatchEngine.stop() 被调用
2. Semaphore.stop() 唤醒所有等待者
3. 等待当前运行中的 item 自然完成（最多 30 秒）
4. 将所有 running 状态的 items 标记为 failed，error_message = '进程关闭'
5. 更新 batch_tasks 状态为 failed
```

### 崩溃恢复

```
进程启动时 BatchEngine.start():
1. 查询所有 status = 'running' 的 batch_tasks
2. 查询这些 tasks 下 status = 'running' 的 batch_items
3. 将这些 orphaned items 标记为 failed，error_message = '进程异常终止'
4. 将对应的 batch_tasks 标记为 failed
5. 记录日志
```

## 10. 扩展性

- 支持多种代码生成语言（Python / Node.js / Shell），由 batch.md 中指定
- 支持执行模板中的多个占位符（如 `${name}` `${code}`）——已在核心设计中实现
- 支持条件过滤（如只执行满足条件的 items）
- 支持失败重试策略（单个重试 / 全部重试）——已通过 batch.retry API 实现
- 未来可考虑：进度条 WebSocket 推送的节流（高频场景下每 N 个 item 推送一次）
