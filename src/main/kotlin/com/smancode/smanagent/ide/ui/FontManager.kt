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
     * 创建字体对象（包含回退逻辑）
     */
    private fun createEditorFont(): Font {
        val fontName = editorScheme.editorFontName
        val fontSize = editorScheme.editorFontSize

        logger.debug("创建编辑器字体: name={}, size={}", fontName, fontSize)

        return try {
            Font(fontName, Font.PLAIN, fontSize).also { font ->
                logger.debug("字体创建成功: name={}, family={}, size={}", font.name, font.family, font.size)
            }
        } catch (e: Exception) {
            logger.warn("字体创建失败，使用回退字体: {}", e.message)
            // 使用回退字体
            Font(Font.SANS_SERIF, Font.PLAIN, fontSize)
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
