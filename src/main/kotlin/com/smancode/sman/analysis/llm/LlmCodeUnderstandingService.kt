package com.smancode.sman.analysis.llm

import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * LLM 驱动的代码理解服务
 *
 * 核心功能：
 * 1. 调用 LLM 分析 Java 文件，生成 .md 文档
 * 2. 调用 LLM 分析 Enum 文件，生成 .md 文档
 * 3. 从 .md 文档解析向量化数据
 *
 * 设计原则：
 * - 严格参数校验：不满足条件直接抛异常
 * - 语义理解交给 LLM：不使用硬编码
 * - 遵循 prompt_rules.md 规则
 */
class LlmCodeUnderstandingService(
    private val llmService: LlmService
) {

    private val logger = LoggerFactory.getLogger(LlmCodeUnderstandingService::class.java)

    /**
     * 分析 Java 文件并生成 .md 文档
     *
     * @param javaFile Java 文件路径（绝对路径）
     * @param javaSource Java 源代码内容
     * @param relativePath 相对路径（相对于项目根目录）
     * @return Markdown 格式的文档内容
     * @throws IllegalArgumentException 如果文件路径或源代码为空
     */
    suspend fun analyzeJavaFile(javaFile: Path, javaSource: String, relativePath: String): String {
        // 白名单校验：文件路径不能为空
        if (javaFile.toString().isEmpty()) {
            throw IllegalArgumentException("文件路径不能为空")
        }

        // 白名单校验：源代码不能为空
        if (javaSource.isBlank()) {
            throw IllegalArgumentException("源代码不能为空")
        }

        val className = javaFile.fileName.toString().removeSuffix(".java")

        // 构建符合 prompt_rules.md 规则的 Prompt
        val systemPrompt = buildJavaAnalysisSystemPrompt()
        val userPrompt = buildJavaAnalysisUserPrompt(className, javaSource, relativePath)

        // 调用 LLM 分析
        val llmResponse = try {
            llmService.simpleRequest(systemPrompt, userPrompt)
        } catch (e: Exception) {
            logger.error("LLM 调用失败: file={}, error={}", javaFile.fileName, e.message)
            throw RuntimeException("LLM 调用失败: ${e.message}", e)
        }

        // 验证返回格式
        if (!llmResponse.contains("# $className")) {
            throw RuntimeException("LLM 返回格式错误: 缺少标题 '# $className'")
        }

        return llmResponse
    }

    /**
     * 分析 Enum 文件并生成 .md 文档
     *
     * @param enumFile Enum 文件路径（绝对路径）
     * @param enumSource Enum 源代码内容
     * @param relativePath 相对路径（相对于项目根目录）
     * @return Markdown 格式的文档内容
     * @throws IllegalArgumentException 如果文件路径或源代码为空
     */
    suspend fun analyzeEnumFile(enumFile: Path, enumSource: String, relativePath: String): String {
        // 白名单校验：文件路径不能为空
        if (enumFile.toString().isEmpty()) {
            throw IllegalArgumentException("文件路径不能为空")
        }

        // 白名单校验：源代码不能为空
        if (enumSource.isBlank()) {
            throw IllegalArgumentException("源代码不能为空")
        }

        val enumName = enumFile.fileName.toString().removeSuffix(".java")

        // 构建符合 prompt_rules.md 规则的 Prompt
        val systemPrompt = buildEnumAnalysisSystemPrompt()
        val userPrompt = buildEnumAnalysisUserPrompt(enumName, enumSource, relativePath)

        // 调用 LLM 分析
        val llmResponse = try {
            llmService.simpleRequest(systemPrompt, userPrompt)
        } catch (e: Exception) {
            logger.error("LLM 调用失败: file={}, error={}", enumFile.fileName, e.message)
            throw RuntimeException("LLM 调用失败: ${e.message}", e)
        }

        // 验证返回格式
        if (!llmResponse.contains("# $enumName")) {
            throw RuntimeException("LLM 返回格式错误: 缺少标题 '# $enumName'")
        }

        return llmResponse
    }

    /**
     * 从 .md 文档解析向量化数据
     *
     * 核心逻辑：按 --- 分割 MD 内容，每个分割块作为独立的向量片段
     * - content = 整个分割块内容 + 相对路径（包含类名、注解、签名、方法名等完整语义信息）
     * - 没有 --- 分割时，整个 MD 内容作为一个向量片段
     *
     * @param sourceFile 源文件路径（绝对路径，用于提取类名）
     * @param mdContent Markdown 文档内容
     * @param relativePath 相对路径（核心！用于 LLM 定位文件）
     * @return 向量片段列表
     * @throws IllegalArgumentException 如果 MD 内容为空
     */
    fun parseMarkdownToVectors(sourceFile: Path, mdContent: String, relativePath: String): List<VectorFragment> {
        // 白名单校验：MD 内容不能为空
        if (mdContent.isBlank()) {
            throw IllegalArgumentException("MD 内容不能为空")
        }

        // 白名单校验：相对路径不能为空
        if (relativePath.isBlank()) {
            throw IllegalArgumentException("相对路径不能为空")
        }

        // 提取类名（从文件名或内容）
        val className = extractClassName(sourceFile, mdContent)

        // 判断是否为 Enum 类型
        val isEnum = mdContent.contains("## 枚举定义") || mdContent.contains("## 字典映射")

        // 按 --- 分割 MD 内容
        val fragments = if (mdContent.contains("---")) {
            // 有 --- 分割，按分割块处理
            mdContent.split("---")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } else {
            // 没有 --- 分割，整个内容作为一个片段
            listOf(mdContent.trim())
        }

        val vectors = mutableListOf<VectorFragment>()

        fragments.forEachIndexed { index, fragmentContent ->
            if (fragmentContent.isBlank()) return@forEachIndexed

            // 从片段中提取标题（## 方法：xxx 或 ## 类信息 等）
            val fragmentTitle = extractFragmentTitle(fragmentContent, className, index, fragments.size)

            // 确定片段类型
            val fragmentType = when {
                fragmentContent.contains("## 方法：") -> "method"
                isEnum -> "enum"
                else -> "class"
            }

            // 从片段中提取方法名（如果是方法片段）
            val methodName = if (fragmentType == "method") {
                extractMethodName(fragmentContent)
            } else {
                null
            }

            // 生成片段 ID
            val fragmentId = when (fragmentType) {
                "method" -> "method:$className.$methodName"
                "enum" -> "enum:$className"
                else -> if (fragments.size == 1) "class:$className" else "class:$className.part$index"
            }

            // 核心：在 content 中注入相对路径（确保每个片段都有相对路径信息）
            val contentWithRelativePath = buildContentWithRelativePath(fragmentContent, relativePath, fragmentType)

            vectors.add(VectorFragment(
                id = fragmentId,
                title = fragmentTitle,
                // 核心：content = 相对路径 + 分割块内容，保留完整语义
                content = contentWithRelativePath,
                fullContent = fragmentContent,
                tags = listOf(fragmentType, "java"),
                metadata = buildMap {
                    put("type", fragmentType)
                    put("className", className)
                    put("relativePath", relativePath)
                    methodName?.let { put("methodName", it) }
                    put("sourceFile", sourceFile.toString())
                },
                vector = floatArrayOf() // 向量在后续生成
            ))
        }

        logger.info("解析 MD 完成: file={}, class={}, fragments={}, relativePath={}",
            sourceFile.fileName, className, vectors.size, relativePath)
        return vectors
    }

    /**
     * 构建带相对路径的 content
     *
     * 在 content 开头注入相对路径信息，确保向量搜索结果中包含文件定位信息
     */
    private fun buildContentWithRelativePath(fragmentContent: String, relativePath: String, @Suppress("UNUSED_PARAMETER") fragmentType: String): String {
        return buildString {
            // 在开头添加相对路径（关键！用于 LLM 定位文件）
            appendLine("文件路径: $relativePath")
            appendLine()
            append(fragmentContent)
        }
    }

    /**
     * 从片段内容中提取标题
     */
    private fun extractFragmentTitle(fragmentContent: String, className: String, index: Int, total: Int): String {
        // 尝试提取方法名
        val methodMatch = Regex("""## 方法：(\S+)""").find(fragmentContent)
        if (methodMatch != null) {
            return methodMatch.groupValues[1]
        }

        // 如果是类信息片段，使用类名
        if (fragmentContent.contains("## 类信息") || fragmentContent.contains("## 枚举定义")) {
            return className
        }

        // 其他情况，使用类名 + 序号
        return if (total == 1) className else "$className-part$index"
    }

    /**
     * 从方法片段中提取方法名
     */
    private fun extractMethodName(fragmentContent: String): String? {
        val methodMatch = Regex("""## 方法：(\S+)""").find(fragmentContent)
        return methodMatch?.groupValues?.get(1)
    }

    /**
     * 从文件路径或 MD 内容中提取类名
     */
    private fun extractClassName(sourceFile: Path, mdContent: String): String {
        // 优先从 MD 内容的标题中提取
        val titleMatch = Regex("""#\s+(\S+)""").find(mdContent)
        if (titleMatch != null) {
            val className = titleMatch.groupValues[1]
            logger.info("从 MD 标题提取类名: className={}, sourceFile={}", className, sourceFile.fileName)
            return className
        }

        // 从文件名提取
        val fileName = sourceFile.fileName.toString()
        val className = when {
            fileName.endsWith(".md") -> fileName.removeSuffix(".md")
            fileName.endsWith(".java") -> fileName.removeSuffix(".java")
            else -> fileName
        }
        logger.warn("从文件名提取类名: className={}, sourceFile={}", className, sourceFile.fileName)
        return className
    }

    /**
     * 构建 Java 分析的系统 Prompt
     * 遵循 prompt_rules.md 规则：英文指令 + 中文输出模板
     */
    private fun buildJavaAnalysisSystemPrompt(): String {
        return """
            # Configuration
            <system_config>
                <language_rule>
                    <input_processing>English (For logic & reasoning)</input_processing>
                    <final_output>Simplified Chinese (For user readability)</final_output>
                </language_rule>
            </system_config>

            # Role
            You are a code analysis expert specialized in understanding Java business logic.

            # Task
            Analyze the provided Java class and generate a comprehensive Markdown documentation.

            # Analysis Requirements

            <thinking_process>
            1. **Analyze in English**:
               - Identify the class purpose and business domain
               - Extract core data models and their business meanings
               - Understand each method's functionality
               - Identify business relationships and dependencies

            2. **Generate Output in Chinese**:
               - Use professional business terminology
               - Keep technical terms in English (e.g., class names, method names)
               - Provide clear and concise descriptions
            </thinking_process>

            # Output Format Template (Strictly Follow)

            ```markdown
            # {ClassName}

            ## 类信息

            - **完整签名**: `{FullSignature}`
            - **相对路径**: `{RelativePath}`
            - **包名**: `{PackageName}`
            - **注解**: `{Annotations}`

            ## 业务描述

            {一句话描述这个类的业务功能（不超过50字）}

            ## 核心数据模型

            {列出重要的字段及其类型和业务含义，格式：- `fieldName` (Type): 业务含义}

            ## 包含功能

            {列出所有公共方法，每个方法用一句话描述功能，格式：- `methodName(params)`: 功能描述}

            ---

            ## 方法：{MethodName1}

            ### 签名
            `{MethodSignature}`

            ### 相对路径
            `{RelativePath}`

            ### 参数
            {列出参数，格式：- `paramName`: 参数说明}

            ### 返回值
            {ReturnType}

            ### 异常
            {列出可能抛出的异常}

            ### 业务描述
            {一句话描述方法功能（不超过50字）}

            ### 源码
            ```java
            {完整源代码}
            ```

            ---

            ## 方法：{MethodName2}
            ...
            ```

            # Anti-Hallucination Rules

            <anti_hallucination_rules>
            1. **Strict Grounding**: Only analyze code that exists in the input
            2. **No Invention**: Do not invent methods or fields not present in the source
            3. **Relative Path**: MUST include the relative path in class info and each method section
            4. **Language Decoupling**:
               - Business descriptions MUST be in Simplified Chinese
               - Keep technical terms (class names, method names, annotations) in English
            5. **Source Code**: MUST preserve complete source code for each method
            6. **Separator**: Use "---" to separate class and method sections
            </anti_hallucination_rules>
        """.trimIndent()
    }

    /**
     * 构建 Java 分析的用户 Prompt
     */
    private fun buildJavaAnalysisUserPrompt(className: String, javaSource: String, relativePath: String): String {
        return """
            ## 类信息
            类名: $className
            相对路径: $relativePath

            ## 源代码
            ```java
            $javaSource
            ```

            请严格按照系统 Prompt 中的模板格式生成 Markdown 文档。
            注意：相对路径必须原样保留在输出的类信息和方法信息中。
        """.trimIndent()
    }

    /**
     * 构建 Enum 分析的系统 Prompt
     */
    private fun buildEnumAnalysisSystemPrompt(): String {
        return """
            # Configuration
            <system_config>
                <language_rule>
                    <input_processing>English (For logic & reasoning)</input_processing>
                    <final_output>Simplified Chinese (For user readability)</final_output>
                </language_rule>
            </system_config>

            # Role
            You are a code analysis expert specialized in understanding Java enum business dictionaries.

            # Task
            Analyze the provided Java enum and generate a comprehensive Markdown documentation.

            # Output Format Template (Strictly Follow)

            ```markdown
            # {EnumName}

            ## 枚举定义
            - **完整签名**: `{FullSignature}`
            - **相对路径**: `{RelativePath}`
            - **包名**: `{PackageName}`

            ## 业务描述

            {一句话描述这个枚举的业务字典含义（不超过50字）}

            ## 字典映射

            | 枚举值 | 编码 | 业务描述 |
            |--------|------|---------|
            | {ENUM_VALUE_1} | {code1} | {业务含义1} |
            | {ENUM_VALUE_2} | {code2} | {业务含义2} |
            ...
            ```

            # Anti-Hallucination Rules

            <anti_hallucination_rules>
            1. **Strict Grounding**: Only analyze enum values that exist in the input
            2. **No Invention**: Do not invent enum values not present in the source
            3. **Relative Path**: MUST include the relative path in enum definition section
            4. **Language Decoupling**:
               - Business descriptions MUST be in Simplified Chinese
               - Keep enum values and codes in their original format
            </anti_hallucination_rules>
        """.trimIndent()
    }

    /**
     * 构建 Enum 分析的用户 Prompt
     */
    private fun buildEnumAnalysisUserPrompt(enumName: String, enumSource: String, relativePath: String): String {
        return """
            ## 枚举信息
            枚举名: $enumName
            相对路径: $relativePath

            ## 源代码
            ```java
            $enumSource
            ```

            请严格按照系统 Prompt 中的模板格式生成 Markdown 文档。
            注意：相对路径必须原样保留在输出的枚举定义中。
        """.trimIndent()
    }
}
