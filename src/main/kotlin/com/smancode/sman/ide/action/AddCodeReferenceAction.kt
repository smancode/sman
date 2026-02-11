package com.smancode.sman.ide.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.smancode.sman.ide.components.CodeReference
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.ide.service.PathUtil

/**
 * 右键菜单：添加代码引用到 Sman
 *
 * 触发方式：编辑器中右键 → "添加到 Sman"
 *
 * 功能：
 * - 获取当前编辑器选中的代码
 * - 提取文件路径、行号范围、代码内容
 * - 将代码引用插入到 Sman 输入框
 */
class AddCodeReferenceAction : AnAction() {

    private val logger = Logger.getInstance(AddCodeReferenceAction::class.java)

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

        logger.info("通过右键菜单创建代码引用: ${codeReference.getFullDescription()}")

        // 隐藏可能存在的浮动提示
        com.smancode.sman.ide.component.CodeReferenceHintProvider.hideHint()

        // 获取 SmanService 并通知插入代码引用
        val smanService = SmanService.getInstance(project)
        smanService.notifyInsertCodeReference(codeReference)
    }

    override fun update(e: AnActionEvent) {
        // 只在编辑器中有选中文本时启用
        val project = e.getData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.selectedText?.isNotBlank() == true

        e.presentation.isEnabledAndVisible = project != null && hasSelection
    }
}
