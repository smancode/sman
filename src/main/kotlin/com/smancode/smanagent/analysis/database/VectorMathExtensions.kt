package com.smancode.smanagent.analysis.database

import kotlin.math.sqrt

/**
 * 向量数学运算扩展函数
 */

/**
 * 计算余弦相似度
 *
 * @param other 另一个向量
 * @return 余弦相似度值（0-1），如果向量维度不匹配或为零向量则返回 0f
 */
fun FloatArray.cosineSimilarity(other: FloatArray?): Float {
    if (other == null || this.size != other.size) {
        return 0f
    }

    var dotProduct = 0f
    var norm1 = 0f
    var norm2 = 0f

    for (i in this.indices) {
        dotProduct += this[i] * other[i]
        norm1 += this[i] * this[i]
        norm2 += other[i] * other[i]
    }

    return if (norm1 == 0f || norm2 == 0f) {
        0f
    } else {
        dotProduct / (sqrt(norm1) * sqrt(norm2))
    }
}
