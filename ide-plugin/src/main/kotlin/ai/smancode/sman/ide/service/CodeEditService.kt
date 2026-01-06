package ai.smancode.sman.ide.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiClass
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 代码编辑服务
 * 
 * 核心能力：接收 Agent 的编码指令，在 IDE 中实施代码修改
 * 
 * 支持的操作：
 * - ADD: 创建新文件
 * - MODIFY: 修改现有文件（支持行号或字符串匹配）
 * - DELETE: 删除文件
 * 
 * @author Bank Core Analysis Team
 * @since 4.3.0
 */
@Service(Service.Level.PROJECT)
class CodeEditService(private val project: Project) {
    
    private val logger = Logger.getInstance(CodeEditService::class.java)
    
    /**
     * 编辑结果
     */
    data class EditResult(
        val success: Boolean,
        val message: String,
        val filePath: String? = null,
        val action: String? = null
    )
    
    /**
     * 批量编辑结果
     */
    data class BatchEditResult(
        val totalEdits: Int,
        val successCount: Int,
        val failedCount: Int,
        val results: List<EditResult>
    ) {
        val allSuccess: Boolean get() = failedCount == 0
    }
    
    /**
     * 执行代码编辑指令
     *
     * @param editsJson JSON 格式的编辑指令，格式：
     * {
     *   "projectPath": "/path/to/project",
     *   "summary": "修改说明",
     *   "edits": [
     *     {
     *       "relativePath": "src/main/java/...",
     *       "action": "ADD|MODIFY|DELETE",
     *       "content": "新内容",
     *       "oldContent": "旧内容（用于精确匹配）",
     *       "startLine": 10,
     *       "endLine": 20,
     *       "description": "修改说明"
     *     }
     *   ]
     * }
     */
    fun executeEdits(editsJson: JSONObject): BatchEditResult {
        val projectPath = editsJson.optString("projectPath", project.basePath ?: "")
        val editsArray = editsJson.optJSONArray("edits") ?: JSONArray()

        // 🔥 按文件分组，同一文件的修改需要特殊处理
        val editsByFile = mutableMapOf<String, MutableList<Pair<Int, JSONObject>>>()
        for (i in 0 until editsArray.length()) {
            val editObj = editsArray.getJSONObject(i)
            val relativePath = editObj.optString("relativePath", "")
            editsByFile.getOrPut(relativePath) { mutableListOf() }.add(i to editObj)
        }
        
        val results = mutableListOf<EditResult>()

        for ((relativePath, edits) in editsByFile) {
            if (edits.size == 1) {
                // 单个修改，直接执行
                val result = executeSingleEdit(projectPath, edits[0].second)
                results.add(result)
            } else {
                // 🔥 同一文件多个修改：按 startLine 倒序执行（从底部向顶部）
                // 这样前面的修改不会影响后面的行号
                val sortedEdits = edits.sortedByDescending { (_, obj) ->
                    obj.optInt("startLine", Int.MAX_VALUE)  // 没有 startLine 的放最后
                }

                logger.info("🔧 同一文件 $relativePath 有 ${edits.size} 个修改，按从下往上的顺序执行")

                for ((_, editObj) in sortedEdits) {
                    val result = executeSingleEdit(projectPath, editObj)
                    results.add(result)
                    
                    // 如果失败，后续修改可能也会失败，但继续尝试
                    if (!result.success) {
                        logger.warn("⚠️ 修改失败，后续修改可能受影响: ${result.message}")
                    }
                }
            }
        }
        
        return BatchEditResult(
            totalEdits = results.size,
            successCount = results.count { it.success },
            failedCount = results.count { !it.success },
            results = results
        )
    }
    
    /**
     * 执行单个编辑指令
     *
     * 🔥 支持两种模式：
     * 1. 结构化指令模式：structuredAction = addImport / addField / addMethod
     * 2. 文本匹配模式：action = MODIFY + oldContent/content
     */
    private fun executeSingleEdit(projectPath: String, editObj: JSONObject): EditResult {
        val relativePath = editObj.optString("relativePath", "")
        val action = editObj.optString("action", "MODIFY")
        val structuredAction = editObj.optString("structuredAction", "")
        val content = editObj.optString("content", "")
        val oldContent = editObj.optString("oldContent", "")
        val startLine = editObj.optInt("startLine", -1)
        val endLine = editObj.optInt("endLine", -1)
        val description = editObj.optString("description", "")

        if (relativePath.isEmpty()) {
            return EditResult(false, "文件路径为空", relativePath, action)
        }

        // 构建完整路径
        val fullPath = if (relativePath.startsWith("/")) {
            relativePath
        } else {
            "$projectPath/$relativePath"
        }
        
        // 🔥 优先检查结构化指令
        if (structuredAction.isNotEmpty()) {
            logger.info("执行结构化指令: structuredAction=$structuredAction, path=$fullPath")
            return executeStructuredAction(fullPath, structuredAction, editObj, description)
        }
        
        logger.info("执行代码编辑: action=$action, path=$fullPath, desc=$description")
        
        return when (action.uppercase()) {
            "ADD" -> createFile(fullPath, content, description)
            "MODIFY" -> modifyFile(fullPath, content, oldContent, startLine, endLine, description)
            "DELETE" -> deleteFile(fullPath, description)
            else -> EditResult(false, "未知操作类型: $action", relativePath, action)
        }
    }
    
    /**
     * 🔥🔥🔥 执行结构化指令（使用 PSI API，不需要文本匹配）
     */
    private fun executeStructuredAction(
        fullPath: String,
        structuredAction: String,
        editObj: JSONObject,
        description: String
    ): EditResult {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: return EditResult(false, "文件不存在: $fullPath", fullPath, structuredAction)
        
        return when (structuredAction.lowercase()) {
            "addimport" -> executeAddImport(virtualFile, editObj, description)
            "addfield" -> executeAddField(virtualFile, editObj, description)
            "addmethod" -> executeAddMethod(virtualFile, editObj, description)
            else -> EditResult(false, "未知结构化指令: $structuredAction", fullPath, structuredAction)
        }
    }
    
    /**
     * 🔥 添加 import（使用 PSI API）
     */
    private fun executeAddImport(
        virtualFile: VirtualFile,
        editObj: JSONObject,
        description: String
    ): EditResult {
        val importsArray = editObj.optJSONArray("imports")
        if (importsArray == null || importsArray.length() == 0) {
            return EditResult(false, "imports 数组为空", virtualFile.path, "addImport")
        }
        
        val imports = (0 until importsArray.length()).map { importsArray.getString(it) }
        
        return try {
            var addedCount = 0
            
            ApplicationManager.getApplication().invokeAndWait {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile is com.intellij.psi.PsiJavaFile) {
                    WriteCommandAction.runWriteCommandAction(project, "SiliconMan: Add Import", null, {
                        val codeStyleManager = com.intellij.psi.codeStyle.JavaCodeStyleManager.getInstance(project)
                        
                        for (importFqn in imports) {
                            // 检查是否已存在
                            val existingImports = psiFile.importList?.allImportStatements
                            val alreadyExists = existingImports?.any { 
                                it.text.contains(importFqn) 
                            } ?: false
                            
                            if (!alreadyExists) {
                                // 使用 PSI 添加 import
                                val importClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                                    .findClass(importFqn, com.intellij.psi.search.GlobalSearchScope.allScope(project))
                                
                                if (importClass != null) {
                                    codeStyleManager.addImport(psiFile, importClass)
                                    addedCount++
                                    logger.info("✅ 使用 PSI 添加 import: $importFqn")
                                } else {
                                    // 类找不到，降级为文本插入
                                    val importStatement = "import $importFqn;"
                                    insertImportAsText(psiFile, importStatement)
                                    addedCount++
                                    logger.info("✅ 使用文本插入 import: $importFqn")
                                }
                            } else {
                                logger.info("ℹ️ import 已存在，跳过: $importFqn")
                            }
                        }
                        
                        // 提交修改
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                    }, psiFile)
                }
            }
            
            EditResult(true, "添加 $addedCount 个 import: $description", virtualFile.path, "addImport")
        } catch (e: Exception) {
            logger.error("添加 import 失败", e)
            EditResult(false, "添加 import 失败: ${e.message}", virtualFile.path, "addImport")
        }
    }
    
    /**
     * 文本方式插入 import（降级方案）
     */
    private fun insertImportAsText(psiFile: com.intellij.psi.PsiJavaFile, importStatement: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
        val importList = psiFile.importList
        
        if (importList != null && importList.allImportStatements.isNotEmpty()) {
            // 在最后一个 import 后插入
            val lastImport = importList.allImportStatements.last()
            val insertOffset = lastImport.textRange.endOffset
            document.insertString(insertOffset, "\n$importStatement")
        } else {
            // 在 package 声明后插入
            val packageStatement = psiFile.packageStatement
            if (packageStatement != null) {
                val insertOffset = packageStatement.textRange.endOffset
                document.insertString(insertOffset, "\n\n$importStatement")
            }
        }
    }
    
    /**
     * 🔥 添加字段（使用 PSI API）
     */
    private fun executeAddField(
        virtualFile: VirtualFile,
        editObj: JSONObject,
        description: String
    ): EditResult {
        val fieldObj = editObj.optJSONObject("field")
        if (fieldObj == null) {
            return EditResult(false, "field 对象为空", virtualFile.path, "addField")
        }
        
        val modifiers = fieldObj.optString("modifiers", "private")
        val type = fieldObj.optString("type", "")
        val name = fieldObj.optString("name", "")
        val initializer = fieldObj.optString("initializer", "")
        
        if (type.isEmpty() || name.isEmpty()) {
            return EditResult(false, "字段类型或名称为空", virtualFile.path, "addField")
        }
        
        // 构建字段代码
        val fieldCode = buildString {
            append("    ") // 缩进
            append(modifiers)
            if (modifiers.isNotEmpty()) append(" ")
            append(type).append(" ").append(name)
            if (initializer.isNotEmpty()) {
                append(" = ").append(initializer)
            }
            append(";")
        }
        
        return try {
            ApplicationManager.getApplication().invokeAndWait {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile is com.intellij.psi.PsiJavaFile) {
                    WriteCommandAction.runWriteCommandAction(project, "SiliconMan: Add Field", null, {
                        val psiClass = psiFile.classes.firstOrNull()
                        if (psiClass != null) {
                            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                            if (document != null) {
                                // 找到类的左花括号后插入
                                val lBrace = psiClass.lBrace
                                if (lBrace != null) {
                                    val insertOffset = lBrace.textRange.endOffset
                                    document.insertString(insertOffset, "\n$fieldCode\n")
                                    logger.info("✅ 添加字段: $name")
                                }
                            }
                        }
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                    }, psiFile)
                }
            }
            
            EditResult(true, "添加字段 $name: $description", virtualFile.path, "addField")
        } catch (e: Exception) {
            logger.error("添加字段失败", e)
            EditResult(false, "添加字段失败: ${e.message}", virtualFile.path, "addField")
        }
    }
    
    /**
     * 🔥 添加方法（使用 PSI API）
     */
    private fun executeAddMethod(
        virtualFile: VirtualFile,
        editObj: JSONObject,
        description: String
    ): EditResult {
        val methodObj = editObj.optJSONObject("method")
        if (methodObj == null) {
            return EditResult(false, "method 对象为空", virtualFile.path, "addMethod")
        }
        
        val modifiers = methodObj.optString("modifiers", "private")
        val returnType = methodObj.optString("returnType", "void")
        val name = methodObj.optString("name", "")
        val parameters = methodObj.optString("parameters", "")
        val body = methodObj.optString("body", "")
        val insertPosition = editObj.optString("insertPosition", "")
        
        if (name.isEmpty()) {
            return EditResult(false, "方法名称为空", virtualFile.path, "addMethod")
        }
        
        // 构建方法代码
        val methodCode = buildString {
            append("\n    ") // 缩进
            append(modifiers)
            if (modifiers.isNotEmpty()) append(" ")
            append(returnType).append(" ").append(name)
            append("(").append(parameters).append(") {\n")
            // 处理方法体
            body.lines().forEach { line ->
                append("        ").append(line).append("\n")
            }
            append("    }\n")
        }
        
        return try {
            ApplicationManager.getApplication().invokeAndWait {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile is com.intellij.psi.PsiJavaFile) {
                    WriteCommandAction.runWriteCommandAction(project, "SiliconMan: Add Method", null, {
                        val psiClass = psiFile.classes.firstOrNull()
                        if (psiClass != null) {
                            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                            if (document != null) {
                                // 根据 insertPosition 决定插入位置
                                val insertOffset = findInsertOffset(psiClass, insertPosition)
                                document.insertString(insertOffset, methodCode)
                                logger.info("✅ 添加方法: $name at offset $insertOffset")
                            }
                        }
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                    }, psiFile)
                }
            }
            
            EditResult(true, "添加方法 $name: $description", virtualFile.path, "addMethod")
        } catch (e: Exception) {
            logger.error("添加方法失败", e)
            EditResult(false, "添加方法失败: ${e.message}", virtualFile.path, "addMethod")
        }
    }
    
    /**
     * 找到插入位置的偏移量
     */
    private fun findInsertOffset(psiClass: com.intellij.psi.PsiClass, insertPosition: String): Int {
        return when {
            insertPosition.startsWith("beforeMethod:") -> {
                val methodName = insertPosition.removePrefix("beforeMethod:")
                val method = psiClass.findMethodsByName(methodName, false).firstOrNull()
                method?.textRange?.startOffset ?: (psiClass.rBrace?.textRange?.startOffset ?: 0)
            }
            insertPosition.startsWith("afterMethod:") -> {
                val methodName = insertPosition.removePrefix("afterMethod:")
                val method = psiClass.findMethodsByName(methodName, false).lastOrNull()
                method?.textRange?.endOffset ?: (psiClass.rBrace?.textRange?.startOffset ?: 0)
            }
            insertPosition == "afterImports" || insertPosition == "afterFields" -> {
                // 在类的第一个方法之前
                val firstMethod = psiClass.methods.firstOrNull()
                firstMethod?.textRange?.startOffset ?: (psiClass.rBrace?.textRange?.startOffset ?: 0)
            }
            else -> {
                // 默认在类的右花括号之前
                psiClass.rBrace?.textRange?.startOffset ?: 0
            }
        }
    }
    
    /**
     * 创建新文件
     */
    private fun createFile(fullPath: String, content: String, description: String): EditResult {
        return try {
            val file = File(fullPath)
            
            // 创建父目录
            file.parentFile?.mkdirs()
            
            // 写入文件
            file.writeText(content)
            
            // 刷新 VFS 让 IDE 感知新文件
            ApplicationManager.getApplication().invokeLater {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
            }
            
            logger.info("✅ 创建文件成功: $fullPath")
            EditResult(true, "创建成功: $description", fullPath, "ADD")
        } catch (e: Exception) {
            logger.error("❌ 创建文件失败: $fullPath", e)
            EditResult(false, "创建失败: ${e.message}", fullPath, "ADD")
        }
    }
    
    /**
     * 修改现有文件
     */
    private fun modifyFile(
        fullPath: String,
        newContent: String,
        oldContent: String,
        startLine: Int,
        endLine: Int,
        description: String
    ): EditResult {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: return EditResult(false, "文件不存在: $fullPath", fullPath, "MODIFY")
        
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return EditResult(false, "无法获取文档: $fullPath", fullPath, "MODIFY")
        
        return try {
            // 使用 WriteCommandAction 包装，支持撤销
            WriteCommandAction.runWriteCommandAction(project, "SiliconMan: $description", null, {
                // 记录修改范围，用于后续只对修改部分 format
                var modifiedStartOffset = 0
                var modifiedEndOffset = 0
                
                if (oldContent.isNotEmpty()) {
                    // 🔥 方式1：模糊匹配替换（忽略缩进差异）
                    val text = document.text
                    val matchResult = fuzzyMatch(text, oldContent)
                    if (matchResult != null) {
                        // 计算新内容的正确缩进
                        val adjustedNewContent = adjustIndentation(newContent, matchResult.detectedIndent)
                        document.replaceString(matchResult.startIndex, matchResult.endIndex, adjustedNewContent)
                        modifiedStartOffset = matchResult.startIndex
                        modifiedEndOffset = matchResult.startIndex + adjustedNewContent.length
                        logger.info("✅ 模糊匹配修改成功: $fullPath")
                    } else {
                        // 回退到精确匹配
                        val index = text.indexOf(oldContent)
                        if (index >= 0) {
                            document.replaceString(index, index + oldContent.length, newContent)
                            modifiedStartOffset = index
                            modifiedEndOffset = index + newContent.length
                            logger.info("✅ 精确匹配修改成功: $fullPath")
                        } else {
                            throw IllegalStateException("未找到匹配的旧内容（精确和模糊都未匹配）")
                        }
                    }
                } else if (startLine > 0 && endLine > 0) {
                    // 方式2：行号范围替换
                    val startOffset = document.getLineStartOffset(startLine - 1)
                    val endOffset = document.getLineEndOffset(endLine - 1)
                    document.replaceString(startOffset, endOffset, newContent)
                    modifiedStartOffset = startOffset
                    modifiedEndOffset = startOffset + newContent.length
                    logger.info("✅ 行号范围修改成功: $fullPath, lines $startLine-$endLine")
                } else {
                    // 方式3：全文替换（不做 format）
                    document.setText(newContent)
                    logger.info("✅ 全文替换成功: $fullPath")
                }
                
                // 提交文档变更
                PsiDocumentManager.getInstance(project).commitDocument(document)
                
                // 🔥 实事求是：改了多少就 format 多少
                // 每个修改点是独立的，format 不会影响其他代码
                if (modifiedEndOffset > modifiedStartOffset) {
                    try {
                        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                        if (psiFile != null) {
                            val codeStyleManager = com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
                            // 只 format 这个修改点的范围
                            codeStyleManager.reformatText(psiFile, modifiedStartOffset, modifiedEndOffset)
                            val lineCount = document.getLineNumber(modifiedEndOffset) - document.getLineNumber(modifiedStartOffset)
                            logger.info("✅ format 修改部分: $lineCount 行")
                        }
                    } catch (e: Exception) {
                        // format 失败不影响修改结果，只记录日志
                        logger.warn("⚠️ format 失败（不影响修改）: ${e.message}")
                    }
                }
            })
            
            EditResult(true, "修改成功: $description", fullPath, "MODIFY")
        } catch (e: Exception) {
            logger.error("❌ 修改文件失败: $fullPath", e)
            EditResult(false, "修改失败: ${e.message}", fullPath, "MODIFY")
        }
    }
    
    /**
     * 🔥 模糊匹配结果
     */
    data class FuzzyMatchResult(
        val startIndex: Int,
        val endIndex: Int,
        val detectedIndent: String  // 检测到的缩进（用于调整新内容）
    )
    
    /**
     * 🔥 模糊匹配（忽略每行的前导空白）
     * 
     * 原理：
     * 1. 将 oldContent 按行分割，去除每行前导空白
     * 2. 在目标文本中逐行查找匹配
     * 3. 记录第一行的实际缩进，用于调整新内容
     */
    private fun fuzzyMatch(text: String, oldContent: String): FuzzyMatchResult? {
        val oldLines = oldContent.lines().map { it.trimStart() }.filter { it.isNotEmpty() }
        if (oldLines.isEmpty()) return null
        
        val textLines = text.lines()
        
        // 逐行搜索匹配起点
        for (i in textLines.indices) {
            val trimmedLine = textLines[i].trimStart()
            if (trimmedLine == oldLines[0]) {
                // 检查后续行是否都匹配
                var allMatch = true
                for (j in 1 until oldLines.size) {
                    if (i + j >= textLines.size) {
                        allMatch = false
                        break
                    }
                    if (textLines[i + j].trimStart() != oldLines[j]) {
                        allMatch = false
                        break
                    }
                }
                
                if (allMatch) {
                    // 计算字符偏移量
                    var startOffset = 0
                    for (k in 0 until i) {
                        startOffset += textLines[k].length + 1  // +1 for newline
                    }
                    
                    var endOffset = startOffset
                    for (k in i until i + oldLines.size) {
                        endOffset += textLines[k].length + 1
                    }
                    endOffset -= 1  // 最后一行不算换行符
                    
                    // 检测第一行的缩进
                    val firstLine = textLines[i]
                    val indent = firstLine.takeWhile { it.isWhitespace() }
                    
                    return FuzzyMatchResult(startOffset, endOffset, indent)
                }
            }
        }
        
        return null
    }
    
    /**
     * 🔥 调整新内容的缩进（智能缩进）
     * 
     * 策略：
     * 1. 检测源代码的基础缩进（第一行非空行的缩进）
     * 2. 计算每行相对于基础缩进的"额外缩进"
     * 3. 将基础缩进替换为目标缩进，保持额外缩进不变
     * 
     * 这样能正确处理：
     * - LLM 给的代码完全没缩进
     * - LLM 给的代码有 4 空格缩进
     * - 目标位置是 8 空格缩进
     */
    private fun adjustIndentation(content: String, targetIndent: String): String {
        val lines = content.lines()
        if (lines.isEmpty()) return content
        
        // 检测新内容的基础缩进（第一行非空行的缩进）
        val firstNonEmptyLine = lines.firstOrNull { it.isNotBlank() } ?: return content
        val sourceIndent = firstNonEmptyLine.takeWhile { it.isWhitespace() }
        
        // 如果源代码没有缩进，且目标有缩进，需要给每行加上目标缩进
        // 并且保持相对缩进（嵌套结构）
        if (sourceIndent.isEmpty() && targetIndent.isNotEmpty()) {
            return lines.joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    else -> {
                        // 检测这一行的实际缩进（相对于无缩进的基础）
                        val lineIndent = line.takeWhile { it.isWhitespace() }
                        // 目标缩进 + 原有的相对缩进
                        targetIndent + line
                    }
                }
            }
        }
        
        // 计算基础缩进的空格数（用于相对计算）
        val sourceIndentLen = sourceIndent.length
        
        return lines.joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith(sourceIndent) -> {
                    // 正常情况：替换基础缩进为目标缩进
                    targetIndent + line.substring(sourceIndentLen)
                }
                    else -> {
                        // 这一行的缩进比基础缩进少（可能是语法结构如 } 或 else）
                        val lineIndentLen = line.takeWhile { it.isWhitespace() }.length
                    if (lineIndentLen < sourceIndentLen) {
                        // 计算减少了多少缩进（相对于基础）
                        val indentDiff = sourceIndentLen - lineIndentLen
                        // 目标缩进相应减少
                        val adjustedTargetLen = maxOf(0, targetIndent.length - indentDiff)
                        targetIndent.take(adjustedTargetLen) + line.trimStart()
                    } else {
                        // 不应该发生，保守处理
                        targetIndent + line.trimStart()
                    }
                }
            }
        }
    }
    
    /**
     * 删除文件
     */
    private fun deleteFile(fullPath: String, description: String): EditResult {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: return EditResult(false, "文件不存在: $fullPath", fullPath, "DELETE")
        
        return try {
            WriteCommandAction.runWriteCommandAction(project, "SiliconMan: 删除 $description", null, {
                virtualFile.delete(this)
            })
            
            logger.info("✅ 删除文件成功: $fullPath")
            EditResult(true, "删除成功: $description", fullPath, "DELETE")
        } catch (e: Exception) {
            logger.error("❌ 删除文件失败: $fullPath", e)
            EditResult(false, "删除失败: ${e.message}", fullPath, "DELETE")
        }
    }
    
    /**
     * 预览编辑（不实际执行，只返回 diff）
     */
    fun previewEdits(editsJson: JSONObject): String {
        val editsArray = editsJson.optJSONArray("edits") ?: return "无编辑内容"
        val sb = StringBuilder()
        
        for (i in 0 until editsArray.length()) {
            val editObj = editsArray.getJSONObject(i)
            val filePath = editObj.optString("filePath", "")
            val action = editObj.optString("action", "MODIFY")
            val description = editObj.optString("description", "")
            
            sb.append("${i + 1}. [$action] $filePath\n")
            sb.append("   说明: $description\n\n")
        }
        
        return sb.toString()
    }
}

