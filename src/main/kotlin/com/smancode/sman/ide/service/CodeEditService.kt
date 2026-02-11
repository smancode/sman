package com.smancode.sman.ide.service

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

/**
 * 代码编辑服务
 * <p>
 * 支持模糊匹配和自动格式化，避免因 LLM 返回的缩进不对导致替换失败
 */
class CodeEditService(private val project: Project) {

    private val logger = Logger.getInstance(CodeEditService::class.java)

    sealed class EditResult {
        data class Success(
            val message: String,
            val modifiedStartOffset: Int = 0,
            val modifiedEndOffset: Int = 0
        ) : EditResult()

        data class Failure(val error: String) : EditResult()
    }

    /**
     * 应用代码修改
     * @param relativePath 文件相对路径
     * @param oldContent 要替换的原始内容
     * @param newContent 新内容
     * @param projectPath 项目路径
     */
    fun applyChange(
        relativePath: String,
        oldContent: String,
        newContent: String,
        projectPath: String
    ): EditResult {
        val basePath = if (projectPath.isEmpty()) project.basePath ?: "" else projectPath
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)

        if (!file.exists()) {
            return EditResult.Failure("文件不存在: ${file.absolutePath}")
        }

        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file)
            ?: return EditResult.Failure("无法找到文件: ${file.absolutePath}")

        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            ?: return EditResult.Failure("无法读取文件: ${file.absolutePath}")

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return EditResult.Failure("无法获取文档: ${file.absolutePath}")

        val fileContent = document.text

        // 尝试精确匹配
        val matchResult = if (fileContent.contains(oldContent)) {
            logger.info("精确匹配成功")
            MatchResult.Success(
                fileContent.indexOf(oldContent),
                fileContent.indexOf(oldContent) + oldContent.length
            )
        } else {
            // 精确匹配失败，尝试模糊匹配
            logger.info("精确匹配失败，尝试模糊匹配")
            fuzzyMatch(fileContent, oldContent)
        }

        when (matchResult) {
            is MatchResult.Success -> {
                val startOffset = matchResult.startOffset
                val endOffset = matchResult.endOffset

                // 调整新内容的缩进
                val adjustedContent = adjustIndentation(
                    fileContent,
                    newContent,
                    startOffset
                )

                // 应用修改
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(startOffset, endOffset, adjustedContent)
                    PsiDocumentManager.getInstance(project).commitDocument(document)

                    // 只格式化修改的部分
                    val modifiedEndOffset = startOffset + adjustedContent.length
                    reformatModifiedSection(psiFile, startOffset, modifiedEndOffset)
                }

                return EditResult.Success("代码修改成功", startOffset, startOffset + adjustedContent.length)
            }
            is MatchResult.Failure -> {
                return EditResult.Failure(matchResult.reason)
            }
        }
    }

    /**
     * 模糊匹配算法
     * <p>
     * 忽略前导空白字符，逐行比较内容
     */
    private fun fuzzyMatch(
        fileContent: String,
        oldContent: String
    ): MatchResult {
        val fileLines = fileContent.lines()
        val oldLines = oldContent.lines()

        if (oldLines.isEmpty()) {
            return MatchResult.Failure("搜索内容为空")
        }

        // 提取旧内容的非空行（去除缩进）
        val oldNonEmptyLines = oldLines.filter { it.trim().isNotEmpty() }

        if (oldNonEmptyLines.isEmpty()) {
            return MatchResult.Failure("搜索内容没有有效代码行")
        }

        // 在文件中搜索匹配
        var matchStartLine = -1
        var matchEndLine = -1

        for (i in 0..fileLines.size - oldNonEmptyLines.size) {
            var allMatch = true
            for (j in oldNonEmptyLines.indices) {
                val fileLine = fileLines[i + j].trim()
                val oldLine = oldNonEmptyLines[j].trim()
                if (fileLine != oldLine) {
                    allMatch = false
                    break
                }
            }

            if (allMatch) {
                matchStartLine = i
                matchEndLine = i + oldLines.size - 1
                break
            }
        }

        if (matchStartLine == -1) {
            return MatchResult.Failure("未找到匹配的内容（已尝试模糊匹配）")
        }

        // 计算实际的起始和结束偏移量
        var startOffset = 0
        for (i in 0 until matchStartLine) {
            startOffset += fileLines[i].length + 1 // +1 for newline
        }

        var endOffset = startOffset
        for (i in matchStartLine..matchEndLine) {
            endOffset += fileLines[i].length + 1
        }
        endOffset -= 1 // Remove last newline

        return MatchResult.Success(startOffset, endOffset)
    }

    /**
     * 调整新内容的缩进
     * <p>
     * 根据目标位置的缩进，调整新内容的缩进
     */
    private fun adjustIndentation(
        fileContent: String,
        newContent: String,
        targetOffset: Int
    ): String {
        // 找到目标位置所在行的缩进
        var lineStart = targetOffset
        while (lineStart > 0 && fileContent[lineStart - 1] != '\n') {
            lineStart--
        }

        val targetIndent = fileContent.substring(lineStart, targetOffset)
            .takeWhile { it == ' ' || it == '\t' }

        // 计算新内容的基础缩进
        val newLines = newContent.lines()
        val newBaseIndent = newLines.firstOrNull { it.trim().isNotEmpty() }?.let { line ->
            line.takeWhile { it == ' ' || it == '\t' }
        } ?: ""

        // 如果新内容没有缩进，直接返回
        if (newBaseIndent.isEmpty()) {
            return newContent
        }

        // 计算缩进差异
        val indentDiff = if (targetIndent.isNotEmpty()) {
            // 目标有缩进，使用目标缩进
            targetIndent
        } else {
            // 目标无缩进，移除新内容的基础缩进
            ""
        }

        // 调整每一行的缩进
        val adjustedLines = newLines.map { line ->
            if (line.trim().isEmpty()) {
                line
            } else {
                val currentIndent = line.takeWhile { it == ' ' || it == '\t' }
                val relativeIndent = if (currentIndent.length >= newBaseIndent.length) {
                    currentIndent.substring(newBaseIndent.length)
                } else {
                    currentIndent
                }
                indentDiff + relativeIndent + line.trimStart()
            }
        }

        return adjustedLines.joinToString("\n")
    }

    /**
     * 只格式化修改的部分
     */
    private fun reformatModifiedSection(
        psiFile: com.intellij.psi.PsiFile,
        startOffset: Int,
        endOffset: Int
    ) {
        try {
            val codeStyleManager = CodeStyleManager.getInstance(project)
            codeStyleManager.reformatText(psiFile, startOffset, endOffset)
        } catch (e: Exception) {
            logger.warn("格式化修改部分失败: ${e.message}")
        }
    }

    private sealed class MatchResult {
        data class Success(val startOffset: Int, val endOffset: Int) : MatchResult()
        data class Failure(val reason: String) : MatchResult()
    }
}
