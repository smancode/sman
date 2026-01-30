package com.smancode.smanagent.ide.util

/**
 * 链接协议常量
 */
object LinkProtocols {

    /** PSI 位置协议（用于代码跳转）*/
    const val PSI_LOCATION = "psi_location://"

    /** 文件协议 */
    const val FILE = "file://"

    /** HTTP 协议 */
    const val HTTP = "http://"

    /** HTTPS 协议 */
    const val HTTPS = "https://"

    /**
     * 检查是否为 PSI 位置协议
     */
    fun isPsiLocation(href: String): Boolean = href.startsWith(PSI_LOCATION)

    /**
     * 检查是否为文件协议
     */
    fun isFile(href: String): Boolean = href.startsWith(FILE)

    /**
     * 检查是否为 HTTP/HTTPS 协议
     */
    fun isHttp(href: String): Boolean = href.startsWith(HTTP) || href.startsWith(HTTPS)

    /**
     * 从 PSI 位置协议中提取位置信息
     */
    fun extractPsiLocation(href: String): String? = if (isPsiLocation(href)) {
        href.removePrefix(PSI_LOCATION)
    } else {
        null
    }
}
