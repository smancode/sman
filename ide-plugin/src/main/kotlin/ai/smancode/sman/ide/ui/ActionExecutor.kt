package ai.smancode.sman.ide.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class ActionExecutor(private val project: Project) {
    fun execute(actions: List<Map<String, Any>>) {
        actions.forEach { action ->
            val command = action["Command"] as? String ?: action["action"] as? String
            val target = action["Target"] as? String ?: action["target"] as? String
            val value = action["Value"] ?: action["value"]
            
            when (command?.lowercase()) {
                "open" -> openFile(target)
                "type" -> typeText(target, value?.toString())
                "click" -> clickElement(target)
                else -> println("Unknown action: $command")
            }
        }
    }
    
    private fun openFile(path: String?) {
        if (path == null) return
        val file = VfsUtil.findFileByIoFile(java.io.File(path), false)
        if (file != null) {
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
    
    private fun typeText(target: String?, text: String?) {
        if (target == null || text == null) return
        val editor = getCurrentEditor() ?: return
        
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.caretModel.offset, text)
        }
    }
    
    private fun clickElement(target: String?) {
        // TODO: 实现元素点击逻辑
    }
    
    private fun getCurrentEditor(): Editor? {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val selectedTextEditor = fileEditorManager.selectedTextEditor
        return selectedTextEditor
    }
}

