package ai.smancode.sman.ide.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 本地工具执行器
 *
 * 接收 Agent 的工具调用请求，在本地执行并返回结果
 *
 * 支持的工具：
 * - read_file: 读取文件内容（使用 PSI）
 * - grep_file: 文件内容搜索（支持正则表达式）
 * - call_chain: 调用链分析
 * - apply_change: 应用代码变更（SEARCH/REPLACE + 自动格式化）
 */
class LocalToolExecutor(private val project: Project) {
    
    private val logger = Logger.getInstance(LocalToolExecutor::class.java)
    
    /**
     * 工具执行结果
     *
     * result 可以是 String（人类可读）或 Map（结构化数据）
     * 后端会直接透传给 Claude Code
     */
    data class ToolResult(
        val success: Boolean,
        val result: Any,
        val executionTime: Long = 0
    )
    
    /**
     * 执行工具
     */
    fun execute(toolName: String, parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val startTime = System.currentTimeMillis()
        
        logger.info("执行本地工具: $toolName, params=$parameters, projectPath=$projectPath")
        
        return try {
            val result = when (toolName) {
                "read_file" -> executeReadFile(parameters, projectPath, null)
                "grep_file" -> executeGrepFile(parameters, projectPath)
                "call_chain" -> executeCallChain(parameters)
                "apply_change" -> executeApplyChange(parameters, projectPath)
                else -> ToolResult(false, "不支持的工具: $toolName")
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            ToolResult(result.success, result.result, elapsed)
            
        } catch (e: Exception) {
            logger.error("工具执行失败: $toolName", e)
            val elapsed = System.currentTimeMillis() - startTime
            ToolResult(false, "工具执行异常: ${e.message}", elapsed)
        }
    }
    
    
    /**
     * 将绝对路径转换为相对路径
     */
    private fun toRelativePath(absolutePath: String, basePath: String): String {
        if (basePath.isEmpty()) return absolutePath
        
        // 统一路径分隔符
        val normalizedAbsolute = absolutePath.replace("\\", "/")
        val normalizedBase = basePath.replace("\\", "/").removeSuffix("/")
        
        return if (normalizedAbsolute.startsWith(normalizedBase)) {
            normalizedAbsolute.removePrefix(normalizedBase).removePrefix("/")
        } else {
            absolutePath
        }
    }
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    /**
     * ✅ 读取文件（使用 PSI）
     *
     * 参数：
     * - relativePath: 文件路径（必需）
     * - start_line: 起始行号（可选，1-based）
     * - end_line: 结束行号（可选，1-based）
     * - line: 中心行号（可选，返回前后各 context_lines 行）
     * - context_lines: 上下文行数（默认 20，仅在指定 line 时生效）
     */
    private fun executeReadFile(parameters: Map<String, Any?>, projectPath: String?, allowedExtensions: List<String>?): ToolResult {
        val inputRelativePath = parameters["relativePath"]?.toString()
            ?: parameters["path"]?.toString()
            ?: return ToolResult(false, "缺少 relativePath 参数")

        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(inputRelativePath).isAbsolute) File(inputRelativePath) else File(basePath, inputRelativePath)

        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }

        if (allowedExtensions != null && !allowedExtensions.any { file.name.endsWith(it) }) {
            return ToolResult(false, "不支持的文件类型: ${file.name}")
        }

        // ✅ 使用 PSI 读取文件（支持 IDE 内存中的未保存文件）
        return ReadAction.compute<ToolResult, Exception> {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                ?: return@compute ToolResult(false, "无法找到文件: ${file.absolutePath}")

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@compute ToolResult(false, "无法读取 PSI: ${file.absolutePath}")

            // 获取文件内容（支持编码）
            val content = psiFile.text
            val lines = content.lines()
            val ext = file.extension.ifEmpty { "text" }

            // 计算相对路径
            val relativePath = toRelativePath(file.absolutePath, basePath)

            // 支持按行号范围读取
            val startLine = (parameters["start_line"] as? Number)?.toInt()
            val endLine = (parameters["end_line"] as? Number)?.toInt()
            val centerLine = (parameters["line"] as? Number)?.toInt()
            val contextLines = (parameters["context_lines"] as? Number)?.toInt() ?: 20

            val sb = StringBuilder()
            sb.append("## 文件: ${file.name}\n\n")
            sb.append("**relativePath**: `$relativePath`\n")
            sb.append("**absolutePath**: `${file.absolutePath}`\n")
            sb.append("**类型**: $ext\n")
            sb.append("**总行数**: ${lines.size}\n")
            sb.append("**文件大小**: ${content.length} 字符\n\n")

            // 确定读取范围
            val (readStart, readEnd) = when {
                startLine != null && endLine != null -> startLine to endLine
                centerLine != null -> maxOf(1, centerLine - contextLines) to minOf(lines.size, centerLine + contextLines)
                else -> 1 to lines.size  // 默认读取全部
            }

            // ✅ 边界处理：自动截断到文件行数
            val actualStart = maxOf(1, readStart)
            val actualEnd = minOf(lines.size, readEnd)

            sb.append("**请求范围**: 第 $readStart - $readEnd 行\n")
            sb.append("**实际范围**: 第 $actualStart - $actualEnd 行\n")

            if (actualEnd < readEnd) {
                sb.append("**⚠️ 已自动截断**: endLine 超出文件行数，已截断到第 $actualEnd 行\n")
            }

            sb.append("**读取行数**: ${actualEnd - actualStart + 1} 行\n\n")

            sb.append("```$ext\n")
            for (i in (actualStart - 1) until actualEnd) {
                val lineNum = String.format("%4d", i + 1)
                val marker = if (centerLine != null && i + 1 == centerLine) " >>> " else " |   "
                sb.append("$lineNum$marker${lines[i]}\n")
            }
            sb.append("```\n")

            if (actualEnd < lines.size) {
                sb.append("\n> 💡 文件还有 ${lines.size - actualEnd} 行未显示。")
                if (centerLine != null) {
                    sb.append("使用 start_line=${actualEnd + 1} 继续读取。\n")
                } else {
                    sb.append("使用 start_line/end_line 参数指定读取范围。\n")
                }
            }

            ToolResult(true, sb.toString())
        }
    }

    /**
     * 🔥 文件内容搜索（支持正则表达式）
     *
     * ✅ 使用 IDE PSI 能力：
     * - 单文件搜索：使用 PSI + 正则表达式
     * - 全项目搜索：使用 PsiSearchHelper（IDE 索引加速）
     *
     * 参数：
     * - relativePath: 文件路径（可选，不指定则为全项目搜索）
     * - pattern: 搜索关键词或正则表达式（必需）
     * - regex: 是否启用正则表达式（默认 false）
     * - case_sensitive: 是否大小写敏感（默认 false）
     * - context_lines: 上下文行数（默认 0）
     * - limit: 最大结果数（全项目搜索时有效，默认 20）
     * - file_type: 文件类型过滤（全项目搜索时有效，默认 "all"）
     */
    private fun executeGrepFile(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val inputRelativePath = parameters["relativePath"]?.toString()
            ?: parameters["path"]?.toString()

        val pattern = parameters["pattern"]?.toString()
            ?: return ToolResult(false, "缺少 pattern 参数（搜索关键词或正则表达式）")

        val useRegex = parameters["regex"] as? Boolean ?: false
        val caseSensitive = parameters["case_sensitive"] as? Boolean ?: false
        val contextLines = (parameters["context_lines"] as? Number)?.toInt() ?: 0
        val limit = (parameters["limit"] as? Number)?.toInt() ?: 20
        val fileType = parameters["file_type"]?.toString() ?: "all"

        // 🔥 判断是单文件搜索还是全项目搜索
        return if (inputRelativePath != null && inputRelativePath.isNotEmpty()) {
            // 单文件搜索（使用 PSI）
            grepSingleFile(inputRelativePath, pattern, useRegex, caseSensitive, contextLines, projectPath)
        } else {
            // 全项目搜索（使用 PsiSearchHelper）
            grepProjectWide(pattern, useRegex, caseSensitive, contextLines, limit, fileType)
        }
    }

    /**
     * 单文件搜索（使用 PSI）
     */
    private fun grepSingleFile(
        relativePath: String,
        pattern: String,
        useRegex: Boolean,
        caseSensitive: Boolean,
        contextLines: Int,
        projectPath: String?
    ): ToolResult {
        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)

        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }

        return ReadAction.compute<ToolResult, Exception> {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                ?: return@compute ToolResult(false, "无法找到文件: ${file.absolutePath}")

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@compute ToolResult(false, "无法读取 PSI: ${file.absolutePath}")

            val content = psiFile.text
            val lines = content.lines()
            val ext = file.extension.ifEmpty { "text" }

            // 编译正则表达式或准备关键词
            val regexPattern = if (useRegex) {
                try {
                    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    Regex(pattern, options)
                } catch (e: Exception) {
                    return@compute ToolResult(false, "无效的正则表达式: $pattern\n错误: ${e.message}")
                }
            } else null

            // 搜索匹配行
            val matches = mutableListOf<GrepMatch>()
            lines.forEachIndexed { index, line ->
                val isMatch = if (useRegex) {
                    regexPattern?.containsMatchIn(line) ?: false
                } else {
                    if (caseSensitive) line.contains(pattern)
                    else line.contains(pattern, ignoreCase = true)
                }

                if (isMatch) {
                    val matchedText = if (useRegex) {
                        regexPattern?.find(line)?.value ?: pattern
                    } else pattern
                    matches.add(GrepMatch(index + 1, line.trim(), matchedText))
                }
            }

            if (matches.isEmpty()) {
                return@compute ToolResult(true, "未找到匹配 `$pattern` 的内容\n\n文件: `${toRelativePath(file.absolutePath, basePath)}`")
            }

            // 格式化输出
            val sb = StringBuilder()
            val displayPath = toRelativePath(file.absolutePath, basePath)
            sb.append("## 文件内容搜索: ${file.name}\n\n")
            sb.append("**relativePath**: `$displayPath`\n")
            sb.append("**搜索内容**: `$pattern`\n")
            sb.append("**正则模式**: ${if (useRegex) "是" else "否"}\n")
            sb.append("**大小写敏感**: ${if (caseSensitive) "是" else "否"}\n")
            sb.append("**匹配数量**: ${matches.size}\n\n")

            for (match in matches) {
                if (contextLines > 0) {
                    val start = maxOf(0, match.lineNumber - contextLines - 1)
                    val end = minOf(lines.size, match.lineNumber + contextLines)

                    sb.append("### 第 ${match.lineNumber} 行\n\n")
                    sb.append("```$ext\n")
                    for (i in start until end) {
                        val lineNum = String.format("%4d", i + 1)
                        if (i + 1 == match.lineNumber) {
                            sb.append("$lineNum >>> ${lines[i]}  // <-- 匹配: ${match.matchedText}\n")
                        } else {
                            sb.append("$lineNum |   ${lines[i]}\n")
                        }
                    }
                    sb.append("```\n\n")
                } else {
                    sb.append("- **第 ${match.lineNumber} 行**: `${match.lineContent}`\n")
                }
            }

            ToolResult(true, sb.toString())
        }
    }

    /**
     * 全项目搜索（使用文件遍历 + PSI 搜索）
     */
    @Suppress("UNUSED_PARAMETER")
    private fun grepProjectWide(
        pattern: String,
        useRegex: Boolean,
        caseSensitive: Boolean,
        contextLines: Int,
        limit: Int,
        fileType: String
    ): ToolResult {
        logger.info("全项目搜索: pattern=$pattern, fileType=$fileType, limit=$limit")

        return ReadAction.compute<ToolResult, Exception> {
            // 文件类型过滤
            val allowedExtensions = when (fileType) {
                "java" -> listOf("java")
                "config" -> listOf("properties", "yml", "yaml", "xml")
                else -> null  // all
            }

            val matches = mutableListOf<ProjectGrepMatch>()

            // 编译正则表达式或准备关键词
            val regexPattern = if (useRegex) {
                try {
                    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    Regex(pattern, options)
                } catch (e: Exception) {
                    return@compute ToolResult(false, "无效的正则表达式: $pattern\n错误: ${e.message}")
                }
            } else null

            // 遍历项目源码根目录
            val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in contentRoots) {
                if (matches.size >= limit) break

                // 递归遍历目录
                traverseDirectoryForGrep(root, pattern, regexPattern, caseSensitive, allowedExtensions, matches, limit)
            }

            if (matches.isEmpty()) {
                return@compute ToolResult(true, "未找到匹配 `$pattern` 的内容\n\n搜索范围: 全项目\n文件类型: $fileType")
            }

            // 格式化输出
            val sb = StringBuilder()
            sb.append("## 🔍 全项目搜索结果\n\n")
            sb.append("**搜索内容**: `$pattern`\n")
            sb.append("**正则模式**: ${if (useRegex) "是" else "否"}\n")
            sb.append("**大小写敏感**: ${if (caseSensitive) "是" else "否"}\n")
            sb.append("**文件类型**: $fileType\n")
            sb.append("**匹配数量**: ${matches.size}\n\n")

            for (match in matches) {
                sb.append("### `${match.relativePath}`\n\n")
                sb.append("- **行号**: ${match.lineNumber}\n")
                sb.append("- **内容**: `${match.content}`\n")
                sb.append("- **匹配**: `${match.matchedText}`\n\n")
            }

            ToolResult(true, sb.toString())
        }
    }

    /**
     * 递归遍历目录进行 Grep 搜索
     */
    private fun traverseDirectoryForGrep(
        dir: VirtualFile,
        pattern: String,
        regexPattern: Regex?,
        caseSensitive: Boolean,
        allowedExtensions: List<String>?,
        matches: MutableList<ProjectGrepMatch>,
        limit: Int
    ) {
        if (!dir.isDirectory || matches.size >= limit) return

        for (child in dir.children) {
            if (matches.size >= limit) break

            if (child.isDirectory) {
                // 递归遍历子目录
                traverseDirectoryForGrep(child, pattern, regexPattern, caseSensitive, allowedExtensions, matches, limit)
            } else {
                // 检查文件类型
                if (allowedExtensions != null) {
                    val ext = child.extension
                    if (ext == null || ext !in allowedExtensions) {
                        continue
                    }
                }

                // 使用 PSI 读取文件并搜索
                val psiFile = PsiManager.getInstance(project).findFile(child) ?: continue
                searchInPsiFile(psiFile, pattern, regexPattern, caseSensitive, matches, limit)
            }
        }
    }

    /**
     * 在 PSI 文件中搜索匹配项
     */
    private fun searchInPsiFile(
        psiFile: PsiFile,
        pattern: String,
        regexPattern: Regex?,
        caseSensitive: Boolean,
        matches: MutableList<ProjectGrepMatch>,
        limit: Int
    ) {
        val content = psiFile.text
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (matches.size >= limit) return

            val isMatch = if (regexPattern != null) {
                regexPattern.containsMatchIn(line)
            } else {
                if (caseSensitive) line.contains(pattern)
                else line.contains(pattern, ignoreCase = true)
            }

            if (isMatch) {
                val matchedText = if (regexPattern != null) {
                    regexPattern.find(line)?.value ?: pattern
                } else pattern

                val relativePath = toRelativePath(psiFile.virtualFile.path, project.basePath ?: "")
                matches.add(ProjectGrepMatch(
                    relativePath = relativePath,
                    lineNumber = index + 1,
                    content = line.trim(),
                    matchedText = matchedText
                ))
            }
        }
    }

    /**
     * 单文件 Grep 匹配结果
     */
    private data class GrepMatch(
        val lineNumber: Int,
        val lineContent: String,
        val matchedText: String
    )

    /**
     * 全项目 Grep 匹配结果
     */
    private data class ProjectGrepMatch(
        val relativePath: String,
        val lineNumber: Int,
        val content: String,
        val matchedText: String
    )

    /**
     * 通过类名查找 PsiClass
     */
    private fun findClass(className: String): PsiClass? {
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        
        // 尝试全限定名
        var psiClass = javaPsiFacade.findClass(className, scope)
        if (psiClass != null) return psiClass
        
        // 尝试简单名查找
        val classes = javaPsiFacade.findClasses(className, scope)
        if (classes.isNotEmpty()) return classes[0]
        
        // 遍历项目查找
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
    
    // ==================== 新增工具实现 ====================
    
    /**
     * 调用链分析工具
     * 
     * 使用 IntelliJ API 分析方法的调用关系
     * 参考 Agent 端 CallChainTool.java
     */
    private fun executeCallChain(parameters: Map<String, Any?>): ToolResult {
        val method = parameters["method"]?.toString()
            ?: return ToolResult(false, "缺少 method 参数（格式：ClassName.methodName）")

        val direction = parameters["direction"]?.toString() ?: "both"
        val depth = (parameters["depth"] as? Number)?.toInt() ?: 1  // 默认1层，避免发散
        // 兼容两种命名：includeSource (驼峰) 和 include_source (下划线)
        val includeSource = (parameters["includeSource"] as? Boolean)
            ?: (parameters["include_source"] as? Boolean)
            ?: false
        
        // 解析类名和方法名
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

            // 分析调用者（谁调用了这个方法）
            if (direction == "callers" || direction == "both") {
                sb.append("### 🔼 调用者（谁调用了这个方法）\n\n")
                val callers = ReferencesSearch.search(targetMethod).findAll()
                if (callers.isEmpty()) {
                    sb.append("未找到调用者\n\n")
                } else {
                    callers.take(20).forEach { ref ->
                        val element = ref.element
                        val containingMethod = element.parentOfType<com.intellij.psi.PsiMethod>()
                        val containingClass = element.parentOfType<com.intellij.psi.PsiClass>()

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

            // 分析被调用者（这个方法调用了谁）
            if (direction == "callees" || direction == "both") {
                sb.append("### 🔽 被调用者（这个方法调用了谁）\n\n")
                val callees = mutableListOf<String>()

                targetMethod.body?.accept(object : com.intellij.psi.JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: com.intellij.psi.PsiMethodCallExpression) {
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
                    callees.distinct().take(30).forEach { callee ->
                        sb.append("- `$callee`\n")
                    }
                    sb.append("\n")
                }
            }

            ToolResult(true, sb.toString())
        }
    }
    
    
    // 辅助方法：获取父元素
    private inline fun <reified T : PsiElement> PsiElement.parentOfType(): T? {
        var parent = this.parent
        while (parent != null) {
            if (parent is T) return parent
            parent = parent.parent
        }
        return null
    }
    
    /**
     * 🔥 智能读取文件，自动检测编码（支持 UTF-8、GBK、GB2312）
     * 
     * 参考 Agent 端 XmlDocumentExtractor.readFileWithEncoding 实现
     */
    private fun readFileWithEncoding(file: File): String {
        val bytes = file.readBytes()
        
        // 1. 检查 XML 声明中的 encoding
        val declaredEncoding = detectEncodingFromXmlDeclaration(bytes)
        if (declaredEncoding != null) {
            try {
                val charset = Charset.forName(declaredEncoding)
                return String(bytes, charset)
            } catch (e: Exception) {
                logger.debug("声明的编码 $declaredEncoding 不可用，尝试其他编码")
            }
        }
        
        // 2. 尝试 UTF-8（无 BOM）
        try {
            val content = String(bytes, Charsets.UTF_8)
            // 检查是否有乱码（简单检测：是否有替换字符）
            if (!content.contains("\uFFFD")) {
                return content
            }
        } catch (e: Exception) {
            // 忽略
        }
        
        // 3. 尝试 GBK（中文 Windows 默认编码）
        try {
            val gbk = Charset.forName("GBK")
            return String(bytes, gbk)
        } catch (e: Exception) {
            logger.debug("GBK 编码读取失败: ${file.path}")
        }
        
        // 4. 尝试 GB2312
        try {
            val gb2312 = Charset.forName("GB2312")
            return String(bytes, gb2312)
        } catch (e: Exception) {
            logger.debug("GB2312 编码读取失败: ${file.path}")
        }
        
        // 5. 最后降级到 ISO-8859-1（不会失败，但可能乱码）
        return String(bytes, Charsets.ISO_8859_1)
    }
    
    /**
     * 从 XML 声明中检测编码
     * 例如: <?xml version="1.0" encoding="GBK"?>
     */
    private fun detectEncodingFromXmlDeclaration(bytes: ByteArray): String? {
        try {
            // 读取前 200 字节（足够包含 XML 声明）
            val len = minOf(bytes.size, 200)
            val header = String(bytes, 0, len, Charsets.ISO_8859_1)
            
            // 查找 encoding="xxx" 或 encoding='xxx'
            val pattern = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            val match = pattern.find(header)
            
            if (match != null) {
                return match.groupValues[1].uppercase()
            }
        } catch (e: Exception) {
            // 忽略
        }
        return null
    }
    

    // ==================== 新增工具：apply_change ====================

    /**
     * 🔥 应用代码变更工具（SEARCH/REPLACE + 自动格式化）
     *
     * 功能：
     * 1. 读取文件
     * 2. 执行 SEARCH/REPLACE
     * 3. 自动格式化修改的部分
     * 4. 记录 TODO/done（防止重复执行）
     *
     * 参数：
     * - projectRoot (必需): 项目根路径
     * - relativePath (必需): 文件相对路径（从项目根目录）
     * - searchContent (必需): 要搜索的内容（SEARCH块）
     * - replaceContent (必需): 要替换的内容（REPLACE块）
     * - description (可选): 修改描述
     *
     * 参考 Agent 端 ApplyCodeChangeTool.java
     */
    private fun executeApplyChange(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val projectRoot = parameters["projectRoot"]?.toString()
            ?: parameters["project_root"]?.toString()
        val relativePath = parameters["relativePath"]?.toString()
        val searchContent = parameters["searchContent"]?.toString()
            ?: parameters["search_content"]?.toString()
        val replaceContent = parameters["replaceContent"]?.toString()
            ?: parameters["replace_content"]?.toString()
        val description = parameters["description"]?.toString() ?: "代码修改"

        // 1. 参数校验
        if (relativePath.isNullOrEmpty()) {
            return ToolResult(false, "缺少必需参数: relativePath")
        }
        // 🔥 searchContent 可以为空（新增文件操作）
        if (replaceContent == null) {
            return ToolResult(false, "缺少必需参数: replaceContent")
        }

        val basePath = projectRoot ?: projectPath ?: project.basePath ?: return ToolResult(false, "无法确定项目路径")

        // 🔥 判断是否是新增文件操作
        val isAddOperation = searchContent.isNullOrEmpty()

        logger.info("🔧 应用代码变更: relativePath=$relativePath, isAdd=$isAddOperation, desc=$description")

        val codeEditService = project.getService(ai.smancode.sman.ide.service.CodeEditService::class.java)
            ?: return ToolResult(false, "无法获取 CodeEditService")

        return try {
            // 🔥 新增文件 vs 修改文件
            if (isAddOperation) {
                // 新增文件操作
                val editJson = org.json.JSONObject().apply {
                    put("projectPath", basePath)
                    put("summary", description)
                    put("edits", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("relativePath", relativePath)  // 🔥 统一使用 relativePath
                            put("action", "ADD")  // 🔥 新增
                            put("content", replaceContent)
                            put("description", description)
                        })
                    })
                }

                logger.info("🔧 执行新增文件操作: $relativePath")
                val batchResult = codeEditService.executeEdits(editJson)

                if (batchResult.allSuccess) {
                    logger.info("✅ apply_change (ADD) 成功: $relativePath")
                    val sb = StringBuilder()
                    sb.append("## 文件创建成功\n\n")
                    sb.append("- **relativePath**: `$relativePath`\n")
                    sb.append("- **修改**: $description\n")
                    sb.append("- **大小**: ${replaceContent.length} 字符\n")
                    ToolResult(true, sb.toString())
                } else {
                    // 🔥 详细记录失败原因
                    val failedResults = batchResult.results.filter { !it.success }
                    val sb = StringBuilder()
                    sb.append("❌ 文件创建失败: ${batchResult.failedCount}/${batchResult.totalEdits}\n\n")
                    sb.append("**文件**: `$relativePath`\n")
                    sb.append("**描述**: $description\n\n")

                    failedResults.forEach { editResult ->
                        sb.append("- **失败原因**: ${editResult.message}\n")
                    }

                    logger.error("❌ apply_change (ADD) 失败:\n{}", sb.toString())
                    ToolResult(false, sb.toString())
                }
            } else {
                // 修改文件操作（原有逻辑）
                val nonNullSearchContent = searchContent!!
                logger.info("🔧 执行修改文件操作: $relativePath")
                logger.info("🔧 searchContent.len=${nonNullSearchContent.length}, replaceContent.len=${replaceContent.length}")
                logger.debug("🔧 searchContent (前150字符): ${nonNullSearchContent.take(150)}")
                logger.debug("🔧 replaceContent (前150字符): ${replaceContent.take(150)}")

                val editJson = org.json.JSONObject().apply {
                    put("projectPath", basePath)
                    put("summary", description)
                    put("edits", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("relativePath", relativePath)  // 🔥 统一使用 relativePath
                            put("action", "MODIFY")
                            put("content", replaceContent)
                            put("oldContent", nonNullSearchContent)
                            put("description", description)
                        })
                    })
                }

                val batchResult = codeEditService.executeEdits(editJson)

                if (batchResult.allSuccess) {
                    logger.info("✅ apply_change (MODIFY) 成功: $relativePath")
                    val sb = StringBuilder()
                    sb.append("## 代码变更应用成功\n\n")
                    sb.append("- **relativePath**: `$relativePath`\n")
                    sb.append("- **修改**: $description\n")
                    sb.append("- **状态**: ✅ 已自动格式化\n")
                    ToolResult(true, sb.toString())
                } else {
                    // 🔥 详细记录失败原因
                    val failedResults = batchResult.results.filter { !it.success }
                    val sb = StringBuilder()
                    sb.append("❌ 代码变更失败: ${batchResult.failedCount}/${batchResult.totalEdits}\n\n")
                    sb.append("**文件**: `$relativePath`\n")
                    sb.append("**描述**: $description\n\n")

                    failedResults.forEach { editResult ->
                        sb.append("- **失败原因**: ${editResult.message}\n")
                    }

                    logger.error("❌ apply_change (MODIFY) 失败:\n{}", sb.toString())
                    ToolResult(false, sb.toString())
                }
            }

        } catch (e: Exception) {
            logger.error("❌ apply_change 异常: ${e.message}", e)
            ToolResult(false, "代码变更异常: ${e.message}")
        }
    }
}

