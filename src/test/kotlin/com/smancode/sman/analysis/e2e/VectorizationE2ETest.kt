package com.smancode.sman.analysis.e2e

import com.smancode.sman.analysis.base.VectorTestBase
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.parser.MarkdownParser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 向量化流程端到端测试
 *
 * 测试完整的向量化流程：
 * 1. 清理旧向量
 * 2. 解析 MD 文件
 * 3. 生成向量
 * 4. 存储向量
 * 5. 语义搜索验证
 */
@DisplayName("向量化流程 E2E 测试")
class VectorizationE2ETest : VectorTestBase() {

    private lateinit var mdDir: java.nio.file.Path
    private lateinit var parser: MarkdownParser

    override fun createProjectKey(): String = "e2e_test"

    override fun setup() = runTest {
        super.setup()
        mdDir = projectPath.resolve(".sman/md")
        Files.createDirectories(mdDir)
        parser = MarkdownParser()
    }

    @Test
    @DisplayName("E2E-1: 清理旧向量测试")
    fun testCleanupOldVectors() = runTest {
        // Given: 添加包含 .md 后缀的旧向量
        repository.add(createTestFragment("class:A.md", "OldClassA", "旧类A"))
        repository.add(createTestFragment("method:A.md.method1", "OldMethod1", "旧方法1"))
        repository.add(createTestFragment("method:A.md.method2", "OldMethod2", "旧方法2"))

        // 验证旧向量存在
        assertNotNull(repository.get("class:A.md"))
        assertNotNull(repository.get("method:A.md.method1"))

        // When: 清理旧向量
        val deletedCount = repository.cleanupMdVectors()

        // Then: 所有旧向量被删除
        assertEquals(3, deletedCount)
        val deletedVectors = listOf("class:A.md", "method:A.md.method1", "method:A.md.method2")
        deletedVectors.forEach { id ->
            val result = repository.get(id)
            assertTrue(result == null, "向量 $id 应该被删除")
        }
    }

    @Test
    @DisplayName("E2E-2: 向量化完整流程测试")
    fun testVectorizationFlow() = runTest {
        // Given: 创建测试 MD 文件
        val mdFile = createMdFile("RepayHandler.md", """
            # RepayHandler

            ## 类信息
            - **完整签名**: `public class RepayHandler extends BaseHandler`
            - **包名**: `com.autoloop.loan.handler`

            ## 业务描述
            处理贷款还款HTTP请求，是还款业务的REST入口。

            ## 包含功能
            - `repay(request)`: 处理贷款还款请求

            ---

            ## 方法：repay
            ### 业务描述
            处理还款请求，包含正常、提前和逾期还款场景。
        """)

        // When: 清理旧向量并解析
        repository.cleanupMdVectors()
        val vectors = parser.parseAll(mdFile, readMdContent(mdFile))

        // Then: 验证向量数量和 ID 格式
        assertEquals(2, vectors.size) // 1 class + 1 method

        val classVector = vectors[0]
        assertEquals("class:RepayHandler", classVector.id)
        assertEquals("RepayHandler", classVector.title)
        assertTrue(classVector.content.contains("还款"))

        val methodVector = vectors[1]
        assertEquals("method:RepayHandler.repay", methodVector.id)
        assertEquals("repay", methodVector.title)

        // 验证 ID 不包含 .md 后缀
        assertNoMdSuffixInIds(vectors)
    }

    @Test
    @DisplayName("E2E-3: 向量质量验证 - RepayHandler")
    fun testRepayHandlerVectorQuality() = runTest {
        // Given: RepayHandler.md 文件
        val mdFile = createMdFile("RepayHandler.md", """
            # RepayHandler

            ## 业务描述
            处理贷款还款HTTP请求，是还款业务的REST入口。

            ---

            ## 方法：repay
            ### 业务描述
            处理还款请求，包含正常、提前和逾期还款场景。

            ## 方法：queryRepayment
            ### 业务描述
            根据ID查询单条还款记录详情。
        """)

        // When: 解析并存储
        repository.cleanupMdVectors()
        val vectors = parser.parseAll(mdFile, readMdContent(mdFile))

        vectors.forEach { repository.add(it) }

        // Then: 验证 RepayHandler 向量存在且正确
        val classVector = repository.get("class:RepayHandler")
        assertNotNull(classVector, "RepayHandler class 向量应该存在")
        assertEquals("RepayHandler", classVector?.title)
        assertTrue(classVector?.content?.contains("还款") == true, "应该包含还款关键字")

        val repayMethod = repository.get("method:RepayHandler.repay")
        assertNotNull(repayMethod, "RepayHandler.repay 方法向量应该存在")
        assertEquals("repay", repayMethod?.title)

        val queryMethod = repository.get("method:RepayHandler.queryRepayment")
        assertNotNull(queryMethod, "RepayHandler.queryRepayment 方法向量应该存在")
    }

    @Test
    @DisplayName("E2E-4: 语义搜索召回测试")
    fun testSemanticSearchRecall() = runTest {
        // Given: 创建 RepayHandler 向量（带实际的向量值）
        val repayHandlerVector = createTestFragmentWithVector(
            "class:RepayHandler",
            "RepayHandler",
            "处理贷款还款HTTP请求，是还款业务的REST入口"
        )

        val otherClassVector = createTestFragmentWithVector(
            "class:AcctRepaymentMapper",
            "AcctRepaymentMapper",
            "查询还款记录详情，用于对账和查询"
        )

        repository.cleanupMdVectors()
        repository.add(repayHandlerVector)
        repository.add(otherClassVector)

        // When: 使用还款相关查询向量搜索
        val queryVector = FloatArray(1024) { 0.5f }
        val results = repository.search(queryVector, topK = 5)

        // Then: 应该能召回 RepayHandler
        assertTrue(results.isNotEmpty(), "应该有召回结果")
        val hasRepayHandler = results.any { it.id == "class:RepayHandler" }
        assertTrue(hasRepayHandler, "应该召回 RepayHandler")
    }

    @Test
    @DisplayName("E2E-5: 批量向量化多个文件")
    fun testBatchVectorization() = runTest {
        // Given: 3 个 MD 文件
        createMdFile("ClassA.md", "# ClassA\n\n描述：类A")
        createMdFile("ClassB.md", "# ClassB\n\n描述：类B")
        createMdFile("ClassC.md", "# ClassC\n\n描述：类C")

        // When: 批量处理
        repository.cleanupMdVectors()

        val mdFiles = listOf("ClassA.md", "ClassB.md", "ClassC.md")
        val allVectors = mutableListOf<VectorFragment>()

        mdFiles.forEach { fileName ->
            val file = mdDir.resolve(fileName)
            val content = readMdContent(file)
            val vectors = parser.parseAll(file, content)
            vectors.forEach { repository.add(it) }
            allVectors.addAll(vectors)
        }

        // Then: 验证所有向量都被正确存储
        assertEquals(3, allVectors.size)

        listOf("ClassA", "ClassB", "ClassC").forEach { className ->
            val vector = repository.get("class:$className")
            assertNotNull(vector, "$className 向量应该存在")
            assertEquals(className, vector?.title)
        }

        // 验证无 .md 后缀
        assertNoMdSuffixInIds(allVectors)
    }

    // ========== 辅助方法 ==========

    private fun createMdFile(fileName: String, content: String): Path {
        val file = mdDir.resolve(fileName).createFile()
        file.writeText(content)
        return file
    }

    private fun readMdContent(file: Path): String = file.toFile().readText()
}
