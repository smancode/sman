package com.smancode.smanagent.ide.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Font

/**
 * 字体管理器 - 统一管理插件的所有字体设置
 *
 * 所有组件都应该从这里获取字体，确保字体大小一致
 */
object FontManager {

    private val editorScheme = EditorColorsManager.getInstance().globalScheme

    /**
     * 获取编辑器字体
     */
    fun getEditorFont(): Font {
        return Font(
            editorScheme.editorFontName,
            Font.PLAIN,
            editorScheme.editorFontSize
        )
    }

    /**
     * 获取编辑器字体名称
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
     */
    fun getEditorFontFamily(): String {
        return editorScheme.editorFontName
    }
}
