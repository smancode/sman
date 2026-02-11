package com.smancode.sman.ide.service

import java.io.File

/**
 * 跨平台路径处理工具类
 *
 * 统一路径格式，解决 Windows/Linux/macOS 路径分隔符差异问题
 */
object PathUtil {

    /**
     * 归一化路径：将所有路径分隔符统一为正斜杠 (/)
     *
     * @param path 原始路径
     * @return 统一使用正斜杠的路径
     */
    fun normalize(path: String): String {
        if (path.isEmpty()) return path
        return path.replace("\\", "/")
    }

    /**
     * 归一化路径列表
     *
     * @param paths 原始路径列表
     * @return 归一化后的路径列表
     */
    fun normalizeAll(paths: List<String>): List<String> {
        return paths.map { normalize(it) }
    }

    /**
     * 判断两个路径是否相等（忽略路径分隔符差异）
     *
     * @param path1 路径1
     * @param path2 路径2
     * @return 是否相等
     */
    fun equals(path1: String, path2: String): Boolean {
        return normalize(path1) == normalize(path2)
    }

    /**
     * 检查路径列表中是否包含指定路径（忽略路径分隔符差异）
     *
     * @param paths 路径列表
     * @param target 目标路径
     * @return 是否包含
     */
    fun contains(paths: Collection<String>, target: String): Boolean {
        val normalizedTarget = normalize(target)
        return paths.any { normalize(it) == normalizedTarget }
    }

    /**
     * 过滤路径列表，返回与目标路径匹配的路径
     *
     * @param paths 路径列表
     * @param target 目标路径
     * @return 匹配的路径（如果存在）
     */
    fun findMatch(paths: Collection<String>, target: String): String? {
        val normalizedTarget = normalize(target)
        return paths.firstOrNull { normalize(it) == normalizedTarget }
    }

    /**
     * 检查路径是否以指定前缀开头（忽略路径分隔符差异）
     *
     * @param path 路径
     * @param prefix 前缀
     * @return 是否匹配
     */
    fun startsWith(path: String, prefix: String): Boolean {
        val normalizedPath = normalize(path)
        val normalizedPrefix = normalize(prefix).removeSuffix("/")
        return normalizedPath.startsWith("$normalizedPrefix/")
    }

    /**
     * 计算相对路径（忽略路径分隔符差异）
     *
     * @param absolutePath 绝对路径
     * @param basePath 基础路径
     * @return 相对路径；如果 absolutePath 不在 basePath 下，返回原始 absolutePath
     */
    fun toRelativePath(absolutePath: String, basePath: String): String {
        if (basePath.isEmpty()) return absolutePath

        val normalizedAbsolute = normalize(absolutePath)
        val normalizedBase = normalize(basePath).removeSuffix("/")

        return if (normalizedAbsolute.startsWith("$normalizedBase/")) {
            normalizedAbsolute.removePrefix("$normalizedBase/")
        } else {
            absolutePath
        }
    }

    /**
     * 安全地拼接路径（自动处理路径分隔符）
     *
     * @param base 基础路径
     * @param relative 相对路径
     * @return 拼接后的路径
     */
    fun join(base: String, relative: String): String {
        val normalizedBase = normalize(base).removeSuffix("/")
        val normalizedRelative = normalize(relative).removePrefix("/")
        return "$normalizedBase/$normalizedRelative"
    }

    /**
     * 获取文件扩展名
     *
     * @param path 文件路径
     * @return 扩展名（包含点号），如果没有则返回空字符串
     */
    fun getExtension(path: String): String {
        val normalizedPath = normalize(path)
        val lastDot = normalizedPath.lastIndexOf('.')
        val lastSlash = normalizedPath.lastIndexOf('/')
        return if (lastDot > lastSlash) {
            normalizedPath.substring(lastDot)
        } else {
            ""
        }
    }

    /**
     * 获取文件名（不含路径）
     *
     * @param path 文件路径
     * @return 文件名
     */
    fun getFileName(path: String): String {
        val normalizedPath = normalize(path)
        val lastSlash = normalizedPath.lastIndexOf('/')
        return if (lastSlash >= 0) {
            normalizedPath.substring(lastSlash + 1)
        } else {
            normalizedPath
        }
    }

    /**
     * 获取父目录路径
     *
     * @param path 文件路径
     * @return 父目录路径；如果是根目录则返回空字符串
     */
    fun getParentPath(path: String): String {
        val normalizedPath = normalize(path).removeSuffix("/")
        val lastSlash = normalizedPath.lastIndexOf('/')
        return if (lastSlash > 0) {
            normalizedPath.substring(0, lastSlash)
        } else {
            ""
        }
    }

    /**
     * 将路径转换为相对于项目根目录的路径（与 LocalToolExecutor.toRelativePath 行为一致）
     *
     * @param absolutePath 绝对路径
     * @param basePath 项目根路径
     * @return 相对路径
     */
    fun toProjectRelativePath(absolutePath: String, basePath: String): String {
        if (basePath.isEmpty()) return absolutePath

        val normalizedAbsolute = normalize(absolutePath)
        val normalizedBase = normalize(basePath).removeSuffix("/")

        return if (normalizedAbsolute.startsWith(normalizedBase)) {
            normalizedAbsolute.removePrefix(normalizedBase).removePrefix("/")
        } else {
            absolutePath
        }
    }
}
