package com.smancode.smanagent.ide.renderer

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.util.ColorUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

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
     * <p>
     * 注意：此函数应在 Markdown 渲染**之后**调用，
     * 并跳过 HTML 标签内部的内容，避免破坏标签结构。
     *
     * @param html 渲染后的 HTML
     * @param project IntelliJ 项目
     * @return 处理后的 HTML
     */
    fun processCodeLinks(html: String, project: Project): String {
        if (html.isBlank()) return html

        return try {
            processWithPatterns(html, project)
        } catch (e: Exception) {
            logger.error("处理代码链接失败", e)
            html // 出错时返回原 HTML
        }
    }

    /**
     * 使用所有模式处理 HTML
     */
    private fun processWithPatterns(html: String, project: Project): String {
        var result = html
        val processedLinks = HashSet<String>()

        for (pattern in PATTERNS) {
            result = processWithSinglePattern(result, pattern, project, processedLinks)
        }

        return result
    }

    /**
     * 使用单个模式处理 HTML
     */
    private fun processWithSinglePattern(
        html: String,
        pattern: Pattern,
        project: Project,
        processedLinks: MutableSet<String>
    ): String {
        val matcher = pattern.matcher(html)
        val buffer = StringBuffer()

        while (matcher.find()) {
            val matchStart = matcher.start()
            val info = extractMatchInfo(matcher)

            if (shouldSkipMatch(info, processedLinks, html, matchStart)) {
                matcher.appendReplacement(buffer, matcher.group(0))
                processedLinks.add(info.fullText)
                continue
            }

            processedLinks.add(info.fullText)

            val linkHtml = processSingleMatch(info, project)
            matcher.appendReplacement(buffer, linkHtml)
        }

        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * 处理单个匹配项
     */
    private fun processSingleMatch(info: MatchInfo, project: Project): String {
        logger.debug("匹配到代码引用 - fullText={}, className={}, methodName={}",
            info.fullText, info.className, info.methodName)

        val fileName = buildFileName(info.className, info.extension)
        val matchedFiles = findFilesWithFallback(fileName, info.extension, project)

        val lineSuffix = if (matchedFiles.isNotEmpty()) {
            resolveLineSuffix(matchedFiles.first(), info.separator, info.methodName, info.lineNumber)
        } else {
            ""
        }

        return buildLinkHtml(info.fullText, info.className, info.methodName, info.lineNumber, lineSuffix)
    }

    /**
     * 构建文件名
     */
    private fun buildFileName(className: String, extension: String?): String {
        return if (extension != null) "$className$extension" else className
    }

    /**
     * 查找文件（带回退机制）
     */
    private fun findFilesWithFallback(fileName: String, extension: String?, project: Project): List<PsiFile> {
        var matchedFiles = findFilesByName(fileName, project)

        // 如果没找到且没有扩展名，尝试添加 .java 后缀
        if (matchedFiles.isEmpty() && extension == null) {
            val fileNameWithJava = "$fileName.java"
            matchedFiles = findFilesByName(fileNameWithJava, project)
        }

        return matchedFiles
    }

    /**
     * 检查匹配位置是否在已生成的链接内或 HTML 标签属性内
     * @return true 表示应该跳过此匹配
     */
    private fun isInExistingLinkOrAttribute(html: String, matchStart: Int): Boolean {
        var tagContent = false // 在标签内容中（如 <code>xxx</code> 中的 xxx）

        for (i in (matchStart - 1) downTo 0) {
            if (i < 0) break
            val char = html[i]

            when {
                char == '"' || char == '\'' -> {
                    // 在属性值内（如 href="..."）
                    return true
                }
                char == '>' -> {
                    // 标签结束，进入标签内容
                    tagContent = true
                    break
                }
                char == '<' -> {
                    // 遇到开始标签
                    if (i > 0 && html[i - 1] != '/') {
                        // <xxx> 的情况，不在内容内
                        tagContent = false
                    }
                    // </xxx> 继续向前查找
                }
            }
        }

        // 如果在标签内容中（如 <code>FileFilterUtil</code>），允许生成链接
        // 如果在标签属性中（如 <a href="xxx">），跳过
        return !tagContent
    }

    /**
     * 解析行号后缀
     *
     * @param file 文件
     * @param separator 分隔符 (#, :, 或 null)
     * @param methodName 方法名
     * @param lineNumber 行号字符串
     * @return 行号后缀，如 #L42
     */
    private fun resolveLineSuffix(
        file: PsiFile,
        separator: String?,
        methodName: String?,
        lineNumber: String?
    ): String {
        return try {
            when {
                // 行号格式：Abc:42
                separator == ":" && lineNumber != null -> {
                    "#L$lineNumber"
                }
                // 方法格式：Abc#method 或 Abc.method
                (separator == "#" || separator == ".") && methodName != null -> {
                    val lineNum = findMethodLineNumber(file, methodName)
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
     * @return 行号（从1开始），找不到返回 -1
     */
    private fun findMethodLineNumber(file: PsiFile, methodName: String): Int {
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
     * <p>
     * 注意：这里只用 FilenameIndex 验证类名是否存在（用于生成链接）
     * 实际跳转由 PsiNavigationHelper 用 PSI API 完成（有内置缓存）
     */
    private fun findFilesByName(fileName: String, project: Project): List<PsiFile> {
        return try {
            FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project)).toList()
        } catch (e: Exception) {
            logger.error("搜索文件失败: fileName={}", fileName, e)
            emptyList()
        }
    }

    /**
     * 构建链接 HTML
     * <p>
     * 使用自定义 psi_location:// 协议，由 PsiNavigationHelper 处理跳转
     * 跨平台兼容，避免文件路径问题
     *
     * @param text 显示文本
     * @param className 类名
     * @param methodName 方法名（可选）
     * @param lineNumber 行号字符串（可选）
     * @param lineSuffix 已解析的行号后缀（如 #L42），可选
     * @return HTML 链接
     */
    private fun buildLinkHtml(
        text: String,
        className: String,
        methodName: String?,
        lineNumber: String?,
        lineSuffix: String
    ): String {
        val locationUrl = buildLocationUrl(className, methodName, lineNumber, lineSuffix)
        val linkColor = ColorUtils.toHexString(ThemeColors.getCurrentColors().codeFunction)

        return "<a href=\"$locationUrl\" style=\"text-decoration: none; color: $linkColor; background-color: transparent; cursor: pointer;\">$text</a>"
    }

    /**
     * 构建 PSI 位置 URL
     */
    private fun buildLocationUrl(
        className: String,
        methodName: String?,
        lineNumber: String?,
        lineSuffix: String
    ): String = when {
        methodName != null && lineNumber != null -> "psi_location://$className.$methodName:$lineNumber"
        methodName != null -> "psi_location://$className.$methodName"
        lineNumber != null -> "psi_location://$className:$lineNumber"
        lineSuffix.isNotEmpty() -> {
            val num = lineSuffix.removePrefix("#L")
            "psi_location://$className:$num"
        }
        else -> "psi_location://$className"
    }

    /**
     * 匹配信息数据类
     */
    private data class MatchInfo(
        val fullText: String,
        val className: String,
        val extension: String?,
        val separator: String?,
        val methodName: String?,
        val lineNumber: String?
    )

    /**
     * 从正则匹配提取结构化信息
     */
    private fun extractMatchInfo(matcher: java.util.regex.Matcher): MatchInfo {
        val className = matcher.group(1)
        val extension = if (matcher.groupCount() >= 2) matcher.group(2) else null
        val separator = if (matcher.groupCount() >= 3) matcher.group(3) else null
        val methodName = if (matcher.groupCount() >= 4) matcher.group(4) else null
        val lineNumber = if (matcher.groupCount() >= 4 && separator == ":") matcher.group(4) else null

        return MatchInfo(
            fullText = matcher.group(0),
            className = className,
            extension = extension,
            separator = separator,
            methodName = methodName,
            lineNumber = lineNumber
        )
    }

    /**
     * 检查是否应该跳过此匹配
     */
    private fun shouldSkipMatch(
        info: MatchInfo,
        processedLinks: Set<String>,
        html: String,
        matchStart: Int
    ): Boolean {
        // 检查是否在已生成的链接内或 HTML 标签属性内
        if (isInExistingLinkOrAttribute(html, matchStart)) {
            return true
        }

        // 避免重复处理
        if (info.fullText in processedLinks) {
            return true
        }

        // 黑名单过滤
        if (info.className in FRONTEND_BLACKLIST) {
            return true
        }

        if (info.methodName != null && info.methodName in FRONTEND_BLACKLIST) {
            return true
        }

        return false
    }
}
