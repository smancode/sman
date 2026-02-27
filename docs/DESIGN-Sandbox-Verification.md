# 设计方案：代码沙盒自验证

> 版本：1.0
> 日期：2026-02-23

---

## 一、设计目标

实现代码生成 → 沙盒测试 → 自动修复的完整闭环：

1. **隔离执行用户代码**：防止恶意代码或错误影响 IDE
2. **自动生成测试用例**：基于代码分析和 LLM 生成测试
3. **运行测试并收集结果**：支持多种测试框架
4. **处理测试失败并自动修复**：迭代修复循环

---

## 二、架构设计

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    SmanCode 自动验证架构                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                        用户交互层                                   │  │
│  │  ┌─────────────┐    ┌──────────────┐    ┌─────────────────────┐  │  │
│  │  │ ChatPanel   │───►│ SmanLoop     │───►│ VerificationUI      │  │  │
│  │  │ (用户输入)   │    │ (ReAct 循环) │    │ (验证结果展示)       │  │  │
│  │  └─────────────┘    └──────────────┘    └─────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                   │                                      │
│                                   ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                      验证核心层                                     │  │
│  │                                                                    │  │
│  │  ┌─────────────────────────────────────────────────────────────┐  │  │
│  │  │                   TestGenerator                              │  │  │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │  │  │
│  │  │  │ 代码分析器   │  │ LLM 生成器  │  │ 测试模板管理器       │  │  │  │
│  │  │  │ (AST/PSI)   │  │ (生成用例)   │  │ (JUnit/TestNG/...)  │  │  │  │
│  │  │  └─────────────┘  └─────────────┘  └─────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────┘  │  │
│  │                                                                    │  │
│  │  ┌─────────────────────────────────────────────────────────────┐  │  │
│  │  │                   TestExecutor                              │  │  │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │  │  │
│  │  │  │ SandboxEnv  │  │ CommandRun  │  │ ResultCollector     │  │  │  │
│  │  │  │ (隔离环境)   │  │ (Gradle/Mvn)│  │ (解析测试结果)       │  │  │  │
│  │  │  └─────────────┘  └─────────────┘  └─────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────┘  │  │
│  │                                                                    │  │
│  │  ┌─────────────────────────────────────────────────────────────┐  │  │
│  │  │                   AutoFixer                                 │  │  │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │  │  │
│  │  │  │ 错误分析器   │  │ Fix策略选择 │  │ 迭代修复控制器       │  │  │  │
│  │  │  │ (根因定位)   │  │ (LLM辅助)   │  │ (最多 N 次)         │  │  │  │
│  │  │  └─────────────┘  └─────────────┘  └─────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                      基础设施层                                     │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐   │  │
│  │  │ LocalTool   │  │ Process     │  │ IntelliJ PSI            │   │  │
│  │  │ Executor    │  │ Isolation   │  │ (代码分析)              │   │  │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 三、核心组件设计

### 3.1 测试生成器

```kotlin
/**
 * 测试用例生成器
 */
class TestGenerator(
    private val project: Project,
    private val llmService: LlmService
) {
    /**
     * 为指定类生成测试用例
     */
    suspend fun generateTests(
        sourceFile: String,
        options: TestGenerationOptions = TestGenerationOptions()
    ): GeneratedTests {
        // 1. PSI 分析：提取类结构、方法签名、注解
        val classInfo = analyzeClass(sourceFile)

        // 2. 构建提示词
        val prompt = buildTestPrompt(classInfo, options)

        // 3. LLM 生成测试
        val testCode = llmService.generate(prompt)

        // 4. 验证生成的代码语法
        val validated = validateAndFixSyntax(testCode)

        // 5. 确定测试文件位置
        val testFilePath = determineTestPath(sourceFile)

        return GeneratedTests(
            testClass = validated,
            testFilePath = testFilePath,
            testCases = extractTestCases(validated),
            coverage = estimateCoverage(classInfo, validated)
        )
    }

    /**
     * PSI 分析：提取类信息
     */
    private fun analyzeClass(sourceFile: String): ClassInfo {
        val psiFile = PsiManager.getInstance(project)
            .findFile(VirtualFileManager.getInstance()
                .findFileByUrl("file://$sourceFile")!!)!!

        val psiClass = psiFile.children.filterIsInstance<PsiClass>().firstOrNull()
            ?: throw IllegalArgumentException("未找到类定义")

        return ClassInfo(
            className = psiClass.name ?: "Unknown",
            methods = psiClass.methods.map { method ->
                MethodInfo(
                    name = method.name,
                    returnType = method.returnType?.presentableText ?: "void",
                    parameters = method.parameterList.parameters.map { param ->
                        ParamInfo(param.name ?: "", param.type.presentableText)
                    },
                    annotations = method.annotations.map { it.qualifiedName ?: "" },
                    isPublic = method.modifierList.hasModifierProperty("public")
                )
            },
            fields = psiClass.fields.map { field ->
                FieldInfo(field.name ?: "", field.type.presentableText)
            }
        )
    }

    /**
     * 构建测试生成提示词
     */
    private fun buildTestPrompt(classInfo: ClassInfo, options: TestGenerationOptions): String {
        return """
            为以下 Kotlin/Java 类生成 ${options.framework} 测试用例。

            ## 类信息

            类名: ${classInfo.className}

            ### 方法

            ${classInfo.methods.joinToString("\n") { method ->
                "- `${method.name}(${method.parameters.joinToString { "${it.name}: ${it.type}" }}): ${method.returnType}`"
            }}

            ### 字段

            ${classInfo.fields.joinToString(", ") { "${it.name}: ${it.type}" }}

            ## 要求

            1. 使用 ${options.style} 风格
            2. ${if (options.includeEdgeCases) "包含边界条件测试" else "仅测试正常路径"}
            3. ${if (options.includeExceptionTests) "包含异常测试" else "不包含异常测试"}
            4. 最多生成 ${options.maxTestCases} 个测试用例
            5. 使用 MockK 进行依赖 Mock
            6. 每个测试用例要有清晰的 Given-When-Then 结构

            ## 输出格式

            只输出测试代码，不要有其他解释。
        """.trimIndent()
    }

    data class TestGenerationOptions(
        val framework: TestFramework = TestFramework.JUNIT5,
        val style: TestStyle = TestStyle.BDD,
        val includeEdgeCases: Boolean = true,
        val includeExceptionTests: Boolean = true,
        val maxTestCases: Int = 10
    )

    enum class TestFramework {
        JUNIT5, TESTNG, SPOCK
    }

    enum class TestStyle {
        BDD, CLASSIC
    }
}
```

### 3.2 沙盒执行器

**方案 A：进程级隔离（轻量级，推荐）**

```kotlin
/**
 * 进程级沙盒执行器
 *
 * 在独立进程中运行测试，隔离执行环境
 */
class ProcessSandboxExecutor(
    private val project: Project
) {
    companion object {
        private val logger = Logger.getInstance(ProcessSandboxExecutor::class.java)
    }

    /**
     * 在沙盒中执行测试
     */
    suspend fun executeInSandbox(
        command: String,
        options: SandboxOptions = SandboxOptions()
    ): SandboxResult {
        logger.info("沙盒执行命令: $command")

        // 1. 构建安全命令
        val safeCommand = buildSafeCommand(command, options)
        if (safeCommand == null) {
            return SandboxResult.Rejected("命令不安全: $command")
        }

        // 2. 创建隔离进程
        val processBuilder = ProcessBuilder(safeCommand)
            .directory(options.workingDir)
            .redirectErrorStream(true)

        // 3. 设置环境变量
        if (options.jvmArgs != null) {
            processBuilder.environment()["JAVA_TOOL_OPTIONS"] = options.jvmArgs
        }

        // 4. 设置资源限制（可选：使用 ulimit）
        if (options.memoryLimit != null) {
            // 在 Unix 系统上使用 ulimit
            // 注意：这需要在 shell 中执行
        }

        // 5. 启动进程
        val startTime = System.currentTimeMillis()
        val process = processBuilder.start()

        // 6. 流式读取输出
        val output = StringBuilder()
        val outputJob = CoroutineScope(Dispatchers.IO).launch {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                    logger.debug { "沙盒输出: $line" }
                }
            }
        }

        // 7. 等待完成（带超时）
        val completed = process.waitFor(options.timeoutSeconds, TimeUnit.SECONDS)

        outputJob.join()

        val duration = System.currentTimeMillis() - startTime

        if (!completed) {
            logger.warn("沙盒执行超时，强制终止: timeout=${options.timeoutSeconds}s")
            process.destroyForcibly()
            return SandboxResult.Timeout(
                timeoutSeconds = options.timeoutSeconds,
                partialOutput = output.toString(),
                durationMs = duration
            )
        }

        val exitCode = process.exitValue()
        logger.info("沙盒执行完成: exitCode=$exitCode, duration=${duration}ms")

        return SandboxResult.Completed(
            exitCode = exitCode,
            output = output.toString(),
            success = exitCode == 0,
            durationMs = duration
        )
    }

    /**
     * 构建安全命令
     */
    private fun buildSafeCommand(command: String, options: SandboxOptions): List<String>? {
        // 检测危险命令
        val dangerousPatterns = listOf(
            "rm -rf", "sudo", "chmod 777", ":(){ :|:& };:",
            "mkfs", "dd if=", "> /dev/sd", "curl | bash"
        )

        if (dangerousPatterns.any { command.contains(it, ignoreCase = true) }) {
            return null
        }

        // 检查是否在允许列表中
        val commandParts = command.split(Regex("\\s+"))
        val baseCommand = commandParts.first()

        if (options.allowedCommands.isNotEmpty() &&
            baseCommand !in options.allowedCommands) {
            return null
        }

        // 根据操作系统选择 Shell
        val (shell, shellArg) = if (SystemInfo.isWindows) {
            if (File("C:\\Windows\\System32\\pwsh.exe").exists()) {
                "pwsh.exe" to "-Command"
            } else {
                "cmd.exe" to "/c"
            }
        } else {
            "/bin/bash" to "-c"
        }

        return listOf(shell, shellArg, command)
    }

    data class SandboxOptions(
        val workingDir: File = File("."),
        val timeoutSeconds: Long = 300,  // 5 分钟
        val jvmArgs: String? = "-Xmx512m -XX:MaxMetaspaceSize=128m",
        val memoryLimit: String? = null,  // 如 "512m"
        val allowedCommands: Set<String> = setOf("./gradlew", "mvn", "java", "npm", "pytest")
    )
}

/**
 * 沙盒执行结果
 */
sealed class SandboxResult {
    data class Completed(
        val exitCode: Int,
        val output: String,
        val success: Boolean,
        val durationMs: Long
    ) : SandboxResult()

    data class Timeout(
        val timeoutSeconds: Long,
        val partialOutput: String,
        val durationMs: Long
    ) : SandboxResult()

    data class Rejected(
        val reason: String
    ) : SandboxResult()
}
```

**方案 B：Docker 隔离（重量级，高风险场景）**

```kotlin
/**
 * Docker 沙盒执行器
 *
 * 参考 OpenClaw 实现，在容器中隔离执行
 */
class DockerSandboxExecutor(
    private val dockerClient: DockerClient
) {
    /**
     * Docker 沙盒配置（参考 OpenClaw）
     */
    data class DockerSandboxConfig(
        val image: String = "sman-sandbox:latest",
        val readOnlyRoot: Boolean = true,
        val tmpfs: List<String> = listOf("/tmp", "/var/tmp", "/run"),
        val network: String = "none",  // 无网络访问
        val capDrop: List<String> = listOf("ALL"),  // 移除所有 Linux 能力
        val memoryLimit: String = "512m",
        val cpuLimit: String = "0.5",
        val timeoutSeconds: Long = 300
    )

    /**
     * 在 Docker 容器中执行命令
     */
    suspend fun executeInContainer(
        command: String,
        workingDir: File,
        config: DockerSandboxConfig = DockerSandboxConfig()
    ): SandboxResult {
        // 1. 创建容器
        val containerConfig = HostConfig.newHostConfig()
            .withReadOnlyRootfs(config.readOnlyRoot)
            .withTmpFs(config.tmpfs.associateWith { "rw,size=100m" })
            .withNetworkMode(config.network)
            .withCapDrop(config.capDrop)
            .withMemory(memoryStringToBytes(config.memoryLimit))
            .withCpuQuota(cpuStringToQuota(config.cpuLimit))
            .withBinds(
                Bind.parse("${workingDir.absolutePath}:/workspace:ro")
            )

        val containerId = dockerClient.createContainerCmd(config.image)
            .withHostConfig(containerConfig)
            .withWorkingDir("/workspace")
            .withCmd("/bin/sh", "-c", command)
            .exec()
            .id

        try {
            // 2. 启动容器
            dockerClient.startContainerCmd(containerId).exec()

            // 3. 等待完成（带超时）
            val waitResult = withTimeoutOrNull(config.timeoutSeconds * 1000) {
                dockerClient.waitContainerCmd(containerId)
                    .exec(WaitContainerResultCallback())
                    .awaitStatusCode()
            }

            if (waitResult == null) {
                // 超时，强制停止
                dockerClient.killContainerCmd(containerId).exec()
                return SandboxResult.Timeout(
                    timeoutSeconds = config.timeoutSeconds,
                    partialOutput = getContainerLogs(containerId),
                    durationMs = config.timeoutSeconds * 1000
                )
            }

            // 4. 收集输出
            val output = getContainerLogs(containerId)

            return SandboxResult.Completed(
                exitCode = waitResult,
                output = output,
                success = waitResult == 0,
                durationMs = 0 // 需要另外记录
            )

        } finally {
            // 5. 清理容器
            dockerClient.removeContainerCmd(containerId)
                .withForce(true)
                .exec()
        }
    }

    private fun getContainerLogs(containerId: String): String {
        return dockerClient.logContainerCmd(containerId)
            .withStdOut(true)
            .withStdErr(true)
            .exec(LogContainerResultCallback())
            .awaitCompletion()
            .toString()
    }
}
```

### 3.3 测试结果收集器

```kotlin
/**
 * 测试结果收集器
 *
 * 解析多种测试框架的输出
 */
class TestResultCollector {

    /**
     * 解析测试结果
     */
    fun parseTestResults(output: String, framework: TestFramework): TestResults {
        return when (framework) {
            TestFramework.JUNIT5 -> parseJUnit5Results(output)
            TestFramework.TESTNG -> parseTestNGResults(output)
            TestFramework.SPOCK -> parseSpockResults(output)
            TestFramework.PYTEST -> parsePytestResults(output)
        }
    }

    /**
     * 解析 JUnit5 测试结果（Gradle 输出）
     */
    private fun parseJUnit5Results(output: String): TestResults {
        // 解析 Gradle/Maven 输出格式
        // 示例：
        // Test run finished after 1234 ms
        // [         10 tests successful      ]
        // [         2 tests failed           ]
        // [         1 tests skipped          ]

        val passed = extractPattern(output, """(\d+)\s+tests?\s+(?:successful|passed)""")?.toIntOrNull() ?: 0
        val failed = extractPattern(output, """(\d+)\s+tests?\s+failed""")?.toIntOrNull() ?: 0
        val skipped = extractPattern(output, """(\d+)\s+tests?\s+skipped""")?.toIntOrNull() ?: 0

        val failures = extractJUnit5Failures(output)

        return TestResults(
            total = passed + failed + skipped,
            passed = passed,
            failed = failed,
            skipped = skipped,
            failures = failures,
            rawOutput = output
        )
    }

    /**
     * 提取失败的测试用例
     */
    private fun extractJUnit5Failures(output: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()

        // 匹配失败模式：
        // MyClass > myTestMethod FAILED
        //     org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
        //         at com.example.MyClassTest.myTestMethod(MyClassTest.kt:42)

        val failurePattern = Regex(
            """(\w+)\s+>\s+(\w+)\s+FAILED\s*\n\s*(.+?)(?:\n\s*at\s+.+)?""",
            RegexOption.MULTILINE
        )

        failurePattern.findAll(output).forEach { match ->
            val (className, testName, errorMessage) = match.destructured

            // 提取期望值和实际值
            val expectedActual = extractExpectedActual(errorMessage)

            failures.add(TestFailure(
                testName = testName,
                className = className,
                errorMessage = errorMessage.trim(),
                stackTrace = extractStackTrace(output, testName),
                expectedValue = expectedActual.first,
                actualValue = expectedActual.second
            ))
        }

        return failures
    }

    /**
     * 提取期望值和实际值
     */
    private fun extractExpectedActual(errorMessage: String): Pair<String?, String?> {
        val pattern = Regex("""expected:\s*<(.+?)>\s*but was:\s*<(.+?)>""")
        val match = pattern.find(errorMessage) ?: return null to null
        return match.groupValues[1] to match.groupValues[2]
    }

    /**
     * 提取堆栈跟踪
     */
    private fun extractStackTrace(output: String, testName: String): String? {
        val pattern = Regex("""at\s+[\w.]+\($testName\))[\s\S]*?(?=\n\s*\n|\n\s*\d+ test|\Z)""")
        return pattern.find(output)?.value?.trim()
    }

    private fun extractPattern(text: String, pattern: String): String? {
        return Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
    }

    data class TestResults(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val skipped: Int,
        val failures: List<TestFailure>,
        val rawOutput: String
    ) {
        fun hasFailures() = failed > 0
        fun isSuccess() = failed == 0 && total > 0
    }

    data class TestFailure(
        val testName: String,
        val className: String,
        val errorMessage: String,
        val stackTrace: String?,
        val expectedValue: String?,
        val actualValue: String?
    )
}
```

### 3.4 自动修复器

```kotlin
/**
 * 自动修复器
 *
 * 分析测试失败原因，自动生成修复方案
 */
class AutoFixer(
    private val project: Project,
    private val llmService: LlmService,
    private val codeEditService: CodeEditService,
    private val testGenerator: TestGenerator,
    private val sandboxExecutor: ProcessSandboxExecutor
) {
    companion object {
        private val logger = Logger.getInstance(AutoFixer::class.java)
        private const val MAX_FIX_ITERATIONS = 3
    }

    /**
     * 自动修复循环
     */
    suspend fun autoFixLoop(
        sourceFile: String,
        testResults: TestResults,
        maxIterations: Int = MAX_FIX_ITERATIONS
    ): FixResult {
        var currentResults = testResults
        var iterations = 0
        val fixHistory = mutableListOf<FixAttempt>()
        val snapshots = mutableListOf<String>()

        // 保存原始代码快照
        val originalCode = File(sourceFile).readText()
        snapshots.add(originalCode)

        while (currentResults.hasFailures() && iterations < maxIterations) {
            iterations++
            logger.info("自动修复迭代 $iterations: ${currentResults.failed} 个失败")

            // 1. 分析失败原因
            val analysis = analyzeFailures(currentResults.failures)
            logger.info("失败分析: ${analysis.summary}")

            // 2. 生成修复方案
            val fixPlan = generateFixPlan(sourceFile, analysis)
            logger.info("修复方案: ${fixPlan.description}")

            // 3. 应用修复
            val fixResult = applyFix(sourceFile, fixPlan)

            if (!fixResult.success) {
                logger.warn("修复应用失败: ${fixResult.error}")
                continue
            }

            // 保存快照
            snapshots.add(File(sourceFile).readText())

            // 4. 重新运行测试
            currentResults = runTests(sourceFile)

            // 5. 记录历史
            fixHistory.add(FixAttempt(
                iteration = iterations,
                analysis = analysis,
                fixPlan = fixPlan,
                results = currentResults
            ))

            // 6. 如果修复引入新失败，回滚
            if (currentResults.failed > testResults.failed) {
                logger.warn("修复引入新失败，回滚")
                File(sourceFile).writeText(snapshots[snapshots.size - 2])
                currentResults = runTests(sourceFile)
            }
        }

        val success = !currentResults.hasFailures()

        if (!success && iterations >= maxIterations) {
            // 达到最大迭代次数仍未成功，恢复原始代码
            File(sourceFile).writeText(originalCode)
            currentResults = runTests(sourceFile)
        }

        return FixResult(
            success = success,
            finalResults = currentResults,
            iterations = iterations,
            fixHistory = fixHistory
        )
    }

    /**
     * 分析失败原因
     */
    private suspend fun analyzeFailures(failures: List<TestFailure>): FailureAnalysis {
        val prompt = """
            分析以下测试失败的原因，提供根因分析：

            ${failures.joinToString("\n\n") { f ->
                """
                ## 测试: ${f.className}.${f.testName}

                错误信息: ${f.errorMessage}

                ${if (f.expectedValue != null && f.actualValue != null) {
                    "期望值: ${f.expectedValue}\n实际值: ${f.actualValue}"
                } else ""}

                ${f.stackTrace?.take(1000) ?: ""}
                """.trimIndent()
            }}

            请提供：
            1. 失败类型（LOGIC_ERROR / BOUNDARY_CONDITION / EXCEPTION_HANDLING / TYPE_MISMATCH / OTHER）
            2. 根因分析（一句话）
            3. 建议修复方向
            4. 置信度（0.0-1.0）

            输出 JSON 格式。
        """.trimIndent()

        return llmService.analyze(prompt)
    }

    /**
     * 生成修复方案
     */
    private suspend fun generateFixPlan(
        sourceFile: String,
        analysis: FailureAnalysis
    ): FixPlan {
        val sourceCode = File(sourceFile).readText()

        val prompt = """
            基于以下分析，生成代码修复方案：

            ## 失败分析
            - 类型: ${analysis.failureType}
            - 根因: ${analysis.rootCause}
            - 建议: ${analysis.suggestedFix}

            ## 源代码

            ```kotlin
            ${sourceCode.take(5000)}
            ```

            请提供：
            1. 修复描述
            2. 需要修改的位置（行号或代码片段）
            3. 修改后的代码
            4. 修改原因

            输出 JSON 格式。
        """.trimIndent()

        return llmService.generateFixPlan(prompt)
    }

    /**
     * 应用修复
     */
    private fun applyFix(sourceFile: String, fixPlan: FixPlan): ApplyFixResult {
        return try {
            codeEditService.applyChange(
                relativePath = sourceFile,
                oldContent = fixPlan.oldCode,
                newContent = fixPlan.newCode,
                projectPath = project.basePath ?: ""
            )
            ApplyFixResult(success = true)
        } catch (e: Exception) {
            ApplyFixResult(success = false, error = e.message)
        }
    }

    /**
     * 运行测试
     */
    private suspend fun runTests(sourceFile: String): TestResults {
        val testFile = determineTestPath(sourceFile)
        val command = "./gradlew test --tests \"*${File(testFile).nameWithoutExtension}*\""

        val result = sandboxExecutor.executeInSandbox(command)

        return when (result) {
            is SandboxResult.Completed -> {
                TestResultCollector().parseTestResults(result.output, TestFramework.JUNIT5)
            }
            is SandboxResult.Timeout -> {
                TestResults(
                    total = 0, passed = 0, failed = 1, skipped = 0,
                    failures = listOf(TestFailure(
                        testName = "timeout",
                        className = "Sandbox",
                        errorMessage = "测试执行超时: ${result.timeoutSeconds}s",
                        stackTrace = null,
                        expectedValue = null,
                        actualValue = null
                    )),
                    rawOutput = result.partialOutput
                )
            }
            is SandboxResult.Rejected -> {
                TestResults(
                    total = 0, passed = 0, failed = 1, skipped = 0,
                    failures = listOf(TestFailure(
                        testName = "rejected",
                        className = "Sandbox",
                        errorMessage = "命令被拒绝: ${result.reason}",
                        stackTrace = null,
                        expectedValue = null,
                        actualValue = null
                    )),
                    rawOutput = ""
                )
            }
        }
    }

    data class FixResult(
        val success: Boolean,
        val finalResults: TestResults,
        val iterations: Int,
        val fixHistory: List<FixAttempt>
    )

    data class FixAttempt(
        val iteration: Int,
        val analysis: FailureAnalysis,
        val fixPlan: FixPlan,
        val results: TestResults
    )

    data class ApplyFixResult(
        val success: Boolean,
        val error: String? = null
    )
}
```

---

## 四、验证工具集成

新增一个 `verify` 工具，集成到现有工具系统：

```kotlin
/**
 * 验证工具
 *
 * 提供代码验证能力的工具
 */
class VerifyTool(
    private val project: Project
) : AbstractTool(), Tool {

    private val testGenerator = TestGenerator(project, LlmService.getInstance())
    private val sandboxExecutor = ProcessSandboxExecutor(project)
    private val autoFixer = AutoFixer(project, LlmService.getInstance(), CodeEditService.getInstance(project))

    override fun getName() = "verify"

    override fun getDescription() = """
        Verify code by running tests. Supports:
        - run_tests: Run existing tests for a file
        - generate_tests: Generate tests for code
        - auto_fix: Run tests and auto-fix failures (recommended)

        Example:
        {
            "action": "auto_fix",
            "target": "src/main/kotlin/com/example/MyService.kt"
        }
    """.trimIndent()

    override fun getParameters() = mapOf(
        "action" to ParameterDef("action", String::class.java, true,
            "Action: run_tests, generate_tests, auto_fix"),
        "target" to ParameterDef("target", String::class.java, true,
            "Target class or file to verify"),
        "options" to ParameterDef("options", Map::class.java, false,
            "Additional options (max_iterations, timeout, etc.)")
    )

    override suspend fun execute(
        projectKey: String,
        params: Map<String, Any>,
        partPusher: Consumer<Part>?
    ): ToolResult {
        val action = params["action"]?.toString()
            ?: return ToolResult.failure("缺少 action 参数")

        val target = params["target"]?.toString()
            ?: return ToolResult.failure("缺少 target 参数")

        val options = params["options"] as? Map<String, Any> ?: emptyMap()

        return when (action) {
            "run_tests" -> runTests(target, options, partPusher)
            "generate_tests" -> generateTests(target, options, partPusher)
            "auto_fix" -> autoFix(target, options, partPusher)
            else -> ToolResult.failure("未知操作: $action")
        }
    }

    private suspend fun runTests(
        target: String,
        options: Map<String, Any>,
        partPusher: Consumer<Part>?
    ): ToolResult {
        partPusher?.accept(TextPart("正在运行测试: $target\n"))

        val testFile = determineTestPath(target)
        val timeout = (options["timeout"] as? Number)?.toLong() ?: 300L

        val command = "./gradlew test --tests \"*${File(testFile).nameWithoutExtension}*\""
        val result = sandboxExecutor.executeInSandbox(command, SandboxOptions(timeoutSeconds = timeout))

        return when (result) {
            is SandboxResult.Completed -> {
                val testResults = TestResultCollector().parseTestResults(result.output, TestFramework.JUNIT5)

                val summary = buildString {
                    append("测试结果: ${testResults.passed}/${testResults.total} 通过")
                    if (testResults.failed > 0) {
                        append(", ${testResults.failed} 失败")
                    }
                    append("\n")
                    append("执行时间: ${result.durationMs}ms\n")

                    if (testResults.hasFailures()) {
                        append("\n失败详情:\n")
                        testResults.failures.forEach { f ->
                            append("- ${f.className}.${f.testName}: ${f.errorMessage}\n")
                        }
                    }
                }

                ToolResult.success(summary)
            }
            is SandboxResult.Timeout -> {
                ToolResult.failure("测试执行超时 (${result.timeoutSeconds}s)")
            }
            is SandboxResult.Rejected -> {
                ToolResult.failure("命令被拒绝: ${result.reason}")
            }
        }
    }

    private suspend fun generateTests(
        target: String,
        options: Map<String, Any>,
        partPusher: Consumer<Part>?
    ): ToolResult {
        partPusher?.accept(TextPart("正在生成测试: $target\n"))

        val generatedTests = testGenerator.generateTests(
            sourceFile = target,
            options = TestGenerationOptions(
                framework = TestFramework.JUNIT5,
                style = TestStyle.BDD,
                includeEdgeCases = options["includeEdgeCases"] as? Boolean ?: true,
                includeExceptionTests = options["includeExceptionTests"] as? Boolean ?: true,
                maxTestCases = (options["maxTestCases"] as? Number)?.toInt() ?: 10
            )
        )

        // 写入测试文件
        File(generatedTests.testFilePath).apply {
            parentFile.mkdirs()
            writeText(generatedTests.testClass)
        }

        val summary = buildString {
            append("已生成测试文件: ${generatedTests.testFilePath}\n")
            append("测试用例数: ${generatedTests.testCases.size}\n")
            append("预估覆盖率: ${generatedTests.coverage}%\n")
        }

        return ToolResult.success(summary)
    }

    private suspend fun autoFix(
        target: String,
        options: Map<String, Any>,
        partPusher: Consumer<Part>?
    ): ToolResult {
        partPusher?.accept(TextPart("开始自动验证和修复: $target\n"))

        // 1. 先生成测试
        partPusher?.accept(TextPart("  步骤 1/3: 生成测试用例...\n"))
        val generatedTests = testGenerator.generateTests(target)
        File(generatedTests.testFilePath).apply {
            parentFile.mkdirs()
            writeText(generatedTests.testClass)
        }

        // 2. 运行测试
        partPusher?.accept(TextPart("  步骤 2/3: 运行测试...\n"))
        val testFile = generatedTests.testFilePath
        val command = "./gradlew test --tests \"*${File(testFile).nameWithoutExtension}*\""
        val result = sandboxExecutor.executeInSandbox(command)
        val testResults = when (result) {
            is SandboxResult.Completed -> {
                TestResultCollector().parseTestResults(result.output, TestFramework.JUNIT5)
            }
            else -> {
                return ToolResult.failure("测试运行失败")
            }
        }

        // 3. 如果有失败，自动修复
        if (testResults.hasFailures()) {
            partPusher?.accept(TextPart("  步骤 3/3: 检测到 ${testResults.failed} 个失败，开始自动修复...\n"))

            val maxIterations = (options["maxIterations"] as? Number)?.toInt() ?: 3
            val fixResult = autoFixer.autoFixLoop(target, testResults, maxIterations)

            val summary = buildString {
                append("自动修复完成:\n")
                append("- 迭代次数: ${fixResult.iterations}\n")
                append("- 最终结果: ${if (fixResult.success) "全部通过" else "${fixResult.finalResults.failed} 个失败"}\n")

                if (fixResult.success) {
                    append("\n代码已通过所有测试验证。")
                } else {
                    append("\n未能修复所有问题，请手动检查。")
                }
            }

            return ToolResult.success(summary)
        } else {
            partPusher?.accept(TextPart("  步骤 3/3: 所有测试通过，无需修复。\n"))
            return ToolResult.success("验证完成: 所有 ${testResults.passed} 个测试通过")
        }
    }

    private fun determineTestPath(sourceFile: String): String {
        // 将源文件路径转换为测试文件路径
        // src/main/kotlin/com/example/MyService.kt -> src/test/kotlin/com/example/MyServiceTest.kt
        return sourceFile
            .replace("/main/", "/test/")
            .replaceBeforeLast(".", "") { "Test$it" }
    }
}
```

---

## 五、安全配置

### 5.1 允许的命令白名单

```kotlin
val DEFAULT_ALLOWED_COMMANDS = setOf(
    // Java/Kotlin
    "./gradlew", "gradlew", "gradle", "mvn", "mvnw",
    "java", "javac", "kotlin", "kotlinc",

    // JavaScript/TypeScript
    "npm", "yarn", "pnpm", "node", "npx",

    // Python
    "python", "python3", "pip", "pytest", "poetry",

    // Go
    "go", "go test",

    // Rust
    "cargo", "cargo test"
)
```

### 5.2 危险命令黑名单

```kotlin
val DANGEROUS_PATTERNS = listOf(
    // 文件系统
    "rm -rf", "rmdir /s", "del /f", "format",

    // 权限
    "sudo", "chmod 777", "chown",

    // 网络
    "curl | bash", "wget | sh", "nc -l",

    // Fork 炸弹
    ":(){ :|:& };:", "fork bomb",

    // 磁盘
    "mkfs", "dd if=", "> /dev/sd",

    // 进程
    "kill -9", "pkill", "killall"
)
```

---

## 六、实施路线

| 阶段 | 内容 | 时间 |
|------|------|------|
| Phase 1 | 测试运行 + 结果解析 | 1 周 |
| Phase 2 | 测试生成器 | 1-2 周 |
| Phase 3 | 进程级沙盒隔离 | 1 周 |
| Phase 4 | 自动修复循环 | 2 周 |
| Phase 5 | Docker 隔离（可选） | 2 周 |

---

## 七、总结

代码沙盒自验证的核心价值：

1. **确保代码可用**：交付前自动验证
2. **减少人工测试**：自动生成和运行测试
3. **自动修复**：失败时自动尝试修复
4. **安全隔离**：沙盒环境执行，不影响 IDE

关键设计决策：

- **进程级隔离优先**：轻量、快速、够用
- **Docker 隔离可选**：高风险场景使用
- **最大迭代次数限制**：防止无限循环
- **快照回滚机制**：修复失败时恢复
