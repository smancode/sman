package com.smancode.sman.analysis.database

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.database.model.DbEntity
import com.smancode.sman.analysis.database.model.QueenTable
import com.smancode.sman.analysis.database.model.TableType
import com.smancode.sman.analysis.database.model.TableUsage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Queen 表检测器（三阶段协调）
 *
 * 阶段1：PSI 盲猜
 * 阶段2：LLM 确认修正
 * 阶段3：Mapper XML 引用验证
 */
class QueenTableDetector {

    private val logger = LoggerFactory.getLogger(QueenTableDetector::class.java)
    private val dbEntityDetector = DbEntityDetector()
    private val pseudoDdlGenerator = PseudoDdlGenerator()
    private val businessConceptInferenceService = BusinessConceptInferenceService()

    /**
     * 检测 Queen 表
     *
     * @param project IntelliJ 项目
     * @param xmlTableUsage XML 表使用情况（可选，阶段3）
     * @return QueenTable 列表（置信度 > 0.7）
     */
    fun detect(
        projectPath: java.nio.file.Path,
        xmlTableUsage: Map<String, TableUsage> = emptyMap()
    ): List<QueenTable> {
        val result = mutableListOf<QueenTable>()

        try {
            // 阶段1：PSI 盲猜
            val dbEntities = dbEntityDetector.detect(projectPath)
            logger.info("Stage 1: Detected ${dbEntities.size} DbEntity")

            // 阶段2：LLM 确认修正（暂时跳过，使用规则推断）
            // 阶段3：Mapper XML 引用验证（如果提供）
            for (dbEntity in dbEntities) {
                val queenTable = buildQueenTable(dbEntity, xmlTableUsage)
                if (queenTable.confidence > 0.7) {
                    result.add(queenTable)
                }
            }

            logger.info("Detected ${result.size} Queen tables (confidence > 0.7)")

        } catch (e: Exception) {
            logger.error("Failed to detect Queen tables", e)
        }

        return result
    }

    /**
     * 构建 QueenTable
     *
     * @param dbEntity 数据库实体
     * @param xmlTableUsage XML 表使用情况
     * @return QueenTable
     */
    private fun buildQueenTable(
        dbEntity: DbEntity,
        xmlTableUsage: Map<String, TableUsage>
    ): QueenTable {
        // 阶段1置信度
        val stage1Confidence = dbEntity.stage1Confidence

        // 阶段2置信度：使用业务概念推断（暂不使用 LLM）
        val businessName = businessConceptInferenceService.inferBusinessConcept(dbEntity)
        val tableType = inferTableType(dbEntity, businessName)
        val stage2Confidence = inferStage2Confidence(dbEntity, tableType)
        val llmReasoning = "基于主键和表名推断"

        // 阶段3置信度：根据 XML 引用次数调整
        val xmlReferenceCount = xmlTableUsage[dbEntity.tableName]?.referenceCount ?: 0
        val stage3Confidence = adjustConfidenceByXmlReference(
            stage2Confidence,
            xmlReferenceCount,
            xmlTableUsage.values.map { it.referenceCount }
        )

        // 最终置信度：取阶段3置信度
        val confidence = stage3Confidence

        // 生成伪 DDL
        val pseudoDdl = pseudoDdlGenerator.generate(dbEntity)

        return QueenTable(
            tableName = dbEntity.tableName,
            className = dbEntity.className,
            businessName = businessName,
            tableType = tableType,
            llmReasoning = llmReasoning,
            confidence = confidence,
            stage1Confidence = stage1Confidence,
            stage2Confidence = stage2Confidence,
            stage3Confidence = stage3Confidence,
            xmlReferenceCount = xmlReferenceCount,
            columnCount = dbEntity.fields.size,
            primaryKey = dbEntity.primaryKey,
            pseudoDdl = pseudoDdl
        )
    }

    /**
     * 推断表类型
     *
     * @param dbEntity 数据库实体
     * @param businessName 业务名称
     * @return 表类型
     */
    private fun inferTableType(dbEntity: DbEntity, businessName: String): TableType {
        // 核心业务表
        val coreBusinessTables = listOf("借据", "合同", "还款计划", "账户", "交易")
        if (businessName in coreBusinessTables) {
            return TableType.QUEEN
        }

        // 系统配置表
        if (dbEntity.tableName.startsWith("sys_") ||
            dbEntity.tableName.startsWith("cfg_") ||
            businessName == "系统" || businessName == "配置") {
            return TableType.SYSTEM
        }

        // 字典表
        if (dbEntity.tableName.startsWith("dict_") ||
            businessName == "字典") {
            return TableType.LOOKUP
        }

        return TableType.COMMON
    }

    /**
     * 推断阶段2置信度
     *
     * @param dbEntity 数据库实体
     * @param tableType 表类型
     * @return 置信度
     */
    private fun inferStage2Confidence(dbEntity: DbEntity, tableType: TableType): Double {
        var confidence = dbEntity.stage1Confidence

        // 根据表类型调整置信度
        when (tableType) {
            TableType.QUEEN -> confidence = (confidence + 0.2).coerceAtMost(1.0)
            TableType.SYSTEM -> confidence = (confidence - 0.1).coerceAtLeast(0.0)
            TableType.LOOKUP -> confidence = (confidence - 0.1).coerceAtLeast(0.0)
            TableType.COMMON -> confidence = confidence
        }

        return confidence
    }

    /**
     * 根据 XML 引用次数调整置信度
     *
     * @param baseConfidence 基础置信度
     * @param referenceCount 引用次数
     * @param allReferenceCounts 所有引用次数
     * @return 调整后的置信度
     */
    private fun adjustConfidenceByXmlReference(
        baseConfidence: Double,
        referenceCount: Int,
        allReferenceCounts: List<Int>
    ): Double {
        if (allReferenceCounts.isEmpty()) {
            return baseConfidence
        }

        // 计算百分位数
        val sorted = allReferenceCounts.sorted()
        val p90Index = (sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)
        val p75Index = (sorted.size * 0.75).toInt().coerceAtMost(sorted.size - 1)
        val p50Index = (sorted.size * 0.5).toInt().coerceAtMost(sorted.size - 1)

        val p90 = sorted[p90Index]
        val p75 = sorted[p75Index]
        val p50 = sorted[p50Index]

        return when {
            referenceCount >= p90 -> (baseConfidence + 0.15).coerceAtMost(0.95)
            referenceCount >= p75 -> (baseConfidence + 0.10).coerceAtMost(0.85)
            referenceCount >= p50 -> (baseConfidence + 0.05).coerceAtMost(0.75)
            referenceCount < p50 -> (baseConfidence - 0.10).coerceAtLeast(0.0)
            referenceCount == 0 -> (baseConfidence - 0.30).coerceAtLeast(0.0)
            else -> baseConfidence
        }
    }
}
