package com.smancode.sman.domain.scheduler

import org.slf4j.LoggerFactory

/**
 * 后台调度器
 *
 * 整合所有组件，提供统一的生命周期管理：
 * - HeartbeatRunner：定期触发
 * - SchedulerCore：执行逻辑
 * - ActivityMonitor：用户活动检测
 * - BackoffPolicy：退避策略
 */
class BackgroundScheduler(
    onTick: suspend () -> Unit,
    initialConfig: SchedulerConfig = SchedulerConfig()
) {
    private val logger = LoggerFactory.getLogger(BackgroundScheduler::class.java)

    private val activityMonitor = ActivityMonitor(initialConfig.activityThresholdMs)
    private val backoffPolicy = BackoffPolicy()
    private var config: SchedulerConfig = initialConfig

    private val schedulerCore = SchedulerCore(
        onTick = onTick,
        activityMonitor = activityMonitor,
        backoffPolicy = backoffPolicy,
        config = config
    )

    private val heartbeatRunner = HeartbeatRunner(
        config = config,
        onTick = schedulerCore::tick
    )

    /**
     * 启动调度器
     */
    fun start() {
        if (!config.enabled) {
            logger.info("调度器已禁用，不启动")
            return
        }
        logger.info("启动 BackgroundScheduler")
        schedulerCore.start()
        heartbeatRunner.start()
    }

    /**
     * 停止调度器
     */
    fun stop() {
        logger.info("停止 BackgroundScheduler")
        heartbeatRunner.stop()
        schedulerCore.stop()
    }

    /**
     * 完全关闭，释放所有资源
     */
    fun shutdown() {
        logger.info("关闭 BackgroundScheduler")
        heartbeatRunner.shutdown()
        schedulerCore.stop()
    }

    /**
     * 手动触发一次
     */
    fun triggerNow() {
        logger.debug("手动触发调度")
        heartbeatRunner.requestWakeNow(WakeReason.MANUAL)
    }

    /**
     * 记录用户活动
     */
    fun recordActivity() {
        activityMonitor.recordActivity()
    }

    /**
     * 获取当前状态
     */
    fun getState(): SchedulerState = schedulerCore.getState()

    /**
     * 获取当前配置
     */
    fun getConfig(): SchedulerConfig = config

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: SchedulerConfig) {
        this.config = newConfig
        heartbeatRunner.updateConfig(newConfig)

        // 如果禁用，同步停止 SchedulerCore
        if (!newConfig.enabled && schedulerCore.getState() != SchedulerState.IDLE) {
            schedulerCore.stop()
        }
    }

    /**
     * 获取总执行次数
     */
    fun getTotalTicks(): Long = schedulerCore.getTotalTicks()

    /**
     * 获取总错误次数
     */
    fun getTotalErrors(): Long = schedulerCore.getTotalErrors()

    /**
     * 获取连续错误次数
     */
    fun getConsecutiveErrors(): Int = backoffPolicy.getConsecutiveErrors()

    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean = heartbeatRunner.isRunning()
}
