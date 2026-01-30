package com.smancode.smanagent.analysis.model

import kotlinx.serialization.Serializable

/**
 * 类 AST 信息（精简版，用于缓存）
 *
 * @property className 完整类名
 * @property simpleName 简单类名
 * @property packageName 包名
 * @property methods 方法列表
 * @property fields 字段列表
 */
@Serializable
data class ClassAstInfo(
    val className: String,
    val simpleName: String,
    val packageName: String,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>
) {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    /**
     * 估算内存占用
     */
    fun estimateSize(): Long {
        val methodsSize = methods.sumOf { it.estimateSize() }
        val fieldsSize = fields.sumOf { it.estimateSize() }
        return 1000L + className.length.toLong() + simpleName.length.toLong() + packageName.length.toLong() + methodsSize + fieldsSize
    }
}

/**
 * 方法信息（精简版，不包含方法体）
 *
 * @property name 方法名
 * @property returnType 返回类型
 * @property parameters 参数列表
 * @property annotations 注解列表
 */
@Serializable
data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<String>,
    val annotations: List<String>
) {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    /**
     * 估算内存占用
     */
    fun estimateSize(): Long {
        return 200L + name.length.toLong() + returnType.length +
               parameters.sumOf { it.length.toLong() } +
               annotations.sumOf { it.length.toLong() }
    }
}

/**
 * 字段信息
 *
 * @property name 字段名
 * @property type 字段类型
 * @property annotations 注解列表
 */
@Serializable
data class FieldInfo(
    val name: String,
    val type: String,
    val annotations: List<String>
) {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    /**
     * 估算内存占用
     */
    fun estimateSize(): Long {
        return 150L + name.length.toLong() + type.length.toLong() +
               annotations.sumOf { it.length.toLong() }
    }
}
