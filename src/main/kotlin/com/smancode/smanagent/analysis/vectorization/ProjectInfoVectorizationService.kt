package com.smancode.smanagent.analysis.vectorization

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.database.VectorStoreService
import com.smancode.smanagent.analysis.model.VectorFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 项目信息向量化服务
 *
 * 将项目分析各步骤的结果向量化并存入 TieredVectorStore（L1内存 + L2 JVector + L3 H2）
 */
class ProjectInfoVectorizationService(
    private val projectKey: String,
    private val vectorStore: VectorStoreService,
    bgeEndpoint: String
) {

    private val logger = LoggerFactory.getLogger(ProjectInfoVectorizationService::class.java)
    private val bgeClient = BgeM3Client(BgeM3Config(
        endpoint = bgeEndpoint,
        timeoutSeconds = 30,
        dimension = 1024
    ))

    /**
     * 向量化配置
     * 定义每种数据类型的向量化元数据
     */
    private data class VectorizationConfig(
        val type: String,
        val title: String,
        val description: String,
        val contentPrefix: String,
        val tags: List<String>
    )

    /**
     * 向量化配置映射
     */
    private val vectorizationConfigs = mapOf(
        "project_structure" to VectorizationConfig(
            type = "project_structure",
            title = "项目结构",
            description = "项目目录结构和文件组织方式",
            contentPrefix = "项目结构:",
            tags = listOf("project", "structure")
        ),
        "tech_stack" to VectorizationConfig(
            type = "tech_stack",
            title = "技术栈",
            description = "项目使用的技术框架和工具",
            contentPrefix = "技术栈:",
            tags = listOf("project", "tech_stack")
        ),
        "db_entities" to VectorizationConfig(
            type = "db_entities",
            title = "数据库实体",
            description = "数据库表和实体映射关系",
            contentPrefix = "数据库实体:",
            tags = listOf("database", "entity")
        ),
        "api_entries" to VectorizationConfig(
            type = "api_entries",
            title = "API入口",
            description = "HTTP API接口和入口点",
            contentPrefix = "API入口:",
            tags = listOf("api", "entry", "controller")
        ),
        "external_apis" to VectorizationConfig(
            type = "external_apis",
            title = "外部API调用",
            description = "项目对外部服务的API调用",
            contentPrefix = "外部API调用:",
            tags = listOf("external", "api", "feign", "retrofit")
        ),
        "enums" to VectorizationConfig(
            type = "enums",
            title = "枚举类",
            description = "业务常量和状态定义",
            contentPrefix = "枚举:",
            tags = listOf("enum", "constant")
        ),
        "common_classes" to VectorizationConfig(
            type = "common_classes",
            title = "公共类",
            description = "通用工具类和帮助类",
            contentPrefix = "公共类:",
            tags = listOf("class", "common", "utility")
        ),
        "xml_configs" to VectorizationConfig(
            type = "xml_configs",
            title = "XML配置",
            description = "配置文件和映射文件",
            contentPrefix = "XML配置:",
            tags = listOf("xml", "config", "mapper")
        )
    )

    /**
     * 向量化项目结构
     */
    suspend fun vectorizeProjectStructure(data: String): Int =
        vectorizeProjectStructureAsMarkdown(data)

    /**
     * 向量化技术栈
     */
    suspend fun vectorizeTechStack(data: String): Int =
        vectorizeTechStackAsMarkdown(data)

    /**
     * 向量化技术栈（Markdown 格式，提升语义搜索效果）
     */
    suspend fun vectorizeTechStackAsMarkdown(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val jsonData = jacksonObjectMapper().readTree(data)

            // 构建 Markdown 内容
            val markdownContent = buildString {
                appendLine("# 技术栈")
                appendLine()
                appendLine("## 常见问题")
                appendLine("- 项目技术栈是什么")
                appendLine("- 项目使用什么框架")
                appendLine("- 项目使用什么数据库")
                appendLine("- 项目使用什么编程语言")
                appendLine("- 项目使用什么构建工具")
                appendLine()

                // 构建类型
                val buildType = jsonData.get("buildType")?.asText() ?: "UNKNOWN"
                appendLine("## 构建工具")
                appendLine("- $buildType")
                appendLine()

                // 框架
                val frameworks = jsonData.get("frameworks")
                if (frameworks != null && frameworks.isArray) {
                    appendLine("## 框架")
                    frameworks.forEach { fw ->
                        val name = fw.get("name")?.asText()
                        val version = fw.get("version")?.asText()
                        if (name != null) {
                            val versionStr = if (version != null) " (v$version)" else ""
                            appendLine("- **$name**$versionStr")
                        }
                    }
                    appendLine()
                }

                // 编程语言
                val languages = jsonData.get("languages")
                if (languages != null && languages.isArray) {
                    appendLine("## 编程语言")
                    languages.forEach { lang ->
                        val name = lang.get("name")?.asText()
                        val version = lang.get("version")?.asText()
                        val fileCount = lang.get("fileCount")?.asInt() ?: 0
                        if (name != null) {
                            val versionStr = if (version != null) " (v$version)" else ""
                            appendLine("- **$name**$versionStr ($fileCount 个文件)")
                        }
                    }
                    appendLine()
                }

                // 数据库
                val databases = jsonData.get("databases")
                if (databases != null && databases.isArray) {
                    appendLine("## 数据库")
                    databases.forEach { db ->
                        val name = db.get("name")?.asText()
                        val type = db.get("type")?.asText()
                        if (name != null) {
                            val typeStr = if (type != null) " ($type)" else ""
                            appendLine("- **$name**$typeStr")
                        }
                    }
                    appendLine()
                }

                // 中间件（如果有）
                val middleware = jsonData.get("middleware")
                if (middleware != null && middleware.isArray && middleware.size() > 0) {
                    appendLine("## 中间件")
                    middleware.forEach { mw ->
                        val name = mw.get("name")?.asText()
                        if (name != null) {
                            appendLine("- **$name**")
                        }
                    }
                    appendLine()
                }
            }

            // 向量化 Markdown 内容
            val vector = bgeClient.embed(markdownContent)

            val fragment = VectorFragment(
                id = "$projectKey:tech_stack",
                title = "技术栈",
                content = markdownContent,
                fullContent = data,  // 保留原始 JSON 数据
                tags = listOf("project", "tech_stack", "技术栈", "框架", "数据库") + listOf(projectKey),
                metadata = mapOf(
                    "type" to "tech_stack",
                    "projectKey" to projectKey,
                    "format" to "markdown"
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化技术栈（Markdown 格式）: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化技术栈失败: projectKey={}", projectKey, e)
            0
        }
    }

    /**
     * 向量化项目结构（Markdown 格式，提升语义搜索效果）
     */
    suspend fun vectorizeProjectStructureAsMarkdown(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val jsonData = jacksonObjectMapper().readTree(data)

            // 构建 Markdown 内容
            val markdownContent = buildString {
                appendLine("# 项目结构")
                appendLine()
                appendLine("## 常见问题")
                appendLine("- 项目结构是什么")
                appendLine("- 项目结构怎么样")
                appendLine("- 介绍下项目结构")
                appendLine("- 项目有哪些模块")
                appendLine()

                // 模块列表
                val modules = jsonData.get("modules")
                if (modules != null && modules.isArray) {
                    appendLine("## 项目模块")
                    modules.forEach { module ->
                        val name = module.get("name")?.asText()
                        val description = module.get("description")?.asText()
                        if (name != null) {
                            appendLine("### $name")
                            if (description != null) {
                                appendLine("$description")
                            }
                            appendLine()
                        }
                    }
                }

                // 分层架构
                val layers = jsonData.get("layers")
                if (layers != null && layers.isArray) {
                    appendLine("## 分层架构")
                    layers.forEach { layer ->
                        val name = layer.get("name")?.asText()
                        val description = layer.get("description")?.asText()
                        if (name != null) {
                            val descStr = if (description != null) ": $description" else ""
                            appendLine("- **$name**$descStr")
                        }
                    }
                    appendLine()
                }

                // 包结构（如果有）
                val packages = jsonData.get("packages")
                if (packages != null && packages.isArray && packages.size() > 0) {
                    appendLine("## 主要包")
                    packages.take(10).forEach { pkg ->
                        val name = pkg.get("name")?.asText()
                        if (name != null) {
                            appendLine("- `$name`")
                        }
                    }
                    if (packages.size() > 10) {
                        appendLine("- ... 还有 ${packages.size() - 10} 个包")
                    }
                    appendLine()
                }
            }

            // 向量化 Markdown 内容
            val vector = bgeClient.embed(markdownContent)

            val fragment = VectorFragment(
                id = "$projectKey:project_structure",
                title = "项目结构",
                content = markdownContent,
                fullContent = data,  // 保留原始 JSON 数据
                tags = listOf("project", "structure", "项目结构", "模块", "架构") + listOf(projectKey),
                metadata = mapOf(
                    "type" to "project_structure",
                    "projectKey" to projectKey,
                    "format" to "markdown"
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化项目结构（Markdown 格式）: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化项目结构失败: projectKey={}", projectKey, e)
            0
        }
    }

    /**
     * 向量化数据库实体（细粒度：每个表独立存储）
     */
    suspend fun vectorizeDbEntitiesIndividually(data: String): Int = withContext(Dispatchers.IO) {
        try {
            // 先删除旧的粗粒度向量
            vectorStore.delete("$projectKey:db_entities")

            val jsonData = jacksonObjectMapper().readTree(data)
            val entities = jsonData.get("entities")?.map { it.asText() } ?: emptyList()
            val tables = jsonData.get("tables")?.map { it.asText() } ?: emptyList()

            logger.info("开始细粒度向量化数据库实体: 实体数={}, 表数={}", entities.size, tables.size)

            var count = 0

            // 向量化每个实体
            for (entity in entities) {
                try {
                    val entityName = extractShortName(entity)
                    val businessDesc = extractEntityBusinessDesc(entity)
                    val searchTerms = buildEntitySearchTerms(entity, businessDesc)

                    val content = buildString {
                        append("数据库实体: ")
                        append(entity)
                        if (businessDesc.isNotEmpty()) {
                            append(", 业务: ")
                            append(businessDesc)
                        }
                    }

                    val vector = bgeClient.embed(content)

                    val fragment = VectorFragment(
                        id = "$projectKey:db_entity:$entity",
                        title = entityName,
                        content = businessDesc.ifEmpty { "数据库实体" },
                        fullContent = buildEntityFullContent(entity, businessDesc, "entity"),
                        tags = listOf("database", "entity") + searchTerms + listOf(projectKey),
                        metadata = mapOf(
                            "type" to "db_entity",
                            "projectKey" to projectKey,
                            "qualifiedName" to entity
                        ),
                        vector = vector
                    )

                    vectorStore.add(fragment)
                    count++
                } catch (e: Exception) {
                    logger.warn("向量化数据库实体失败: entity={}, error={}", entity, e.message)
                }
            }

            // 向量化每个表
            for (table in tables) {
                try {
                    val tableDesc = extractTableBusinessDesc(table)
                    val searchTerms = buildTableSearchTerms(table, tableDesc)

                    val content = "数据库表: $table, 业务: ${tableDesc.ifEmpty { "数据存储" }}"

                    val vector = bgeClient.embed(content)

                    val fragment = VectorFragment(
                        id = "$projectKey:db_table:$table",
                        title = table,
                        content = tableDesc.ifEmpty { "数据库表" },
                        fullContent = buildEntityFullContent(table, tableDesc, "table"),
                        tags = listOf("database", "table") + searchTerms + listOf(projectKey),
                        metadata = mapOf(
                            "type" to "db_table",
                            "projectKey" to projectKey,
                            "tableName" to table
                        ),
                        vector = vector
                    )

                    vectorStore.add(fragment)
                    count++
                } catch (e: Exception) {
                    logger.warn("向量化数据库表失败: table={}, error={}", table, e.message)
                }
            }

            logger.info("数据库实体细粒度向量化完成: 成功={}", count)
            count
        } catch (e: Exception) {
            logger.error("数据库实体细粒度向量化失败", e)
            0
        }
    }

    /**
     * 提取实体业务描述
     */
    private fun extractEntityBusinessDesc(qualifiedName: String): String {
        val className = qualifiedName.substringAfterLast(".").removeSuffix("Entity").removeSuffix("DO")

        val businessMap = mapOf(
            "Loan" to "贷款",
            "Payment" to "还款",
            "User" to "用户",
            "Account" to "账户",
            "Order" to "订单",
            "Customer" to "客户",
            "Product" to "产品",
            "Transaction" to "交易",
            "Contract" to "合同"
        )

        var desc = className
        businessMap.forEach { (en, cn) ->
            desc = desc.replace(en, cn)
        }
        desc += "实体"

        return desc
    }

    /**
     * 提取表业务描述
     */
    private fun extractTableBusinessDesc(tableName: String): String {
        // 表名通常是 snake_case 或下划线分隔
        val businessMap = mapOf(
            "loan" to "贷款",
            "payment" to "还款",
            "user" to "用户",
            "account" to "账户",
            "order" to "订单",
            "customer" to "客户",
            "product" to "产品",
            "transaction" to "交易"
        )

        var desc = tableName
        businessMap.forEach { (en, cn) ->
            desc = desc.replace(en, cn, ignoreCase = true)
        }

        return desc
    }

    /**
     * 构建实体搜索关键词
     */
    private fun buildEntitySearchTerms(qualifiedName: String, businessDesc: String): List<String> {
        val terms = mutableListOf<String>()

        val className = qualifiedName.substringAfterLast(".").removeSuffix("Entity").removeSuffix("DO")

        // 驼峰分词
        val camelCaseParts = className.split("(?=[A-Z])".toRegex())
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
        terms.addAll(camelCaseParts)

        // 添加业务关键词
        terms.add("entity")
        terms.add("实体")

        return terms.distinct()
    }

    /**
     * 构建表搜索关键词
     */
    private fun buildTableSearchTerms(tableName: String, businessDesc: String): List<String> {
        val terms = mutableListOf<String>()

        terms.add("table")
        terms.add("表")
        terms.add(tableName.lowercase())

        if (businessDesc.isNotEmpty()) {
            terms.add(businessDesc.lowercase())
        }

        return terms.distinct()
    }

    /**
     * 构建实体完整内容
     */
    private fun buildEntityFullContent(name: String, desc: String, type: String): String {
        return jacksonObjectMapper().writeValueAsString(mapOf(
            "name" to name,
            "businessDesc" to desc,
            "type" to type
        ))
    }

    /**
     * 向量化数据库实体（旧方法：聚合存储）
     * @deprecated 使用细粒度向量化方法代替
     */
    @Deprecated("使用 vectorizeDbEntitiesIndividually 代替")
    suspend fun vectorizeDbEntities(data: String): Int =
        vectorizeData("db_entities", data)

    /**
     * 向量化 API 入口（细粒度：每个接口独立存储）
     */
    suspend fun vectorizeApiEntriesIndividually(data: String): Int = withContext(Dispatchers.IO) {
        try {
            // 先删除旧的粗粒度向量
            vectorStore.delete("$projectKey:api_entries")

            val jsonData = jacksonObjectMapper().readTree(data)
            val entries = jsonData.get("entries")?.map { it.asText() } ?: emptyList()
            val controllers = jsonData.get("controllers")?.map { it.asText() } ?: emptyList()

            logger.info("开始细粒度向量化 API 入口: 总数={}, 控制器数={}", entries.size, controllers.size)

            var count = 0
            for (entry in entries) {
                try {
                    // 提取简单的业务描述
                    val businessDesc = extractBusinessDescription(entry)
                    val searchTerms = buildSearchTerms(entry, businessDesc)

                    // 构建向量化内容
                    val content = buildString {
                        append("API入口: ")
                        append(entry)
                        if (businessDesc.isNotEmpty()) {
                            append(", 功能: ")
                            append(businessDesc)
                        }
                        if (searchTerms.isNotEmpty()) {
                            append(", 搜索关键词: ")
                            append(searchTerms.joinToString(", "))
                        }
                    }

                    val vector = bgeClient.embed(content)

                    val fragment = VectorFragment(
                        id = "$projectKey:api_entry:${entry}",
                        title = extractShortName(entry),
                        content = businessDesc.ifEmpty { "HTTP API接口" },
                        fullContent = buildFullContent(entry, businessDesc),
                        tags = listOf("api", "entry", "controller") + searchTerms + listOf(projectKey),
                        metadata = mapOf(
                            "type" to "api_entry",
                            "projectKey" to projectKey,
                            "qualifiedName" to entry,
                            "isController" to (entry in controllers).toString()
                        ),
                        vector = vector
                    )

                    vectorStore.add(fragment)
                    count++
                } catch (e: Exception) {
                    logger.warn("向量化 API 入口失败: entry={}, error={}", entry, e.message)
                }
            }

            logger.info("API 入口细粒度向量化完成: 成功={}", count)
            count
        } catch (e: Exception) {
            logger.error("API 入口细粒度向量化失败", e)
            0
        }
    }

    /**
     * 提取短名称（不含包名）
     */
    private fun extractShortName(qualifiedName: String): String {
        return qualifiedName.substringAfterLast(".")
    }

    /**
     * 提取业务描述（简单返回类名，语义理解交给 LLM）
     *
     * 注意：这里不使用硬编码的关键词映射，因为：
     * 1. 硬编码无法覆盖所有业务场景
     * 2. 不同项目的业务术语可能不同
     * 3. 语义理解应该交给 LLM 在查询时动态处理
     */
    private fun extractBusinessDescription(qualifiedName: String): String {
        val className = qualifiedName.substringAfterLast(".")
        // 简单返回类名，让 BGE 向量化模型处理语义
        // LLM 在 expert_consult 时会根据向量召回结果和用户问题进行语义匹配
        return className
    }

    /**
     * 构建搜索关键词
     */
    private fun buildSearchTerms(qualifiedName: String, businessDesc: String): List<String> {
        val terms = mutableListOf<String>()

        // 1. 从类名提取驼峰分词（英文）
        val className = qualifiedName.substringAfterLast(".")
        val camelCaseParts = className.split("(?=[A-Z])".toRegex())
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

        terms.addAll(camelCaseParts)

        // 2. 添加完整的类名（小写）
        terms.add(className.lowercase())

        // 3. 添加业务描述的中文关键词
        // 中文分词：提取业务术语
        val chineseTerms = extractChineseTerms(businessDesc)
        terms.addAll(chineseTerms)

        // 4. 添加完整的业务描述
        if (businessDesc.isNotEmpty()) {
            terms.add(businessDesc.lowercase())
        }

        // 5. 添加包名的最后一段（如果有）
        val packageName = qualifiedName.substringBeforeLast(".", "").substringAfterLast(".")
        if (packageName.isNotEmpty()) {
            terms.add(packageName.lowercase())
        }

        return terms.distinct()
    }

    /**
     * 从中文业务描述中提取关键词
     *
     * 注意：移除硬编码，因为语义理解应该交给 LLM
     */
    private fun extractChineseTerms(desc: String): List<String> {
        // 不使用硬编码关键词
        // BGE-M3 向量化会自动处理语义
        // LLM 在 expert_consult 时会做语义匹配
        return emptyList()
    }

    /**
     * 构建完整内容
     */
    private fun buildFullContent(qualifiedName: String, businessDesc: String): String {
        return jacksonObjectMapper().writeValueAsString(mapOf(
            "qualifiedName" to qualifiedName,
            "businessDesc" to businessDesc,
            "type" to "api_entry"
        ))
    }

    /**
     * 向量化 API 入口（旧方法：聚合存储）
     * @deprecated 使用 vectorizeApiEntriesIndividually 代替
     */
    @Deprecated("使用细粒度向量化方法代替")
    suspend fun vectorizeApiEntries(data: String): Int =
        vectorizeData("api_entries", data)

    /**
     * 向量化外部 API 调用
     */
    suspend fun vectorizeExternalApis(data: String): Int =
        vectorizeData("external_apis", data)

    /**
     * 向量化枚举类（细粒度：每个枚举独立存储）
     */
    suspend fun vectorizeEnumsIndividually(data: String): Int = withContext(Dispatchers.IO) {
        try {
            // 先删除旧的粗粒度向量
            vectorStore.delete("$projectKey:enums")

            val jsonData = jacksonObjectMapper().readTree(data)
            val enums = jsonData.get("enums")?.map { it.asText() } ?: emptyList()
            val allConstants = jsonData.get("constants")?.map { it.asText() } ?: emptyList()

            logger.info("开始细粒度向量化枚举类: 数量={}", enums.size)

            var count = 0
            enums.forEach { qualifiedName ->
                try {
                    val enumName = extractShortName(qualifiedName)
                    val enumConstants = allConstants.filter { it.startsWith("$enumName.") }
                        .map { it.substringAfter(".") }

                    // 构建业务描述
                    val businessDesc = extractEnumBusinessDesc(enumName)

                    // 构建向量化内容
                    val content = buildString {
                        append("枚举类: ")
                        append(enumName)
                        if (businessDesc.isNotEmpty()) {
                            append(", 业务: ")
                            append(businessDesc)
                        }
                        if (enumConstants.isNotEmpty()) {
                            append(", 枚举值: ")
                            append(enumConstants.take(5).joinToString(", "))
                        }
                    }

                    val vector = bgeClient.embed(content)

                    val fragment = VectorFragment(
                        id = "$projectKey:enum:$enumName",
                        title = enumName,
                        content = businessDesc.ifEmpty { "业务常量定义" },
                        fullContent = buildEnumFullContent(qualifiedName, enumName, enumConstants, businessDesc),
                        tags = listOf("enum", "constant", "状态") + extractEnumSearchTerms(enumName, enumConstants) + listOf(projectKey),
                        metadata = mapOf(
                            "type" to "enum",
                            "projectKey" to projectKey,
                            "qualifiedName" to qualifiedName,
                            "constants" to enumConstants.joinToString(",")
                        ),
                        vector = vector
                    )

                    vectorStore.add(fragment)
                    count++
                } catch (e: Exception) {
                    logger.warn("向量化枚举失败: enum={}, error={}", qualifiedName, e.message)
                }
            }

            logger.info("枚举类细粒度向量化完成: 成功={}", count)
            count
        } catch (e: Exception) {
            logger.error("枚举类细粒度向量化失败", e)
            0
        }
    }

    /**
     * 提取枚举业务描述
     */
    private fun extractEnumBusinessDesc(enumName: String): String {
        val businessMap = mapOf(
            "Status" to "状态",
            "Type" to "类型",
            "State" to "状态",
            "LoanStatus" to "贷款状态",
            "PaymentStatus" to "还款状态",
            "OrderStatus" to "订单状态",
            "UserType" to "用户类型"
        )

        return businessMap[enumName] ?: ""
    }

    /**
     * 提取枚举搜索关键词
     */
    private fun extractEnumSearchTerms(enumName: String, constants: List<String>): List<String> {
        val terms = mutableListOf<String>()

        terms.add("enum")
        terms.add("枚举")
        terms.add("状态")

        // 枚举名分词
        val camelCaseParts = enumName.split("(?=[A-Z])".toRegex())
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
        terms.addAll(camelCaseParts)

        // 添加常量值作为搜索词
        constants.take(10).forEach { const ->
            terms.add(const.lowercase())
        }

        return terms.distinct()
    }

    /**
     * 构建枚举完整内容
     */
    private fun buildEnumFullContent(qualifiedName: String, enumName: String, constants: List<String>, desc: String): String {
        return jacksonObjectMapper().writeValueAsString(mapOf(
            "qualifiedName" to qualifiedName,
            "enumName" to enumName,
            "constants" to constants,
            "businessDesc" to desc
        ))
    }

    /**
     * 向量化枚举（旧方法：聚合存储）
     * @deprecated 使用细粒度向量化方法代替
     */
    @Deprecated("使用 vectorizeEnumsIndividually 代替")
    suspend fun vectorizeEnums(data: String): Int =
        vectorizeData("enums", data)

    /**
     * 向量化公共类
     */
    suspend fun vectorizeCommonClasses(data: String): Int =
        vectorizeData("common_classes", data)

    /**
     * 向量化 XML 代码
     */
    suspend fun vectorizeXmlCodes(data: String): Int =
        vectorizeData("xml_configs", data)

    /**
     * 通用向量化方法
     */
    private suspend fun vectorizeData(configKey: String, data: String): Int = withContext(Dispatchers.IO) {
        try {
            val config = vectorizationConfigs[configKey]
                ?: throw IllegalArgumentException("未知的向量化配置: $configKey")

            val content = buildContentPrefix(config, data)
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:${config.type}",
                title = config.title,
                content = config.description,
                fullContent = data,
                tags = config.tags + projectKey,
                metadata = mapOf(
                    "type" to config.type,
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化${config.title}: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化失败: configKey={}", configKey, e)
            0
        }
    }

    /**
     * 构建内容前缀
     */
    private fun buildContentPrefix(config: VectorizationConfig, data: String): String {
        return if (config.type == "project_structure") {
            "${config.contentPrefix} $data"
        } else {
            "${config.contentPrefix} $data, 项目: $projectKey"
        }
    }

    /**
     * 关闭服务
     */
    fun close() {
        bgeClient.close()
        logger.info("项目信息向量化服务已关闭: projectKey={}", projectKey)
    }
}
