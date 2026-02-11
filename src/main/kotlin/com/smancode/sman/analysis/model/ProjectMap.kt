package com.smancode.sman.analysis.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// ========== 类型别名（用于兼容旧代码） ==========
/**
 * 旧的 StepStatus 枚举的别名
 * 为了兼容性保留，映射到 StepState
 */
typealias StepStatus = StepState

/**
 * 旧的 StepResult 的简化版本
 * 为了兼容性保留
 */
@Serializable
data class StepResult(
    val stepName: String,
    val stepDescription: String,
    val status: StepState,
    val startTime: Long,
    val endTime: Long? = null,
    val data: String? = null,
    val error: String? = null
) {
    /**
     * 获取耗时（毫秒）
     */
    fun getDuration(): Long? {
        return if (endTime != null && startTime > 0) {
            endTime - startTime
        } else {
            null
        }
    }

    companion object {
        /**
         * 从字符串解析 StepState
         */
        fun valueOf(name: String): StepState {
            return try {
                StepState.valueOf(name)
            } catch (e: IllegalArgumentException) {
                StepState.PENDING
            }
        }
    }
}

/**
 * 项目映射数据模型
 *
 * 用于跟踪所有已分析项目的状态和元数据
 *
 * @property version 数据格式版本
 * @property lastUpdate 最后更新时间（毫秒时间戳）
 * @property projects 项目列表（key 为 projectKey）
 */
@Serializable
data class ProjectMap(
    @SerialName("version")
    val version: String = "1.0.0",

    @SerialName("lastUpdate")
    val lastUpdate: Long = System.currentTimeMillis(),

    @SerialName("projects")
    val projects: Map<String, ProjectEntry> = emptyMap()
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * 创建空的 ProjectMap
         */
        fun empty() = ProjectMap()

        /**
         * 从 JSON 字符串解析
         */
        fun fromJson(jsonString: String): ProjectMap {
            return json.decodeFromString(serializer(), jsonString)
        }
    }

    /**
     * 转换为 JSON 字符串
     */
    fun toJson(): String {
        return json.encodeToString(serializer(), this)
    }

    /**
     * 更新或添加项目
     */
    fun withProject(projectKey: String, entry: ProjectEntry): ProjectMap {
        return copy(projects = projects + (projectKey to entry))
    }

    /**
     * 更新最后更新时间
     */
    fun withTimestamp(timestamp: Long = System.currentTimeMillis()): ProjectMap {
        return copy(lastUpdate = timestamp)
    }
}

/**
 * 单个项目的信息
 *
 * @property path 项目路径
 * @property lastAnalyzed 最后分析时间（毫秒时间戳）
 * @property projectMd5 项目内容 MD5 哈希值
 * @property analysisStatus 各分析类型的完成状态
 * @property mdFiles 已生成的 Markdown 文件（key 为文件路径，value 为 MD5）
 */
@Serializable
data class ProjectEntry(
    @SerialName("path")
    val path: String,

    @SerialName("lastAnalyzed")
    val lastAnalyzed: Long,

    @SerialName("projectMd5")
    val projectMd5: String,

    @SerialName("analysisStatus")
    val analysisStatus: AnalysisStatus,

    @SerialName("mdFiles")
    val mdFiles: Map<String, String> = emptyMap()
) {
    /**
     * 检查特定分析类型是否完成
     */
    fun isAnalysisComplete(type: AnalysisType): Boolean {
        return when (type) {
            AnalysisType.PROJECT_STRUCTURE -> analysisStatus.projectStructure == StepState.COMPLETED
            AnalysisType.TECH_STACK -> analysisStatus.techStack == StepState.COMPLETED
            AnalysisType.API_ENTRIES -> analysisStatus.apiEntries == StepState.COMPLETED
            AnalysisType.DB_ENTITIES -> analysisStatus.dbEntities == StepState.COMPLETED
            AnalysisType.ENUMS -> analysisStatus.enums == StepState.COMPLETED
            AnalysisType.CONFIG_FILES -> analysisStatus.configFiles == StepState.COMPLETED
        }
    }

    /**
     * 更新分析状态
     */
    fun withAnalysisStatus(type: AnalysisType, state: StepState): ProjectEntry {
        val newStatus = when (type) {
            AnalysisType.PROJECT_STRUCTURE -> analysisStatus.copy(projectStructure = state)
            AnalysisType.TECH_STACK -> analysisStatus.copy(techStack = state)
            AnalysisType.API_ENTRIES -> analysisStatus.copy(apiEntries = state)
            AnalysisType.DB_ENTITIES -> analysisStatus.copy(dbEntities = state)
            AnalysisType.ENUMS -> analysisStatus.copy(enums = state)
            AnalysisType.CONFIG_FILES -> analysisStatus.copy(configFiles = state)
        }
        return copy(analysisStatus = newStatus)
    }

    /**
     * 更新最后分析时间
     */
    fun withAnalyzedTime(timestamp: Long = System.currentTimeMillis()): ProjectEntry {
        return copy(lastAnalyzed = timestamp)
    }

    /**
     * 更新项目 MD5
     */
    fun withProjectMd5(md5: String): ProjectEntry {
        return copy(projectMd5 = md5)
    }
}

/**
 * 各分析类型的完成状态
 *
 * @property projectStructure 项目结构分析状态
 * @property techStack 技术栈识别状态
 * @property apiEntries API 入口扫描状态
 * @property dbEntities 数据库实体分析状态
 * @property enums 枚举分析状态
 * @property configFiles 配置文件分析状态
 */
@Serializable
data class AnalysisStatus(
    @SerialName("project_structure")
    val projectStructure: StepState = StepState.PENDING,

    @SerialName("tech_stack")
    val techStack: StepState = StepState.PENDING,

    @SerialName("api_entries")
    val apiEntries: StepState = StepState.PENDING,

    @SerialName("db_entities")
    val dbEntities: StepState = StepState.PENDING,

    @SerialName("enums")
    val enums: StepState = StepState.PENDING,

    @SerialName("config_files")
    val configFiles: StepState = StepState.PENDING
) {
    /**
     * 检查是否有任何分析在进行中
     */
    fun hasRunningAnalysis(): Boolean {
        return values().any { it == StepState.RUNNING }
    }

    /**
     * 检查所有分析是否完成
     */
    fun isAllComplete(): Boolean {
        return values().all { it == StepState.COMPLETED }
    }

    /**
     * 获取所有状态值
     */
    fun values(): List<StepState> {
        return listOf(projectStructure, techStack, apiEntries, dbEntities, enums, configFiles)
    }

    companion object {
        /**
         * 创建全为 PENDING 状态的 AnalysisStatus
         */
        fun pending() = AnalysisStatus()

        /**
         * 创建全为 RUNNING 状态的 AnalysisStatus
         */
        fun running() = AnalysisStatus(
            projectStructure = StepState.RUNNING,
            techStack = StepState.RUNNING,
            apiEntries = StepState.RUNNING,
            dbEntities = StepState.RUNNING,
            enums = StepState.RUNNING,
            configFiles = StepState.RUNNING
        )
    }
}

/**
 * 单个分析步骤的状态
 */
@Serializable
enum class StepState {
    /** 待处理 */
    PENDING,

    /** 执行中 */
    RUNNING,

    /** 已完成 */
    COMPLETED,

    /** 失败 */
    FAILED,

    /** 跳过 */
    SKIPPED
}
