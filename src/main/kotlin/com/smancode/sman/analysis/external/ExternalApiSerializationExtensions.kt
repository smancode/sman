package com.smancode.sman.analysis.external

/**
 * LegacyExternalApiInfo 序列化扩展函数
 */

/**
 * 将 LegacyExternalApiInfo 转换为可序列化的 Map
 */
fun LegacyExternalApiInfo.toSerializableMap(): Map<String, Any> = mapOf(
    "qualifiedName" to qualifiedName,
    "simpleName" to apiName,
    "apiType" to apiType.name,
    "targetUrl" to baseUrl,
    "serviceName" to serviceName,
    "methodCount" to methods.size,
    "methods" to methods.map { it.toSerializableMap() }
)

/**
 * 将 LegacyApiMethodInfo 转换为可序列化的 Map
 */
fun LegacyApiMethodInfo.toSerializableMap(): Map<String, String> = mapOf(
    "name" to name,
    "httpMethod" to httpMethod,
    "path" to path,
    "returnType" to returnType
)
