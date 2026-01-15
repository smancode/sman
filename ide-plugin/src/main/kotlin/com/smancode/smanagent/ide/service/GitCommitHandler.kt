package com.smancode.smanagent.ide.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Git Commit 操作处理器
 * 使用 Runtime.exec 调用 git 命令
 */
class GitCommitHandler(private val project: com.intellij.openapi.project.Project) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(GitCommitHandler::class.java)
    }

    /**
     * Commit 结果
     */
    sealed class CommitResult {
        data class Success(val fileCount: Int, val commitMessage: String, val files: FileChangeSummary) : CommitResult()
        data class NoChanges(val message: String) : CommitResult()
        data class Error(val message: String) : CommitResult()
    }

    /**
     * 文件变更摘要
     */
    data class FileChangeSummary(
        val addFiles: List<String>,
        val modifyFiles: List<String>,
        val deleteFiles: List<String>
    )

    /**
     * 执行 Git commit
     */
    fun executeCommit(
        commitMessage: String,
        addFiles: List<String>,
        modifyFiles: List<String>,
        deleteFiles: List<String>,
        onResult: (CommitResult) -> Unit
    ) {
        try {
            log.info("【Git Commit】开始执行: message={}, add={}, modify={}, delete={}",
                    commitMessage, addFiles.size, modifyFiles.size, deleteFiles.size)

            val projectBasePath = project.basePath
            if (projectBasePath == null) {
                log.warn("【Git Commit】项目路径为空")
                onResult(CommitResult.Error("项目路径为空"))
                return
            }

            // 1. 检查是否是 Git 仓库
            if (!isGitRepository(projectBasePath)) {
                log.warn("【Git Commit】不是 Git 仓库")
                onResult(CommitResult.Error("请先初始化 Git 仓库"))
                return
            }

            // 2. 合并所有文件
            val allFiles = addFiles + modifyFiles + deleteFiles

            // 3. 过滤有实际变更的文件
            val filesToCommit = filterChangedFiles(projectBasePath, allFiles)

            if (filesToCommit.isEmpty()) {
                log.info("【Git Commit】没有需要提交的文件")
                onResult(CommitResult.NoChanges("没有需要提交的文件"))
                return
            }

            log.info("【Git Commit】过滤后需要提交的文件数: {}", filesToCommit.size)

            // 4. Git add
            gitAddFiles(projectBasePath, filesToCommit)

            // 5. Git commit
            gitCommit(projectBasePath, commitMessage)

            // 6. 构建变更摘要
            val summary = FileChangeSummary(
                addFiles = addFiles.filter { filesToCommit.contains(it) },
                modifyFiles = modifyFiles.filter { filesToCommit.contains(it) },
                deleteFiles = deleteFiles.filter { filesToCommit.contains(it) }
            )

            log.info("【Git Commit】提交成功: fileCount={}", filesToCommit.size)
            onResult(CommitResult.Success(filesToCommit.size, commitMessage, summary))

        } catch (e: Exception) {
            log.error("【Git Commit】执行失败", e)
            onResult(CommitResult.Error(e.message ?: "提交失败"))
        }
    }

    /**
     * 检查是否是 Git 仓库
     */
    private fun isGitRepository(projectPath: String): Boolean {
        val gitDir = java.io.File(projectPath, ".git")
        return gitDir.exists()
    }

    /**
     * 过滤有实际变更的文件
     * 使用 git status --porcelain 检查
     */
    private fun filterChangedFiles(projectPath: String, files: List<String>): List<String> {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("git", "status", "--porcelain"),
                null,
                java.io.File(projectPath)
            )
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val changedFiles = output.lines()
                .mapNotNull { line ->
                    // git status --porcelain 输出格式: XY filename
                    if (line.length > 3) line.substring(3) else null
                }
                .toSet()

            return files.filter { path ->
                // 检查文件或其父目录是否在变更列表中
                changedFiles.contains(path) || changedFiles.any { it.startsWith("$path/") }
            }
        } catch (e: Exception) {
            log.warn("【Git Commit】检查文件变更失败，返回所有文件", e)
            return files
        }
    }

    /**
     * Git add 文件
     */
    private fun gitAddFiles(projectPath: String, files: List<String>) {
        for (file in files) {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("git", "add", file),
                    null,
                    java.io.File(projectPath)
                )
                process.waitFor()
                log.debug("【Git Commit】git add: {}", file)
            } catch (e: Exception) {
                log.warn("【Git Commit】git add 失败: {}", file, e)
            }
        }
    }

    /**
     * Git commit
     */
    private fun gitCommit(projectPath: String, message: String) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("git", "commit", "-m", message),
                null,
                java.io.File(projectPath)
            )
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                log.info("【Git Commit】git commit 成功: {}", message)
            } else {
                val error = process.errorStream.bufferedReader().use { it.readText() }
                throw Exception("Git commit 失败: $error")
            }
        } catch (e: Exception) {
            throw Exception("Commit 失败: ${e.message}", e)
        }
    }
}
