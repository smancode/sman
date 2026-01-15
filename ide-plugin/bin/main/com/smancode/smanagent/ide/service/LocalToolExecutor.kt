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
import java.io.File

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
 */
class LocalToolExecutor(private val project: Project) {

    private val logger = Logger.getInstance(LocalToolExecutor::class.java)

    companion object {
        /**
         * 源码文件扩展名（按优先级排序）
         */
        private val SOURCE_FILE_EXTENSIONS = listOf(
            "java", "xml", "yml", "yaml", "html", "vue",
            "kt", "js", "ts", "jsx", "tsx",
            "py", "go", "rs", "c", "cpp", "h", "hpp",
            "md", "json", "properties"
        )
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
        
        val normalizedAbsolute = absolutePath.replace("\\", "/")
        val normalizedBase = basePath.replace("\\", "/").removeSuffix("/")
        
        return if (normalizedAbsolute.startsWith(normalizedBase)) {
            normalizedAbsolute.removePrefix(normalizedBase).removePrefix("/")
        } else {
            absolutePath
        }
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
                } else if (regex.matches(file.name) && SOURCE_FILE_EXTENSIONS.any { file.name.endsWith(".$it") }) {
                    // 只匹配源码文件，排除 .class 等编译产物
                    val relativePath = file.absolutePath.removePrefix(basePath).removePrefix("/")
                    matches.add(mapOf(
                        "path" to relativePath,
                        "name" to file.name
                    ))
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
        val endLine = (parameters["endLine"] as? Number)?.toInt() ?: 100

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
            val actualStartLine = if (relativePath == null && startLine == 1 && endLine == 100) 1 else startLine
            val actualEndLine = if (relativePath == null && startLine == 1 && endLine == 100) 100 else endLine

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
            val relativePath = toRelativePath(file.absolutePath, basePath)

            ToolResult(
                success = true,
                result = sb.toString(),
                relativePath = relativePath,  // 新增：存储相对路径
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
     */
    private fun executeGrepFile(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val pattern = parameters["pattern"]?.toString()
            ?: return ToolResult(false, "缺少 pattern 参数")
        
        val relativePath = parameters["relativePath"]?.toString() ?: "."
        
        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)
        
        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }
        
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return ToolResult(false, "无效的正则表达式: ${e.message}")
        }
        
        val content = file.readText()
        val lines = content.lines()
        
        val matches = mutableListOf<Map<String, Any>>()
        lines.forEachIndexed { index, line ->
            if (regex.containsMatchIn(line)) {
                matches.add(mapOf(
                    "lineNumber" to (index + 1),
                    "line" to line
                ))
            }
        }
        
        val sb = StringBuilder()
        sb.append("找到 ${matches.size} 处匹配:\n\n")
        matches.forEach { match ->
            val lineNum = match["lineNumber"] as Int
            val line = match["line"] as String
            sb.append(":$lineNum: $line\n")
        }
        
        return ToolResult(true, sb.toString())
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
}
