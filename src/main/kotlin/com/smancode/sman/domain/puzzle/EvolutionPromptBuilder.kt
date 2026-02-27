package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle

/**
 * 知识进化循环的 Prompt 构建器
 *
 * 职责：构建进化循环所需的 Prompt，保持单一职责
 */
object EvolutionPromptBuilder {

    /**
     * 构建进化 Prompt
     *
     * 设计原则：
     * - 只给目标和底线
     * - 不写死具体步骤
     * - 让 LLM 自由发挥
     *
     * 底线要求：
     * - content 必须是深度分析，不能是目录列表
     * - 必须包含业务语义：规则、流程、关系
     * - 不能只输出"看了什么"，要输出"发现了什么"
     */
    fun build(iterationId: String, context: EvolutionContext): String {
        return buildString {
            appendLine("# 知识进化循环")
            appendLine()
            appendLine("你是项目的智能知识管理员，负责持续改进项目理解。")
            appendLine()
            appendLine("## 当前任务")
            appendLine(iterationId)
            appendLine()
            appendLine("## 触发原因")
            appendLine(context.triggerDescription)
            appendLine()

            // 注入真实项目代码（核心改进）
            if (context.projectCode.isNotEmpty()) {
                appendLine("## 项目代码（真实源码）")
                appendLine()
                appendLine("以下是项目的真实源代码，你必须基于这些代码进行分析：")
                appendLine()
                appendLine(formatProjectCode(context.projectCode))
                appendLine()
            }

            appendLine("## 现有知识（已分析的拼图）")
            appendLine(formatExistingPuzzles(context.existingPuzzles))
            appendLine()
            appendLine("## 关键指令（必须遵守）")
            appendLine()
            appendLine("### 必须基于真实代码分析")
            appendLine("你的分析必须基于上面提供的**真实项目代码**，而不是凭空猜测。")
            appendLine("- 必须引用具体的代码文件和代码片段")
            appendLine("- 必须从代码中提取实际的业务逻辑")
            appendLine("- 禁止编造不存在的类、方法或业务规则")
            appendLine()
            appendLine("### 必须引用现有知识")
            appendLine("你的 hypothesis 必须明确引用上面的现有知识，格式如：")
            appendLine("- \"基于已分析的 [API端点]，发现...\"")
            appendLine("- \"结合 [业务规则] 中的规则，推断...\"")
            appendLine("- \"根据 [数据模型] 的实体关系，分析...\"")
            appendLine()
            appendLine("### 深度分析要求")
            appendLine("1. **禁止输出目录树**：不要列出文件结构")
            appendLine("2. **禁止浅层描述**：不要只说\"这个模块做了什么\"")
            appendLine("3. **必须深度分析**：提取业务规则、数据关系、调用链、状态机")
            appendLine("4. **必须有洞察**：发现代码中的隐含逻辑和模式")
            appendLine("5. **hypothesis 必须详细**：至少 200 字，包含具体的推理过程")
            appendLine()
            appendLine("## 目标")
            appendLine("请执行完整的知识进化循环：")
            appendLine("1. 观察：分析现有知识和真实代码，识别空白")
            appendLine("2. 假设：提出本轮的分析目标（**必须引用真实代码**）")
            appendLine("3. 计划：决定需要分析什么")
            appendLine("4. 执行：深入分析目标内容，提取业务语义")
            appendLine("5. 评估：验证分析结果")
            appendLine("6. 合并：输出需要更新的知识")
            appendLine()
            appendLine("## 输出要求")
            appendLine("请用以下 JSON 格式输出你的完整分析过程和结果：")
            appendLine()
            appendLine("""```json""")
            appendLine("""{""")
            appendLine("""  "hypothesis": "本轮分析目标/假设（至少200字，必须引用真实代码，包含推理过程）",""")
            appendLine("""  "tasks": [""")
            appendLine("""    {"target": "分析目标", "description": "任务描述", "priority": 0.0-1.0}""")
            appendLine("""  ],""")
            appendLine("""  "results": [""")
            appendLine("""    {""")
            appendLine("""      "target": "分析目标",""")
            appendLine("""      "title": "发现的知识点标题",""")
            appendLine("""      "content": "Markdown 格式的深度分析：包含业务规则、数据关系、调用链、状态机等（必须基于真实代码）",""")
            appendLine("""      "tags": ["tag1", "tag2"],""")
            appendLine("""      "confidence": 0.0-1.0""")
            appendLine("""    }""")
            appendLine("""  ],""")
            appendLine("""  "evaluation": {""")
            appendLine("""    "hypothesisConfirmed": true/false,""")
            appendLine("""    "newKnowledgeGained": 数量,""")
            appendLine("""    "conflictsFound": ["冲突描述"],""")
            appendLine("""    "qualityScore": 0.0-1.0（0.9+表示优秀，0.7-0.9表示良好，0.5-0.7表示一般，<0.5表示差）,""")
            appendLine("""    "contextUtilization": 0.0-1.0（表示对现有知识和真实代码的利用程度）,""")
            appendLine("""    "lessonsLearned": ["学到的教训"]""")
            appendLine("""  }""")
            appendLine("""}""")
            appendLine("```")
        }
    }

    /**
     * 格式化项目代码
     *
     * 将真实代码注入 Prompt，限制每个文件 2000 字符
     */
    private fun formatProjectCode(codeFiles: Map<String, String>): String {
        if (codeFiles.isEmpty()) {
            return "（无项目代码）"
        }

        return codeFiles.entries.take(10).joinToString("\n\n") { (path, content) ->
            val truncatedContent = if (content.length > 2000) {
                content.take(2000) + "\n... (截断，共 ${content.length} 字符)"
            } else {
                content
            }
            """
### 文件: $path
```
$truncatedContent
```
            """.trimIndent()
        }
    }

    /**
     * 格式化现有拼图
     */
    private fun formatExistingPuzzles(puzzles: List<Puzzle>): String {
        if (puzzles.isEmpty()) {
            return "（暂无现有知识）"
        }

        return puzzles.take(10).joinToString("\n\n") { puzzle ->
            buildString {
                appendLine("## ${puzzle.id}")
                appendLine("- 类型: ${puzzle.type.name}")
                appendLine("- 完整度: ${(puzzle.completeness * 100).toInt()}%")
                appendLine("- 摘要: ${puzzle.content.take(200)}")
            }
        }
    }
}
