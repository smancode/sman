package com.smancode.sman.analysis.external

import com.smancode.sman.analysis.model.ApiType
import com.smancode.sman.analysis.model.ApiMethodInfo
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ExternalApiScanner 单元测试
 *
 * 测试策略：
 * 1. 白名单准入测试 - 验证正常输入能正确处理
 * 2. 白名单拒绝测试 - 验证非法输入抛出异常
 * 3. 边界值测试 - 验证空列表、单元素等情况
 */
@DisplayName("ExternalApiScanner 测试")
class ExternalApiScannerTest {

    private lateinit var scanner: ExternalApiScanner

    @BeforeEach
    fun setUp() {
        scanner = ExternalApiScanner()
    }

    @Nested
    @DisplayName("白名单准入测试")
    inner class WhitelistAcceptanceTests {

        @Test
        @DisplayName("扫描包含 Feign 接口的项目 - 返回正确列表")
        fun testScan_包含Feign接口_返回正确列表() = runTest {
            // Given: 模拟包含 @FeignClient 注解的 KtClass
            val mockClass = mockk<KtClass> {
                every { fqName?.asString() } returns "com.example.api.UserApi"
                every { getName() } returns "UserApi"

                // 模拟 @FeignClient 注解
                every { annotationEntries } returns listOf(
                    mockKtAnnotation("@FeignClient(name = \"user-service\", url = \"http://localhost:8080\")")
                )

                // 模拟方法
                every { declarations } returns listOf(
                    mockKtFunction("getUserById", "@GetMapping(\"/users/{id}\")"),
                    mockKtFunction("createUser", "@PostMapping(\"/users\")")
                )
            }

            // When: 执行扫描
            val result = scanner.scanClass(mockClass)

            // Then: 验证结果
            assertEquals(1, result.size)
            val apiInfo = result[0]
            assertEquals("com.example.api.UserApi", apiInfo.qualifiedName)
            assertEquals("UserApi", apiInfo.simpleName)
            assertEquals(ApiType.FEIGN, apiInfo.apiType)
            assertEquals("user-service", apiInfo.serviceName)
            assertEquals("http://localhost:8080", apiInfo.targetUrl)
            assertEquals(2, apiInfo.methods.size)

            // 验证第一个方法
            val method1 = apiInfo.methods[0]
            assertEquals("getUserById", method1.name)
            assertEquals("GET", method1.httpMethod)
            assertEquals("/users/{id}", method1.path)

            // 验证第二个方法
            val method2 = apiInfo.methods[1]
            assertEquals("createUser", method2.name)
            assertEquals("POST", method2.httpMethod)
            assertEquals("/users", method2.path)
        }

        @Test
        @DisplayName("扫描包含 Retrofit 接口的项目 - 返回正确列表")
        fun testScan_包含Retrofit接口_返回正确列表() = runTest {
            // Given: 模拟包含 Retrofit 注解的 KtClass
            val mockClass = mockk<KtClass> {
                every { fqName?.asString() } returns "com.example.api.ProductApi"
                every { getName() } returns "ProductApi"
                every { annotationEntries } returns listOf(
                    mockKtAnnotation("@GET(\"/products/{id}\")")
                )
                every { declarations } returns listOf(
                    mockKtFunction("getProduct", "@GET(\"/products/{id}\")")
                )
            }

            // When: 执行扫描
            val result = scanner.scanClass(mockClass)

            // Then: 验证结果
            assertEquals(1, result.size)
            val apiInfo = result[0]
            assertEquals(ApiType.RETROFIT, apiInfo.apiType)
        }
    }

    @Nested
    @DisplayName("白名单拒绝测试")
    inner class WhitelistRejectionTests {

        @Test
        @DisplayName("ktClass 为 null - 抛出 IllegalArgumentException")
        fun testScanClass_ktClass为Null_抛异常() {
            // When & Then: 必须抛异常
            val exception = assertThrows(IllegalArgumentException::class.java) {
                scanner.scanClass(null)
            }
            assertTrue(exception.message!!.contains("ktClass 不能为 null"))
        }

        @Test
        @DisplayName("ktClass 没有全限定名 - 抛出 IllegalArgumentException")
        fun testScanClass_无全限定名_抛异常() = runTest {
            // Given: 模拟没有 fqName 的 KtClass
            val mockClass = mockk<KtClass> {
                every { fqName } returns null
                every { getName() } returns "TestApi"
            }

            // When & Then: 必须抛异常
            val exception = assertThrows(IllegalArgumentException::class.java) {
                scanner.scanClass(mockClass)
            }
            assertTrue(exception.message!!.contains("缺少全限定名"))
        }
    }

    @Nested
    @DisplayName("边界值测试")
    inner class BoundaryValueTests {

        @Test
        @DisplayName("项目没有外调接口 - 返回空列表")
        fun testScanClass_无外调接口_返回空列表() = runTest {
            // Given: 模拟没有注解的普通类
            val mockClass = mockk<KtClass> {
                every { fqName?.asString() } returns "com.example.NormalClass"
                every { getName() } returns "NormalClass"
                every { annotationEntries } returns emptyList()
                every { declarations } returns emptyList()
            }

            // When: 执行扫描
            val result = scanner.scanClass(mockClass)

            // Then: 返回空列表
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("接口没有方法 - 返回空方法列表")
        fun testScanClass_接口无方法_返回空方法列表() = runTest {
            // Given: 模拟有注解但无方法的类
            val mockClass = mockk<KtClass> {
                every { fqName?.asString() } returns "com.example.EmptyApi"
                every { getName() } returns "EmptyApi"
                every { annotationEntries } returns listOf(
                    mockKtAnnotation("@FeignClient(name = \"empty\")")
                )
                every { declarations } returns emptyList()
            }

            // When: 执行扫描
            val result = scanner.scanClass(mockClass)

            // Then: 返回有 API 信息但方法为空
            assertEquals(1, result.size)
            assertTrue(result[0].methods.isEmpty())
        }
    }

    // ========== Mock 辅助函数 ==========

    private fun mockKtAnnotation(text: String) = mockk<org.jetbrains.kotlin.psi.KtAnnotationEntry> {
        // Kotlin psi 使用的是属性而不是 getter 方法
        every { getText() } returns text
        every { shortName?.asString() } returns when {
            text.contains("FeignClient") -> "FeignClient"
            text.contains("@GetMapping") -> "GetMapping"
            text.contains("@PostMapping") -> "PostMapping"
            text.contains("FeignClient") -> "FeignClient"
            text.contains("@GET") -> "GET"
            text.contains("@POST") -> "POST"
            else -> null
        }
    }

    private fun mockKtFunction(name: String, annotationText: String) = mockk<KtNamedFunction> {
        // name 是属性，需要使用 property() 来 mock
        every { getName() } returns name
        every { annotationEntries } returns listOf(mockKtAnnotation(annotationText))
        every { getTypeReference() } returns mockk {
            every { getText() } returns "Any"
        }
    }
}
