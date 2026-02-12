package com.smancode.sman.analysis.vectorization

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.database.VectorStoreService
import com.smancode.sman.analysis.paths.ProjectPaths
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 项目信息向量化服务测试（生产级）
 *
 * 验证细粒度向量化功能：
 * 1. API 入口按每个接口独立存储
 * 2. 数据库实体按每个表独立存储
 * 3. 枚举类按每个枚举独立存储
 * 4. 旧的粗粒度向量被正确删除
 */
@DisplayName("项目信息向量化服务测试")
class ProjectInfoVectorizationServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var vectorStore: VectorStoreService
    private lateinit var service: ProjectInfoVectorizationService
    private val projectKey = "test_project"

    @BeforeEach
    fun setup() {
        val paths = ProjectPaths.forProject(tempDir)
        val config = VectorDatabaseConfig(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(dimension = 1024),
            databasePath = paths.databaseFile.toString(),
            vectorDimension = 1024
        )
        vectorStore = TieredVectorStore(config)
        service = ProjectInfoVectorizationService(
            projectKey = projectKey,
            vectorStore = vectorStore,
            bgeEndpoint = "http://localhost:8000"
        )
    }

    @AfterEach
    fun tearDown() {
        vectorStore.close()
    }

    @Test
    @DisplayName("API 入口细粒度向量化：每个接口独立存储")
    fun testVectorizeApiEntriesIndividually() = runTest {
        // Given: API 入口扫描结果
        val apiEntriesJson = """
            {
              "entries": [
                  "com.example.controller.LoanController",
                  "com.example.controller.UserController",
                  "com.example.controller.PaymentController"
              ],
              "entryCount": 3,
              "entriesByType": {"REST_CONTROLLER": 3},
              "totalMethods": 15,
              "controllers": [
                  "com.example.controller.LoanController",
                  "com.example.controller.UserController",
                  "com.example.controller.PaymentController"
              ],
              "controllerCount": 3,
              "feignClients": [],
              "feignClientCount": 0,
              "listeners": [],
              "listenerCount": 0,
              "scheduledTasks": [],
              "scheduledTaskCount": 0
          }
        """.trimIndent()

        // When: 执行细粒度向量化
        val count = service.vectorizeApiEntriesIndividually(apiEntriesJson)

        // Then: 验证每个接口都有独立向量
        assertEquals(3, count, "应该向量化 3 个 API 入口")

        // 验证每个接口的 ID 格式
        val loanController = vectorStore.get("$projectKey:api_entry:com.example.controller.LoanController")
        assertNotNull(loanController, "应该找到 LoanController 向量")
        assertEquals("LoanController", loanController.title)

        val userController = vectorStore.get("$projectKey:api_entry:com.example.controller.UserController")
        assertNotNull(userController, "应该找到 UserController 向量")

        val paymentController = vectorStore.get("$projectKey:api_entry:com.example.controller.PaymentController")
        assertNotNull(paymentController, "应该找到 PaymentController 向量")

        // 验证旧的粗粒度向量被删除
        val oldVector = vectorStore.get("$projectKey:api_entries")
        assertTrue(oldVector == null || oldVector.id != "$projectKey:api_entries",
            "旧的 api_entries 向量应该被删除或不存在")
    }

    @Test
    @DisplayName("数据库实体细粒度向量化：每个表独立存储")
    fun testVectorizeDbEntitiesIndividually() = runTest {
        // Given: 数据库实体扫描结果
        val dbEntitiesJson = """
            {
              "entities": [
                  "com.example.entity.LoanEntity",
                  "com.example.entity.UserEntity",
                  "com.example.entity.PaymentEntity"
              ],
              "tables": [
                  "loan",
                  "user",
                  "payment"
              ],
              "entityCount": 3,
              "tableCount": 3
          }
        """.trimIndent()

        // When: 执行细粒度向量化
        val count = service.vectorizeDbEntitiesIndividually(dbEntitiesJson)

        // Then: 验证每个实体都有独立向量
        assertEquals(6, count, "应该向量化 3 个实体 + 3 个表 = 6 个向量")

        // 验证实体向量
        val loanEntity = vectorStore.get("$projectKey:db_entity:com.example.entity.LoanEntity")
        assertNotNull(loanEntity, "应该找到 LoanEntity 向量")
        assertEquals("LoanEntity", loanEntity.title)

        // 验证表向量
        val loanTable = vectorStore.get("$projectKey:db_table:loan")
        assertNotNull(loanTable, "应该找到 loan 表向量")
        assertEquals("loan", loanTable.title)

        // 验证旧的粗粒度向量被删除
        val oldVector = vectorStore.get("$projectKey:db_entities")
        assertTrue(oldVector == null || oldVector.id != "$projectKey:db_entities",
            "旧的 db_entities 向量应该被删除")
    }

    @Test
    @DisplayName("枚举类细粒度向量化：每个枚举独立存储")
    fun testVectorizeEnumsIndividually() = runTest {
        // Given: 枚举类扫描结果
        val enumsJson = """
            {
              "enums": [
                  "com.example.enums.LoanStatus",
                  "com.example.enums.PaymentStatus",
                  "com.example.enums.UserType"
              ],
              "constants": [
                  "LoanStatus.PENDING",
                  "LoanStatus.APPROVED",
                  "LoanStatus.REJECTED",
                  "PaymentStatus.UNPAID",
                  "PaymentStatus.PAID",
                  "UserType.INDIVIDUAL",
                  "UserType.CORPORATE"
              ],
              "count": 3
          }
        """.trimIndent()

        // When: 执行细粒度向量化
        val count = service.vectorizeEnumsIndividually(enumsJson)

        // Then: 验证每个枚举都有独立向量
        assertEquals(3, count, "应该向量化 3 个枚举类")

        // 验证枚举向量
        val loanStatus = vectorStore.get("$projectKey:enum:LoanStatus")
        assertNotNull(loanStatus, "应该找到 LoanStatus 枚举向量")
        assertEquals("LoanStatus", loanStatus.title)
        assertTrue(loanStatus.tags.contains("enum"), "应该包含 enum 标签")
        assertTrue(loanStatus.tags.contains("状态"), "应该包含 状态 标签")

        // 验证旧的粗粒度向量被删除
        val oldVector = vectorStore.get("$projectKey:enums")
        assertTrue(oldVector == null || oldVector.id != "$projectKey:enums",
            "旧的 enums 向量应该被删除")
    }

    @Test
    @DisplayName("边界条件测试：空数据不崩溃")
    fun testEdgeCases() = runTest {
        // Given: 空的 API 入口数据
        val emptyJson = """{"entries": [], "controllers": []}"""

        // When: 执行向量化
        val count = service.vectorizeApiEntriesIndividually(emptyJson)

        // Then: 不应该崩溃，返回 0
        assertEquals(0, count, "空数据应该返回 0")
    }

    @Test
    @DisplayName("回归测试：粗粒度向量化仍然正常")
    fun testBackwardCompatibility() = runTest {
        // Given: 粗粒度数据
        val techStackJson = """{"frameworks": ["Spring", "MyBatis"], "languages": ["Kotlin", "Java"]}"""

        // When: 执行粗粒度向量化
        val count = service.vectorizeTechStack(techStackJson)

        // Then: 应该成功
        assertEquals(1, count, "粗粒度向量化应该返回 1")

        // 验证向量存在
        val vector = vectorStore.get("$projectKey:tech_stack")
        assertNotNull(vector, "应该找到 tech_stack 向量")
    }
}
