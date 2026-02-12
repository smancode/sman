package com.smancode.sman.analysis.paths

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * 项目存储路径
 *
 * 统一管理项目分析相关的所有文件路径。
 * 所有路径都基于项目目录下的 .sman 文件夹。
 *
 * @property projectRoot 项目根目录（绝对路径）
 * @property smanDir 项目 .sman 目录
 * @property databaseFile H2 数据库文件
 * @property mdDir Markdown 分析结果目录（根目录，保留兼容）
 * @property reportsDir 项目级分析报告目录（project-structure.md, tech-stack.md 等）
 * @property classesDir 类级分析报告目录（每个类的 *.md 文件）
 * @property astAnalysisDir AST 分析结果目录
 * @property caseSopDir 案例 SOP 目录
 * @property codeVectorizationDir 代码向量化目录
 * @property codeWalkthroughDir 代码走读目录
 * @property cacheDir 缓存目录
 * @property baseDir 基础分析目录
 */
data class ProjectStoragePaths(
    val projectRoot: Path,
    val smanDir: Path,
    val databaseFile: Path,
    val mdDir: Path,
    val reportsDir: Path,
    val classesDir: Path,
    val astAnalysisDir: Path,
    val caseSopDir: Path,
    val codeVectorizationDir: Path,
    val codeWalkthroughDir: Path,
    val cacheDir: Path,
    val baseDir: Path
) {
    /**
     * 确保所有必要目录存在
     */
    fun ensureDirectoriesExist() {
        val directories = listOf(
            smanDir,
            mdDir,
            reportsDir,
            classesDir,
            astAnalysisDir,
            caseSopDir,
            codeVectorizationDir,
            codeWalkthroughDir,
            cacheDir,
            baseDir
        )

        for (dir in directories) {
            if (!dir.exists()) {
                try {
                    Files.createDirectories(dir)
                } catch (e: Exception) {
                    throw IllegalStateException("创建目录失败: $dir", e)
                }
            }
        }
    }

    /**
     * 获取 project_map.json 文件路径（项目特定）
     *
     * 每个项目有自己的 project_map.json，存储在项目的 .sman 目录中
     */
    fun getProjectMapFile(): Path {
        return smanDir.resolve(PROJECT_MAP_FILE)
    }

    /**
     * 获取数据库 JDBC URL
     */
    fun getDatabaseJdbcUrl(): String {
        return "jdbc:h2:$databaseFile;MODE=PostgreSQL;AUTO_SERVER=TRUE"
    }

    private companion object {
        /**
         * project_map.json 文件名
         */
        private const val PROJECT_MAP_FILE = "project_map.json"
    }
}

/**
 * 项目路径工厂
 *
 * 提供静态方法创建项目特定的路径配置
 */
object ProjectPaths {
    private const val SMAN_DIR = ".sman"
    private const val DATABASE_FILE = "analysis"
    private const val MD_DIR = "md"
    private const val REPORTS_DIR = "reports"       // 项目级分析报告
    private const val CLASSES_DIR = "classes"       // 类级分析报告
    private const val AST_ANALYSIS_DIR = "astAnalysis"
    private const val CASE_SOP_DIR = "caseSop"
    private const val CODE_VECTORIZATION_DIR = "codeVectorization"
    private const val CODE_WALKTHROUGH_DIR = "codeWalkthrough"
    private const val CACHE_DIR = "cache"
    private const val BASE_DIR = "base"
    private const val PROJECT_MAP_FILE = "project_map.json"

    /**
     * 为指定项目创建存储路径
     *
     * @param projectRoot 项目根目录（绝对路径）
     * @return 项目存储路径配置
     */
    fun forProject(projectRoot: Path): ProjectStoragePaths {
        val smanDir = projectRoot.resolve(SMAN_DIR)
        val mdDir = smanDir.resolve(MD_DIR)

        return ProjectStoragePaths(
            projectRoot = projectRoot,
            smanDir = smanDir,
            databaseFile = smanDir.resolve("$DATABASE_FILE.mv.db"),
            mdDir = mdDir,
            reportsDir = mdDir.resolve(REPORTS_DIR),
            classesDir = mdDir.resolve(CLASSES_DIR),
            astAnalysisDir = smanDir.resolve(AST_ANALYSIS_DIR),
            caseSopDir = smanDir.resolve(CASE_SOP_DIR),
            codeVectorizationDir = smanDir.resolve(CODE_VECTORIZATION_DIR),
            codeWalkthroughDir = smanDir.resolve(CODE_WALKTHROUGH_DIR),
            cacheDir = smanDir.resolve(CACHE_DIR),
            baseDir = smanDir.resolve(BASE_DIR)
        )
    }

    /**
     * 为指定项目创建存储路径（字符串路径版本）
     *
     * @param projectRoot 项目根目录（绝对路径字符串）
     * @return 项目存储路径配置
     */
    fun forProject(projectRoot: String): ProjectStoragePaths {
        return forProject(Paths.get(projectRoot))
    }

    /**
     * 获取项目的 project_map.json 文件路径
     *
     * @param projectRoot 项目根目录
     * @return project_map.json 文件路径
     */
    fun getProjectMapFile(projectRoot: Path): Path {
        return projectRoot.resolve(SMAN_DIR).resolve(PROJECT_MAP_FILE)
    }

    /**
     * 获取项目的 project_map.json 文件路径（字符串版本）
     *
     * @param projectRoot 项目根目录
     * @return project_map.json 文件路径
     */
    fun getProjectMapFile(projectRoot: String): Path {
        return getProjectMapFile(Paths.get(projectRoot))
    }
}
