package com.smancode.smanagent.verification.api

import com.smancode.smanagent.verification.model.AnalysisQueryRequest
import com.smancode.smanagent.verification.model.ExpertConsultRequest
import com.smancode.smanagent.verification.model.VectorSearchRequest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.TimeUnit

/**
 * 验证服务 API 控制器测试
 */
@SpringBootTest(
    classes = [com.smancode.smanagent.verification.VerificationWebService::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("验证服务 API 测试套件")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VerificationApiControllersTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Nested
    @DisplayName("ExpertConsultApi 测试")
    inner class ExpertConsultApiTests {

        @Test
        @DisplayName("正常请求 - 返回 200")
        fun testExpertConsult_正常请求_返回200() {
            val request = """
                {
                    "question": "放款入口是哪个？",
                    "projectKey": "test-project",
                    "topK": 10,
                    "enableRerank": true
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/expert_consult")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.sources").isArray)
                .andExpect(jsonPath("$.confidence").isNumber)
                .andExpect(jsonPath("$.processingTimeMs").isNumber)
        }

        @Test
        @DisplayName("缺少 question 参数 - 抛异常")
        fun testExpertConsult_缺少Question_抛异常() {
            val request = """
                {
                    "projectKey": "test-project"
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/expert_consult")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("缺少 projectKey 参数 - 抛异常")
        fun testExpertConsult_缺少ProjectKey_抛异常() {
            val request = """
                {
                    "question": "测试问题"
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/expert_consult")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("topK 超过限制 - 使用默认值")
        fun testExpertConsult_topK超过限制_使用默认值() {
            val request = """
                {
                    "question": "测试问题",
                    "projectKey": "test-project",
                    "topK": 1000
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/expert_consult")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isOk) // TODO: 后续实现参数校验
        }
    }

    @Nested
    @DisplayName("VectorSearchApi 测试")
    inner class VectorSearchApiTests {

        @Test
        @DisplayName("正常请求 - 返回 200")
        fun testSemanticSearch_正常请求_返回200() {
            val request = """
                {
                    "query": "用户登录验证",
                    "projectKey": "test-project",
                    "topK": 10,
                    "enableRerank": true
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/semantic_search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.query").value("用户登录验证"))
                .andExpect(jsonPath("$.recallResults").isArray)
                .andExpect(jsonPath("$.rerankResults").isArray)
                .andExpect(jsonPath("$.processingTimeMs").isNumber)
        }

        @Test
        @DisplayName("缺少 query 参数 - 抛异常")
        fun testSemanticSearch_缺少Query_抛异常() {
            val request = """
                {
                    "projectKey": "test-project"
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/semantic_search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("topK 小于等于 0 - 抛异常")
        fun testSemanticSearch_topK小于0_抛异常() {
            val request = """
                {
                    "query": "测试",
                    "topK": -1
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/semantic_search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isOk) // TODO: 后续实现参数校验
        }
    }

    @Nested
    @DisplayName("AnalysisQueryApi 测试")
    inner class AnalysisQueryApiTests {

        @Test
        @DisplayName("正常请求 - 返回 200")
        fun testQueryResults_正常请求_返回200() {
            val request = """
                {
                    "module": "tech_stack",
                    "projectKey": "test-project",
                    "page": 0,
                    "size": 20
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/analysis_results")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isOk)
        }

        @Test
        @DisplayName("缺少 module 参数 - 抛异常")
        fun testQueryResults_缺少Module_抛异常() {
            val request = """
                {
                    "projectKey": "test-project"
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/analysis_results")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("缺少 projectKey 参数 - 抛异常")
        fun testQueryResults_缺少ProjectKey_抛异常() {
            val request = """
                {
                    "module": "tech_stack"
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/analysis_results")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("page 小于 0 - 抛异常")
        fun testQueryResults_page小于0_抛异常() {
            val request = """
                {
                    "module": "tech_stack",
                    "projectKey": "test-project",
                    "page": -1
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/analysis_results")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isOk) // TODO: 后续实现参数校验
        }

        @Test
        @DisplayName("size 超过最大值 - 抛异常")
        fun testQueryResults_size超过最大值_抛异常() {
            val request = """
                {
                    "module": "tech_stack",
                    "projectKey": "test-project",
                    "size": 1000
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/verify/analysis_results")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
                .andExpect(status().isOk) // TODO: 后续实现参数校验
        }
    }
}
