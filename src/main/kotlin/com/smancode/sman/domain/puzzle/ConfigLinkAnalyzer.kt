package com.smancode.sman.domain.puzzle

import org.slf4j.LoggerFactory
import java.io.File

/**
 * 配置关联类型
 */
enum class ConfigLinkType {
    /** MyBatis Mapper 接口 ↔ XML */
    MYBATIS_MAPPER,
    /** XML 中定义的 Procedure 类 */
    XML_PROCEDURE,
    /** transaction.xml 配置 */
    TRANSACTION_CONFIG,
    /** 类被 XML 引用（反向关联） */
    XML_REFERENCE,
    /** 普通 Java 类 */
    JAVA_CLASS,
    /** Spring 配置 */
    SPRING_CONFIG
}

/**
 * 配置关联
 */
data class ConfigLink(
    /** 关联类型 */
    val type: ConfigLinkType,
    /** 目标路径（文件路径或类全名） */
    val targetPath: String,
    /** 目标类名（如果是 Java 类） */
    val targetClass: String = "",
    /** 置信度 0.0-1.0 */
    val confidence: Double,
    /** 深度（0=直接关联，1=间接关联...） */
    val depth: Int = 0,
    /** 额外上下文 */
    val context: Map<String, String> = emptyMap()
)

/**
 * 配置关联分析器
 *
 * 职责：发现 Java 代码与配置文件之间的关联关系
 *
 * 支持的模式：
 * 1. MyBatis Mapper 接口 ↔ XML
 * 2. Service → TransactionExecutor → transaction.xml
 * 3. XML → Procedure 类
 * 4. 反向关联：类 → 引用它的 XML
 */
class ConfigLinkAnalyzer(
    private val projectPath: String = System.getProperty("user.dir")
) {
    private val logger = LoggerFactory.getLogger(ConfigLinkAnalyzer::class.java)

    /**
     * 查找与指定文件关联的所有配置
     *
     * @param filePath 源文件路径（相对项目根目录）
     * @return 关联列表
     */
    fun findLinkedConfigs(filePath: String): List<ConfigLink> {
        val links = mutableListOf<ConfigLink>()
        val file = File(projectPath, filePath)

        if (!file.exists()) {
            logger.debug("文件不存在: {}", filePath)
            return emptyList()
        }

        val content = file.readText()

        // 根据文件类型选择分析策略
        when {
            // Java Mapper 接口 → 找对应的 XML
            isMyBatisMapper(content, filePath) -> {
                links.addAll(findMyBatisXml(filePath, content))
            }

            // Java Service → 找 transaction.xml
            isServiceCallingTransactionExecutor(content) -> {
                links.addAll(findTransactionXml(content))
            }

            // XML 文件 → 找引用的 Java 类
            filePath.endsWith(".xml") -> {
                links.addAll(findReferencedJavaClasses(filePath, content))
            }
        }

        logger.info("分析文件 {} 发现 {} 个关联", filePath, links.size)
        return links
    }

    /**
     * 发现完整调用链（递归分析）
     *
     * @param startPath 起点文件路径
     * @param maxDepth 最大递归深度
     * @return 调用链中的所有关联
     */
    fun discoverCallChain(startPath: String, maxDepth: Int): List<ConfigLink> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<ConfigLink>()
        val queue = ArrayDeque<Pair<String, Int>>()

        queue.add(Pair(startPath, 0))

        while (queue.isNotEmpty()) {
            val (currentPath, depth) = queue.removeFirst()

            if (depth > maxDepth || currentPath in visited) continue
            visited.add(currentPath)

            val links = findLinkedConfigs(currentPath)
            links.forEach { link ->
                val linkWithDepth = link.copy(depth = depth + 1)
                result.add(linkWithDepth)

                // 递归探索
                if (depth + 1 < maxDepth) {
                    val targetPath = link.targetPath
                    if (targetPath.isNotEmpty() && File(projectPath, targetPath).exists()) {
                        queue.add(Pair(targetPath, depth + 1))
                    }
                }
            }
        }

        return result
    }

    // ========== 私有方法 ==========

    /**
     * 判断是否是 MyBatis Mapper 接口
     */
    private fun isMyBatisMapper(content: String, filePath: String): Boolean {
        return filePath.endsWith("Mapper.java") &&
                (content.contains("@Mapper") ||
                        content.contains("extends BaseMapper") ||
                        content.contains("interface"))
    }

    /**
     * 判断是否是调用 TransactionExecutor/XmlTransaction 的 Service
     */
    private fun isServiceCallingTransactionExecutor(content: String): Boolean {
        return content.contains("TransactionExecutor") ||
                content.contains("transactionExecutor.execute") ||
                content.contains(".execute(\"") ||
                content.contains("executeXmlTransaction") ||
                content.contains("XmlTransaction")
    }

    /**
     * 找 MyBatis Mapper 对应的 XML
     */
    private fun findMyBatisXml(mapperPath: String, content: String): List<ConfigLink> {
        val links = mutableListOf<ConfigLink>()

        // 从 Mapper 接口提取 namespace（包名.类名）
        val namespace = extractNamespace(mapperPath)
        if (namespace.isEmpty()) return emptyList()

        // 提取 Mapper 类名
        val mapperClassName = File(mapperPath).nameWithoutExtension

        // 可能的 XML 位置（多种常见模式）
        val possiblePaths = mutableListOf<String>()

        // 1. 同目录结构，替换 java -> resources
        possiblePaths.add(
            mapperPath
                .replace("/java/", "/resources/")
                .replace(".java", ".xml")
        )

        // 2. resources/mapper 目录（最常见）
        val modulePrefix = extractModulePrefix(mapperPath)
        if (modulePrefix.isNotEmpty()) {
            possiblePaths.add("${modulePrefix}src/main/resources/mapper/${mapperClassName}.xml")
        }

        // 3. 直接在 resources 下
        possiblePaths.add("src/main/resources/mapper/${mapperClassName}.xml")

        // 4. 与 Java 同目录结构但后缀为 xml
        possiblePaths.add(mapperPath.replace(".java", ".xml"))

        for (xmlPath in possiblePaths) {
            val xmlFile = File(projectPath, xmlPath)
            if (xmlFile.exists()) {
                // 验证 namespace 匹配
                val xmlContent = xmlFile.readText()
                if (xmlContent.contains("namespace=\"$namespace\"")) {
                    links.add(ConfigLink(
                        type = ConfigLinkType.MYBATIS_MAPPER,
                        targetPath = xmlPath,
                        targetClass = namespace,
                        confidence = 0.95
                    ))
                    break // 找到一个就够
                }
            }
        }

        return links
    }

    /**
     * 提取模块前缀
     *
     * 例如: loan/src/main/java/... -> loan/
     */
    private fun extractModulePrefix(path: String): String {
        val srcIndex = path.indexOf("/src/")
        if (srcIndex > 0) {
            return path.substring(0, srcIndex + 1)
        }
        return ""
    }

    /**
     * 从 Mapper 文件路径提取 namespace
     *
     * 例如: loan/src/main/java/com/autoloop/loan/mapper/AcctTransactionMapper.java
     * → com.autoloop.loan.mapper.AcctTransactionMapper
     */
    private fun extractNamespace(mapperPath: String): String {
        // 提取包路径部分
        val javaIndex = mapperPath.indexOf("/java/")
        if (javaIndex == -1) return ""

        val packagePath = mapperPath.substring(javaIndex + 6) // 跳过 "/java/"
            .replace(".java", "")
            .replace("/", ".")

        return packagePath
    }

    /**
     * 从 Service 代码中找 transaction.xml 关联
     *
     * 策略：
     * 1. 精确匹配：从代码中提取 TransactionCode
     * 2. 模糊匹配：从注释中搜索可能的 code
     * 3. 字符串搜索：在 XML 中搜索代码中的关键字符串
     */
    private fun findTransactionXml(content: String): List<ConfigLink> {
        val links = mutableListOf<ConfigLink>()

        // 提取 TransactionCode（多种模式）
        val transactionCodeRegexes = listOf(
            // .execute("2001", ...)
            Regex("""\.execute\s*\(\s*"(\d+)""""),
            // transCode=2001 或 transCode = 2001
            Regex("""transCode\s*[=:]\s*"?(\d{4})"?"""),
            // TransactionCode="2001"
            Regex("""TransactionCode\s*=\s*"(\d+)""""),
            // execute("2001")
            Regex("""execute\s*\(\s*"(\d+)""""),
            // 注释中的 code："事务代码 2001" 或 "transCode: 2001"
            Regex("""(?:事务代码|transCode|交易码|trans\s*code)[\s:：]*"?(\d{4})"?""", RegexOption.IGNORE_CASE)
        )

        var transactionCode: String? = null
        for (regex in transactionCodeRegexes) {
            val match = regex.find(content)
            if (match != null) {
                transactionCode = match.groupValues[1]
                logger.debug("从代码中提取 TransactionCode: {}", transactionCode)
                break
            }
        }

        // 查找 transaction.xml 文件
        val projectDir = File(projectPath)
        val transactionXmls = projectDir.walkTopDown()
            .filter { it.name == "transaction.xml" }
            .toList()

        for (xmlFile in transactionXmls) {
            val relativePath = xmlFile.relativeTo(projectDir).path
            val xmlContent = xmlFile.readText()

            if (transactionCode != null) {
                // 精确匹配：验证 XML 中包含该 TransactionCode
                if (xmlContent.contains("TransactionCode=\"$transactionCode\"")) {
                    links.add(ConfigLink(
                        type = ConfigLinkType.TRANSACTION_CONFIG,
                        targetPath = relativePath,
                        confidence = 0.9,
                        context = mapOf("transactionCode" to transactionCode)
                    ))
                    continue
                }
            }

            // 脑洞：字符串搜索 - 在 XML 中搜索代码中的关键字符串
            val fuzzyCode = fuzzyMatchByContent(content, xmlContent)
            if (fuzzyCode != null) {
                links.add(ConfigLink(
                    type = ConfigLinkType.TRANSACTION_CONFIG,
                    targetPath = relativePath,
                    confidence = 0.75,
                    context = mapOf(
                        "transactionCode" to fuzzyCode,
                        "matchType" to "fuzzy"
                    )
                ))
                continue
            }

            // 最后兜底：只要有关键词就关联（最低置信度）
            if (content.contains("XmlTransaction", ignoreCase = true) ||
                content.contains("executeXmlTransaction", ignoreCase = true) ||
                content.contains("transaction.xml", ignoreCase = true)) {
                links.add(ConfigLink(
                    type = ConfigLinkType.TRANSACTION_CONFIG,
                    targetPath = relativePath,
                    confidence = 0.5,
                    context = mapOf("matchType" to "keyword")
                ))
            }
        }

        return links
    }

    /**
     * 脑洞：通过字符串搜索进行模糊匹配
     *
     * 原理：代码注释或字符串中可能包含业务关键词，
     * 这些关键词可能出现在 XML 的 TransactionName 或注释中
     *
     * 例如：
     * - Java 注释："正常还款" → XML: TransactionName="正常还款"
     * - Java 字符串："REPAYMENT" → XML: filter 中包含 REPAYMENT
     */
    private fun fuzzyMatchByContent(javaContent: String, xmlContent: String): String? {
        // 从 Java 中提取可能的业务关键词
        val keywords = extractBusinessKeywords(javaContent)

        // 在 XML 中搜索这些关键词
        for (keyword in keywords) {
            // 检查是否匹配 TransactionName
            val transactionNameMatch = Regex("""TransactionName="([^"]*${Regex.escape(keyword)}[^"]*)"""")
                .find(xmlContent)
            if (transactionNameMatch != null) {
                // 尝试提取对应的 TransactionCode
                val surroundingText = xmlContent.substring(
                    (transactionNameMatch.range.first - 200).coerceAtLeast(0),
                    (transactionNameMatch.range.last + 200).coerceAtMost(xmlContent.length)
                )
                val codeMatch = Regex("""TransactionCode="(\d+)"""").find(surroundingText)
                if (codeMatch != null) {
                    logger.debug("模糊匹配成功: 关键词='{}', TransactionCode={}", keyword, codeMatch.groupValues[1])
                    return codeMatch.groupValues[1]
                }
            }
        }

        return null
    }

    /**
     * 从 Java 代码中提取业务关键词
     *
     * 提取来源：
     * 1. 注释中的中文词汇
     * 2. 字符串常量中的大写词汇
     * 3. 方法名中的业务词汇
     */
    private fun extractBusinessKeywords(content: String): List<String> {
        val keywords = mutableListOf<String>()

        // 1. 提取注释中的中文词汇（2-10 个字）
        val chineseInComments = Regex("""//.*?([\u4e00-\u9fa5]{2,10})""")
        chineseInComments.findAll(content).forEach { match ->
            keywords.add(match.groupValues[1])
        }

        // 2. 提取方法名中的业务词汇（如 executeXmlRepayment → Repayment）
        val methodNames = Regex("""fun\s+(\w+)|def\s+(\w+)|\.\s*(\w+)\s*\(""")
        methodNames.findAll(content).forEach { match ->
            val methodName = match.groupValues.filter { it.isNotEmpty() }.firstOrNull() ?: return@forEach
            // 提取驼峰命名中的词汇
            val words = methodName.split(Regex("(?=[A-Z])")).filter { it.length > 3 }
            keywords.addAll(words.map { it.lowercase().replaceFirstChar { it.uppercase() } })
        }

        // 3. 提取字符串常量中的业务词汇
        val stringConstants = Regex(""""([A-Z_]{3,})"""")
        stringConstants.findAll(content).forEach { match ->
            // LOAN_REPAYMENT → Repayment
            val parts = match.groupValues[1].split("_")
            if (parts.isNotEmpty()) {
                keywords.add(parts.last().lowercase().replaceFirstChar { it.uppercase() })
            }
        }

        return keywords.distinct().take(10)
    }

    /**
     * 从 XML 中找引用的 Java 类
     */
    private fun findReferencedJavaClasses(xmlPath: String, content: String): List<ConfigLink> {
        val links = mutableListOf<ConfigLink>()

        // 检查是否是 transaction.xml 类型
        if (content.contains("<TransactionConfig") || content.contains("<Procedure")) {
            // 提取所有 Procedure class
            val procedureRegex = Regex("""class="([^"]+)"""")
            procedureRegex.findAll(content).forEach { match ->
                val className = match.groupValues[1]

                // 转换类名为文件路径
                val javaPath = classToPath(className)

                links.add(ConfigLink(
                    type = ConfigLinkType.XML_PROCEDURE,
                    targetPath = javaPath,
                    targetClass = className,
                    confidence = 0.85
                ))
            }
        }

        // 检查是否是 MyBatis Mapper XML
        if (content.contains("<mapper") && content.contains("namespace=")) {
            // 提取 namespace
            val namespaceRegex = Regex("""namespace="([^"]+)"""")
            val namespace = namespaceRegex.find(content)?.groupValues?.get(1)

            if (namespace != null) {
                val javaPath = classToPath(namespace)
                links.add(ConfigLink(
                    type = ConfigLinkType.MYBATIS_MAPPER,
                    targetPath = javaPath,
                    targetClass = namespace,
                    confidence = 0.95
                ))
            }
        }

        return links
    }

    /**
     * 类名转文件路径
     *
     * 例如: com.autoloop.loan.procedure.LoadLoanProcedure
     * → src/main/java/com/autoloop/loan/procedure/LoadLoanProcedure.java
     */
    private fun classToPath(className: String): String {
        // 需要找到正确的源码根目录
        val possibleRoots = listOf("src/main/java/", "src/test/java/", "")

        for (root in possibleRoots) {
            val path = root + className.replace(".", "/") + ".java"
            if (File(projectPath, path).exists()) {
                return path
            }
        }

        // 默认返回标准路径
        return "src/main/java/${className.replace(".", "/")}.java"
    }
}
