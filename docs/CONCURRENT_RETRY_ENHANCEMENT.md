# 并发控制和重试机制增强

## 概述

本次更新为 SmanAgent 项目添加了完善的并发控制和重试机制，主要针对 BGE-M3 向量化服务和项目分析模块。

## 实现的组件

### 1. Token 估算器 (`TokenEstimator.kt`)

- 估算文本的 token 数量（中文/英文/代码）
- 估算字符数限制
- 检查文本是否超过 token 限制
- 文本统计信息收集

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/vectorization/TokenEstimator.kt`

### 2. 自适应截断器 (`AdaptiveTruncation.kt`)

- 检测 BGE 长度错误（413、too long 等）
- 预处理文本截断
- 自适应处理（逐步缩小 1000 字符）
- 多种截断策略（HEAD/TAIL/MIDDLE/SMART）
- 截断历史记录

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/vectorization/AdaptiveTruncation.kt`

### 3. 增强版重试策略 (`EnhancedRetryStrategy.kt`)

- `EnhancedRetryPolicy` - 指数退避 + 抖动
- `EnhancedRetryExecutor` - 重试执行器
- 支持自定义重试条件和退避算法
- 预定义策略：DEFAULT、AGGRESSIVE、CONSERVATIVE、RATE_LIMIT、NETWORK_ERROR

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/retry/EnhancedRetryStrategy.kt`

### 4. 并发限流器 (`ConcurrencyLimiter.kt`)

- 基于 Semaphore 的并发控制
- 活跃线程数监控
- 专用限流器工厂方法（BGE/LLM/Vectorization/Analysis）

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/retry/ConcurrencyLimiter.kt`

### 5. 熔断器 (`CircuitBreaker.kt`)

- `CircuitBreaker` 状态机（CLOSED/OPEN/HALF_OPEN）
- 失败/成功阈值配置
- 自动恢复机制
- `CircuitBreakerOpenException` 异常

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/retry/CircuitBreaker.kt`

### 6. 分片处理器 (`ChunkProcessor.kt`)

- `ChunkProcessor` - 批量→单条重试
- 分片并发处理
- 失败项单条重试
- 失败记录持久化接口

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/retry/ChunkProcessor.kt`

### 7. 失败记录持久化服务 (`FailureRecordService.kt`)

- H2 数据库表初始化
- 失败记录 CRUD
- 待重试记录查询
- 成功记录清理
- 统计信息收集

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/retry/FailureRecordService.kt`

### 8. 离线重试调度器 (`RetryScheduler.kt`)

- 定时扫描失败记录
- 自动重试失败任务
- 重试状态更新
- 后台调度支持

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/retry/RetryScheduler.kt`

### 9. 监控指标收集器 (`RetryMetricsCollector.kt`)

- `RetryMetrics` - 指标数据结构
- `RetryMetricsCollector` - 指标收集
- 成功率/延迟/分布统计
- 报告打印和 JSON 导出

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/retry/RetryMetricsCollector.kt`

### 10. BGE 配置增强 (`BgeConfig.kt`)

新增配置项：
- `maxTokens` - Token 限制
- `truncationStrategy` - 截断策略
- `truncationStepSize` - 截断步长
- `maxTruncationRetries` - 最大截断重试次数
- `concurrentLimit` - 并发限制
- `circuitBreakerThreshold` - 熔断器阈值

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/config/BgeConfig.kt`

### 11. BGE 客户端集成 (`BgeM3Client.kt`)

集成所有新组件：
- 自适应截断器
- 增强版重试执行器
- 并发限流器
- 熔断器
- 监控指标收集

**文件路径**: `src/main/kotlin/com/smancode/smanagent/analysis/vectorization/BgeM3Client.kt`

## 配置文件更新

在 `smanagent.properties` 中添加了以下配置项：

```properties
# BGE Token 限制（默认 8192）
bge.max.tokens=8192

# BGE 截断配置
bge.truncation.strategy=TAIL
bge.truncation.step.size=1000
bge.max.truncation.retries=10

# BGE 重试配置
bge.retry.max=3
bge.retry.base.delay=1000

# BGE 并发配置
bge.concurrent.limit=3

# BGE 熔断器配置
bge.circuit.breaker.threshold=5
```

## 关键特性

### 1. 自适应文本截断
- 检测 BGE 长度错误（413、too long 等）
- 逐步缩小文本（每次 1000 字符）
- 支持多种截断策略（保留头部、尾部、两端、智能截断）

### 2. 增强版重试策略
- 指数退避 + 抖动（避免雷击效应）
- 可配置最大重试次数和基础延迟
- 支持自定义重试条件

### 3. 并发限流
- 基于 Semaphore 控制并发数
- 避免触发 API 速率限制
- 实时监控活跃线程数

### 4. 熔断器保护
- 连续失败达到阈值后暂停请求
- 避免持续调用不可用的服务
- 支持自动恢复

### 5. 失败记录持久化
- H2 数据库存储失败记录
- 支持离线重试
- 统计信息收集

## 使用示例

### 基本使用

```kotlin
// BGE 客户端自动使用所有增强功能
val client = BgeM3Client(config)

// 文本自动截断和重试
val vector = client.embed("长文本...")

// 批量处理
val vectors = client.batchEmbed(listOf("文本1", "文本2"))

// 获取统计信息
val stats = client.getStatistics()
println(stats)
```

### 监控指标

```kotlin
// 打印重试指标报告
RetryMetricsCollector.GLOBAL.printReport()

// 获取汇总统计
val summary = RetryMetricsCollector.GLOBAL.getSummaryMetrics()
println(summary)
```

## 实现优先级

- ✅ **P0**: Token 估算器、自适应截断器、增强版重试策略
- ✅ **P0**: 并发限流器、熔断器
- ✅ **P1**: 分片处理器、失败记录持久化
- ✅ **P2**: 离线重试调度器、监控指标收集

## 编译状态

- ✅ 所有新组件编译通过
- ⚠️ 部分现有测试需要更新（由于重试机制变化）

## 下一步工作

1. 更新现有测试以适配新的重试机制
2. 在设置对话框中添加性能配置界面
3. 编写新组件的单元测试
4. 添加集成测试验证并发场景
