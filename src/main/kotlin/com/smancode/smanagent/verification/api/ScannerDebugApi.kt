package com.smancode.smanagent.verification.api

import com.smancode.smanagent.analysis.common.CommonClassScanner
import com.smancode.smanagent.analysis.external.ExternalApiScanner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Paths

/**
 * 扫描器调试 API
 *
 * 用于测试扫描器是否正常工作
 */
@RestController
@RequestMapping("/api/debug")
open class ScannerDebugApi {

    @GetMapping("/test-common-class-scanner")
    open fun testCommonClassScanner(
        @RequestParam(required = false) projectPath: String?
    ): ResponseEntity<Map<String, Any>> {
        val path = Paths.get(projectPath ?: "/Users/liuchao/projects/autoloop")

        val result = mutableMapOf<String, Any>()
        result["projectPath"] = path.toString()

        // 先检查文件是否存在
        val pathExists = path.toFile().exists()
        result["pathExists"] = pathExists

        try {
            val scanner = CommonClassScanner()
            val commonClasses = scanner.scan(path)

            result["count"] = commonClasses.size
            result["classes"] = commonClasses.take(20).map { cls ->
                mapOf(
                    "className" to cls.className,
                    "qualifiedName" to cls.qualifiedName,
                    "packageName" to cls.packageName,
                    "methodCount" to cls.methods.size
                )
            }

            val fileFilterUtil = commonClasses.find { it.className == "FileFilterUtil" }
            result["foundFileFilterUtil"] = fileFilterUtil != null

        } catch (e: Exception) {
            result["error"] = (e.message ?: "Unknown error")
            result["stackTrace"] = e.stackTraceToString()
        }

        return ResponseEntity.ok(result)
    }

    @GetMapping("/test-external-api-scanner")
    open fun testExternalApiScanner(
        @RequestParam(required = false) projectPath: String?
    ): ResponseEntity<Map<String, Any>> {
        val path = Paths.get(projectPath ?: "/Users/liuchao/projects/autoloop")

        val result = mutableMapOf<String, Any>()
        result["projectPath"] = path.toString()

        try {
            val scanner = ExternalApiScanner()
            val apis = scanner.scan(path)

            result["count"] = apis.size
            result["apis"] = apis.take(20).map { api ->
                mapOf(
                    "apiName" to api.apiName,
                    "apiType" to api.apiType.name,
                    "methodCount" to api.methods.size,
                    "methods" to api.methods.take(5).map { method ->
                        mapOf(
                            "name" to method.name,
                            "httpMethod" to method.httpMethod,
                            "path" to method.path
                        )
                    }
                )
            }

            val restClientApis = apis.filter { it.apiType.name == "REST_CLIENT" }
            result["foundRestClient"] = restClientApis.isNotEmpty()
            result["restClientCount"] = restClientApis.size

        } catch (e: Exception) {
            result["error"] = (e.message ?: "Unknown error")
            result["stackTrace"] = e.stackTraceToString()
        }

        return ResponseEntity.ok(result)
    }
}
