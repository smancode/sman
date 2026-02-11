package com.smancode.sman.ide.util

import java.awt.Color

/**
 * 颜色转换工具类
 */
object ColorUtils {

    /**
     * 将 Color 转换为十六进制字符串
     * @param color 颜色对象
     * @return 十六进制字符串，如 "#FF0000"
     */
    fun toHexString(color: Color): String {
        return "#${color.red.toString(16).padStart(2, '0')}" +
               "${color.green.toString(16).padStart(2, '0')}" +
               "${color.blue.toString(16).padStart(2, '0')}"
    }
}
