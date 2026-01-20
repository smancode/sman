package com.smancode.smanagent.ide.service

import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Git Commit 操作处理器
 * 使用 IDEA ChangeListManager + ProcessBuilder（跨平台兼容）
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
     * 使用 IDEA ChangeListManager 检查文件状态
     */
    private fun filterChangedFiles(projectPath: String, files: List<String>): List<String> {
        try {
            val changeListManager = ChangeListManager.getInstance(project)

            // 获取所有变更文件的相对路径
            val changedFiles = mutableSetOf<String>()

            // 从 changeListManager 获取所有变更
            for (change in changeListManager.allChanges) {
                val virtualFile = getVirtualFile(change)
                if (virtualFile != null) {
                    val relativePath = PathUtil.toProjectRelativePath(virtualFile.path, projectPath)
                    val normalizedPath = PathUtil.normalize(relativePath)
                    changedFiles.add(normalizedPath)
                    log.debug("【Git Commit】检测到变更文件: {}", normalizedPath)
                }
            }

            // 同时检查 unversioned 文件
            try {
                val defaultChangeList = changeListManager.defaultChangeList
                for (change in defaultChangeList.changes) {
                    if (change.type == Change.Type.NEW) {
                        val virtualFile = getVirtualFile(change)
                        if (virtualFile != null) {
                            val relativePath = PathUtil.toProjectRelativePath(virtualFile.path, projectPath)
                            val normalizedPath = PathUtil.normalize(relativePath)
                            changedFiles.add(normalizedPath)
                            log.debug("【Git Commit】检测到新文件: {}", normalizedPath)
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("【Git Commit】获取 unversioned 文件失败（非致命）", e)
            }

            log.info("【Git Commit】Git 检测到 {} 个变更文件", changedFiles.size)

            // 过滤出有实际变更的文件
            return files.filter { path ->
                val normalizedPath = PathUtil.normalize(path)
                changedFiles.contains(normalizedPath)
            }
        } catch (e: Exception) {
            log.warn("【Git Commit】检查文件变更失败，返回所有文件", e)
            return files
        }
    }

    /**
     * 从 Change 对象获取 VirtualFile
     */
    private fun getVirtualFile(change: Change): VirtualFile? {
        return change.virtualFile
            ?: (change.afterRevision?.file as? VirtualFile)
            ?: (change.beforeRevision?.file as? VirtualFile)
    }

    /**
     * Git add 文件
     */
    private fun gitAddFiles(projectPath: String, files: List<String>) {
        for (file in files) {
            try {
                val result = executeGitCommand(projectPath, "add", file)
                if (result.exitCode != 0) {
                    log.warn("【Git Commit】git add 失败: {}, stderr: {}", file, result.stderr)
                } else {
                    log.debug("【Git Commit】git add: {}", file)
                }
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
            val result = executeGitCommand(projectPath, "commit", "-m", message)
            if (result.exitCode != 0) {
                throw Exception("Git commit 失败: ${result.stderr}")
            }
            log.info("【Git Commit】git commit 成功: {}", message)
        } catch (e: Exception) {
            throw Exception("Commit 失败: ${e.message}", e)
        }
    }

    /**
     * 执行 git 命令（跨平台）
     */
    private fun executeGitCommand(projectPath: String, vararg args: String): CommandResult {
        val gitExecutable = findGitExecutable() ?: throw Exception("找不到 git 可执行文件")

        val command = mutableListOf(gitExecutable)
        command.addAll(args)

        log.debug("【Git Commit】执行命令: {}", command.joinToString(" "))

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(java.io.File(projectPath))

        val process = processBuilder.start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        return CommandResult(exitCode, stdout.trim(), stderr.trim())
    }

    /**
     * 查找 git 可执行文件
     */
    private fun findGitExecutable(): String? {
        val os = System.getProperty("os.name").lowercase()

        // Windows: 尝试常见路径
        if (os.contains("win")) {
            val commonPaths = listOf(
                "C:\\Program Files\\Git\\bin\\git.exe",
                "C:\\Program Files\\Git\\cmd\\git.exe",
                "C:\\Program Files (x86)\\Git\\bin\\git.exe",
                "C:\\Program Files (x86)\\Git\\cmd\\git.exe"
            )

            for (path in commonPaths) {
                if (java.io.File(path).exists()) {
                    return path
                }
            }

            // 尝试从 PATH 环境变量查找
            val pathEnv = System.getenv("PATH")
            if (pathEnv != null) {
                val pathDirs = pathEnv.split(";")
                for (dir in pathDirs) {
                    val gitExe = java.io.File(dir, "git.exe")
                    if (gitExe.exists()) {
                        return gitExe.absolutePath
                    }
                }
            }
        }

        // Linux/macOS: 使用 which 查找
        try {
            val whichCommand = if (os.contains("mac") || os.contains("nix") || os.contains("nux")) "which" else "where"
            val output = executeSimpleCommand(whichCommand, "git")
            if (output != null) {
                return output.trim()
            }
        } catch (e: Exception) {
            log.debug("【Git Commit】使用 which/where 查找 git 失败", e)
        }

        // 默认使用 "git"，希望系统 PATH 中有
        return "git"
    }

    /**
     * 执行简单命令并返回输出
     */
    private fun executeSimpleCommand(vararg command: String): String? {
        return try {
            val processBuilder = ProcessBuilder(*command)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 命令执行结果
     */
    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )
}
