package com.smancode.sman.analysis.integration

import com.smancode.sman.analysis.executor.AnalysisTaskExecutor
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.paths.ProjectPaths
import com.smancode.sman.analysis.util.ProjectHashCalculator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 项目分析集成测试
 *
 * 直接测试分析流程，不依赖 IntelliJ 平台
 */
@DisplayName("项目分析集成测试")
class ProjectAnalysisIntegrationTest {

    // 使用 autoloop 项目作为测试目标
    private val projectRoot = Paths.get(System.getProperty("user.home"), "projects", "autoloop")
    private val projectKey = "autoloop"

    @BeforeEach
    fun setUp() {
        println("=== 测试环境 ===")
        println("项目根目录: $projectRoot")
        println("项目存在: ${Files.exists(projectRoot)}")
    }

    @Test
    @DisplayName("验证项目目录存在")
    fun testProjectExists() {
        assertTrue(Files.exists(projectRoot), "项目目录应该存在: $projectRoot")
        assertTrue(Files.isDirectory(projectRoot), "应该是目录: $projectRoot")
    }

    @Test
    @DisplayName("验证 .sman 目录结构")
    fun testSmanDirectoryStructure() {
        val paths = ProjectPaths.forProject(projectRoot)
        println("=== .sman 目录结构 ===")
        println("smanDir: ${paths.smanDir}")
        println("databaseFile: ${paths.databaseFile}")
        println("mdDir: ${paths.mdDir}")
        println("projectMapFile: ${paths.getProjectMapFile()}")

        // 确保目录存在
        paths.ensureDirectoriesExist()

        assertTrue(Files.exists(paths.smanDir), ".sman 目录应该存在")
    }

    @Test
    @DisplayName("注册项目到 ProjectMap")
    fun testRegisterProject() {
        val paths = ProjectPaths.forProject(projectRoot)
        paths.ensureDirectoriesExist()

        // 计算项目 MD5
        val md5 = ProjectHashCalculator.calculateDirectoryHash(projectRoot)
        println("项目 MD5: $md5")

        // 注册项目
        ProjectMapManager.registerProject(projectRoot, projectKey, projectRoot.toString(), md5)

        // 验证注册成功
        val entry = ProjectMapManager.getProjectEntry(projectRoot, projectKey)
        assertNotNull(entry, "项目应该已注册")
        println("项目已注册: $entry")

        // 验证 project_map.json 文件存在
        val mapFile = paths.getProjectMapFile()
        assertTrue(Files.exists(mapFile), "project_map.json 应该存在: $mapFile")
        println("project_map.json 内容:\n${Files.readString(mapFile)}")
    }

    @Test
    @DisplayName("执行项目结构分析")
    @EnabledIf("isAutoloopProjectAvailable")
    fun testExecuteProjectStructureAnalysis() {
        println("=== 开始项目结构分析 ===")

        // 1. 确保目录结构存在
        val paths = ProjectPaths.forProject(projectRoot)
        paths.ensureDirectoriesExist()

        // 2. 注册项目
        val md5 = ProjectHashCalculator.calculateDirectoryHash(projectRoot)
        if (!ProjectMapManager.isProjectRegistered(projectRoot, projectKey)) {
            ProjectMapManager.registerProject(projectRoot, projectKey, projectRoot.toString(), md5)
        }

        // 3. 创建执行器（不依赖 SmanService）
        val executor = TestAnalysisExecutor(projectKey, projectRoot)

        // 4. 执行项目结构分析
        val result = executor.execute(AnalysisType.PROJECT_STRUCTURE)
        println("分析结果: $result")

        assertTrue(result.success, "分析应该成功")
        assertNotNull(result.data, "应该有分析数据")

        // 5. 验证生成的文件
        val mdFile = paths.mdDir.resolve("project-structure.md")
        println("检查文件: $mdFile")
        if (Files.exists(mdFile)) {
            println("项目结构 MD 文件内容:\n${Files.readString(mdFile).take(500)}...")
        }
    }

    @Test
    @DisplayName("执行技术栈分析")
    @EnabledIf("isAutoloopProjectAvailable")
    fun testExecuteTechStackAnalysis() {
        println("=== 开始技术栈分析 ===")

        val paths = ProjectPaths.forProject(projectRoot)
        paths.ensureDirectoriesExist()

        val md5 = ProjectHashCalculator.calculateDirectoryHash(projectRoot)
        if (!ProjectMapManager.isProjectRegistered(projectRoot, projectKey)) {
            ProjectMapManager.registerProject(projectRoot, projectKey, projectRoot.toString(), md5)
        }

        val executor = TestAnalysisExecutor(projectKey, projectRoot)
        val result = executor.execute(AnalysisType.TECH_STACK)

        println("分析结果: $result")
        assertTrue(result.success, "分析应该成功")
    }

    @Test
    @DisplayName("执行 API 入口分析")
    @EnabledIf("isAutoloopProjectAvailable")
    fun testExecuteApiEntriesAnalysis() {
        println("=== 开始 API 入口分析 ===")

        val paths = ProjectPaths.forProject(projectRoot)
        paths.ensureDirectoriesExist()

        val md5 = ProjectHashCalculator.calculateDirectoryHash(projectRoot)
        if (!ProjectMapManager.isProjectRegistered(projectRoot, projectKey)) {
            ProjectMapManager.registerProject(projectRoot, projectKey, projectRoot.toString(), md5)
        }

        val executor = TestAnalysisExecutor(projectKey, projectRoot)
        val result = executor.execute(AnalysisType.API_ENTRIES)

        println("分析结果: $result")
        assertTrue(result.success, "分析应该成功")
    }

    companion object {
        @JvmStatic
        fun isAutoloopProjectAvailable(): Boolean {
            val path = Paths.get(System.getProperty("user.home"), "projects", "autoloop")
            return Files.exists(path) && Files.isDirectory(path)
        }
    }
}

/**
 * 测试用分析执行器
 *
 * 简化版，不依赖 SmanService
 */
class TestAnalysisExecutor(
    private val projectKey: String,
    private val projectRoot: java.nio.file.Path
) {
    data class AnalysisResult(
        val success: Boolean,
        val data: String? = null,
        val error: String? = null
    )

    fun execute(type: AnalysisType): AnalysisResult {
        return try {
            println("执行分析: ${type.key}")

            when (type) {
                AnalysisType.PROJECT_STRUCTURE -> executeProjectStructure()
                AnalysisType.TECH_STACK -> executeTechStack()
                AnalysisType.API_ENTRIES -> executeApiEntries()
                AnalysisType.DB_ENTITIES -> executeDbEntities()
                AnalysisType.ENUMS -> executeEnums()
                AnalysisType.CONFIG_FILES -> executeConfigFiles()
            }

            AnalysisResult(success = true, data = "${type.key} 分析完成")
        } catch (e: Exception) {
            println("分析失败: ${e.message}")
            e.printStackTrace()
            AnalysisResult(success = false, error = e.message)
        }
    }

    private fun executeProjectStructure() {
        println("扫描项目结构: $projectRoot")

        val paths = ProjectPaths.forProject(projectRoot)
        paths.ensureDirectoriesExist()

        // 扫描目录结构
        val structure = StringBuilder()
        structure.appendLine("# 项目结构分析")
        structure.appendLine()
        structure.appendLine("项目: $projectKey")
        structure.appendLine("路径: $projectRoot")
        structure.appendLine()

        Files.walk(projectRoot)
            .filter { Files.isDirectory(it) }
            .filter { !it.toString().contains("/.") }  // 排除隐藏目录
            .filter { !it.toString().contains("build") }
            .filter { !it.toString().contains("target") }
            .limit(100)
            .forEach { dir ->
                val relativePath = projectRoot.relativize(dir).toString()
                if (relativePath.isNotEmpty() && relativePath.count { it == '/' } < 4) {
                    structure.appendLine("- $relativePath/")
                }
            }

        // 保存 MD 文件
        val mdFile = paths.reportsDir.resolve("project-structure.md")
        Files.writeString(mdFile, structure.toString())
        println("已生成: $mdFile")

        // 更新状态
        ProjectMapManager.updateAnalysisStepState(projectRoot, projectKey, AnalysisType.PROJECT_STRUCTURE,
            com.smancode.sman.analysis.model.StepState.COMPLETED)
    }

    private fun executeTechStack() {
        println("识别技术栈: $projectRoot")

        val paths = ProjectPaths.forProject(projectRoot)

        val content = StringBuilder()
        content.appendLine("# 技术栈分析")
        content.appendLine()
        content.appendLine("项目: $projectKey")
        content.appendLine()

        // 检测构建工具
        if (Files.exists(projectRoot.resolve("build.gradle")) || Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            content.appendLine("- 构建工具: Gradle")
        }
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            content.appendLine("- 构建工具: Maven")
        }

        // 检测框架
        Files.walk(projectRoot)
            .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
            .limit(50)
            .forEach { file ->
                val text = Files.readString(file)
                if (text.contains("@SpringBootApplication") || text.contains("@Controller")) {
                    content.appendLine("- 框架: Spring Boot")
                }
                if (text.contains("@Mapper") || text.contains("mybatis")) {
                    content.appendLine("- 框架: MyBatis")
                }
            }

        val mdFile = paths.reportsDir.resolve("tech-stack.md")
        Files.writeString(mdFile, content.toString())
        println("已生成: $mdFile")

        ProjectMapManager.updateAnalysisStepState(projectRoot, projectKey, AnalysisType.TECH_STACK,
            com.smancode.sman.analysis.model.StepState.COMPLETED)
    }

    private fun executeApiEntries() {
        println("扫描 API 入口: $projectRoot")

        val paths = ProjectPaths.forProject(projectRoot)

        val content = StringBuilder()
        content.appendLine("# API 入口分析")
        content.appendLine()
        content.appendLine("项目: $projectKey")
        content.appendLine()

        // 查找 Controller 类
        Files.walk(projectRoot)
            .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
            .filter { !it.toString().contains("/build/") }
            .forEach { file ->
                val text = Files.readString(file)
                val fileName = file.fileName.toString()
                if (text.contains("@RestController") || text.contains("@Controller")) {
                    content.appendLine("## $fileName")
                    content.appendLine("路径: ${projectRoot.relativize(file)}")
                    content.appendLine()

                    // 提取 RequestMapping
                    val requestMappingRegex = Regex("@(Get|Post|Put|Delete|Request)Mapping\\([^)]*\\)")
                    requestMappingRegex.findAll(text).forEach { match ->
                        content.appendLine("- ${match.value}")
                    }
                    content.appendLine()
                }
            }

        val mdFile = paths.reportsDir.resolve("api-entries.md")
        Files.writeString(mdFile, content.toString())
        println("已生成: $mdFile")

        ProjectMapManager.updateAnalysisStepState(projectRoot, projectKey, AnalysisType.API_ENTRIES,
            com.smancode.sman.analysis.model.StepState.COMPLETED)
    }

    private fun executeDbEntities() {
        println("扫描数据库实体: $projectRoot")
        // 简化实现
        ProjectMapManager.updateAnalysisStepState(projectRoot, projectKey, AnalysisType.DB_ENTITIES,
            com.smancode.sman.analysis.model.StepState.COMPLETED)
    }

    private fun executeEnums() {
        println("扫描枚举: $projectRoot")
        // 简化实现
        ProjectMapManager.updateAnalysisStepState(projectRoot, projectKey, AnalysisType.ENUMS,
            com.smancode.sman.analysis.model.StepState.COMPLETED)
    }

    private fun executeConfigFiles() {
        println("扫描配置文件: $projectRoot")
        // 简化实现
        ProjectMapManager.updateAnalysisStepState(projectRoot, projectKey, AnalysisType.CONFIG_FILES,
            com.smancode.sman.analysis.model.StepState.COMPLETED)
    }
}
