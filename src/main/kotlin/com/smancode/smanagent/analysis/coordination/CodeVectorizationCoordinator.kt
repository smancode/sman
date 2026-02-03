package com.smancode.smanagent.analysis.coordination

import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.database.TieredVectorStore
import com.smancode.smanagent.analysis.database.VectorStoreService
import com.smancode.smanagent.analysis.llm.LlmCodeUnderstandingService
import com.smancode.smanagent.analysis.model.VectorFragment
import com.smancode.smanagent.analysis.scanner.PsiAstScanner
import com.smancode.smanagent.analysis.util.Md5FileTracker
import com.smancode.smanagent.analysis.vectorization.BgeM3Client
import com.smancode.smanagent.smancode.llm.LlmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

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

    // PSI 扫描器（用于读取源代码）
    private val psiScanner = PsiAstScanner()

    // MD5 文件追踪器
    private val md5Tracker: Md5FileTracker by lazy {
        val cacheDir = projectPath.resolve(".smanunion/cache")
        Files.createDirectories(cacheDir)
        Md5FileTracker(projectPath).apply {
            val cacheFile = cacheDir.resolve("md5_cache.json")
            if (cacheFile.exists()) {
                loadCache(cacheFile)
            }
        }
    }

    // 向量存储服务
    private val vectorStore: VectorStoreService by lazy {
        val config = VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig()
        )
        TieredVectorStore(config)
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

                // 向量化单个文件
                val vectors = vectorizeFile(sourceFile)
                processedCount++
                totalVectors += vectors.size

                // 更新 MD5 缓存
                md5Tracker.trackFile(sourceFile)

            } catch (e: Exception) {
                logger.error("向量化文件失败: file={}, error={}", sourceFile.fileName, e.message)
                errors.add(VectorizationResult.FileError(sourceFile, e.message ?: "未知错误"))
            }
        }

        // 保存 MD5 缓存
        saveMd5Cache()

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

        // 根据文件类型调用不同的分析方法
        val mdContent = when {
            sourceFile.fileName.toString().endsWith(".java") -> {
                when {
                    isEnumFile(sourceCode) -> llmCodeUnderstandingService.analyzeEnumFile(sourceFile, sourceCode)
                    else -> llmCodeUnderstandingService.analyzeJavaFile(sourceFile, sourceCode)
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

        // 解析 .md 文档为向量片段
        val vectors = llmCodeUnderstandingService.parseMarkdownToVectors(sourceFile, mdContent)

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

        logger.info("文件向量化完成: file={}, vectors={}", sourceFile.fileName, vectors.size)
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
     */
    private fun saveMarkdownDocument(sourceFile: Path, mdContent: String): Path {
        val mdDir = projectPath.resolve(".smanunion/md")
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
     * 保存 MD5 缓存
     */
    private fun saveMd5Cache() {
        try {
            val cacheDir = projectPath.resolve(".smanunion/cache")
            Files.createDirectories(cacheDir)
            val cacheFile = cacheDir.resolve("md5_cache.json")
            md5Tracker.saveCache(cacheFile)
        } catch (e: Exception) {
            logger.error("保存 MD5 缓存失败", e)
        }
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
