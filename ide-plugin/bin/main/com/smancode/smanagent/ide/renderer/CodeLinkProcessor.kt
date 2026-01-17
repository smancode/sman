package com.smancode.smanagent.ide.renderer

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.smancode.smanagent.ide.theme.ThemeColors
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Pattern
import java.awt.Color

/**
 * 代码链接处理器
 *
 * 功能：自动识别 HTML 中的代码引用，转换为可点击的文件链接
 *
 * 支持的格式：
 * - 类名：Abc, Abc.java
 * - 方法：Abc.method, Abc.method()
 * - 方法带参数：Abc.method(d,e), Abc.method(d,e,f)
 * - 哈希语法：Abc#method, Abc#method(), Abc#method(d,e)
 * - 行号：Abc:42, Abc.java:42
 */
object CodeLinkProcessor {

    private val logger = LoggerFactory.getLogger(CodeLinkProcessor::class.java)

    /**
     * 前端相关的类名/方法名黑名单
     * 这些是插件自己的类，不应该被转换为链接
     */
    private val FRONTEND_BLACKLIST = setOf(
        // 前端类名
        "SmanAgentChatPanel",
        "CliInputArea",
        "CliControlBar",
        "TaskProgressBar",
        "WelcomePanel",
        "HistoryPopup",
        "SettingsDialog",
        "StyledMessageRenderer",
        "MarkdownRenderer",
        "CodeLinkProcessor",
        "ThemeColors",
        "LocalToolExecutor",
        "AgentWebSocketClient",
        "SessionInfo",
        // 前端相关方法名
        "sendMessage",
        "appendPartToUI",
        "applyTheme",
        "refreshTheme",
        "setupLinkNavigation",
        "navigateToUrl",
        "navigateToFile",
        "jumpToLine",
        "updateCursorForPosition",
        "processCodeLinks",
        "markdownToHtml",
        "createStyledEditorKit",
        // 工具方法名
        "read_file",
        "find_file",
        "search_files",
        "write_file",
        "apply_change",
        "list_directory",
        "get_file_info",
        "semantic_search",
        "execute_command",
        // 常见的 Kotlin/Java 标准库类（避免误匹配）
        "String",
        "Integer",
        "Long",
        "Double",
        "Float",
        "Boolean",
        "List",
        "Map",
        "Set",
        "Collection",
        "ArrayList",
        "HashMap",
        "HashSet"
    )

    /**
     * 匹配代码引用的多个正则模式
     *
     * 按优先级排序（更具体的模式在前）
     */
    private val PATTERNS = listOf(
        // Abc.method(d,e), Abc.method(d,e,f), Abc#method(d,e)
        Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)(\\.[a-z]+)?([#.])([a-z][a-zA-Z0-9_]*)\\(([^)]*)\\)"),
        // Abc.method, Abc#method
        Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)(\\.[a-z]+)?([#.])([a-z][a-zA-Z0-9_]*)"),
        // Abc:42, Abc.java:42
        Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)(\\.[a-z]+)?:(\\d+)"),
        // Abc.java, Abc
        Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)(\\.[a-z]+)?\\b")
    )

    /**
     * 处理 HTML 中的代码引用，转换为可点击链接
     *
     * @param html 原始 HTML
     * @param project IntelliJ 项目
     * @return 处理后的 HTML
     */
    fun processCodeLinks(html: String, project: Project): String {
        if (html.isBlank()) return html

        try {
            logger.info("=== CodeLinkProcessor 开始 === html长度: {}", html.length)
            var result = html
            val processedLinks = HashSet<String>() // 避免重复处理

            for ((patternIndex, pattern) in PATTERNS.withIndex()) {
                logger.info("→ 使用模式 {}: {}", patternIndex, pattern)
                val matcher = pattern.matcher(result)
                val buffer = StringBuffer()

                var matchCount = 0
                while (matcher.find()) {
                    matchCount++
                    val fullText = matcher.group(0)
                    logger.info("→ 匹配 #{}: fullText='{}'", matchCount, fullText)

                    // 避免重复处理
                    if (processedLinks.contains(fullText)) {
                        logger.info("  已跳过（重复处理）")
                        matcher.appendReplacement(buffer, matcher.group(0))
                        continue
                    }
                    processedLinks.add(fullText)

                    val className = matcher.group(1)       // 类名
                    val extension = if (matcher.groupCount() >= 2) matcher.group(2) else null        // 扩展名 .java
                    val separator = if (matcher.groupCount() >= 3) matcher.group(3) else null        // #, : 或 null
                    val methodName = if (matcher.groupCount() >= 4) matcher.group(4) else null
                    val params = if (matcher.groupCount() >= 5) matcher.group(5) else null
                    val lineNumber = if (matcher.groupCount() >= 4 && separator == ":")
                        matcher.group(4) else null

                    logger.info("  解析: className='{}', extension='{}', separator='{}', methodName='{}', lineNumber='{}'",
                        className, extension, separator, methodName, lineNumber)

                    // 黑名单过滤：跳过前端相关的类名/方法名
                    if (className in FRONTEND_BLACKLIST ||
                        (methodName != null && methodName in FRONTEND_BLACKLIST)) {
                        logger.info("  已跳过（黑名单）")
                        matcher.appendReplacement(buffer, matcher.group(0))
                        continue
                    }

                    // 构建文件名
                    val fileName = if (extension != null) "$className$extension" else className
                    logger.info("  搜索文件: fileName='{}'", fileName)

                    // 搜索文件
                    var matchedFiles = findFilesByName(fileName, project)

                    // 如果没找到且没有扩展名，尝试添加 .java 后缀
                    if (matchedFiles.isEmpty() && extension == null) {
                        val fileNameWithJava = "$fileName.java"
                        logger.info("  未找到，尝试使用 .java 后缀: fileName='{}'", fileNameWithJava)
                        matchedFiles = findFilesByName(fileNameWithJava, project)
                    }

                    logger.info("  搜索结果: 找到 {} 个文件", matchedFiles.size)

                    if (matchedFiles.isEmpty()) {
                        // 没找到文件，保持原样
                        logger.info("  已跳过（未找到文件）")
                        matcher.appendReplacement(buffer, matcher.group(0))
                        continue
                    }

                    val file = matchedFiles.first()
                    val filePath = file.virtualFile.path
                    logger.info("  文件路径: {}", filePath)

                    val lineSuffix = resolveLineSuffix(file, separator, methodName, lineNumber, params)
                    logger.info("  行号后缀: '{}'", lineSuffix)

                    // 构建链接
                    val linkHtml = buildLinkHtml(fullText, filePath, lineSuffix)
                    logger.info("  链接HTML: {}", linkHtml)
                    matcher.appendReplacement(buffer, linkHtml)
                }
                matcher.appendTail(buffer)
                result = buffer.toString()
                logger.info("→ 模式 {} 完成，共匹配 {} 次", patternIndex, matchCount)
            }

            logger.info("=== CodeLinkProcessor 完成 ===")
            return result

        } catch (e: Exception) {
            logger.error("处理代码链接失败", e)
            return html // 出错时返回原 HTML
        }
    }

    /**
     * 解析行号后缀
     *
     * @param file 文件
     * @param separator 分隔符 (#, :, 或 null)
     * @param methodName 方法名
     * @param lineNumber 行号字符串
     * @param params 方法参数
     * @return 行号后缀，如 #L42
     */
    private fun resolveLineSuffix(
        file: PsiFile,
        separator: String?,
        methodName: String?,
        lineNumber: String?,
        params: String?
    ): String {
        return try {
            when {
                // 行号格式：Abc:42
                separator == ":" && lineNumber != null -> {
                    "#L$lineNumber"
                }
                // 方法格式：Abc#method 或 Abc.method
                (separator == "#" || separator == ".") && methodName != null -> {
                    val lineNum = findMethodLineNumber(file, methodName, params)
                    if (lineNum > 0) "#L$lineNum" else ""
                }
                else -> ""
            }
        } catch (e: Exception) {
            logger.debug("解析行号失败", e)
            ""
        }
    }

    /**
     * 在文件中查找方法定义的行号
     *
     * @param file 文件
     * @param methodName 方法名
     * @param params 方法参数（可选）
     * @return 行号（从1开始），找不到返回 -1
     */
    private fun findMethodLineNumber(file: PsiFile, methodName: String, params: String?): Int {
        return try {
            // 遍历文件中的类
            val classes = file.children.flatMap {
                when (it) {
                    is PsiClass -> listOf(it)
                    else -> emptyList()
                }
            }

            for (psiClass in classes) {
                // 查找方法
                val methods = psiClass.allMethods
                for (method in methods) {
                    if (method.name == methodName) {
                        // 找到方法，返回行号
                        return getLineNumber(method, file)
                    }
                }
            }

            -1
        } catch (e: Exception) {
            logger.debug("查找方法失败: file={}, method={}", file.name, methodName, e)
            -1
        }
    }

    /**
     * 获取 PSI 元素的行号
     */
    private fun getLineNumber(element: PsiElement, file: PsiFile): Int {
        return try {
            val document = com.intellij.psi.PsiDocumentManager.getInstance(element.project)
                .getDocument(file)
            if (document != null) {
                val lineNumber = document.getLineNumber(element.textOffset)
                lineNumber + 1 // 转换为从1开始
            } else {
                -1
            }
        } catch (e: Exception) {
            logger.debug("获取行号失败", e)
            -1
        }
    }

    /**
     * 在项目中搜索文件
     */
    private fun findFilesByName(fileName: String, project: Project): List<PsiFile> {
        return try {
            FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project))
                .toList()
        } catch (e: Exception) {
            logger.error("搜索文件失败: fileName={}", fileName, e)
            emptyList()
        }
    }

    /**
     * 构建链接 HTML
     */
    private fun buildLinkHtml(text: String, filePath: String, lineSuffix: String): String {
        // 使用 file:// 协议
        val fileUrl = "file://$filePath$lineSuffix"

        // 使用主题颜色，自动适配深色/浅色主题
        val colors = ThemeColors.getCurrentColors()
        val linkColor = toHexString(colors.codeFunction)  // 使用代码函数高亮色（蓝色）

        // 添加样式：使用主题色 + 无下划线 + 手型光标
        return "<a href=\"$fileUrl\" style=\"text-decoration: none; color: $linkColor; background-color: transparent; cursor: pointer;\">$text</a>"
    }

    /**
     * 将 Color 转换为十六进制字符串
     */
    private fun toHexString(color: Color): String {
        return "#${color.red.toString(16).padStart(2, '0')}${color.green.toString(16).padStart(2, '0')}${color.blue.toString(16).padStart(2, '0')}"
    }
}
