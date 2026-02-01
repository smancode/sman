package com.smancode.smanagent.analysis.model

import kotlinx.serialization.Serializable

/**
 * 外调接口信息
 *
 * 识别项目中的外部 API 调用，包括：
 * - Spring Cloud OpenFeign (@FeignClient)
 * - Retrofit (retrofit2.http.*)
 * - RestTemplate
 * - WebClient
 *
 * @property qualifiedName 类全限定名
 * @property simpleName 类简单名称
 * @property apiType API 类型
 * @property targetUrl 目标 URL（如果配置）
 * @property serviceName 服务名称（针对微服务）
 * @property methods 方法列表
 */
@Serializable
data class ExternalApiInfo(
    val qualifiedName: String,
    val simpleName: String,
    val apiType: ApiType,
    val targetUrl: String? = null,
    val serviceName: String? = null,
    val methods: List<ApiMethodInfo> = emptyList()
)

/**
 * API 类型
 */
@Serializable
enum class ApiType {
    /** Spring Cloud OpenFeign */
    FEIGN,
    /** Retrofit */
    RETROFIT,
    /** Spring RestTemplate */
    REST_TEMPLATE,
    /** Spring WebClient */
    WEB_CLIENT,
    /** 未知类型 */
    UNKNOWN
}

/**
 * API 方法信息
 *
 * @property name 方法名
 * @property httpMethod HTTP 方法 (GET, POST, PUT, DELETE)
 * @property path URL 路径
 * @property returnType 返回类型
 * @property parameters 参数列表
 */
@Serializable
data class ApiMethodInfo(
    val name: String,
    val httpMethod: String,
    val path: String,
    val returnType: String,
    val parameters: List<ApiParameterInfo> = emptyList()
)

/**
 * API 参数信息
 *
 * @property name 参数名
 * @property type 参数类型
 * @property annotation 注解 (@PathVariable, @RequestParam, @RequestBody)
 */
@Serializable
data class ApiParameterInfo(
    val name: String,
    val type: String,
    val annotation: String? = null
)
