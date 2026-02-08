package com.smancode.smanagent.ide.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.smancode.smanagent.ide.component.CodeReferenceHintProvider
import com.smancode.smanagent.ide.service.SmanAgentService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 编辑器选区监听器
 *
 * 监听用户选中文本的变化，在选中代码时显示提示
 */
class CodeSelectionListener(private val project: Project) {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(CodeSelectionListener::class.java)

        /**
         * 为编辑器添加选区监听
         */
        fun setupSelectionListener(editor: Editor, project: Project) {
            logger.debug("setupSelectionListener 被调用: editor={}, project={}",
                editor.virtualFile?.name, project.name)

            val selectionModel = editor.selectionModel

            // 创建选区变化监听器
            val selectionListener = object : com.intellij.openapi.editor.event.SelectionListener {
                override fun selectionChanged(e: com.intellij.openapi.editor.event.SelectionEvent) {
                    logger.debug("selectionChanged 触发: hasSelection={}", selectionModel.hasSelection())

                    // 检查是否有选区
                    if (!selectionModel.hasSelection()) {
                        CodeReferenceHintProvider.hideHint()
                        return
                    }

                    // 检查选中文本长度（太短不显示）
                    val selectedText = selectionModel.selectedText ?: return
                    logger.debug("选中文本长度: {}", selectedText.length)

                    if (selectedText.length < 5) {
                        CodeReferenceHintProvider.hideHint()
                        return
                    }

                    logger.info("显示代码引用提示: 文本长度={}", selectedText.length)

                    // 显示提示
                    CodeReferenceHintProvider.showHint(editor) {
                        // 点击时触发插入代码引用
                        insertCodeReference(editor, project)
                    }
                }
            }

            // 添加监听器（使用 project 作为 Disposable parent）
            editor.selectionModel.addSelectionListener(selectionListener, project)
            logger.info("SelectionListener 已添加到编辑器: {}, file={}",
                editor.virtualFile?.name,
                editor.virtualFile?.path)
        }

        /**
         * 插入代码引用
         */
        private fun insertCodeReference(editor: Editor, project: Project) {
            val selectionModel = editor.selectionModel
            val selectedText = selectionModel.selectedText

            if (selectedText.isNullOrBlank()) {
                return
            }

            // 获取文件信息
            val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
                ?: return

            // 获取行号范围
            val document = editor.document
            val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
            val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

            // 获取相对路径
            val basePath = project.basePath ?: ""
            val absolutePath = virtualFile.path
            val relativePath = com.smancode.smanagent.ide.service.PathUtil.toProjectRelativePath(absolutePath, basePath)
            val fileName = virtualFile.name

            // 创建代码引用
            val codeReference = com.smancode.smanagent.ide.components.CodeReference(
                filePath = relativePath,
                fileName = fileName,
                startLine = startLine,
                endLine = endLine,
                codeContent = selectedText
            )

            // 隐藏提示
            CodeReferenceHintProvider.hideHint()

            // 通知插入
            val smanAgentService = SmanAgentService.getInstance(project)
            smanAgentService.notifyInsertCodeReference(codeReference)
        }
    }
}
