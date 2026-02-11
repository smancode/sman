package com.smancode.sman.analysis.model

/**
 * 分析类型枚举
 *
 * 定义所有支持的项目分析类型
 *
 * @property key 唯一标识符（用于存储和检索）
 * @property displayName 显示名称（用于 UI 展示）
 * @property mdFileName 生成的 Markdown 文件名
 */
enum class AnalysisType(
    val key: String,
    val displayName: String,
    val mdFileName: String
) {
    /** 项目结构分析 */
    PROJECT_STRUCTURE(
        key = "project_structure",
        displayName = "项目结构分析",
        mdFileName = "01_project_structure.md"
    ),

    /** 技术栈识别 */
    TECH_STACK(
        key = "tech_stack",
        displayName = "技术栈识别",
        mdFileName = "02_tech_stack.md"
    ),

    /** API 入口扫描 */
    API_ENTRIES(
        key = "api_entries",
        displayName = "API 入口扫描",
        mdFileName = "03_api_entries.md"
    ),

    /** 数据库实体分析 */
    DB_ENTITIES(
        key = "db_entities",
        displayName = "数据库实体分析",
        mdFileName = "04_db_entities.md"
    ),

    /** 枚举分析 */
    ENUMS(
        key = "enums",
        displayName = "枚举分析",
        mdFileName = "05_enums.md"
    ),

    /** 配置文件分析 */
    CONFIG_FILES(
        key = "config_files",
        displayName = "配置文件分析",
        mdFileName = "06_config_files.md"
    );

    companion object {
        /**
         * 从 key 获取 AnalysisType
         *
         * @param key 分析类型标识符
         * @return 对应的 AnalysisType，如果不存在返回 null
         */
        fun fromKey(key: String): AnalysisType? {
            return values().firstOrNull { it.key == key }
        }

        /**
         * 获取所有分析类型
         */
        fun allTypes(): List<AnalysisType> = values().toList()

        /**
         * 获取核心分析类型（优先执行）
         * 核心类型：项目结构、技术栈
         */
        fun coreTypes(): List<AnalysisType> {
            return listOf(PROJECT_STRUCTURE, TECH_STACK)
        }

        /**
         * 获取标准分析类型（核心类型之后执行）
         */
        fun standardTypes(): List<AnalysisType> {
            return listOf(
                API_ENTRIES,
                DB_ENTITIES,
                ENUMS,
                CONFIG_FILES
            )
        }
    }

    /**
     * 获取提示词资源路径
     */
    fun getPromptPath(): String {
        return "analysis/$mdFileName"
    }

    /**
     * 获取生成的 Markdown 文件路径（相对于 .sman/base/）
     */
    fun getMdFilePath(): String {
        return mdFileName
    }
}
