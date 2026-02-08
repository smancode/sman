package com.smancode.smanagent.ide.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.smancode.smanagent.ide.components.CodeReference
import com.smancode.smanagent.ide.service.SmanAgentService
import com.smancode.smanagent.ide.service.PathUtil
import com.intellij.openapi.actionSystem.DataKeys
import javax.swing.JOptionPane

/**
 * 插入代码引用 Action
 *
 * 触发方式：
 * 1. 快捷键 Ctrl+L（或 Cmd+L on macOS）
 * 2. 点击输入框上方的提示按钮
 *
 * 功能：
 * - 获取当前编辑器选中的代码
 * - 提取文件路径、行号范围、代码内容
 * - 将代码引用插入到输入框
 */
class InsertCodeReferenceAction : AnAction() {

    private val logger = Logger.getInstance(InsertCodeReferenceAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: run {
            logger.warn("无法获取 Project")
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            logger.warn("无法获取 Editor（可能不在编辑器中）")
            return
        }

        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: run {
            logger.warn("无法获取 VirtualFile")
            return
        }

        // 获取选中的文本
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrBlank()) {
            logger.warn("未选中任何文本")
            return
        }

        // 获取选区的行号范围
        val selectionModel = editor.selectionModel
        val document = editor.document
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1  // 转为 1-based
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1    // 转为 1-based

        // 获取相对路径
        val basePath = project.basePath ?: ""
        val absolutePath = virtualFile.path
        val relativePath = PathUtil.toProjectRelativePath(absolutePath, basePath)
        val fileName = virtualFile.name

        // 创建代码引用
        val codeReference = CodeReference(
            filePath = relativePath,
            fileName = fileName,
            startLine = startLine,
            endLine = endLine,
            codeContent = selectedText
        )

        logger.info("创建代码引用: ${codeReference.getFullDescription()}")

        // 隐藏提示
        com.smancode.smanagent.ide.component.CodeReferenceHintProvider.hideHint()

        // 获取 SmanAgentService 并通知插入代码引用
        val smanAgentService = SmanAgentService.getInstance(project)
        smanAgentService.notifyInsertCodeReference(codeReference)
    }

    override fun update(e: AnActionEvent) {
        // 只在编辑器中有选中文本时启用
        val project = e.getData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.selectedText?.isNotBlank() == true

        e.presentation.isEnabledAndVisible = project != null && hasSelection
    }
}
