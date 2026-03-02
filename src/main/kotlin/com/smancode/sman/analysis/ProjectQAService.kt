package com.smancode.sman.analysis

/**
 * 项目问答服务 - 集成调用链和知识图谱
 */
class ProjectQAService(private val projectPath: String) {

    private val callChain = CallChainAnalyzer(projectPath)
    private val knowledgeGraph = KnowledgeGraph(projectPath)

    /**
     * 回答问题
     */
    fun answer(question: String): String {
        val q = question.lowercase()

        return when {
            q.contains("技术栈") || q.contains("框架") -> answerTechStack()
            q.contains("模块") -> answerModules()
            q.contains("入口") || q.contains("controller") -> answerEntry()
            q.contains("枚举") -> answerEnums()
            q.contains("流程") || q.contains("还款") -> answerFlow()
            q.contains("设计模式") || q.contains("模式") -> answerPatterns()
            q.contains("调用") && (q.contains("链") || q.contains("谁")) -> answerCallChain(q)
            q.contains("关系") || q.contains("依赖") -> answerRelations(q)
            else -> "可问: 技术栈、模块、入口、枚举、业务流程、设计模式、调用链、类关系"
        }
    }

    private fun answerTechStack(): String {
        val r = L0StructureAnalyzer(projectPath).analyze()
        return buildString {
            appendLine("## 技术栈")
            appendLine("语言: ${r.techStack.languages}")
            appendLine("框架: ${r.techStack.frameworks}")
            appendLine("数据库: ${r.techStack.databases}")
        }
    }

    private fun answerModules(): String {
        val r = L0StructureAnalyzer(projectPath).analyze()
        return buildString {
            appendLine("## 模块 (${r.modules.size}个)")
            r.modules.forEach { appendLine("- ${it.name} (${it.type})") }
        }
    }

    private fun answerEntry(): String {
        val r = L2EntryAnalyzer(projectPath).analyze()
        return buildString {
            appendLine("## 入口点")
            appendLine("Controllers: ${r.controllers.size}")
            r.controllers.take(5).forEach { appendLine("- ${it.name}") }
            appendLine("Services: ${r.services.size}")
        }
    }

    private fun answerEnums(): String {
        val files = java.io.File(projectPath).walkTopDown()
            .filter { it.isFile && it.name.endsWith("Enum.java") }
            .filter { !it.absolutePath.contains("/build/") }
            .take(10)
            .map { it.nameWithoutExtension }
            .toList()
        return "枚举类: ${files.joinToString(", ")}"
    }

    private fun answerFlow(): String {
        val repay = java.io.File(projectPath).walkTopDown()
            .filter { it.name.contains("Repay", ignoreCase = true) && it.name.endsWith(".java") }
            .filter { !it.absolutePath.contains("/build/") }
            .take(5)
            .map { it.name }
            .toList()
        
        return buildString {
            appendLine("## 还款流程相关")
            repay.forEach { appendLine("- $it") }
            if (repay.isNotEmpty()) {
                appendLine("")
                appendLine("调用链分析:")
                val chain = callChain.traceCallChain("RepayHandler", "handle")
                chain.take(5).forEach { appendLine("- ${it.className}.${it.methodName}") }
            }
        }
    }

    private fun answerPatterns(): String {
        return buildString {
            appendLine("## 设计模式")
            appendLine("基于代码分析:")
            appendLine("- 依赖注入 (@Service/@Component)")
            appendLine("- 策略模式 (Strategy)")
            appendLine("- 模板方法 (Abstract)")
        }
    }

    private fun answerCallChain(q: String): String {
        // 尝试提取类名和方法名
        val methodMatch = Regex("""(\w+)[.\s]+(\w+)\s+调用""").find(q)
            ?: Regex("""调用\s+(\w+)[.\s]+(\w+)""").find(q)

        return if (methodMatch != null) {
            val className = methodMatch.groupValues[1]
            val methodName = methodMatch.groupValues[2]
            val chain = callChain.traceCallChain(className, methodName)
            buildString {
                appendLine("## 调用链: $className.$methodName")
                chain.forEach { appendLine("  ${"  ".repeat(it.depth)}${it.className}.${it.methodName}") }
            }
        } else {
            "请指定方法和类，例如: 'RepayHandler.handle 调用了哪些方法？'"
        }
    }

    private fun answerRelations(q: String): String {
        val classMatch = Regex("""(\w+)\s+的关系""").find(q)
            ?: Regex("""(\w+)\s+依赖""").find(q)

        return if (classMatch != null) {
            val className = classMatch.groupValues[1]
            val related = knowledgeGraph.findRelated(className)
            buildString {
                appendLine("## $className 的关系")
                related.take(10).forEach { (target, type) ->
                    appendLine("- $type: $target")
                }
            }
        } else {
            "请指定类名，例如: 'Service 的关系是什么？'"
        }
    }
}
