package com.smancode.sman.analysis.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件快照
 *
 * @property relativePath 相对路径
 * @property fileName 文件名
 * @property size 文件大小
 * @property lastModified 最后修改时间（时间戳）
 * @property md5 MD5 哈希值
 */
@Serializable
data class FileSnapshot(
    val relativePath: String,
    val fileName: String,
    val size: Long,
    val lastModified: Long,
    val md5: String,
    // 人类可读的最后修改时间
    @EncodeDefault
    val lastModifiedReadable: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastModified))
) {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 变化检测结果
 *
 * @property file 文件路径
 * @property oldSnapshot 旧快照
 * @property newSnapshot 新快照
 */
data class ChangeDetectionResult(
    val file: String,
    val oldSnapshot: FileSnapshot?,
    val newSnapshot: FileSnapshot
) {
    /**
     * 是否变化
     */
    fun hasChanged(): Boolean {
        return if (oldSnapshot == null) {
            true
        } else {
            oldSnapshot.md5 != newSnapshot.md5
        }
    }
}
