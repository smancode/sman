package com.smancode.smanagent.ide.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.smancode.smanagent.model.part.Part
import com.smancode.smanagent.model.part.TextPart
import java.io.File
import java.util.function.Consumer

/**
 * 本地工具执行器
 *
 * 接收 Agent 的工具调用请求，在本地执行并返回结果
 *
 * 支持的工具：
 * - find_file: 按文件名查找文件
 * - read_file: 读取文件内容
 * - grep_file: 文件内容搜索
 * - call_chain: 调用链分析
 * - extract_xml: 提取 XML 内容
 * - apply_change: 应用代码修改
 * - run_shell_command: 执行 Shell 命令（支持流式输出）
 */
class LocalToolExecutor(private val project: Project) {

    private val logger = Logger.getInstance(LocalToolExecutor::class.java)

    companion object {
        /**
         * 源码文件扩展名（按优先级排序）
         */
        private val SOURCE_FILE_EXTENSIONS = listOf(
            "java", "xml", "yml", "yaml", "html", "vue",
            "kt", "kts", "js", "ts", "jsx", "tsx",
            "py", "go", "rs", "c", "cpp", "h", "hpp",
            "md", "json", "properties",
            "gradle"  // 添加构建文件扩展名
        )

        /**
         * 不需要过滤扩展名的文件名（精确匹配）
         * 这些文件名可以直接匹配，不需要检查扩展名
         */
        private val EXACT_MATCH_FILENAMES = setOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradlew",
            "gradlew.bat",
            "pom.xml",
            "package.json",
            "tsconfig.json",
            "webpack.config.js"
        )

        /**
         * grep_file 最大返回结果数（防止 token 爆炸）
         */
        private const val MAX_GREP_RESULTS = 100

        /**
         * 流式输出默认会话 ID
         */
        private const val DEFAULT_SESSION_ID = "current"

        /**
         * 通知符号
         */
        private const val ICON_EXECUTING = "\uD83D\uDD27"  // 🔧
        private const val ICON_SUCCESS = "\u2705"           // ✅
        private const val ICON_ERROR = "\u274C"             // ❌

        /**
         * 每个文件最多显示的匹配数
         */
        private const val MAX_MATCHES_PER_FILE = 10
    }
    
    data class ToolResult(
        val success: Boolean,
        val result: Any,
        val executionTime: Long = 0,
        val relativePath: String? = null,  // 新增：相对路径
        val relatedFilePaths: List<String>? = null,  // 新增：相关文件列表
        val metadata: Map<String, Any>? = null  // 新增：元数据
    )
    
    fun execute(toolName: String, parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val startTime = System.currentTimeMillis()

        logger.info("执行本地工具: $toolName, params=$parameters, projectPath=$projectPath")
        logger.info("参数详细信息: ${parameters.entries.joinToString { "${it.key}=${it.value}" }}")

        return try {
            val result = when (toolName) {
                "find_file" -> executeFindFile(parameters, projectPath)
                "read_file" -> executeReadFile(parameters, projectPath)
                "grep_file" -> executeGrepFile(parameters, projectPath)
                "call_chain" -> executeCallChain(parameters)
                "extract_xml" -> executeExtractXml(parameters, projectPath)
                "apply_change" -> executeApplyChange(parameters, projectPath)
                "run_shell_command" -> executeShellCommand(parameters, projectPath, null)
                else -> ToolResult(false, "不支持的工具: $toolName")
            }

            val elapsed = System.currentTimeMillis() - startTime
            // 保留所有字段，只更新 executionTime
            ToolResult(
                success = result.success,
                result = result.result,
                executionTime = elapsed,
                relativePath = result.relativePath,
                relatedFilePaths = result.relatedFilePaths,
                metadata = result.metadata
            )

        } catch (e: Exception) {
            logger.error("工具执行失败: $toolName", e)
            val elapsed = System.currentTimeMillis() - startTime
            ToolResult(
                success = false,
                result = "工具执行异常: ${e.message}",
                executionTime = elapsed,
                relativePath = null,
                relatedFilePaths = null,
                metadata = null
            )
        }
    }

    /**
     * 流式执行工具（用于支持实时输出的工具）
     */
    fun executeStreaming(
        toolName: String,
        parameters: Map<String, Any?>,
        projectPath: String?,
        partPusher: Consumer<Part>
    ): ToolResult {
        val startTime = System.currentTimeMillis()

        logger.info("流式执行本地工具: $toolName, params=$parameters, projectPath=$projectPath")

        return try {
            val result = when (toolName) {
                "run_shell_command" -> executeShellCommand(parameters, projectPath, partPusher)
                else -> {
                    // 不支持流式输出的工具，使用普通执行
                    logger.warn("工具 $toolName 不支持流式输出，使用普通执行")
                    execute(toolName, parameters, projectPath)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            ToolResult(
                success = result.success,
                result = result.result,
                executionTime = elapsed,
                relativePath = result.relativePath,
                relatedFilePaths = result.relatedFilePaths,
                metadata = result.metadata
            )

        } catch (e: Exception) {
            logger.error("流式工具执行失败: $toolName", e)
            val elapsed = System.currentTimeMillis() - startTime
            ToolResult(
                success = false,
                result = "工具执行异常: ${e.message}",
                executionTime = elapsed,
                relativePath = null,
                relatedFilePaths = null,
                metadata = null
            )
        }
    }

    /**
     * 将绝对路径转换为相对路径
     * 使用 PathUtil 进行路径归一化，确保跨平台兼容性
     */
    private fun toRelativePath(absolutePath: String, basePath: String): String {
        return PathUtil.toRelativePath(absolutePath, basePath)
    }

    /**
     * 查找文件
     */
    private fun executeFindFile(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        // 兼容两种参数名：pattern 和 filePattern
        val pattern = parameters["pattern"]?.toString()
            ?: parameters["filePattern"]?.toString()
            ?: run {
                logger.error("缺少 pattern/filePattern 参数，可用参数: ${parameters.keys.joinToString()}")
                return ToolResult(false, "缺少 pattern/filePattern 参数")
            }

        logger.info("使用 pattern: $pattern")

        val basePath = projectPath ?: project.basePath ?: ""
        val baseDir = File(basePath)

        if (!baseDir.exists()) {
            return ToolResult(false, "项目目录不存在: $basePath")
        }

        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return ToolResult(false, "无效的正则表达式: ${e.message}")
        }

        val matches = mutableListOf<Map<String, String>>()

        fun findFiles(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    findFiles(file)
                } else {
                    // 检查是否匹配正则表达式
                    if (!regex.matches(file.name)) {
                        return@forEach
                    }

                    // 如果是精确匹配的文件名（如 build.gradle），直接匹配
                    // 否则检查扩展名是否在允许列表中
                    val isExactMatch = EXACT_MATCH_FILENAMES.contains(file.name)
                    val hasValidExtension = SOURCE_FILE_EXTENSIONS.any { file.name.endsWith(".$it") }

                    if (isExactMatch || hasValidExtension) {
                        // 使用 PathUtil 确保跨平台路径兼容性
                        val relativePath = toRelativePath(file.absolutePath, basePath)
                        matches.add(mapOf(
                            "path" to relativePath,
                            "name" to file.name
                        ))
                    }
                }
            }
        }

        findFiles(baseDir)

        val filePaths = matches.map { it["path"]!! }  // 提取所有文件路径

        val sb = StringBuilder()
        if (matches.size > 3) {
            sb.append("找到 ${matches.size} 个文件（显示前 3 个）:\n\n")
            matches.take(3).forEach { match ->
                sb.append("${match["path"]}\n")
            }
            sb.append("... 还有 ${matches.size - 3} 个文件未显示\n")
        } else {
            sb.append("找到 ${matches.size} 个文件:\n\n")
            matches.forEach { match ->
                sb.append("${match["path"]}\n")
            }
        }

        return ToolResult(
            success = true,
            result = sb.toString(),
            relatedFilePaths = filePaths  // 新增：存储所有匹配的文件路径
        )
    }
    
    /**
     * 读取文件
     */
    private fun executeReadFile(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        // 检查 relativePath 参数
        val relativePath = parameters["relativePath"]?.toString()
            ?: parameters["path"]?.toString()

        // 获取行号参数
        val startLine = (parameters["startLine"] as? Number)?.toInt() ?: 1
        val endLine = (parameters["endLine"] as? Number)?.toInt() ?: 300

        // 如果没有 relativePath，检查是否有 simpleName
        val actualPath = if (relativePath == null) {
            val simpleName = parameters["simpleName"]?.toString()
            if (simpleName == null) {
                return ToolResult(false, "缺少 relativePath 或 simpleName 参数")
            }

            logger.info("使用 simpleName 搜索文件: $simpleName")

            // 按优先级尝试的扩展名列表
            val extensions = SOURCE_FILE_EXTENSIONS

            val basePath = projectPath ?: project.basePath ?: ""

            // 如果 simpleName 已包含扩展名，直接查找
            val fileNameToFind = if (simpleName.contains(".")) {
                val file = File(basePath, simpleName)
                if (!file.exists()) {
                    return ToolResult(false, "未找到文件: $simpleName")
                }
                simpleName
            } else {
                // 没有扩展名，按优先级依次尝试递归查找
                var foundFile: File? = null
                for (ext in extensions) {
                    val fileName = "$simpleName.$ext"
                    // 在 basePath 下递归查找文件
                    val file = findFileRecursively(basePath, fileName)
                    if (file != null) {
                        logger.info("找到文件: ${file.absolutePath}")
                        foundFile = file
                        break
                    }
                }

                if (foundFile != null) {
                    foundFile.absolutePath.removePrefix(basePath).removePrefix("/")
                } else {
                    return ToolResult(false, "未找到文件: $simpleName（已尝试扩展名: ${extensions.joinToString(", ", transform = { ".$it" })}）")
                }
            }

            fileNameToFind
        } else {
            relativePath
        }

        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(actualPath).isAbsolute) File(actualPath) else File(basePath, actualPath)

        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }

        return ReadAction.compute<ToolResult, Exception> {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                ?: return@compute ToolResult(false, "无法找到文件: ${file.absolutePath}")

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@compute ToolResult(false, "无法读取文件: ${file.absolutePath}")

            val content = psiFile.text
            val allLines = content.lines()
            val totalLines = allLines.size

            // 如果用户没有指定行号，使用默认值
            val actualStartLine = if (relativePath == null && startLine == 1 && endLine == 300) 1 else startLine
            val actualEndLine = if (relativePath == null && startLine == 1 && endLine == 300) 300 else endLine

            // 转换为 0-based 索引
            val startIndex = (actualStartLine - 1).coerceAtLeast(0)
            val endIndex = actualEndLine.coerceAtMost(totalLines)

            val selectedLines = if (startIndex >= totalLines) {
                listOf("// 文件只有 $totalLines 行，请求的起始行 $actualStartLine 超出范围")
            } else {
                allLines.toList().subList(startIndex, endIndex)
            }

            val sb = StringBuilder()
            sb.append(selectedLines.joinToString("\n"))

            // 如果还有更多内容，提示用户
            if (endIndex < totalLines) {
                val remainingLines = totalLines - endIndex
                sb.append("\n\n... (文件共 $totalLines 行，当前显示第 ${actualStartLine}-$endIndex 行，还有 $remainingLines 行未显示)")
                sb.append("\n提示：可以使用 startLine=${endIndex + 1}, endLine=${Math.min(endIndex + 100, totalLines)} 继续读取")
            } else if (startIndex > 0 || endIndex < totalLines) {
                sb.append("\n\n(文件共 $totalLines 行，当前显示第 ${actualStartLine}-$endIndex 行)")
            }

            // 计算相对路径
            val calculatedRelativePath = toRelativePath(file.absolutePath, basePath)

            ToolResult(
                success = true,
                result = sb.toString(),
                relativePath = calculatedRelativePath,  // 存储相对路径
                metadata = mapOf(  // 新增：存储元数据
                    "absolutePath" to file.absolutePath,
                    "totalLines" to totalLines,
                    "startLine" to actualStartLine,
                    "endLine" to endIndex,
                    "isComplete" to (endIndex == totalLines)
                )
            )
        }
    }
    
    /**
     * 搜索文件内容
     * 支持两种模式：
     * 1. filePattern: 在多个文件中搜索（文件名匹配正则）
     * 2. relativePath: 在单个文件中搜索
     */
    private fun executeGrepFile(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val pattern = parameters["pattern"]?.toString()
            ?: return ToolResult(false, "缺少 pattern 参数")

        val filePattern = parameters["filePattern"]?.toString()
        val relativePath = parameters["relativePath"]?.toString()

        val basePath = projectPath ?: project.basePath ?: ""

        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            return ToolResult(false, "无效的正则表达式: ${e.message}")
        }

        // 模式1：filePattern 搜索多个文件
        if (filePattern != null && relativePath == null) {
            return executeGrepMultiFiles(filePattern, pattern, regex, basePath)
        }

        // 模式2：relativePath 搜索单个文件
        val actualPath = relativePath ?: "."
        val file = if (File(actualPath).isAbsolute) File(actualPath) else File(basePath, actualPath)

        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }

        // 如果是目录，则在目录下所有源码文件中搜索
        if (file.isDirectory) {
            return executeGrepInDirectory(file, regex, basePath)
        }

        // 单个文件搜索
        return executeGrepInSingleFile(file, regex, basePath)
    }

    /**
     * 在多个文件中搜索（通过 filePattern 匹配文件名）
     */
    private fun executeGrepMultiFiles(filePattern: String, pattern: String, regex: Regex, basePath: String): ToolResult {
        val fileRegex = try {
            Regex(filePattern)
        } catch (e: Exception) {
            return ToolResult(false, "无效的文件名正则表达式: ${e.message}")
        }

        val baseDir = File(basePath)
        if (!baseDir.exists()) {
            return ToolResult(false, "项目目录不存在: $basePath")
        }

        // 按文件分组存储匹配结果
        val matchesByFile = mutableMapOf<String, List<Map<String, Any>>>()
        val matchedFilePaths = mutableListOf<String>()

        fun searchFiles(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (!shouldSkipDirectory(file.name)) {
                        searchFiles(file)
                    }
                } else if (fileRegex.matches(file.name) && SOURCE_FILE_EXTENSIONS.any { file.name.endsWith(".$it") }) {
                    // 在这个文件中搜索 pattern
                    val fileMatches = searchInFile(file, regex, basePath)
                    if (fileMatches.isNotEmpty()) {
                        val relativePath = toRelativePath(file.absolutePath, basePath)
                        // 每个文件最多保存 MAX_MATCHES_PER_FILE 条
                        matchesByFile[relativePath] = fileMatches.take(MAX_MATCHES_PER_FILE)
                        matchedFilePaths.add(relativePath)
                    }
                }
            }
        }

        searchFiles(baseDir)

        // 计算总匹配数
        val totalMatches = matchesByFile.values.sumOf { it.size }

        val sb = StringBuilder()
        if (matchesByFile.isEmpty()) {
            sb.append("未找到匹配内容\n")
            sb.append("搜索条件: 文件名匹配 `$filePattern`, 内容匹配 `$pattern`\n")
        } else {
            sb.append("在 ${matchedFilePaths.size} 个文件中找到 $totalMatches 处匹配")
            if (totalMatches >= MAX_GREP_RESULTS) {
                sb.append("（已限制显示前 $totalMatches 条，实际可能更多）")
            }
            sb.append(":\n\n")

            // 按文件分组显示，最多显示 MAX_GREP_RESULTS 条
            var displayedCount = 0
            for ((filePath, matches) in matchesByFile) {
                if (displayedCount >= MAX_GREP_RESULTS) break

                sb.append("📄 $filePath (${matches.size} 处匹配):\n")
                for (match in matches) {
                    if (displayedCount >= MAX_GREP_RESULTS) break
                    val lineNumber = match["lineNumber"] as Int
                    val line = match["line"] as String
                    sb.append("  :$lineNumber: $line\n")
                    displayedCount++
                }
                sb.append("\n")
            }

            if (totalMatches > MAX_GREP_RESULTS) {
                sb.append("... 还有 ${totalMatches - MAX_GREP_RESULTS} 条结果未显示（超出限制）\n")
                sb.append("提示：请缩小搜索范围或使用更精确的正则表达式\n")
            }
        }

        return ToolResult(
            success = true,
            result = sb.toString(),
            relatedFilePaths = matchedFilePaths
        )
    }

    /**
     * 在目录中搜索所有源码文件
     */
    private fun executeGrepInDirectory(dir: File, regex: Regex, basePath: String): ToolResult {
        // 按文件分组存储匹配结果
        val matchesByFile = mutableMapOf<String, List<Map<String, Any>>>()
        val matchedFilePaths = mutableListOf<String>()

        fun searchFiles(directory: File) {
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (!shouldSkipDirectory(file.name)) {
                        searchFiles(file)
                    }
                } else if (SOURCE_FILE_EXTENSIONS.any { file.name.endsWith(".$it") }) {
                    val fileMatches = searchInFile(file, regex, basePath)
                    if (fileMatches.isNotEmpty()) {
                        val relativePath = toRelativePath(file.absolutePath, basePath)
                        // 每个文件最多保存 MAX_MATCHES_PER_FILE 条
                        matchesByFile[relativePath] = fileMatches.take(MAX_MATCHES_PER_FILE)
                        matchedFilePaths.add(relativePath)
                    }
                }
            }
        }

        searchFiles(dir)

        // 计算总匹配数
        val totalMatches = matchesByFile.values.sumOf { it.size }

        val sb = StringBuilder()
        if (matchesByFile.isEmpty()) {
            sb.append("未找到匹配内容\n")
        } else {
            sb.append("在 ${matchedFilePaths.size} 个文件中找到 $totalMatches 处匹配")
            if (totalMatches >= MAX_GREP_RESULTS) {
                sb.append("（已限制显示前 $totalMatches 条，实际可能更多）")
            }
            sb.append(":\n\n")

            // 按文件分组显示，最多显示 MAX_GREP_RESULTS 条
            var displayedCount = 0
            for ((filePath, matches) in matchesByFile) {
                if (displayedCount >= MAX_GREP_RESULTS) break

                sb.append("📄 $filePath (${matches.size} 处匹配):\n")
                for (match in matches) {
                    if (displayedCount >= MAX_GREP_RESULTS) break
                    val lineNumber = match["lineNumber"] as Int
                    val line = match["line"] as String
                    sb.append("  :$lineNumber: $line\n")
                    displayedCount++
                }
                sb.append("\n")
            }

            if (totalMatches > MAX_GREP_RESULTS) {
                sb.append("... 还有 ${totalMatches - MAX_GREP_RESULTS} 条结果未显示（超出限制）\n")
                sb.append("提示：请缩小搜索范围或使用更精确的正则表达式\n")
            }
        }

        return ToolResult(
            success = true,
            result = sb.toString(),
            relatedFilePaths = matchedFilePaths
        )
    }

    /**
     * 在单个文件中搜索
     */
    private fun executeGrepInSingleFile(file: File, regex: Regex, basePath: String): ToolResult {
        val matches = searchInFile(file, regex, basePath)

        val sb = StringBuilder()
        val relativePath = toRelativePath(file.absolutePath, basePath)

        if (matches.isEmpty()) {
            sb.append("未找到匹配内容\n")
        } else {
            // 限制显示数量
            val displayMatches = matches.take(MAX_GREP_RESULTS)
            sb.append("在 `$relativePath` 中找到 ${matches.size} 处匹配")
            if (matches.size > MAX_GREP_RESULTS) {
                sb.append("（已限制显示前 $MAX_GREP_RESULTS 条）")
            }
            sb.append(":\n\n")

            displayMatches.forEach { match ->
                val lineNumber = match["lineNumber"] as Int
                val line = match["line"] as String
                sb.append(":$lineNumber: $line\n")
            }

            if (matches.size > MAX_GREP_RESULTS) {
                sb.append("\n... 还有 ${matches.size - MAX_GREP_RESULTS} 条结果未显示（超出限制）\n")
            }
        }

        return ToolResult(
            success = true,
            result = sb.toString(),
            relatedFilePaths = listOf(relativePath)
        )
    }

    /**
     * 在文件中搜索正则匹配的行
     */
    private fun searchInFile(file: File, regex: Regex, basePath: String): List<Map<String, Any>> {
        val matches = mutableListOf<Map<String, Any>>()
        try {
            val content = file.readText()
            val lines = content.lines()
            val relativePath = toRelativePath(file.absolutePath, basePath)

            lines.forEachIndexed { index, line ->
                if (regex.containsMatchIn(line)) {
                    matches.add(mapOf(
                        "filePath" to relativePath,
                        "lineNumber" to (index + 1),
                        "line" to line
                    ))
                }
            }
        } catch (e: Exception) {
            logger.warn("无法读取文件: ${file.absolutePath}, ${e.message}")
        }
        return matches
    }
    
    /**
     * 调用链分析工具
     */
    private fun executeCallChain(parameters: Map<String, Any?>): ToolResult {
        val method = parameters["method"]?.toString()
            ?: return ToolResult(false, "缺少 method 参数（格式：ClassName.methodName）")

        val direction = parameters["direction"]?.toString() ?: "both"
        val depth = (parameters["depth"] as? Number)?.toInt() ?: 1
        val includeSource = (parameters["includeSource"] as? Boolean)
            ?: (parameters["include_source"] as? Boolean)
            ?: false
        
        if (!method.contains(".")) {
            return ToolResult(false, "方法签名格式错误，应为: ClassName.methodName")
        }
        
        val lastDot = method.lastIndexOf(".")
        val className = method.substring(0, lastDot)
        val methodName = method.substring(lastDot + 1).substringBefore("(")
        
        logger.info("分析调用链: class=$className, method=$methodName, direction=$direction, depth=$depth")
        
        return ReadAction.compute<ToolResult, Exception> {
            val psiClass = findClass(className)
            if (psiClass == null) {
                return@compute ToolResult(false, "未找到类: $className")
            }

            val methods = psiClass.findMethodsByName(methodName, false)
            if (methods.isEmpty()) {
                return@compute ToolResult(false, "在类 $className 中未找到方法: $methodName")
            }

            val targetMethod = methods[0]
            val basePath = project.basePath ?: ""
            val sb = StringBuilder()
            sb.append("## 调用链分析: $method\n\n")
            sb.append("**分析方向**: $direction\n")
            sb.append("**分析深度**: $depth\n\n")

            // 分析调用者
            if (direction == "callers" || direction == "both") {
                sb.append("### 🔼 调用者（谁调用了这个方法）\n\n")
                val callers = ReferencesSearch.search(targetMethod).findAll()
                if (callers.isEmpty()) {
                    sb.append("未找到调用者\n\n")
                } else {
                    callers.take(20).forEach { ref ->
                        val element = ref.element
                        val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                        val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

                        if (containingMethod != null && containingClass != null) {
                            val absolutePath = containingClass.containingFile?.virtualFile?.path ?: ""
                            val relativePath = toRelativePath(absolutePath, basePath)
                            sb.append("- `${containingClass.name}.${containingMethod.name}()`")
                            sb.append(" → `$relativePath`\n")

                            if (includeSource) {
                                sb.append("  ```java\n")
                                sb.append("  ${containingMethod.text.take(500)}")
                                if (containingMethod.text.length > 500) sb.append("...")
                                sb.append("\n  ```\n")
                            }
                        }
                    }
                    sb.append("\n")
                }
            }

            // 分析被调用者
            if (direction == "callees" || direction == "both") {
                sb.append("### 🔽 被调用者（这个方法调用了谁）\n\n")
                val callees = mutableListOf<String>()

                targetMethod.body?.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        super.visitMethodCallExpression(expression)
                        val calledMethod = expression.resolveMethod()
                        if (calledMethod != null) {
                            val calledClass = calledMethod.containingClass
                            if (calledClass != null) {
                                callees.add("${calledClass.name}.${calledMethod.name}()")
                            }
                        }
                    }
                })

                if (callees.isEmpty()) {
                    sb.append("未找到被调用的方法\n\n")
                } else {
                    callees.distinct().sorted().forEach { callee ->
                        sb.append("- $callee\n")
                    }
                }
            }

            ToolResult(true, sb.toString())
        }
    }
    
    /**
     * 应用代码修改
     * 支持两种模式：
     * 1. replace: 替换现有内容（需要 searchContent）
     * 2. create: 创建新文件
     */
    private fun executeApplyChange(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val relativePath = parameters["relativePath"]?.toString()
            ?: return ToolResult(false, "缺少 relativePath 参数")

        val mode = parameters["mode"]?.toString()?.lowercase() ?: "replace"
        val newContent = parameters["newContent"]?.toString()
            ?: return ToolResult(false, "缺少 newContent 参数")

        val description = parameters["description"]?.toString() ?: "代码修改"

        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)

        // 创建新文件模式
        if (mode == "create") {
            if (file.exists()) {
                return ToolResult(false, "文件已存在: ${file.absolutePath}，请使用 replace 模式")
            }

            return com.intellij.openapi.application.WriteAction.compute<ToolResult, Exception> {
                // 确保父目录存在
                file.parentFile?.mkdirs()

                // 创建文件并写入内容
                file.writeText(newContent)

                // 刷新文件系统
                LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)

                val sb = StringBuilder()
                sb.append("## ✅ 文件创建成功\n\n")
                sb.append("**文件**: `$relativePath`\n")
                sb.append("**描述**: $description\n\n")
                sb.append("新文件已创建并保存。\n")

                ToolResult(true, sb.toString())
            }
        }

        // 修改现有文件模式
        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}，如需创建新文件请使用 mode: \"create\"")
        }

        val searchContent = parameters["searchContent"]?.toString()
            ?: return ToolResult(false, "replace 模式缺少 searchContent 参数")

        // 使用 CodeEditService 进行模糊匹配和自动格式化
        val codeEditService = CodeEditService(project)

        return when (val result = codeEditService.applyChange(relativePath, searchContent, newContent, basePath)) {
            is CodeEditService.EditResult.Success -> {
                // 构建改动摘要（放在 metadata 中供后端生成 commit message 使用）
                val changeSummary = StringBuilder()
                if (description.isNotEmpty()) {
                    changeSummary.append("**改动**: $description\n\n")
                }
                // 添加改动摘要（搜索内容的前几行）
                val searchLines = searchContent.lines().take(3)
                if (searchLines.isNotEmpty()) {
                    changeSummary.append("**修改位置**:\n")
                    searchLines.forEach { line ->
                        changeSummary.append("  $line\n")
                    }
                    if (searchContent.lines().size > 3) {
                        changeSummary.append("  ...\n")
                    }
                }

                ToolResult(
                    success = true,
                    result = "✅ 执行成功",
                    relativePath = relativePath,
                    metadata = mapOf(
                        "changeSummary" to changeSummary.toString(),
                        "description" to description,
                        "searchContent" to searchContent
                    )
                )
            }
            is CodeEditService.EditResult.Failure -> {
                ToolResult(false, "❌ ${result.error}")
            }
        }
    }
    
    /**
     * 提取 XML 内容
     */
    private fun executeExtractXml(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val relativePath = parameters["relativePath"]?.toString()
            ?: return ToolResult(false, "缺少 relativePath 参数")

        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)

        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }

        // 兼容两种参数名：tagPattern 和 tagName
        val tagPattern = parameters["tagPattern"]?.toString()
            ?: parameters["tagName"]?.toString()
            ?: return ToolResult(false, "缺少 tagPattern/tagName 参数")

        val content = file.readText()

        val sb = StringBuilder()

        // 如果 tagPattern 包含特殊字符（如 .、*、"），使用正则匹配
        if (tagPattern.contains(Regex("[.*\"=]"))) {
            // 复杂模式，使用正则表达式
            try {
                val regex = Regex("<$tagPattern[^>]*>(.*?)</$tagPattern>", RegexOption.DOT_MATCHES_ALL)
                val matches = regex.findAll(content).toList()

                sb.append("找到 ${matches.size} 个匹配 `<$tagPattern>` 的标签:\n\n")
                matches.forEach { match ->
                    sb.append(match.groupValues[1].trim() + "\n\n")
                }
            } catch (e: Exception) {
                return ToolResult(false, "正则表达式错误: ${e.message}")
            }
        } else {
            // 简单标签名，直接匹配
            val regex = Regex("<$tagPattern[^>]*>(.*?)</$tagPattern>", RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(content).toList()

            sb.append("找到 ${matches.size} 个 <$tagPattern> 标签:\n\n")
            matches.forEach { match ->
                sb.append(match.groupValues[1].trim() + "\n\n")
            }
        }

        return ToolResult(true, sb.toString())
    }

    /**
     * 递归查找文件
     * 跳过 bin、build、test 等目录，其他目录全部递归搜索
     */
    private fun findFileRecursively(basePath: String, fileName: String): File? {
        val baseDir = File(basePath)
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return null
        }

        fun search(dir: File): File? {
            if (!dir.isDirectory) {
                return if (dir.name == fileName) dir else null
            }

            // 跳过特定目录
            if (shouldSkipDirectory(dir.name)) {
                return null
            }

            dir.listFiles()?.forEach { file ->
                val found = search(file)
                if (found != null) return found
            }

            return null
        }

        return search(baseDir)
    }

    /**
     * 判断目录是否应该跳过
     */
    private fun shouldSkipDirectory(dirName: String): Boolean {
        val skipDirs = setOf(
            // 构建产物目录
            "build", "out", "target", "classes", "generated",
            // IDE 和工具目录
            ".idea", ".vscode", ".eclipse", "node_modules", ".gradle",
            // 版本控制
            ".git", ".svn",
            // 临时和缓存目录
            "tmp", "temp", "cache", ".cache",
            // 二进制目录
            "bin", "obj"
        )
        return dirName in skipDirs || dirName.startsWith(".")
    }

    /**
     * 通过类名查找 PsiClass
     */
    private fun findClass(className: String): PsiClass? {
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        
        var psiClass = javaPsiFacade.findClass(className, scope)
        if (psiClass != null) return psiClass
        
        val classes = javaPsiFacade.findClasses(className, scope)
        if (classes.isNotEmpty()) return classes[0]
        
        val shortName = className.substringAfterLast(".")
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
            psiClass = findClassInDirectory(root, shortName)
            if (psiClass != null) return psiClass
        }
        
        return null
    }
    
    private fun findClassInDirectory(dir: VirtualFile, className: String): PsiClass? {
        if (!dir.isDirectory) return null
        
        for (child in dir.children) {
            if (child.isDirectory) {
                val found = findClassInDirectory(child, className)
                if (found != null) return found
            } else if (child.name == "$className.java") {
                val psiFile = PsiManager.getInstance(project).findFile(child)
                if (psiFile is PsiJavaFile) {
                    return psiFile.classes.firstOrNull { it.name == className }
                }
            }
        }
        return null
    }

    /**
     * 执行 Shell 命令（支持流式输出）
     *
     * @param parameters 参数映射
     * @param projectPath 项目路径
     * @param partPusher Part 推送器（用于实时输出，null 表示不推送）
     */
    private fun executeShellCommand(
        parameters: Map<String, Any?>,
        projectPath: String?,
        partPusher: Consumer<Part>?
    ): ToolResult {
        val command = parameters["command"]?.toString()
            ?: return ToolResult(false, "缺少 command 参数")

        val basePath = projectPath ?: project.basePath ?: ""
        val workingDir = File(basePath)

        if (!workingDir.exists()) {
            return ToolResult(false, "工作目录不存在: $basePath")
        }

        logger.info("执行 Shell 命令: command='$command', dir='$basePath'")

        // 推送开始通知
        partPusher?.accept(createNotificationPart("$ICON_EXECUTING 执行命令: `$command`"))

        return try {
            // 检测操作系统并选择合适的 Shell
            val (shell, shellArgs) = detectShell()
            val fullCommand = buildList {
                add(shell)
                addAll(shellArgs)
                add(command)
            }

            logger.info("使用 Shell: $shell ${shellArgs.joinToString(" ")}")

            val process = ProcessBuilder(fullCommand)
                .directory(workingDir)
                .redirectErrorStream(true)  // 合并 stdout 和 stderr
                .start()

            val output = StringBuilder()

            // 流式读取输出
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")

                    // 实时推送每一行
                    if (partPusher != null) {
                        partPusher.accept(TextPart().apply {
                            text = "  $line"
                            sessionId = DEFAULT_SESSION_ID
                        })
                    }
                }
            }

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                partPusher?.accept(createNotificationPart("$ICON_SUCCESS 命令执行成功"))
                ToolResult(true, output.toString())
            } else {
                val errorMsg = "命令执行失败 (退出码: $exitCode)\n$output"
                partPusher?.accept(createNotificationPart("$ICON_ERROR $errorMsg"))
                ToolResult(false, errorMsg)
            }

        } catch (e: Exception) {
            logger.error("Shell 命令执行失败", e)
            val errorMsg = "命令执行异常: ${e.message}"
            partPusher?.accept(createNotificationPart("$ICON_ERROR $errorMsg"))
            ToolResult(false, errorMsg)
        }
    }

    /**
     * 检测系统 Shell（带缓存优化）
     */
    private fun detectShell(): Pair<String, List<String>> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                // Windows: 优先级 pwsh > powershell > cmd
                when {
                    hasCommandCached("pwsh") -> "pwsh" to listOf("-Command")
                    hasCommandCached("powershell") -> "powershell" to listOf("-Command")
                    else -> "cmd" to listOf("/c")
                }
            }
            else -> "bash" to listOf("-c")
        }
    }

    /**
     * 检查命令是否可用（带缓存优化）
     */
    private fun hasCommandCached(command: String): Boolean {
        // 使用缓存避免重复检测
        return commandCache.getOrPut(command) {
            checkCommandAvailability(command)
        }
    }

    /**
     * 检查命令可用性的实际实现
     */
    private fun checkCommandAvailability(command: String): Boolean {
        return try {
            val os = System.getProperty("os.name").lowercase()
            val process = if (os.contains("win")) {
                ProcessBuilder("where", command).start()
            } else {
                ProcessBuilder("which", command).start()
            }
            val available = process.waitFor() == 0
            logger.debug("命令 $command 可用: $available")
            available
        } catch (e: Exception) {
            logger.debug("检查命令 $command 失败: ${e.message}")
            false
        }
    }

    /**
     * 创建通知 Part
     */
    private fun createNotificationPart(text: String): Part {
        return TextPart().apply {
            this.text = text
            sessionId = DEFAULT_SESSION_ID
        }
    }

    /**
     * 命令可用性缓存
     */
    private val commandCache = mutableMapOf<String, Boolean>()
}
