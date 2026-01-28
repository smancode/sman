package com.smancode.smanagent.model

import java.time.Instant

/**
 * 领域知识
 *
 * 存储领域知识（业务规则、SOP、代码映射关系等），支持向量相似度搜索。
 *
 * 简化设计：只存储核心字段，content 为纯文本，embedding 为 BGE-M3 向量（Base64 编码）。
 */
class DomainKnowledge {

    /**
     * 主键 ID（UUID）
     */
    var id: String? = null

    /**
     * 项目标识
     */
    var projectKey: String? = null

    /**
     * 知识标题
     */
    var title: String? = null

    /**
     * 知识内容（纯文本）
     */
    var content: String? = null

    /**
     * BGE-M3 向量（Base64 编码）
     */
    var embedding: String? = null

    /**
     * 创建时间
     */
    var createdAt: Instant = Instant.now()

    /**
     * 更新时间
     */
    var updatedAt: Instant = Instant.now()

    constructor() {
        this.createdAt = Instant.now()
        this.updatedAt = Instant.now()
    }

    constructor(id: String, projectKey: String, title: String, content: String?) {
        this.id = id
        this.projectKey = projectKey
        this.title = title
        this.content = content
        this.createdAt = Instant.now()
        this.updatedAt = Instant.now()
    }

    /**
     * 更新时间戳
     */
    fun touch() {
        this.updatedAt = Instant.now()
    }

    /**
     * 判断是否有向量
     */
    fun hasEmbedding(): Boolean = !embedding.isNullOrEmpty()
}
