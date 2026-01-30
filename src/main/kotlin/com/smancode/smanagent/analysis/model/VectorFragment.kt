package com.smancode.smanagent.analysis.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import kotlinx.serialization.json.JsonElement

/**
 * 向量片段
 *
 * @property id 片段 ID（唯一标识）
 * @property title 标题
 * @property content 内容摘要（用于 embedding）
 * @property fullContent 完整内容（用于展示）
 * @property tags 标签（用于过滤）
 * @property metadata 元数据
 * @property vector 向量（可选）
 */
@Serializable
data class VectorFragment(
    val id: String,
    val title: String,
    val content: String,
    val fullContent: String,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    @Contextual
    val vector: FloatArray? = null
) {
    /**
     * 判断是否为特定标签
     */
    fun hasTag(tag: String): Boolean {
        return tags.contains(tag)
    }

    /**
     * 获取元数据值
     */
    fun getMetadata(key: String): String? {
        return metadata[key]
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
