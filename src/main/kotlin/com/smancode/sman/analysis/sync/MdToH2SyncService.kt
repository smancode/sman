package com.smancode.sman.analysis.sync

import com.smancode.sman.analysis.config.BgeM3Config
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.config.TruncationStrategy
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.util.MdParser
import com.smancode.sman.analysis.vectorization.BgeM3Client
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Markdown 到 H2 同步服务
 *
 * 负责将 LLM 生成的 Markdown 文件解析并向量化，存储到 H2 数据库
 */
class MdToH2SyncService(
    private val projectKey: String,
    private val bgeEndpoint: String,
    private val projectBasePath: Path
) {
    private val logger = LoggerFactory.getLogger(MdToH2SyncService::class.java)

    // 向量存储（懒加载）
    private val vectorStore: TieredVectorStore by lazy {
        val config = VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig()
        )
        TieredVectorStore(config)
    }

    // BGE 客户端（懒加载）
    private val bgeClient: BgeM3Client by lazy {
        val config = BgeM3Config(
            endpoint = bgeEndpoint,
            truncationStrategy = TruncationStrategy.TAIL,
            truncationStepSize = 1000,
            maxTruncationRetries = 10
        )
        BgeM3Client(config)
    }

    /**
     * 同步单个分析类型的 MD 文件
     *
     * @param type 分析类型
     * @return 同步的向量片段数量
     */
    suspend fun syncAnalysisResult(type: AnalysisType): Int {
        val mdFile = projectBasePath.resolve(".sman").resolve("base").resolve(type.mdFileName)

        if (!Files.exists(mdFile)) {
            logger.warn("MD 文件不存在: {}", mdFile)
            return 0
        }

        return try {
            // 解析 Markdown
            val document = MdParser.parseFile(mdFile)

            // 向量化文档内容
            var count = 0

            // 向量化标题和概述
            if (document.title.isNotBlank()) {
                val titleVector = createVectorFragment(
                    type = type.key,
                    title = document.title,
                    content = document.title,
                    fullContent = document.title,
                    metadata = mapOf(
                        "section" to "title",
                        "file" to type.mdFileName
                    )
                )
                vectorStore.add(titleVector)
                count++
            }

            // 向量化各章节
            document.sections.forEach { section ->
                val sectionVector = createVectorFragment(
                    type = type.key,
                    title = section.name,
                    content = "${section.name}\n\n${section.content}",
                    fullContent = section.content,
                    metadata = mapOf(
                        "section" to section.name,
                        "level" to section.level.toString(),
                        "file" to type.mdFileName
                    )
                )
                vectorStore.add(sectionVector)
                count++
            }

            // 存储到 H2
            logger.info("已同步分析结果: type={}, fragments={}", type.key, count)

            count
        } catch (e: Exception) {
            logger.error("同步分析结果失败: type={}", type.key, e)
            0
        }
    }

    /**
     * 同步所有基础分析结果
     *
     * @return 同步的总向量片段数量
     */
    suspend fun syncAllBaseAnalysis(): Int {
        var totalCount = 0

        AnalysisType.values().forEach { type ->
            val count = syncAnalysisResult(type)
            totalCount += count
        }

        logger.info("已完成所有基础分析同步: projectKey={}, total={}", projectKey, totalCount)
        return totalCount
    }

    /**
     * 从内容创建向量片段
     */
    private suspend fun createVectorFragment(
        type: String,
        title: String,
        content: String,
        fullContent: String,
        metadata: Map<String, String>
    ): VectorFragment {
        // 调用 BGE 生成向量
        val embedding = bgeClient.embed(content)

        return VectorFragment(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            content = content,
            fullContent = fullContent,
            vector = embedding,
            tags = listOf(type),
            metadata = metadata
        )
    }

    /**
     * 向量化文本内容
     *
     * @param text 文本内容
     * @return 向量数组（浮点数列表）
     */
    private suspend fun embedText(text: String): FloatArray {
        return bgeClient.embed(text)
    }

    /**
     * 向量化并存储表格
     *
     * @param type 分析类型
     * @param table 解析后的表格
     */
    private suspend fun vectorizeTable(type: AnalysisType, table: MdParser.Table) {
        // 将表格转换为 Markdown 文本
        val markdown = tableToMarkdown(table)

        val vector = createVectorFragment(
            type = type.key,
            title = "表格: ${table.headers.joinToString(",")}",
            content = markdown,
            fullContent = markdown,
            metadata = mapOf(
                "section" to "table",
                "file" to type.mdFileName,
                "headers" to table.headers.joinToString(",")
            )
        )

        vectorStore.add(vector)
    }

    /**
     * 将表格转换为 Markdown
     */
    private fun tableToMarkdown(table: MdParser.Table): String {
        val sb = StringBuilder()

        // 表头
        sb.append("| ").append(table.headers.joinToString(" | ")).append(" |\n")
        sb.append("| ").append(table.headers.map { "---" }.joinToString(" | ")).append(" |\n")

        // 表格行
        table.rows.forEach { row ->
            sb.append("| ").append(row.joinToString(" | ")).append(" |\n")
        }

        return sb.toString()
    }

    /**
     * 检查 MD 文件是否需要更新（通过比较 MD5）
     *
     * @param type 分析类型
     * @param md5 文件 MD5
     * @return true 如果需要更新
     */
    fun needsUpdate(type: AnalysisType, md5: String): Boolean {
        // 简化实现：总是返回 true（需要更新）
        // 实际实现可以从 H2 读取并比较 MD5
        return true
    }

    /**
     * 清理过期的向量数据
     *
     * @param type 分析类型
     */
    fun cleanupStaleData(type: AnalysisType) {
        try {
            // 删除该类型的所有向量（使用前缀匹配）
            vectorStore.delete("${type.key}:")
            logger.info("已清理过期数据: type={}", type.key)
        } catch (e: Exception) {
            logger.error("清理数据失败: type={}", type.key, e)
        }
    }

    /**
     * 获取存储的统计信息
     *
     * @return 各类型的向量片段数量
     */
    fun getStats(): Map<String, Int> {
        return try {
            val stats = mutableMapOf<String, Int>()

            AnalysisType.values().forEach { type ->
                // 简化实现：返回 0
                stats[type.key] = 0
            }

            stats
        } catch (e: Exception) {
            logger.error("获取统计信息失败", e)
            emptyMap()
        }
    }
}
