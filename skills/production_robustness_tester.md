# 生产级鲁棒性测试 Skill

> 为 SmanCode 知识进化系统创建生产级别的鲁棒性测试，验证系统在极端情况下的可靠性

---

## 角色定义

### 核心职责

1. **识别系统可能的失败模式** - 分析代码逻辑，找出所有可能导致异常的地方
2. **设计极端场景测试** - 构建边界条件、异常输入、并发冲突等测试用例
3. **确保测试覆盖生产环境可能遇到的所有问题** - 从基础设施故障到业务逻辑漏洞

### 测试哲学

- 不只是测试"正常工作"，更要测试"异常时优雅降级"
- 每一个假设都应该有对应的测试来验证
- 故障注入测试比正常运行测试更重要
- 预测最坏情况，而不是期望最好情况

---

## 测试维度覆盖

### 维度 1: 业务功能测试

| 测试类别 | 测试目标 | 核心验证点 |
|---------|---------|-----------|
| Happy Path | 正常业务流程 | 数据流转正确、状态变更正确、输出符合预期 |
| 边缘情况 | 边界值、空输入 | 空字符串、null、空集合、零值、单元素 |
| 错误处理 | 异常捕获与恢复 | 异常信息准确、状态不回滚、资源不泄漏 |

### 维度 2: 性能与压力测试

| 测试类别 | 测试目标 | 核心验证点 |
|---------|---------|-----------|
| 大数据量 | 超大文件、超多文件 | 内存不溢出、分批处理正确、进度可追踪 |
| 并发场景 | 多线程/协程并发 | 竞态条件、死锁、数据不一致 |
| 资源限制 | 内存、CPU、磁盘 | 资源耗尽时的优雅降级 |

### 维度 3: 故障注入测试

| 故障类型 | 注入方式 | 预期行为 |
|---------|---------|---------|
| LLM 服务不可用 | Mock 抛出异常 | 任务标记失败、重试机制触发 |
| 网络超时 | 设置超时 | 正确超时处理、可配置超时时间 |
| 文件系统错误 | Mock IOException | 错误信息清晰、可恢复 |
| JSON 解析异常 | 损坏的 JSON | 解析失败不影响其他任务 |

### 维度 4: 混沌工程测试

| 测试场景 | 注入方式 | 验证目标 |
|---------|---------|---------|
| 随机中断 | Thread.interrupt() | 资源不泄漏、状态一致 |
| 数据损坏 | 写入损坏数据 | 检测机制触发、数据恢复 |
| 状态不一致 | 并发写入冲突 | 最终一致性保证 |

### 维度 5: 安全与边界测试

| 测试类别 | 测试目标 | 核心验证点 |
|---------|---------|-----------|
| 恶意输入 | 超长字符串、特殊字符 | 无注入漏洞、无崩溃 |
| 超大输入 | 超过内存限制的输入 | 正确拒绝、提示清晰 |
| 特殊字符 | Unicode、NULL bytes | 正确处理、不产生乱码 |

---

## 目标系统组件

本 Skill 针对以下核心组件进行测试设计：

### 1. TaskExecutor (`TaskExecutor.kt`)

**职责**：
- 执行分析任务
- 调用 LLM 分析代码
- 生成/更新 Puzzle
- 支持幂等执行

**需测试的失败模式**：
- LLM 调用超时/失败
- 文件读取失败
- Checksum 计算错误
- 任务状态竞争
- Puzzle 保存失败

### 2. PuzzleCoordinator (`PuzzleCoordinator.kt`)

**职责**：
- 协调多个 Puzzle 分析任务
- 管理任务优先级
- 控制并发度

**需测试的失败模式**：
- 任务队列并发冲突
- 优先级计算错误
- 任务漏执行

### 3. TaskScheduler (`TaskScheduler.kt`)

**职责**：
- 调度待分析任务
- 维护任务队列

**需测试的失败模式**：
- 任务重复调度
- 任务丢失
- 调度死循环

### 4. KnowledgeEvolutionLoop (`KnowledgeEvolutionLoop.kt`)

**职责**：
- 知识自迭代循环
- 拼图合并与更新

**需测试的失败模式**：
- 迭代不收敛
- 知识丢失
- 熵增失控

### 5. LlmAnalyzer (`LlmAnalyzer.kt`, `DefaultLlmAnalyzer.kt`)

**职责**：
- 调用 LLM 进行代码分析

**需测试的失败模式**：
- 网络超时
- 响应解析失败
- Token 预算超限

### 6. FileReader (`FileReader.kt`)

**职责**：
- 读取文件内容
- 构建分析上下文

**需测试的失败模式**：
- 文件不存在
- 文件编码问题
- 超大文件处理
- 符号链接循环

---

## 测试场景矩阵

### TaskExecutor 测试矩阵

| 场景 ID | 测试类别 | 测试描述 | 预期结果 |
|--------|---------|---------|---------|
| TE-01 | Happy Path | 正常执行 PENDING 任务 | Success, Puzzle 已保存 |
| TE-02 | 边缘情况 | 执行空目标文件任务 | Success, 生成最小 Puzzle |
| TE-03 | 边缘情况 | 执行不存在的目标文件 | Failed, 错误信息清晰 |
| TE-04 | 错误处理 | LLM 分析抛出异常 | Failed, 任务标记为 FAILED |
| TE-05 | 错误处理 | Puzzle 保存失败 | Failed, 任务状态正确 |
| TE-06 | 故障注入 | Checksum 计算异常 | 优雅降级, 跳过幂等检查 |
| TE-07 | 并发 | 多协程同时执行同一任务 | 只有一个执行成功 |
| TE-08 | 并发 | 任务执行中被中断 | 状态不损坏, 可恢复 |
| TE-09 | 性能 | 执行 1000+ 文件任务 | 内存稳定, 可分批完成 |
| TE-10 | 边界 | 目标路径超长 | 正确拒绝, 抛出异常 |

### PuzzleCoordinator 测试矩阵

| 场景 ID | 测试类别 | 测试描述 | 预期结果 |
|--------|---------|---------|---------|
| PC-01 | Happy Path | 启动协调器执行多个任务 | 任务依次完成 |
| PC-02 | 边缘情况 | 无任务时启动 | 空转, 不报错 |
| PC-03 | 边缘情况 | 任务全部完成 | 自动停止 |
| PC-04 | 错误处理 | 任务执行失败 | 错误记录, 继续执行其他任务 |
| PC-05 | 并发 | 多个协程同时获取任务 | 并发度受控 |
| PC-06 | 故障注入 | 存储服务不可用 | 优雅降级 |
| PC-07 | 混沌 | 任务执行过程中添加新任务 | 新任务被正确调度 |
| PC-08 | 边界 | 超过最大并发数 | 排队等待 |

### KnowledgeEvolutionLoop 测试矩阵

| 场景 ID | 测试类别 | 测试描述 | 预期结果 |
|--------|---------|---------|---------|
| KL-01 | Happy Path | 正常执行一次迭代 | 知识正确更新 |
| KL-02 | 边缘情况 | 目标目录为空 | 跳过, 不报错 |
| KL-03 | 边缘情况 | 已有完整拼图 | 幂等跳过 |
| KL-04 | 错误处理 | LLM 分析失败 | 迭代标记失败 |
| KL-05 | 错误处理 | 拼图合并冲突 | 正确解决冲突 |
| KL-06 | 混沌 | 迭代过程中删除拼图文件 | 检测到并重建 |
| KL-07 | 性能 | 100+ 文件迭代 | 内存稳定 |
| KL-08 | 边界 | 迭代不收敛 (死循环) | DoomLoopGuard 阻止 |

### LlmAnalyzer 测试矩阵

| 场景 ID | 测试类别 | 测试描述 | 预期结果 |
|--------|---------|---------|---------|
| LA-01 | Happy Path | 正常调用 LLM | 返回正确解析结果 |
| LA-02 | 边缘情况 | 空上下文 | 返回默认结果 |
| LA-03 | 错误处理 | LLM 返回无效 JSON | 抛出解析异常 |
| LA-04 | 错误处理 | LLM 服务超时 | 抛出超时异常 |
| LA-05 | 错误处理 | LLM 服务不可用 | 抛出连接异常 |
| LA-06 | 性能 | 超大代码上下文 | 正确分批处理 |
| LA-07 | 边界 | 目标文件不存在 | 抛出文件不存在异常 |
| LA-08 | 边界 | Token 超出限制 | 正确截断或拒绝 |

---

## 测试代码模板

### 模板 1: 故障注入测试基类

```kotlin
/**
 * 故障注入测试基类
 *
 * 提供统一的故障注入机制和恢复验证
 */
abstract class FaultInjectionTest {

    /**
     * 注入故障的回调
     */
    protected abstract suspend fun injectFault()

    /**
     * 验证系统行为的回调
     */
    protected abstract suspend fun verifyBehavior()

    /**
     * 清理测试环境的回调
     */
    protected open suspend fun cleanup() {}

    @Test
    @DisplayName("故障注入后系统应优雅降级")
    fun `system should degrade gracefully when fault injected`() = runBlocking {
        try {
            injectFault()
            verifyBehavior()
        } finally {
            cleanup()
        }
    }
}
```

### 模板 2: LLM 故障注入测试

```kotlin
@DisplayName("TaskExecutor LLM 故障注入测试")
class TaskExecutorLlmFaultTest : FaultInjectionTest() {

    private lateinit var taskExecutor: TaskExecutor
    private lateinit var mockLlmAnalyzer: MockLlmAnalyzer
    private var taskMarkedFailed = false

    @BeforeEach
    fun setUp() {
        mockLlmAnalyzer = MockLlmAnalyzer()
        taskExecutor = createTaskExecutor(llmAnalyzer = mockLlmAnalyzer)
    }

    @Test
    @DisplayName("LLM 服务不可用时应标记任务为 FAILED")
    fun `when LLM unavailable should mark task as FAILED`() = runBlocking {
        // Arrange
        val task = createTestTask(status = TaskStatus.PENDING)
        mockLlmAnalyzer.setFault(LlmFault.ServiceUnavailable)

        // Act
        val result = taskExecutor.execute(task)

        // Assert
        assertTrue(result is ExecutionResult.Failed)
        verify(taskQueueStore).update(match {
            it.status == TaskStatus.FAILED && it.errorMessage != null
        })
    }

    @Test
    @DisplayName("LLM 超时时应有重试机制")
    fun `when LLM timeout should trigger retry`() = runBlocking {
        // Arrange
        val task = createTestTask(status = TaskStatus.PENDING, retryCount = 0)
        mockLlmAnalyzer.setFault(LlmFault.Timeout)

        // Act
        repeat(3) {
            taskExecutor.execute(task)
        }

        // Assert
        verify(atLeast = 1) { llmAnalyzer.analyze(any(), any()) }
    }

    @Test
    @DisplayName("LLM 返回无效响应时应抛出解析异常")
    fun `when LLM returns invalid response should throw parse exception`() = runBlocking {
        // Arrange
        val task = createTestTask(status = TaskStatus.PENDING)
        mockLlmAnalyzer.setFault(LlmFault.InvalidResponse("invalid json"))

        // Act & Assert
        assertThrows<AnalysisException> {
            taskExecutor.execute(task)
        }
    }

    @Test
    @DisplayName("LLM 服务恢复后应能继续执行")
    fun `when LLM recovers should continue execution`() = runBlocking {
        // Arrange
        val task = createTestTask(status = TaskStatus.PENDING)
        mockLlmAnalyzer.setFault(LlmFault.ServiceUnavailable)

        // First call fails
        taskExecutor.execute(task)

        // LLM recovers
        mockLlmAnalyzer.setFault(LlmFault.None)
        mockLlmAnalyzer.setResult(createAnalysisResult())

        // Act
        val result = taskExecutor.execute(task)

        // Assert
        assertTrue(result is ExecutionResult.Success)
    }
}

/**
 * LLM 故障类型
 */
enum class LlmFault {
    None,
    ServiceUnavailable,
    Timeout,
    InvalidResponse(String),
    RateLimited
}
```

### 模板 3: 并发竞争测试

```kotlin
@DisplayName("TaskExecutor 并发测试")
class TaskExecutorConcurrencyTest {

    private lateinit var taskExecutor: TaskExecutor
    private val executedCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        taskExecutor = createTaskExecutor()
    }

    @Test
    @DisplayName("多协程同时执行同一任务应保证幂等")
    fun `multiple coroutines executing same task should be idempotent`() = runBlocking {
        // Arrange
        val task = createTestTask(id = "task-1", status = TaskStatus.PENDING)
        val analysisResult = createAnalysisResult()

        coEvery { llmAnalyzer.analyze(any(), any()) } coAnswers {
            executedCount.incrementAndGet()
            delay(100) // 模拟 LLM 调用耗时
            analysisResult
        }

        // Act - 10 个协程同时执行同一任务
        val jobs = (1..10).map { index ->
            launch {
                taskExecutor.execute(task)
            }
        }
        jobs.forEach { it.join() }

        // Assert - LLM 只应被调用一次
        assertEquals(1, executedCount.get())
    }

    @Test
    @DisplayName("并发执行不同任务应正确完成所有任务")
    fun `concurrent execution of different tasks should complete all`() = runBlocking {
        // Arrange
        val tasks = (1..20).map { i ->
            createTestTask(id = "task-$i", status = TaskStatus.PENDING)
        }

        coEvery { llmAnalyzer.analyze(any(), any()) } coAnswers {
            delay(10)
            successCount.incrementAndGet()
            createAnalysisResult()
        }

        // Act
        tasks.forEach { task ->
            launch {
                taskExecutor.execute(task)
            }
        }

        // Assert
        assertEquals(20, successCount.get())
    }

    @Test
    @DisplayName("高并发下内存使用应保持稳定")
    fun `memory usage should remain stable under high concurrency`() = runBlocking {
        // Arrange
        val tasks = (1..100).map { i ->
            createTestTask(id = "task-$i", status = TaskStatus.PENDING)
        }

        coEvery { llmAnalyzer.analyze(any(), any()) } returns createAnalysisResult()

        // Act
        val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        tasks.forEach { task ->
            launch {
                taskExecutor.execute(task)
            }
        }

        // Give time for GC
        System.gc()
        Thread.sleep(100)

        val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Assert - 内存增长不应超过初始的 50%
        assertTrue(finalMemory < initialMemory * 1.5,
            "Memory increased too much: initial=$initialMemory, final=$finalMemory")
    }
}
```

### 模板 4: 混沌工程测试

```kotlin
@DisplayName("TaskExecutor 混沌工程测试")
class TaskExecutorChaosTest {

    @Test
    @DisplayName("任务执行中中断应保证状态一致")
    fun `task interruption should guarantee state consistency`() = runBlocking {
        // Arrange
        val taskExecutor = createTaskExecutor()
        val task = createTestTask(status = TaskStatus.PENDING)

        var interruptionOccurred = false

        coEvery { llmAnalyzer.analyze(any(), any()) } coAnswers {
            // 在分析过程中触发中断
            if (!interruptionOccurred) {
                interruptionOccurred = true
                // 模拟外部中断
                throw InterruptedException("Task interrupted")
            }
            createAnalysisResult()
        }

        // Act
        val result = taskExecutor.execute(task)

        // Assert - 任务应被标记为失败，不应处于 RUNNING 状态
        verify(taskQueueStore).update(match {
            it.status != TaskStatus.RUNNING
        })
    }

    @Test
    @DisplayName("数据损坏后应有检测和恢复机制")
    fun `data corruption should be detected and recovered`() = runBlocking {
        // Arrange
        val puzzleStore = createCorruptedPuzzleStore()
        val taskExecutor = createTaskExecutor(puzzleStore = puzzleStore)
        val task = createTestTask(status = TaskStatus.PENDING)

        // Act
        val result = taskExecutor.execute(task)

        // Assert - 应能检测到损坏并重建
        assertTrue(result is ExecutionResult.Success)
        verify(puzzleStore).save(any()) // 重新保存了正确的 Puzzle
    }

    @Test
    @DisplayName("状态不一致时应能自动修复")
    fun `inconsistent state should be auto-repaired`() = runRunning {
        // Arrange - 模拟任务状态不一致（RUNNING 但无进程）
        val orphanedTask = createTestTask(
            id = "orphaned-task",
            status = TaskStatus.RUNNING,
            startedAt = Instant.now().minusSeconds(3600) // 1 小时前开始
        )

        every { taskQueueStore.findRunnable(any()) } returns listOf(orphanedTask)
        every { taskQueueStore.findStaleRunningTasks(any()) } returns listOf(orphanedTask)

        // Act
        recoveryService.recoverStaleTasks()

        // Assert - 孤儿任务应被重置
        verify(taskQueueStore).update(match {
            it.status == TaskStatus.PENDING
        })
    }
}
```

### 模板 5: 边界测试

```kotlin
@DisplayName("TaskExecutor 边界测试")
class TaskExecutorBoundaryTest {

    @Test
    @DisplayName("空目标文件应能生成最小 Puzzle")
    fun `empty target file should generate minimal Puzzle`() = runBlocking {
        // Arrange
        val task = createTestTask(target = "empty.kt")
        val emptyContent = AnalysisContext(
            files = listOf(FileContext("empty.kt", "", emptyList()))
        )

        every { fileReader.readWithContext("empty.kt") } returns emptyContent
        coEvery { llmAnalyzer.analyze("empty.kt", emptyContent) } returns
            AnalysisResult(title = "空文件", content = "", tags = emptyList(), confidence = 0.5)

        // Act
        val result = taskExecutor.execute(task)

        // Assert
        assertTrue(result is ExecutionResult.Success)
    }

    @Test
    @DisplayName("超长目标路径应抛出异常")
    fun `overlong target path should throw exception`() = runBlocking {
        // Arrange
        val overlongPath = "a".repeat(500) + ".kt"
        val task = createTestTask(target = overlongPath)

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            taskExecutor.execute(task)
        }
    }

    @Test
    @DisplayName("特殊字符文件名应正确处理")
    fun `special characters in filename should be handled correctly`() = runBlocking {
        // Arrange
        val specialFileName = "test-file-中文-emoji-\u0000.kt"
        val task = createTestTask(target = specialFileName)

        coEvery { llmAnalyzer.analyze(any(), any()) } returns createAnalysisResult()

        // Act
        val result = taskExecutor.execute(task)

        // Assert - 应能正确处理，不崩溃
        assertTrue(result is ExecutionResult.Success || result is ExecutionResult.Failed)
    }

    @Test
    @DisplayName("NULL bytes in content should not cause issues`() {
        // Arrange
        val contentWithNulls = "normal content\u0000\u0000more content"

        // Act & Assert - 应能正确处理
        assertDoesNotThrow {
            // 处理逻辑
        }
    }

    @Test
    @DisplayName("零值参数应正确处理")
    fun `zero values should be handled correctly`() = runBlocking {
        // Arrange - confidence 为 0
        val resultWithZero = AnalysisResult(
            title = "Test",
            content = "Content",
            tags = emptyList(),
            confidence = 0.0
        )

        coEvery { llmAnalyzer.analyze(any(), any()) } returns resultWithZero

        // Act
        val task = createTestTask()
        taskExecutor.execute(task)

        // Assert - 应能保存，不应崩溃
        verify(puzzleStore).save(any())
    }
}
```

---

## 测试执行规范

### 故障注入原则

1. **注入点选择** - 选择最接近真实故障的注入点
2. **独立性** - 每个测试应独立运行，不依赖其他测试的状态
3. **可重复性** - 测试应能重复执行并得到一致结果
4. **清理** - 测试后必须清理注入的故障

### 验证标准

| 指标 | 合格标准 |
|------|---------|
| 测试覆盖率 | 核心路径 > 80% |
| 故障覆盖率 | 已识别失败模式 > 90% |
| 测试执行时间 | 单个测试 < 5s |
| 内存泄漏 | 无 |

---

## 输出格式

生成测试后，按以下格式报告：

```json
{
  "测试概览": {
    "测试类数量": N,
    "测试方法数量": N,
    "覆盖的组件": ["TaskExecutor", "PuzzleCoordinator", ...],
    "覆盖的测试维度": ["业务功能", "性能压力", "故障注入", "混沌工程", "安全边界"]
  },
  "测试矩阵": [
    {
      "场景ID": "TE-01",
      "测试类": "TaskExecutorHappyPathTest",
      "测试方法": "normal execution should succeed",
      "测试维度": "业务功能",
      "状态": "PASS/FAIL"
    }
  ],
  "覆盖率报告": {
    "TaskExecutor": "95%",
    "PuzzleCoordinator": "88%",
    ...
  },
  "发现的问题": [
    {
      "severity": "HIGH/MEDIUM/LOW",
      "description": "问题描述",
      "recommendation": "修复建议"
    }
  ]
}
```

---

## 使用说明

### 加载 Skill

```bash
# 在 Claude Code 中加载
/claude skill add skills/production_robustness_tester.md
```

### 生成测试

```bash
# 为 TaskExecutor 生成鲁棒性测试
/claude:production_robustness_tester generate src/main/kotlin/com/smancode/sman/domain/puzzle/TaskExecutor.kt

# 为 PuzzleCoordinator 生成鲁棒性测试
/claude:production_robustness_tester generate src/main/kotlin/com/smancode/sman/domain/puzzle/PuzzleCoordinator.kt

# 为整个 puzzle 包生成测试
/claude:production_robustness_tester generate src/main/kotlin/com/smancode/sman/domain/puzzle/
```

### 运行测试

```bash
# 运行所有鲁棒性测试
./gradlew test --tests "*RobustnessTest*"

# 运行特定组件的测试
./gradlew test --tests "*TaskExecutor*Test*"

# 运行混沌工程测试
./gradlew test --tests "*ChaosTest*"
```

---

## 关键规则

> **测试优先级**：
> 1. 故障注入测试 > 正常流程测试
> 2. 边界测试 > Happy Path 测试
> 3. 并发测试 > 串行测试
>
> **通过标准**：
> - 所有测试必须通过
> - 无内存泄漏
> - 无资源泄漏
> - 故障恢复时间 < 5s

---

---

## 基于 GitHub 先进测试模式的增强

### 1. Red-Teaming 测试（红队攻击测试）

源自 GitHub 上的 llamator、PromptKit 等项目：

| 测试场景 | 攻击方式 | 验证目标 |
|---------|---------|---------|
| 提示注入 | 恶意 Prompt 注入 | 系统不被误导 |
| 角色扮演攻击 | 尝试绕过系统限制 | 边界防护有效 |
| 越狱攻击 | 尝试获取不当输出 | 内容过滤有效 |

### 2. Stress Testing（压力测试）

源自 on erun 等项目的规模化模拟：

| 测试场景 | 规模 | 验证目标 |
|---------|-----|---------|
| 批量任务执行 | 1000+ 任务 | 无性能退化 |
| 资源耗尽 | 内存 90%+ | 优雅降级 |
| 速率限制 | 超过 API 限制 | 正确排队 |

### 3. Observability Testing（可观测性测试）

源自 RagaAI-Catalyst 等框架：

| 测试场景 | 验证目标 |
|---------|---------|
| 追踪完整性 | 所有操作可追溯 |
| 指标准确性 | 指标数据正确 |
| 日志完整性 | 错误有足够上下文 |

### 4. Self-Play Testing（自博弈测试）

源自自动化评估工作流：

| 测试场景 | 验证目标 |
|---------|---------|
| 对抗性输入 | 系统能处理恶意输入 |
| 迭代收敛性 | 自迭代不会发散 |
| 知识一致性 | 新知识不破坏旧知识 |

### 5. RAG Evaluation（知识增强测试）

| 测试场景 | 验证目标 |
|---------|---------|
| 上下文检索 | 相关知识被正确检索 |
| 知识冲突 | 冲突能被检测 |
| 遗忘检测 | 旧知识不被意外删除 |

---

## 版本信息

*框架版本：v1.1*
*设计原则：故障优先、边界覆盖、混沌验证、生产就绪、红队攻击*
*新增：Red-Teaming、Stress Testing、Observability、Self-Play、RAG Evaluation*
