package com.smancode.smanagent.analysis.llm

import com.smancode.smanagent.analysis.model.VectorFragment
import com.smancode.smanagent.smancode.llm.LlmService
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
     * @param javaFile Java 文件路径
     * @param javaSource Java 源代码内容
     * @return Markdown 格式的文档内容
     * @throws IllegalArgumentException 如果文件路径或源代码为空
     */
    suspend fun analyzeJavaFile(javaFile: Path, javaSource: String): String {
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
        val userPrompt = buildJavaAnalysisUserPrompt(className, javaSource)

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
     * @param enumFile Enum 文件路径
     * @param enumSource Enum 源代码内容
     * @return Markdown 格式的文档内容
     * @throws IllegalArgumentException 如果文件路径或源代码为空
     */
    suspend fun analyzeEnumFile(enumFile: Path, enumSource: String): String {
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
        val userPrompt = buildEnumAnalysisUserPrompt(enumName, enumSource)

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
     * @param sourceFile 源文件路径（用于提取类名）
     * @param mdContent Markdown 文档内容
     * @return 向量片段列表
     * @throws IllegalArgumentException 如果 MD 内容为空
     */
    fun parseMarkdownToVectors(sourceFile: Path, mdContent: String): List<VectorFragment> {
        // 白名单校验：MD 内容不能为空
        if (mdContent.isBlank()) {
            throw IllegalArgumentException("MD 内容不能为空")
        }

        val vectors = mutableListOf<VectorFragment>()

        try {
            // 解析类信息
            parseClassVector(sourceFile, mdContent)?.let { vectors.add(it) }

            // 解析方法信息
            val methodVectors = parseMethodVectors(sourceFile, mdContent)
            vectors.addAll(methodVectors)

        } catch (e: Exception) {
            logger.warn("解析 MD 部分失败: file={}, error={}", sourceFile.fileName, e.message)
            // 优雅降级：返回已解析的向量
        }

        return vectors
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
            3. **Language Decoupling**:
               - Business descriptions MUST be in Simplified Chinese
               - Keep technical terms (class names, method names, annotations) in English
            4. **Source Code**: MUST preserve complete source code for each method
            5. **Separator**: Use "---" to separate class and method sections
            </anti_hallucination_rules>
        """.trimIndent()
    }

    /**
     * 构建 Java 分析的用户 Prompt
     */
    private fun buildJavaAnalysisUserPrompt(className: String, javaSource: String): String {
        return """
            ## 类信息
            类名: $className

            ## 源代码
            ```java
            $javaSource
            ```

            请严格按照系统 Prompt 中的模板格式生成 Markdown 文档。
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
            3. **Language Decoupling**:
               - Business descriptions MUST be in Simplified Chinese
               - Keep enum values and codes in their original format
            </anti_hallucination_rules>
        """.trimIndent()
    }

    /**
     * 构建 Enum 分析的用户 Prompt
     */
    private fun buildEnumAnalysisUserPrompt(enumName: String, enumSource: String): String {
        return """
            ## 枚举信息
            枚举名: $enumName

            ## 源代码
            ```java
            $enumSource
            ```

            请严格按照系统 Prompt 中的模板格式生成 Markdown 文档。
        """.trimIndent()
    }

    /**
     * 解析类向量
     */
    private fun parseClassVector(sourceFile: Path, mdContent: String): VectorFragment? {
        // 提取类名（第一个 # 标题）
        val titleMatch = Regex("""#\s+(\S+)""").find(mdContent)
        val className = titleMatch?.groupValues?.get(1) ?: return null

        // 提取业务描述
        val descMatch = Regex("""## 业务描述\s*\n\s*(.+?)(?=\n\s*##|\n\s*---|\Z)""", RegexOption.DOT_MATCHES_ALL)
            .find(mdContent)
        val businessDesc = descMatch?.groupValues?.get(1)?.trim() ?: ""

        // 提取类信息
        val classInfoMatch = Regex("""## 类信息\s*\n(.*?)(?=##\s*(?:业务描述|核心数据模型|包含功能)|---)""", RegexOption.DOT_MATCHES_ALL)
            .find(mdContent)
        val classInfo = classInfoMatch?.groupValues?.get(1)?.trim() ?: ""

        return VectorFragment(
            id = "class:${className}",
            title = className,
            content = businessDesc.ifEmpty { "Java 类" },
            fullContent = classInfo,
            tags = listOf("class", "java"),
            metadata = mapOf(
                "type" to "class",
                "className" to className,
                "sourceFile" to sourceFile.toString()
            ),
            vector = floatArrayOf() // 向量在后续生成
        )
    }

    /**
     * 解析方法向量列表
     */
    private fun parseMethodVectors(sourceFile: Path, mdContent: String): List<VectorFragment> {
        val vectors = mutableListOf<VectorFragment>()

        // 匹配所有方法块
        val methodPattern = Regex(
            """## 方法：(\S+)\s*\n(.*?)(?=##\s*(?:方法：|类信息)|---|\Z)""",
            RegexOption.DOT_MATCHES_ALL
        )

        methodPattern.findAll(mdContent).forEach { match ->
            val methodName = match.groupValues[1]
            val methodBlock = match.groupValues[2]

            // 提取业务描述
            val descMatch = Regex("""### 业务描述\s*\n\s*(.+?)(?=\n\s*###|\n\s*##|\Z)""", RegexOption.DOT_MATCHES_ALL)
                .find(methodBlock)
            val businessDesc = descMatch?.groupValues?.get(1)?.trim() ?: ""

            // 提取源码
            val sourceMatch = Regex("""### 源码\s*\n```java\s*(.+?)\n```""", RegexOption.DOT_MATCHES_ALL)
                .find(methodBlock)
            val sourceCode = sourceMatch?.groupValues?.get(1)?.trim() ?: ""

            vectors.add(VectorFragment(
                id = "method:${sourceFile.fileName.toString().removeSuffix(".java")}.$methodName",
                title = methodName,
                content = businessDesc.ifEmpty { "方法" },
                fullContent = sourceCode,
                tags = listOf("method", "java"),
                metadata = mapOf(
                    "type" to "method",
                    "methodName" to methodName,
                    "sourceFile" to sourceFile.toString()
                ),
                vector = floatArrayOf() // 向量在后续生成
            ))
        }

        return vectors
    }
}
