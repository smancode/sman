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
    
    data class ToolResult(
        val success: Boolean,
        val result: Any,
        val executionTime: Long = 0
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
                } else if (regex.matches(file.name)) {
                    val relativePath = file.absolutePath.removePrefix(basePath).removePrefix("/")
                    matches.add(mapOf(
                        "path" to relativePath,
                        "name" to file.name
                    ))
                }
            }
        }

        findFiles(baseDir)

        val sb = StringBuilder()
        sb.append("找到 ${matches.size} 个文件:\n\n")
        matches.forEach { match ->
            sb.append("- `${match["path"]}`\n")
        }

        return ToolResult(true, sb.toString())
    }
    
    /**
     * 读取文件
     */
    private fun executeReadFile(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val relativePath = parameters["relativePath"]?.toString()
            ?: parameters["path"]?.toString()
            ?: return ToolResult(false, "缺少 relativePath 参数")
        
        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)
        
        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }
        
        return ReadAction.compute<ToolResult, Exception> {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                ?: return@compute ToolResult(false, "无法找到文件: ${file.absolutePath}")
            
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@compute ToolResult(false, "无法读取文件: ${file.absolutePath}")
            
            val content = psiFile.text
            
            ToolResult(true, content)
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
     */
    private fun executeApplyChange(parameters: Map<String, Any?>, projectPath: String?): ToolResult {
        val relativePath = parameters["relativePath"]?.toString()
            ?: return ToolResult(false, "缺少 relativePath 参数")
        
        val searchContent = parameters["searchContent"]?.toString()
            ?: return ToolResult(false, "缺少 searchContent 参数")
        
        val replaceContent = parameters["replaceContent"]?.toString()
            ?: return ToolResult(false, "缺少 replaceContent 参数")
        
        val description = parameters["description"]?.toString() ?: "代码修改"
        
        val basePath = projectPath ?: project.basePath ?: ""
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)
        
        if (!file.exists()) {
            return ToolResult(false, "文件不存在: ${file.absolutePath}")
        }
        
        return ReadAction.compute<ToolResult, Exception> {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                ?: return@compute ToolResult(false, "无法找到文件: ${file.absolutePath}")
            
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@compute ToolResult(false, "无法读取文件: ${file.absolutePath}")
            
            val content = psiFile.text
            
            // 查找并替换
            if (!content.contains(searchContent)) {
                return@compute ToolResult(false, "未找到要替换的内容")
            }
            
            val newContent = content.replace(searchContent, replaceContent)
            
            // 应用修改
            com.intellij.openapi.application.WriteAction.run<Exception> {
                virtualFile.setBinaryContent(newContent.toByteArray())
            }
            
            val sb = StringBuilder()
            sb.append("## ✅ 代码修改成功\n\n")
            sb.append("**文件**: `$relativePath`\n")
            sb.append("**描述**: $description\n\n")
            sb.append("修改已应用并保存。\n")
            
            ToolResult(true, sb.toString())
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
