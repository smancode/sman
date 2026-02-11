package com.smancode.sman.model.subtask

import java.time.Instant

/**
 * SubTask 结论
 *
 * 记录 SubTask 的执行结果，包括结论、证据、迭代次数等。
 */
class SubTaskConclusion {

    /**
     * SubTask ID
     */
    var subTaskId: String? = null

    /**
     * 目标对象
     */
    var target: String? = null

    /**
     * 要回答的问题
     */
    var question: String? = null

    /**
     * 结论（LLM 生成）
     */
    var conclusion: String? = null

    /**
     * 支持证据（文件路径、知识 ID 等）
     */
    var evidence: MutableList<String> = mutableListOf()

    /**
     * 实际内部迭代次数
     */
    var internalIterations: Int = 0

    /**
     * 完成时间
     */
    var completedAt: Instant? = null

    constructor()

    constructor(subTaskId: String, target: String?, question: String?) {
        this.subTaskId = subTaskId
        this.target = target
        this.question = question
        this.evidence = mutableListOf()
        this.internalIterations = 0
        this.completedAt = Instant.now()
    }

    /**
     * 判断是否有结论
     */
    fun hasConclusion(): Boolean = !conclusion.isNullOrEmpty()

    /**
     * 判断是否有证据
     */
    fun hasEvidence(): Boolean = evidence.isNotEmpty()

    /**
     * 获取证据数量
     */
    fun getEvidenceCount(): Int = evidence.size

    /**
     * 添加证据
     */
    fun addEvidence(evidenceItem: String?) {
        if (!evidenceItem.isNullOrEmpty()) {
            evidence.add(evidenceItem)
        }
    }
}
