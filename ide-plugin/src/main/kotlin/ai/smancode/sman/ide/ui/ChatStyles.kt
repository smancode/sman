package ai.smancode.sman.ide.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.text.html.StyleSheet
import java.awt.Color

object ChatStyles {
    
    // 获取当前颜色的 Hex 字符串
    fun colorToHex(c: Color): String {
        return "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }

    fun createStyleSheet(): StyleSheet {
        // 1. 获取 UI 字体 (用于正文)
        val labelFont = UIUtil.getLabelFont()
        val bodyFontSize = labelFont.size.coerceAtLeast(10)
        
        // 2. 获取编辑器字体 (用于代码)
        val editorScheme = EditorColorsManager.getInstance().globalScheme
        val editorFontName = editorScheme.editorFontName
        val editorFontSize = editorScheme.editorFontSize.coerceAtLeast(10)
        
        val textColorHex = colorToHex(ChatColors.textPrimary)
        val codeBgHex = colorToHex(ChatColors.codeBackground)
        val inlineCodeHex = colorToHex(ChatColors.inlineCode)
        val dividerHex = colorToHex(ChatColors.divider)
        // 表格边框色使用次级文本颜色，以达到"灰白色/看得清"的效果
        val tableBorderHex = colorToHex(ChatColors.textSecondary)
        val surfaceHex = colorToHex(ChatColors.surface)
        // 链接颜色设置为舒适的橙色
        val linkColorHex = colorToHex(ChatColors.linkColor)

        return StyleSheet().apply {
            // 全局样式
            // 注意：Swing HTML 引擎只支持 HTML 3.2 和 CSS Level 1
            // 必须避免使用现代 CSS 属性，否则可能导致 Windows 下解析崩溃
            // 关键修改：使用 pt 单位，确保与 Java Font.getSize() (Points) 一致
            addRule("""
                body { 
                    font-family: "${labelFont.family}", sans-serif; 
                    font-size: 100%;
                    color: $textColorHex;
                    margin-top: 0;
                    margin-bottom: 0;
                    margin-left: 0;
                    margin-right: 0;
                    word-wrap: break-word;
                    line-height: 1.4;
                }
                
                p {
                    margin-top: 4px;
                    margin-bottom: 4px;
                }
                
                ul, ol {
                    margin-top: 4px;
                    margin-bottom: 4px;
                    margin-left: 15px; 
                }
                
                li {
                    margin-top: 2px;
                    margin-bottom: 2px;
                }
                
                li p {
                    margin-top: 0;
                    margin-bottom: 0;
                }
            """.trimIndent())
            
            // 强制重置标题样式
            // Swing 默认的 h1/h2 样式非常巨大，这里将其重置为 100% (与正文一致)
            // 仅通过加粗来区分，符合"层级差异不要太大"的要求
            addRule("h1, h2, h3, h4, h5, h6 { font-size: 100%; font-weight: bold; margin-top: 10px; margin-bottom: 5px; }")
            
            addRule("a { color: $linkColorHex; text-decoration: none; }")

            // 表格样式
            // 外框稍粗 (2px)，内线细 (1px)，颜色为灰白色 (tableBorderHex)
            addRule("""
                table {
                    border: 2px solid $tableBorderHex;
                    width: 100%;
                    border-spacing: 0;
                    border-collapse: collapse;
                    margin-top: 8px;
                    margin-bottom: 8px;
                    font-size: 100%;
                }
            """.trimIndent())

            addRule("""
                th {
                    border: 1px solid $tableBorderHex;
                    padding: 6px;
                    font-weight: bold;
                    background-color: $surfaceHex;
                    text-align: left;
                }
            """.trimIndent())

            addRule("""
                td {
                    border: 1px solid $tableBorderHex;
                    padding: 6px;
                    font-weight: bold;
                }
            """.trimIndent())

            // 行内代码样式
            // 关键修改：使用 100% 字体大小以与周围文本保持一致
            // 之前使用 px/pt 导致与继承的 UI 字体大小不匹配
            addRule("""
                code { 
                    background-color: transparent;
                    font-family: "$editorFontName", Monospaced;
                    font-size: 100%;
                    color: $inlineCodeHex;
                }
            """.trimIndent())
            
            // 代码块样式 (Swing 不支持 border-radius, white-space: pre-wrap 等)
            // 关键修改：使用 100% 字体大小以与周围文本保持一致
            addRule("""
                pre { 
                    background-color: $codeBgHex;
                    padding: 5px; 
                    font-family: "$editorFontName", Monospaced;
                    font-size: 100%;
                    margin-top: 8px;
                    margin-bottom: 8px;
                    border: 1px solid $dividerHex;
                }
            """.trimIndent())
        }
    }
}
