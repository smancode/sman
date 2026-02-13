package com.smancode.sman.analysis.coordination

import com.smancode.sman.analysis.config.BgeM3Config
import com.smancode.sman.analysis.database.VectorStoreService
import com.smancode.sman.analysis.llm.LlmCodeUnderstandingService
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.util.Md5FileTracker
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.smancode.llm.LlmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 代码向量化协调器（核心）
 *
 * 负责：
 * 1. 扫描项目中的源代码文件
 * 2. 检查文件 MD5 变化
 * 3. 调用 LLM 分析变化的文件
 * 4. 生成 .md 文档并持久化
 * 5. 解析 .md 文档为向量片段
 * 6. 向量化并存储到向量数据库
 *
 * 设计原则：
 * - 增量更新：只处理变化的文件
 * - 白名单机制：参数不满足直接抛异常
 * - 优雅降级：单个文件失败不影响整体
 */
class CodeVectorizationCoordinator(
    private val projectKey: String,
    private val projectPath: Path,
    private val llmService: LlmService,
    private val bgeEndpoint: String
) {

    private val logger = LoggerFactory.getLogger(CodeVectorizationCoordinator::class.java)

    // LLM 代码理解服务
    private val llmCodeUnderstandingService = LlmCodeUnderstandingService(llmService)

    // MD5 文件追踪器
    private val md5Tracker: Md5FileTracker by lazy {
        val cacheDir = projectPath.resolve(".sman/cache")
        Files.createDirectories(cacheDir)
        Md5FileTracker(projectPath).apply {
            val cacheFile = cacheDir.resolve("md5_cache.json")
            if (cacheFile.exists()) {
                loadCache(cacheFile)
            }
        }
    }

    // MD5 保存互斥锁（保护并发更新）
    private val md5SaveLock = ReentrantLock()

    // 向量存储服务（通过 VectorStoreManager 单例管理，避免 H2 连接池冲突）
    private val vectorStore: VectorStoreService by lazy {
        com.smancode.sman.analysis.database.VectorStoreManager.getOrCreate(projectKey, projectPath)
    }

    // BGE-M3 向量化客户端
    private val bgeClient: BgeM3Client by lazy {
        val config = BgeM3Config(
            endpoint = bgeEndpoint,
            modelName = "BAAI/bge-m3",
            dimension = 1024,
            timeoutSeconds = 30
        )
        BgeM3Client(config)
    }

    /**
     * 向量化项目中的所有文件
     *
     * @param forceUpdate 是否强制更新所有文件
     * @return 向量化结果
     */
    suspend fun vectorizeProject(forceUpdate: Boolean = false): VectorizationResult = withContext(Dispatchers.IO) {
        logger.info("开始项目向量化: projectKey={}, path={}, forceUpdate={}",
            projectKey, projectPath, forceUpdate)

        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<VectorizationResult.FileError>()

        // 扫描项目中的所有源代码文件
        val sourceFiles = scanSourceFiles()
        logger.info("扫描到源代码文件: {} 个", sourceFiles.size)

        var processedCount = 0
        var skippedCount = 0
        var totalVectors = 0

        for (sourceFile in sourceFiles) {
            try {
                // 检查文件是否需要处理
                if (!forceUpdate && !md5Tracker.hasChanged(sourceFile)) {
                    skippedCount++
                    logger.debug("跳过未变化文件: {}", sourceFile.fileName)
                    continue
                }

                // 向量化单个文件（包含：LLM分析 → 保存MD → 向量化 → 更新MD5）
                val vectors = vectorizeFileWithImmediateMd5Update(sourceFile)
                processedCount++
                totalVectors += vectors.size

            } catch (e: Exception) {
                logger.error("向量化文件失败: file={}, error={}", sourceFile.fileName, e.message)
                errors.add(VectorizationResult.FileError(sourceFile, e.message ?: "未知错误"))
            }
        }

        // 最终保存一次（确保所有文件都同步）
        saveMd5Cache()

        // 注意：不再自动调用 vectorizeFromExistingMd()
        // 原因：每次都重新向量化所有 MD 文件会导致不必要的重复处理
        // vectorizeFromExistingMd() 只用于数据恢复场景，应显式调用
        logger.info("项目向量化完成，跳过已有 MD 文件的重复处理（如需恢复数据，请显式调用 vectorizeFromExistingMd）")


        val elapsedTime = System.currentTimeMillis() - startTime
        logger.info("项目向量化完成: 处理={}, 跳过={}, 向量数={}, 耗时={}ms",
            processedCount, skippedCount, totalVectors, elapsedTime)

        VectorizationResult(
            totalFiles = sourceFiles.size,
            processedFiles = processedCount,
            skippedFiles = skippedCount,
            totalVectors = totalVectors,
            errors = errors,
            elapsedTimeMs = elapsedTime
        )
    }

    /**
     * 向量化单个文件（如果 MD5 变化）
     *
     * @param sourceFile 源代码文件
     * @return 生成的向量片段列表
     * @throws IllegalArgumentException 如果文件不存在或为空
     */
    suspend fun vectorizeFileIfChanged(sourceFile: Path): List<VectorFragment> = withContext(Dispatchers.IO) {
        // 白名单校验：文件必须存在
        if (!sourceFile.exists()) {
            throw IllegalArgumentException("文件不存在: $sourceFile")
        }

        // 白名单校验：文件不能为空
        if (Files.size(sourceFile) == 0L) {
            throw IllegalArgumentException("文件为空: $sourceFile")
        }

        // 检查 MD5 是否变化
        if (!md5Tracker.hasChanged(sourceFile)) {
            logger.debug("文件未变化，跳过: {}", sourceFile.fileName)
            emptyList()
        } else {
            // 向量化文件
            val vectors = vectorizeFile(sourceFile)

            // 更新 MD5 缓存
            md5Tracker.trackFile(sourceFile)
            saveMd5Cache()

            vectors
        }
    }

    /**
     * 向量化单个文件（核心逻辑）
     */
    private suspend fun vectorizeFile(sourceFile: Path): List<VectorFragment> {
        logger.info("开始向量化文件: {}", sourceFile.fileName)

        // 读取源代码
        val sourceCode = sourceFile.toFile().readText()

        // 计算相对路径（核心！用于 LLM 定位文件）
        val relativePath = calculateRelativePath(sourceFile)

        // 根据文件类型调用不同的分析方法
        val mdContent = when {
            sourceFile.fileName.toString().endsWith(".java") -> {
                when {
                    isEnumFile(sourceCode) -> llmCodeUnderstandingService.analyzeEnumFile(sourceFile, sourceCode, relativePath)
                    else -> llmCodeUnderstandingService.analyzeJavaFile(sourceFile, sourceCode, relativePath)
                }
            }
            sourceFile.fileName.toString().endsWith(".xml") -> {
                // TODO: 实现 XML 分析
                logger.warn("XML 文件分析暂未实现: {}", sourceFile.fileName)
                return emptyList()
            }
            else -> {
                logger.warn("不支持的文件类型: {}", sourceFile.fileName)
                return emptyList()
            }
        }

        // 保存 .md 文档
        saveMarkdownDocument(sourceFile, mdContent)

        // 解析 .md 文档为向量片段（传入相对路径）
        val vectors = llmCodeUnderstandingService.parseMarkdownToVectors(sourceFile, mdContent, relativePath)

        // 为每个向量生成嵌入并存储
        for (vector in vectors) {
            try {
                // 生成向量嵌入
                val embedding = bgeClient.embed(vector.content)

                // 更新向量值
                val vectorWithEmbedding = vector.copy(vector = embedding)

                // 删除旧向量
                vectorStore.delete(vector.id)

                // 存储新向量
                vectorStore.add(vectorWithEmbedding)

            } catch (e: Exception) {
                logger.error("向量化失败: id={}, error={}", vector.id, e.message)
                throw e
            }
        }

        logger.info("文件向量化完成: file={}, vectors={}, relativePath={}", sourceFile.fileName, vectors.size, relativePath)
        return vectors
    }

    /**
     * 计算相对路径（相对于项目根目录）
     */
    private fun calculateRelativePath(sourceFile: Path): String {
        return try {
            projectPath.relativize(sourceFile).toString().replace("\\", "/")
        } catch (e: Exception) {
            logger.warn("计算相对路径失败: sourceFile={}, error={}", sourceFile, e.message)
            sourceFile.fileName.toString()
        }
    }

    /**
     * 向量化单个文件（带即时 MD5 更新）
     *
     * 流程：
     * 1. LLM 分析 → 生成 MD 文档
     * 2. 立即更新 MD5（防止 LLM 重做）
     * 3. 向量化 MD 文档
     * 4. 立即保存 MD5 缓存（防止进度丢失）
     *
     * @param sourceFile 源代码文件
     * @return 生成的向量片段列表
     */
    private suspend fun vectorizeFileWithImmediateMd5Update(sourceFile: Path): List<VectorFragment> {
        logger.info("开始向量化文件（带即时MD5更新）: {}", sourceFile.fileName)

        // 计算相对路径（核心！用于 LLM 定位文件）
        val relativePath = calculateRelativePath(sourceFile)

        // 步骤 1: LLM 分析 → 生成 MD 文档
        val sourceCode = sourceFile.toFile().readText()
        val mdContent = when {
            sourceFile.fileName.toString().endsWith(".java") -> {
                when {
                    isEnumFile(sourceCode) -> llmCodeUnderstandingService.analyzeEnumFile(sourceFile, sourceCode, relativePath)
                    else -> llmCodeUnderstandingService.analyzeJavaFile(sourceFile, sourceCode, relativePath)
                }
            }
            sourceFile.fileName.toString().endsWith(".xml") -> {
                logger.warn("XML 文件分析暂未实现: {}", sourceFile.fileName)
                return emptyList()
            }
            else -> {
                logger.warn("不支持的文件类型: {}", sourceFile.fileName)
                return emptyList()
            }
        }

        // 保存 .md 文档
        saveMarkdownDocument(sourceFile, mdContent)

        // 步骤 2: 立即更新 MD5（LLM 完成了，不需要重做）
        md5SaveLock.withLock {
            md5Tracker.trackFile(sourceFile)
            saveMd5Cache()
        }
        logger.debug("MD5 已更新（LLM 完成后）: {}", sourceFile.fileName)

        // 步骤 3: 解析 .md 文档为向量片段（传入相对路径）
        val vectors = llmCodeUnderstandingService.parseMarkdownToVectors(sourceFile, mdContent, relativePath)

        // 步骤 4: 向量化并存储
        for (vector in vectors) {
            try {
                // 生成向量嵌入
                val embedding = bgeClient.embed(vector.content)

                // 更新向量值
                val vectorWithEmbedding = vector.copy(vector = embedding)

                // 删除旧向量
                vectorStore.delete(vector.id)

                // 存储新向量
                vectorStore.add(vectorWithEmbedding)

            } catch (e: Exception) {
                logger.error("向量化失败: id={}, error={}", vector.id, e.message)
                throw e
            }
        }

        logger.info("文件向量化完成: file={}, vectors={}, relativePath={}", sourceFile.fileName, vectors.size, relativePath)
        return vectors
    }

    /**
     * 扫描项目中的所有源代码文件
     */
    private fun scanSourceFiles(): List<Path> {
        val sourceFiles = mutableListOf<Path>()

        // 扫描 Java 源代码
        val javaFiles = Files.walk(projectPath)
            .filter { path ->
                path.toFile().isFile &&
                path.fileName.toString().endsWith(".java") &&
                !path.toString().contains("/build/") &&
                !path.toString().contains("/.gradle/") &&
                !path.toString().contains("/out/")
            }
            .toList()

        sourceFiles.addAll(javaFiles)

        // 扫描 XML 文件（可选）
        val xmlFiles = Files.walk(projectPath)
            .filter { path ->
                path.toFile().isFile &&
                (path.fileName.toString().endsWith(".xml") ||
                 path.fileName.toString().endsWith(".xsd")) &&
                (path.toString().contains("/mapper/") ||
                 path.toString().contains("/resources/"))
            }
            .toList()

        sourceFiles.addAll(xmlFiles)

        return sourceFiles.distinctBy { it.toString() }
    }

    /**
     * 判断是否为枚举文件
     */
    private fun isEnumFile(sourceCode: String): Boolean {
        return sourceCode.contains("enum class") ||
               (sourceCode.contains("public enum") && !sourceCode.contains("class enum"))
    }

    /**
     * 保存 Markdown 文档
     *
     * 保存到 .sman/md/classes/ 目录（类级分析结果）
     */
    private fun saveMarkdownDocument(sourceFile: Path, mdContent: String): Path {
        val mdDir = projectPath.resolve(".sman/md/classes")
        Files.createDirectories(mdDir)

        val mdFileName = sourceFile.fileName.toString()
            .replace(".java", ".md")
            .replace(".xml", ".md")
        val mdFile = mdDir.resolve(mdFileName)

        Files.writeString(mdFile, mdContent)
        logger.debug("保存 Markdown 文档: {}", mdFile.fileName)

        return mdFile
    }

    /**
     * 从 MD 文件中提取相对路径
     *
     * 优先从 MD 内容中解析相对路径，如果没有则从包名推断
     */
    private fun extractRelativePathFromMd(mdFile: Path, mdContent: String): String {
        // 1. 尝试从 MD 内容中提取相对路径
        val relativePathMatch = Regex("""[-*]\s*\*\*相对路径\*\*:\s*`?([^`\n]+)`?""").find(mdContent)
        if (relativePathMatch != null) {
            val path = relativePathMatch.groupValues[1].trim()
            if (path.isNotEmpty()) {
                return path
            }
        }

        // 2. 尝试从包名推断
        val packageMatch = Regex("""[-*]\s*\*\*包名\*\*:\s*`?([^`\n]+)`?""").find(mdContent)
        val className = mdFile.fileName.toString().removeSuffix(".md")

        if (packageMatch != null) {
            val packageName = packageMatch.groupValues[1].trim()
            if (packageName.isNotEmpty()) {
                // 将包名转换为路径
                val packagePath = packageName.replace(".", "/")
                return "src/main/java/$packagePath/$className.java"
            }
        }

        // 3. 默认返回类名（兜底）
        logger.warn("无法从 MD 文件提取相对路径: {}, 使用默认值", mdFile.fileName)
        return "src/main/java/$className.java"
    }

    /**
     * 保存 MD5 缓存
     */
    private fun saveMd5Cache() {
        try {
            val cacheDir = projectPath.resolve(".sman/cache")
            Files.createDirectories(cacheDir)
            val cacheFile = cacheDir.resolve("md5_cache.json")
            md5Tracker.saveCache(cacheFile)
        } catch (e: Exception) {
            logger.error("保存 MD5 缓存失败", e)
        }
    }

    /**
     * 从已有的 .md 文件重新向量化（跳过 LLM 分析）
     *
     * 用于修复持久化问题后的数据恢复。
     * 直接读取 .sman/md/classes/ 目录下的 .md 文件，解析并向量化，不调用 LLM。
     *
     * @return 向量化结果
     */
    suspend fun vectorizeFromExistingMd(): VectorizationResult = withContext(Dispatchers.IO) {
        logger.info("开始从已有 .md 文件向量化: projectKey={}", projectKey)

        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<VectorizationResult.FileError>()

        // 先删除所有旧的 .md 向量（清理历史遗留问题）
        // 注意：这里暂时跳过，因为需要添加 H2 查询方法
        // TODO: 添加清理旧向量的逻辑

        // 扫描 .md 文件目录（classes 目录）
        val mdDir = projectPath.resolve(".sman/md/classes")
        if (!mdDir.exists()) {
            logger.warn(".md 文件目录不存在: {}", mdDir)
            return@withContext VectorizationResult(
                totalFiles = 0,
                processedFiles = 0,
                skippedFiles = 0,
                totalVectors = 0,
                errors = emptyList(),
                elapsedTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 扫描所有 .md 文件
        val mdFiles = Files.walk(mdDir)
            .filter { it.toFile().isFile && it.fileName.toString().endsWith(".md") }
            .toList()

        logger.info("找到 .md 文件: {} 个", mdFiles.size)

        var processedCount = 0
        var totalVectors = 0

        for (mdFile in mdFiles) {
            try {
                // 读取 .md 文件内容
                val mdContent = mdFile.toFile().readText()

                // 跳过空文件
                if (mdContent.isBlank()) {
                    logger.debug("跳过空 .md 文件: {}", mdFile.fileName)
                    continue
                }

                // 从 MD 文件名推断对应的 Java 文件路径，计算相对路径
                // MD 文件在 .sman/md/classes/ 目录，文件名如 RepayHandler.md
                // 对应的 Java 文件需要从 MD 内容中提取包名，或从 MD 文件名推断
                val relativePath = extractRelativePathFromMd(mdFile, mdContent)

                // 解析 .md 文件为向量片段（传入相对路径）
                val vectors = llmCodeUnderstandingService.parseMarkdownToVectors(mdFile, mdContent, relativePath)

                if (vectors.isEmpty()) {
                    logger.debug(".md 文件没有解析出向量: {}", mdFile.fileName)
                    continue
                }

                // 为每个向量生成嵌入并存储
                for (vector in vectors) {
                    try {
                        // 生成向量嵌入
                        val embedding = bgeClient.embed(vector.content)

                        // 更新向量值
                        val vectorWithEmbedding = vector.copy(vector = embedding)

                        // 删除旧向量
                        vectorStore.delete(vector.id)

                        // 存储新向量（会持久化到 H2）
                        vectorStore.add(vectorWithEmbedding)

                    } catch (e: Exception) {
                        logger.error("向量化失败: id={}, error={}", vector.id, e.message)
                        throw e
                    }
                }

                processedCount++
                totalVectors += vectors.size

                logger.debug("文件向量化完成: file={}, vectors={}", mdFile.fileName, vectors.size)

            } catch (e: Exception) {
                logger.error("向量化 .md 文件失败: file={}, error={}", mdFile.fileName, e.message)
                errors.add(VectorizationResult.FileError(mdFile, e.message ?: "未知错误"))
            }
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        logger.info("从已有 .md 文件向量化完成: 处理={}, 向量数={}, 耗时={}ms",
            processedCount, totalVectors, elapsedTime)

        VectorizationResult(
            totalFiles = mdFiles.size,
            processedFiles = processedCount,
            skippedFiles = mdFiles.size - processedCount,
            totalVectors = totalVectors,
            errors = errors,
            elapsedTimeMs = elapsedTime
        )
    }

    /**
     * 关闭协调器
     */
    fun close() {
        try {
            bgeClient.close()
        } catch (e: Exception) {
            logger.error("关闭 BGE 客户端失败", e)
        }

        // 释放向量存储引用（不直接关闭，由 VectorStoreManager 管理）
        try {
            com.smancode.sman.analysis.database.VectorStoreManager.release(projectPath)
        } catch (e: Exception) {
            logger.error("释放向量存储引用失败", e)
        }
    }
}

/**
 * 向量化结果
 */
data class VectorizationResult(
    val totalFiles: Int,
    val processedFiles: Int,
    val skippedFiles: Int,
    val totalVectors: Int,
    val errors: List<FileError>,
    val elapsedTimeMs: Long = 0
) {
    /**
     * 是否成功（无错误）
     */
    val isSuccess: Boolean
        get() = errors.isEmpty()

    /**
     * 文件错误
     */
    data class FileError(
        val file: Path,
        val error: String
    )
}
