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
            appendLine("## 现有知识（已分析的拼图）")
            appendLine(formatExistingPuzzles(context.existingPuzzles))
            appendLine()
            appendLine("## 关键指令（必须遵守）")
            appendLine("**你必须基于现有知识进行深度推理，而不是重新分析基础内容！**")
            appendLine("- 不要重复描述已知的 API 列表")
            appendLine("- 要在现有知识基础上发现新的关联和模式")
            appendLine("- 必须回答：现有知识之间有什么关系？有什么矛盾？有什么新发现？")
            appendLine()
            appendLine("## 底线要求（必须遵守）")
            appendLine("1. **禁止输出目录树**：不要列出文件结构")
            appendLine("2. **禁止浅层描述**：不要只说\"这个模块做了什么\"")
            appendLine("3. **必须深度分析**：提取业务规则、数据关系、调用链、状态机")
            appendLine("4. **必须有洞察**：发现代码中的隐含逻辑和模式")
            appendLine()
            appendLine("## 目标")
            appendLine("请执行完整的知识进化循环：")
            appendLine("1. 观察：分析现有知识，识别空白")
            appendLine("2. 假设：提出本轮的分析目标")
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
            appendLine("""  "hypothesis": "本轮分析目标/假设（1-2句话）",""")
            appendLine("""  "tasks": [""")
            appendLine("""    {"target": "分析目标", "description": "任务描述", "priority": 0.0-1.0}""")
            appendLine("""  ],""")
            appendLine("""  "results": [""")
            appendLine("""    {""")
            appendLine("""      "target": "分析目标",""")
            appendLine("""      "title": "发现的知识点标题",""")
            appendLine("""      "content": "Markdown 格式的深度分析：包含业务规则、数据关系、调用链、状态机等",""")
            appendLine("""      "tags": ["tag1", "tag2"],""")
            appendLine("""      "confidence": 0.0-1.0""")
            appendLine("""    }""")
            appendLine("""  ],""")
            appendLine("""  "evaluation": {""")
            appendLine("""    "hypothesisConfirmed": true/false,""")
            appendLine("""    "newKnowledgeGained": 数量,""")
            appendLine("""    "conflictsFound": ["冲突描述"],""")
            appendLine("""    "qualityScore": 0.0-1.0,""")
            appendLine("""    "lessonsLearned": ["学到的教训"]""")
            appendLine("""  }""")
            appendLine("""}""")
            appendLine("```")
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
