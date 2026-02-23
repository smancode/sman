# PRD: BackgroundScheduler 后台调度器

> 版本: 1.0
> 日期: 2026-02-23
> 作者: Claude

---

## 一、背景与目标

### 1.1 背景

当前 PuzzleCoordinator 是**触发式执行**：
- `start()` 只执行一次恢复和检测
- `trigger()` 需要外部调用才会执行

这不符合"自迭代"的核心理念——系统应该**自主运行**，持续发现和完成任务。

### 1.2 目标

构建一个**稳定的后台调度器**：
1. **常驻后台**：插件启动后一直运行
2. **自主发现**：自动检测空白、生成任务、执行分析
3. **智能调度**：根据系统负载、用户活动动态调整
4. **稳定可靠**：不崩溃、不阻塞、可恢复

---

## 二、参考设计（来自 OpenClaw）

### 2.1 两层调度系统

```
┌─────────────────────────────────────────────────────────────┐
│                     两层调度架构                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Layer 1: Heartbeat Runner（轻量级心跳）                    │
│  ────────────────────────────────────────                   │
│  - 定期唤醒（默认 5 分钟）                                   │
│  - 检查是否有待执行任务                                      │
│  - 触发 PuzzleCoordinator.trigger(SCHEDULED)               │
│                                                             │
│  Layer 2: Task Executor（任务执行器）                       │
│  ────────────────────────────────────────                   │
│  - 实际执行分析任务                                          │
│  - 受 Token 预算限制                                         │
│  - 有超时和重试机制                                          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计模式

| 模式 | 描述 | 借鉴价值 |
|------|------|---------|
| **Lane 隔离** | 不同类型任务隔离执行 | 高 - 避免后台阻塞前台 |
| **Timer Clamp** | 最大定时器延迟 60 秒 | 中 - 防止系统休眠后漂移 |
| **Coalesce Window** | 250ms 请求合并窗口 | 高 - 避免频繁唤醒 |
| **Generation Counter** | 防止旧状态干扰 | 高 - 安全重启 |
| **Exponential Backoff** | 错误后指数退避 | 高 - 稳定性保障 |
| **AbortController** | 任务取消机制 | 高 - 超时控制 |

---

## 三、核心需求

### 3.1 功能需求

| 需求 | 描述 | 优先级 |
|------|------|--------|
| F1 | 常驻后台运行，定期触发自迭代 | P0 |
| F2 | 可配置执行间隔（默认 5 分钟） | P0 |
| F3 | 用户活动检测：活跃时暂停后台任务 | P0 |
| F4 | 优雅启停：不丢失任务状态 | P0 |
| F5 | 错误恢复：连续失败后指数退避 | P0 |
| F6 | 可通过 UI 手动触发 | P1 |
| F7 | 运行状态可视化 | P1 |
| F8 | 动态配置更新 | P2 |

### 3.2 非功能需求

| 需求 | 描述 |
|------|------|
| NF1 | 不影响 IDE 响应速度 |
| NF2 | 内存占用 < 50MB |
| NF3 | 单次执行时间 < 30 秒 |
| NF4 | 支持优雅降级 |

---

## 四、架构设计

### 4.1 组件架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    BackgroundScheduler                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │ HeartbeatRunner │  │ ActivityMonitor │  │  BackoffPolicy  │ │
│  │   心跳运行器     │  │  活动监视器      │  │   退避策略      │ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘ │
│           │                    │                    │          │
│           └────────────────────┼────────────────────┘          │
│                                ▼                                │
│                    ┌─────────────────────┐                     │
│                    │   SchedulerCore     │                     │
│                    │     调度核心        │                     │
│                    └──────────┬──────────┘                     │
│                               ▼                                 │
│                    ┌─────────────────────┐                     │
│                    │ PuzzleCoordinator   │                     │
│                    │      协调器         │                     │
│                    └─────────────────────┘                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 状态机

```
┌─────────────────────────────────────────────────────────────────┐
│                    Scheduler 状态机                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                    ┌─────────┐                                  │
│                    │  IDLE   │◄─────────────────────┐           │
│                    └────┬────┘                      │           │
│                         │ start()                   │           │
│                         ▼                           │           │
│                    ┌─────────┐                      │           │
│         ┌─────────│ RUNNING │─────────┐            │           │
│         │         └────┬────┘         │            │           │
│         │              │              │            │           │
│    用户活跃      执行完成/超时      错误           │           │
│         │              │              │            │           │
│         ▼              ▼              ▼            │           │
│    ┌─────────┐   ┌─────────┐   ┌─────────┐        │           │
│    │ PAUSED  │   │  IDLE   │   │BACKOFF  │────────┘           │
│    └────┬────┘   └─────────┘   └────┬────┘  退避后恢复         │
│         │                           │                          │
│         └───────────────────────────┘                          │
│                    用户空闲后恢复                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 Lane 隔离设计

借鉴 OpenClaw 的 Lane 概念，将任务分为不同通道：

```kotlin
enum class TaskLane {
    /** 主通道：用户交互响应 */
    MAIN,

    /** 后台通道：自动分析任务 */
    BACKGROUND,

    /** 低优先级通道：知识整理、清理等 */
    LOW_PRIORITY
}

data class LaneConfig(
    val maxConcurrent: Int,      // 最大并发数
    val queueCapacity: Int,      // 队列容量
    val priority: Int            // 优先级（越高越优先）
)
```

**默认配置**：

| Lane | maxConcurrent | queueCapacity | priority |
|------|--------------|---------------|----------|
| MAIN | 3 | 100 | 100 |
| BACKGROUND | 1 | 50 | 50 |
| LOW_PRIORITY | 1 | 20 | 10 |

---

## 五、详细设计

### 5.1 HeartbeatRunner

```kotlin
/**
 * 心跳运行器
 *
 * 负责定期触发后台任务
 */
class HeartbeatRunner(
    private val schedulerCore: SchedulerCore,
    private val config: SchedulerConfig
) {
    companion object {
        /** 默认心跳间隔（毫秒） */
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L  // 5 分钟

        /** 最小心跳间隔 */
        const val MIN_INTERVAL_MS = 30 * 1000L  // 30 秒

        /** 最大定时器延迟（防止系统休眠后漂移） */
        const val MAX_TIMER_DELAY_MS = 60 * 1000L  // 60 秒
    }

    private var generation: Int = 0
    private var isRunning: Boolean = false
    private var nextWakeTime: Instant? = null

    /**
     * 启动心跳
     */
    fun start()

    /**
     * 停止心跳
     */
    fun stop()

    /**
     * 请求立即唤醒（合并窗口 250ms）
     */
    fun requestWakeNow(reason: WakeReason = WakeReason.MANUAL)

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: SchedulerConfig)
}

enum class WakeReason {
    INTERVAL,      // 定时触发
    FILE_CHANGE,   // 文件变更
    USER_QUERY,    // 用户查询
    MANUAL         // 手动触发
}
```

### 5.2 ActivityMonitor

```kotlin
/**
 * 用户活动监视器
 *
 * 检测用户活跃状态，活跃时暂停后台任务
 */
class ActivityMonitor(
    private val project: Project
) {
    companion object {
        /** 用户活跃判定阈值（毫秒） */
        const val ACTIVITY_THRESHOLD_MS = 60 * 1000L  // 1 分钟无操作视为空闲
    }

    /**
     * 检查用户是否活跃
     */
    fun isUserActive(): Boolean

    /**
     * 获取用户空闲时间
     */
    fun getIdleTime(): Duration

    /**
     * 注册活动监听器
     */
    fun registerListener(listener: ActivityListener)

    /**
     * 记录用户活动
     */
    fun recordActivity()
}
```

### 5.3 BackoffPolicy

```kotlin
/**
 * 退避策略
 *
 * 连续失败后指数退避，避免疯狂重试
 */
class BackoffPolicy {
    companion object {
        /** 退避时间表（毫秒） */
        val BACKOFF_SCHEDULE_MS = listOf(
            30_000L,        // 第 1 次失败 → 30 秒
            60_000L,        // 第 2 次失败 → 1 分钟
            5 * 60_000L,    // 第 3 次失败 → 5 分钟
            15 * 60_000L,   // 第 4 次失败 → 15 分钟
            60 * 60_000L    // 第 5+ 次失败 → 1 小时
        )
    }

    private var consecutiveErrors: Int = 0
    private var lastErrorTime: Instant? = null

    /**
     * 记录错误
     */
    fun recordError()

    /**
     * 记录成功（重置退避）
     */
    fun recordSuccess()

    /**
     * 获取下次执行前的等待时间
     */
    fun getNextDelayMs(): Long

    /**
     * 是否处于退避状态
     */
    fun isInBackoff(): Boolean
}
```

### 5.4 SchedulerCore

```kotlin
/**
 * 调度核心
 *
 * 协调各个组件，执行实际的调度逻辑
 */
class SchedulerCore(
    private val coordinator: PuzzleCoordinator,
    private val activityMonitor: ActivityMonitor,
    private val backoffPolicy: BackoffPolicy,
    private val config: SchedulerConfig
) {
    private val state = AtomicReference(SchedulerState.IDLE)
    private val generation = AtomicInteger(0)

    /**
     * 执行一次调度
     */
    suspend fun tick(): TickResult

    /**
     * 获取当前状态
     */
    fun getState(): SchedulerState

    /**
     * 暂停调度
     */
    fun pause()

    /**
     * 恢复调度
     */
    fun resume()
}

enum class SchedulerState {
    IDLE,       // 空闲
    RUNNING,    // 运行中
    PAUSED,     // 暂停（用户活跃）
    BACKOFF     // 退避（连续错误）
}

sealed class TickResult {
    object Success : TickResult()
    object Skipped : TickResult()
    object Paused : TickResult()
    data class Error(val exception: Throwable) : TickResult()
}
```

### 5.5 BackgroundScheduler（主入口）

```kotlin
/**
 * 后台调度器主入口
 *
 * 整合所有组件，提供统一的生命周期管理
 */
class BackgroundScheduler(
    private val project: Project,
    private val coordinator: PuzzleCoordinator
) : Disposable {

    private val config: SchedulerConfig
    private val activityMonitor: ActivityMonitor
    private val backoffPolicy: BackoffPolicy
    private val schedulerCore: SchedulerCore
    private val heartbeatRunner: HeartbeatRunner

    /**
     * 启动调度器
     */
    fun start()

    /**
     * 停止调度器
     */
    fun stop()

    /**
     * 手动触发一次
     */
    fun triggerNow()

    /**
     * 获取状态
     */
    fun getStatus(): SchedulerStatus

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: SchedulerConfig)

    override fun dispose() {
        stop()
    }
}

data class SchedulerConfig(
    val enabled: Boolean = true,
    val intervalMs: Long = 5 * 60 * 1000L,  // 5 分钟
    val maxTokensPerTick: Int = 4000,
    val pauseOnUserActive: Boolean = true,
    val activityThresholdMs: Long = 60 * 1000L  // 1 分钟
)

data class SchedulerStatus(
    val state: SchedulerState,
    val lastTickTime: Instant?,
    val lastTickResult: TickResult?,
    val nextTickTime: Instant?,
    val consecutiveErrors: Int,
    val totalTicks: Long,
    val totalErrors: Long
)
```

---

## 六、IntelliJ 平台集成

### 6.1 后台任务 API

使用 IntelliJ 的 `Task.Backgroundable`：

```kotlin
class BackgroundAnalysisTask(
    project: Project,
    private val coordinator: PuzzleCoordinator
) : Task.Backgroundable(project, "SmanCode Analysis", true) {

    override fun run(indicator: ProgressIndicator) {
        indicator.text = "Analyzing project knowledge..."
        indicator.isIndeterminate = false

        // 执行分析
        runBlocking {
            coordinator.trigger(TriggerType.SCHEDULED)
        }
    }

    override fun onSuccess() {
        // 更新状态
    }

    override fun onThrowable(error: Throwable) {
        // 错误处理
    }
}
```

### 6.2 定时任务

使用 `JobScheduler` 或 `Alarm`：

```kotlin
// 方案 1: 使用 Alarm（推荐）
class SchedulerAlarm(
    private val project: Project,
    private val scheduler: BackgroundScheduler
) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    fun scheduleNext(delayMs: Long) {
        alarm.addRequest({
            scheduler.tick()
            scheduleNext(config.intervalMs)
        }, delayMs)
    }

    fun cancel() {
        alarm.cancelAllRequests()
    }

    override fun dispose() {
        cancel()
    }
}

// 方案 2: 使用 JobScheduler
class SchedulerJob(
    private val scheduler: BackgroundScheduler
) {

    private val scheduledFuture: ScheduledFuture<*>?

    fun start() {
        scheduledFuture = JobScheduler.getScheduler().scheduleWithFixedDelay(
            { scheduler.tick() },
            config.intervalMs,
            config.intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        scheduledFuture?.cancel(false)
    }
}
```

### 6.3 用户活动监听

```kotlin
class UserActivityListener(
    private val activityMonitor: ActivityMonitor
) : DynamicPluginListener, FileEditorManagerListener {

    // 文件编辑
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        activityMonitor.recordActivity()
    }

    // 文档变更
    override fun documentChanged(event: DocumentEvent) {
        activityMonitor.recordActivity()
    }

    // 可以添加更多监听器...
}
```

---

## 七、配置管理

### 7.1 配置存储

使用 IntelliJ 的 `PersistentStateComponent`：

```kotlin
@State(
    name = "SmanCodeSchedulerConfig",
    storages = [Storage("sman-config.xml")]
)
class SchedulerConfigService : PersistentStateComponent<SchedulerConfig> {

    private var config = SchedulerConfig()

    override fun getState(): SchedulerConfig = config

    override fun loadState(state: SchedulerConfig) {
        config = state
    }
}
```

### 7.2 默认配置

```yaml
# sman-config.yaml
scheduler:
  enabled: true
  interval_ms: 300000       # 5 分钟
  max_tokens_per_tick: 4000
  pause_on_user_active: true
  activity_threshold_ms: 60000

  lanes:
    main:
      max_concurrent: 3
      queue_capacity: 100
      priority: 100
    background:
      max_concurrent: 1
      queue_capacity: 50
      priority: 50
    low_priority:
      max_concurrent: 1
      queue_capacity: 20
      priority: 10

  backoff:
    schedule_ms: [30000, 60000, 300000, 900000, 3600000]
```

---

## 八、UI 集成

### 8.1 状态栏显示

```kotlin
class SchedulerStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun getText(): String {
        return when (scheduler.getState()) {
            SchedulerState.IDLE -> "SmanCode: 空闲"
            SchedulerState.RUNNING -> "SmanCode: 分析中..."
            SchedulerState.PAUSED -> "SmanCode: 已暂停"
            SchedulerState.BACKOFF -> "SmanCode: 退避中"
        }
    }

    override fun getTooltipText(): String {
        val status = scheduler.getStatus()
        return """
            下次执行: ${status.nextTickTime}
            连续错误: ${status.consecutiveErrors}
            总执行次数: ${status.totalTicks}
        """.trimIndent()
    }
}
```

### 8.2 手动触发按钮

```kotlin
class TriggerAnalysisAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val scheduler = ServiceManager.getService(project, BackgroundScheduler::class.java)

        scheduler.triggerNow()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
```

---

## 九、测试场景

### 9.1 单元测试

| 测试类 | 测试场景 |
|--------|---------|
| HeartbeatRunnerTest | 心跳触发、合并窗口、配置更新 |
| ActivityMonitorTest | 活跃检测、空闲时间计算 |
| BackoffPolicyTest | 退避计算、成功重置 |
| SchedulerCoreTest | 状态转换、暂停恢复 |

### 9.2 健壮性测试

| 场景 | 验证点 |
|------|--------|
| 长时间运行 | 24 小时无崩溃 |
| 连续错误 | 退避策略生效 |
| 用户活跃 | 正确暂停和恢复 |
| 插件重启 | 状态恢复 |
| 高负载 | 不阻塞 IDE |

### 9.3 E2E 测试

| 场景 | 验证点 |
|------|--------|
| 完整自迭代循环 | 检测 → 调度 → 执行 → 更新 |
| 多任务并发 | Lane 隔离生效 |
| 配置热更新 | 无需重启生效 |

---

## 十、实施计划

### Phase 5.1: 核心组件 (2 天)

| 任务 | 交付物 |
|------|--------|
| HeartbeatRunner | 心跳运行器 + 10 个测试 |
| BackoffPolicy | 退避策略 + 8 个测试 |
| ActivityMonitor | 活动监视器 + 8 个测试 |

### Phase 5.2: 调度核心 (2 天)

| 任务 | 交付物 |
|------|--------|
| SchedulerCore | 调度核心 + 12 个测试 |
| Lane 隔离 | 通道隔离 + 10 个测试 |

### Phase 5.3: 集成与 UI (2 天)

| 任务 | 交付物 |
|------|--------|
| BackgroundScheduler | 主入口 + 10 个测试 |
| 状态栏显示 | UI 集成 |
| 配置管理 | 持久化 |

### Phase 5.4: E2E 验证 (1 天)

| 任务 | 交付物 |
|------|--------|
| 健壮性测试 | 长时间运行验证 |
| E2E 测试 | 完整流程验证 |

---

## 十一、风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| 影响 IDE 性能 | 使用 POOLED_THREAD、限制并发、暂停策略 |
| 内存泄漏 | Disposable 机制、Generation Counter |
| 线程安全问题 | 状态使用 AtomicReference |
| 系统休眠 | Timer Clamp、下次唤醒时间检查 |
| 配置丢失 | PersistentStateComponent 持久化 |

---

## 十二、验收标准

### 功能验收

- [ ] 插件启动后自动开始后台调度
- [ ] 按配置间隔执行自迭代
- [ ] 用户活跃时正确暂停
- [ ] 连续错误后正确退避
- [ ] 可通过 UI 手动触发
- [ ] 状态正确显示

### 质量验收

- [ ] 所有单元测试通过
- [ ] 健壮性测试通过
- [ ] E2E 测试通过
- [ ] 24 小时运行无崩溃
- [ ] 不影响 IDE 响应速度
