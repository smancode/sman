package com.smancode.smanagent.ide.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Font

/**
 * 字体管理器 - 统一管理插件的所有字体设置
 *
 * 所有组件都应该从这里获取字体，确保字体大小一致
 */
object FontManager {

    private val logger: Logger = LoggerFactory.getLogger(FontManager::class.java)
    private val editorScheme = EditorColorsManager.getInstance().globalScheme

    // 缓存实际创建的字体对象
    private var cachedEditorFont: Font? = null

    init {
        logger.debug("FontManager 初始化: editorFontName={}, editorFontSize={}",
            editorScheme.editorFontName, editorScheme.editorFontSize)
    }

    /**
     * 获取编辑器字体
     * 返回实际创建的字体对象（包含回退后的实际字体名称）
     */
    fun getEditorFont(): Font {
        return cachedEditorFont ?: createEditorFont().also {
            cachedEditorFont = it
        }
    }

    /**
     * 创建字体对象（包含中文字体回退逻辑）
     */
    private fun createEditorFont(): Font {
        val fontName = editorScheme.editorFontName
        val fontSize = editorScheme.editorFontSize

        logger.debug("创建编辑器字体: name={}, size={}", fontName, fontSize)

        return try {
            val font = Font(fontName, Font.PLAIN, fontSize)
            logger.debug("字体创建成功: name={}, family={}, size={}", font.name, font.family, font.size)

            // 检查字体是否支持中文，如果不支持则使用回退字体
            if (!supportsChinese(font)) {
                logger.warn("字体 {} 不支持中文，尝试使用回退字体", font.name)
                return createChineseFallbackFont(fontSize)
            }

            font
        } catch (e: Exception) {
            logger.warn("字体创建失败，使用回退字体: {}", e.message)
            createChineseFallbackFont(fontSize)
        }
    }

    /**
     * 创建支持中文的回退字体
     * 按平台优先级选择字体
     */
    private fun createChineseFallbackFont(size: Int): Font {
        val osName = System.getProperty("os.name", "").lowercase()
        val fallbackFonts = when {
            osName.contains("win") -> {
                // Windows 平台优先使用微软雅黑或宋体
                listOf("Microsoft YaHei", "SimSun", "Dialog")
            }
            osName.contains("mac") || osName.contains("darwin") -> {
                // macOS 平台优先使用苹方或黑体
                listOf("PingFang SC", "STHeiti", "Dialog")
            }
            else -> {
                // Linux 平台优先使用文泉驿正黑
                listOf("WenQuanYi Zen Hei", "Dialog")
            }
        }

        logger.debug("尝试中文字体回退: os={}, candidates={}", osName, fallbackFonts)

        for (fontName in fallbackFonts) {
            try {
                val font = Font(fontName, Font.PLAIN, size)
                if (supportsChinese(font)) {
                    logger.info("使用中文字体回退: {} (支持中文)", fontName)
                    return font
                }
                logger.debug("字体 {} 不支持中文，继续尝试下一个", fontName)
            } catch (e: Exception) {
                logger.debug("字体 {} 创建失败: {}", fontName, e.message)
                continue
            }
        }

        // 最后的兜底：使用系统默认字体
        logger.warn("所有中文字体回退失败，使用系统默认字体")
        return Font(Font.SANS_SERIF, Font.PLAIN, size)
    }

    /**
     * 检查字体是否支持中文
     */
    private fun supportsChinese(font: Font): Boolean {
        return try {
            // 使用字体的 canDisplay 方法检查是否能显示中文字符
            val testChar = '中'
            val canDisplay = font.canDisplay(testChar.code)

            // Dialog 是 Java 的回退字体，通常不支持所有字符
            val isGenericFont = font.name.equals("Dialog", ignoreCase = true) ||
                               font.name.equals("SansSerif", ignoreCase = true)

            canDisplay && !isGenericFont
        } catch (e: Exception) {
            logger.debug("检查中文支持时出错: {}", e.message)
            false
        }
    }

    /**
     * 获取编辑器字体名称（期望的名称）
     */
    fun getEditorFontName(): String {
        return editorScheme.editorFontName
    }

    /**
     * 获取编辑器字体大小
     */
    fun getEditorFontSize(): Int {
        return editorScheme.editorFontSize
    }

    /**
     * 获取编辑器字体家族（用于 CSS）
     * 重要：返回实际字体对象的名称，而不是期望的名称
     * 这样 CSS 会使用与 JTextPane 相同的字体
     */
    fun getEditorFontFamily(): String {
        return getEditorFont().name
    }

    /**
     * 获取等宽字体（用于代码显示）
     */
    fun getMonospacedFont(): Font {
        val fontSize = editorScheme.editorFontSize
        return try {
            // 尝试使用编辑器字体作为等宽字体
            Font(editorScheme.editorFontName, Font.PLAIN, fontSize)
        } catch (e: Exception) {
            logger.warn("等宽字体创建失败，使用回退字体: {}", e.message)
            Font(Font.MONOSPACED, Font.PLAIN, fontSize)
        }
    }
}
