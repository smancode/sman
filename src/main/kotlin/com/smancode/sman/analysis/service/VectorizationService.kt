package com.smancode.sman.analysis.service

import com.smancode.sman.analysis.coordination.VectorizationResult
import com.smancode.sman.analysis.parser.MarkdownParser
import com.smancode.sman.analysis.storage.VectorRepository
import com.smancode.sman.analysis.storage.TieredVectorRepository
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.BgeM3Config
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.paths.ProjectPaths
import com.smancode.sman.analysis.paths.ProjectStoragePaths
import com.smancode.sman.smancode.llm.LlmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * 向量化服务
 *
 * 负责：
 * - 编排向量化流程
 * - 清理旧向量
 * - 扫描和解析 .md 文件
 * - 生成和存储向量
 */
class VectorizationService(
    private val projectKey: String,
    private val projectPath: Path,
    private val llmService: LlmService,
    bgeEndpoint: String
) {
    private val logger = LoggerFactory.getLogger(VectorizationService::class.java)

    private val parser = MarkdownParser()
    private val bgeClient = BgeM3Client(BgeM3Config(endpoint = bgeEndpoint))
    private lateinit var repository: VectorRepository

    // 缓存项目路径，避免重复创建
    private val paths: ProjectStoragePaths by lazy {
        ProjectPaths.forProject(projectPath)
    }

    private companion object {
        // Markdown 文件扩展名
        const val MD_FILE_EXTENSION = ".md"
    }

    /**
     * 从已有的 .md 文件向量化
     *
     * 流程：
     * 1. 初始化存储仓库
     * 2. 清理旧向量（删除所有包含 .md 的向量）
     * 3. 扫描 .md 文件
     * 4. 解析每个文件
     * 5. 生成向量
     * 6. 存储向量
     */
    suspend fun vectorizeFromExistingMd(): VectorizationResult = withContext(Dispatchers.IO) {
        logger.info("开始从已有 .md 文件向量化: projectKey={}", projectKey)

        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<VectorizationResult.FileError>()

        // 初始化存储仓库
        repository = createRepository()

        // 1. 清理旧的 .md 向量（关键步骤！）
        cleanupOldVectors()

        // 2. 扫描 .md 文件目录
        val mdFiles = scanMdFiles()

        // 检查是否有文件
        if (mdFiles.isEmpty()) {
            logger.warn("未找到 .md 文件")
            return@withContext createEmptyResult(startTime)
        }

        // 3-6. 处理每个文件
        val (processedCount, totalVectors) = processFiles(mdFiles, errors)

        // 构造结果
        val elapsedTime = System.currentTimeMillis() - startTime
        logger.info("向量化完成: files={}, vectors={}, time={}ms",
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
     * 创建存储仓库
     */
    private fun createRepository(): VectorRepository {
        val config = VectorDatabaseConfig(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            databasePath = paths.databaseFile.toString()
        )
        return TieredVectorRepository(
            projectKey = projectKey,
            projectPath = projectPath,
            config = config
        )
    }

    /**
     * 清理旧向量
     */
    private fun cleanupOldVectors() {
        try {
            val deletedCount = repository.cleanupMdVectors()
            logger.info("清理旧的 .md 向量: 删除 {} 条", deletedCount)
        } catch (e: Exception) {
            logger.warn("清理旧向量失败: {}", e.message)
        }
    }

    /**
     * 扫描 MD 文件
     */
    private fun scanMdFiles(): List<Path> {
        val mdDir = paths.mdDir

        if (!Files.exists(mdDir)) {
            logger.warn(".md 文件目录不存在: {}", mdDir)
            return emptyList()
        }

        return Files.walk(mdDir)
            .filter { it.toFile().isFile && it.fileName.toString().endsWith(MD_FILE_EXTENSION) }
            .toList()
    }

    /**
     * 处理文件列表
     */
    private fun processFiles(
        mdFiles: List<Path>,
        errors: MutableList<VectorizationResult.FileError>
    ): Pair<Int, Int> {
        var processedCount = 0
        var totalVectors = 0

        for (mdFile in mdFiles) {
            try {
                val vectors = processFile(mdFile) ?: continue
                processedCount++
                totalVectors += vectors.size
            } catch (e: Exception) {
                logger.error("处理文件失败: file={}, error={}", mdFile.fileName, e.message)
                errors.add(VectorizationResult.FileError(mdFile, e.message ?: "未知错误"))
            }
        }

        return processedCount to totalVectors
    }

    /**
     * 处理单个文件
     */
    private fun processFile(mdFile: Path): List<com.smancode.sman.analysis.model.VectorFragment>? {
        // 读取文件
        val mdContent = Files.readString(mdFile)
        if (mdContent.isBlank()) {
            logger.debug("跳过空文件: {}", mdFile.fileName)
            return null
        }

        // 解析向量
        val vectors = parser.parseAll(mdFile, mdContent)
        if (vectors.isEmpty()) {
            logger.debug("文件没有解析出向量: {}", mdFile.fileName)
            return null
        }

        // 生成向量并存储
        vectors.forEach { vector ->
            vectorizeAndStore(vector)
        }

        return vectors
    }

    /**
     * 向量化并存储单个向量
     */
    private fun vectorizeAndStore(vector: com.smancode.sman.analysis.model.VectorFragment) {
        val embedding = bgeClient.embed(vector.content)
        val vectorWithEmbedding = vector.copy(vector = embedding)

        // 删除旧向量（如果有）
        repository.delete(vector.id)

        // 添加新向量
        repository.add(vectorWithEmbedding)
    }

    /**
     * 创建空结果
     */
    private fun createEmptyResult(startTime: Long): VectorizationResult {
        return VectorizationResult(
            totalFiles = 0,
            processedFiles = 0,
            skippedFiles = 0,
            totalVectors = 0,
            errors = emptyList(),
            elapsedTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 关闭服务
     */
    fun close() {
        try {
            bgeClient.close()
        } catch (e: Exception) {
            logger.warn("关闭 BGE 客户端失败: {}", e.message)
        }

        if (::repository.isInitialized) {
            try {
                (repository as? AutoCloseable)?.close()
            } catch (e: Exception) {
                logger.warn("关闭存储仓库失败: {}", e.message)
            }
        }
    }
}
