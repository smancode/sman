package com.smancode.sman.domain.puzzle

import java.io.File
import java.security.MessageDigest

/**
 * Checksum 计算器
 *
 * 用于计算文件和目录的 checksum，检测文件变更
 */
class ChecksumCalculator {

    companion object {
        private const val ALGORITHM = "SHA-256"
        private const val PREFIX = "sha256:"
        private const val BUFFER_SIZE = 8192
    }

    /**
     * 计算单个文件的 checksum
     *
     * @param file 要计算的文件
     * @return SHA256 checksum，格式为 "sha256:xxx"
     * @throws IllegalArgumentException 如果文件不存在
     */
    fun calculate(file: File): String {
        require(file.exists()) { "文件不存在: ${file.path}" }

        val digest = MessageDigest.getInstance(ALGORITHM)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return PREFIX + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算多个文件的合并 checksum
     *
     * @param files 文件列表
     * @return 合并的 SHA256 checksum，空列表返回空字符串
     */
    fun calculateMultiple(files: List<File>): String {
        if (files.isEmpty()) return ""

        val digest = MessageDigest.getInstance(ALGORITHM)

        // 按路径排序确保顺序一致
        files.sortedBy { it.path }.forEach { file ->
            if (file.exists()) {
                // 先写入文件路径作为分隔
                digest.update(file.path.toByteArray())
                // 再写入文件内容
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            }
        }

        return PREFIX + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算目录的 checksum
     *
     * @param directory 目录
     * @return 目录下所有文件的合并 checksum
     */
    fun calculateDirectory(directory: File): String {
        if (!directory.exists() || !directory.isDirectory) return ""

        val files = directory.walkTopDown()
            .filter { it.isFile }
            .toList()

        return calculateMultiple(files)
    }

    /**
     * 检查文件是否已变更
     *
     * @param file 要检查的文件
     * @param oldChecksum 旧的 checksum
     * @return true 如果文件已变更或旧 checksum 为空
     */
    fun hasChanged(file: File, oldChecksum: String): Boolean {
        if (oldChecksum.isBlank()) return true
        if (!file.exists()) return true

        val newChecksum = calculate(file)
        return newChecksum != oldChecksum
    }

    /**
     * 检查多个文件是否已变更
     *
     * @param files 文件列表
     * @param oldChecksum 旧的 checksum
     * @return true 如果任一文件已变更
     */
    fun hasChangedMultiple(files: List<File>, oldChecksum: String): Boolean {
        if (oldChecksum.isBlank()) return true
        if (files.isEmpty()) return false

        val newChecksum = calculateMultiple(files)
        return newChecksum != oldChecksum
    }
}
