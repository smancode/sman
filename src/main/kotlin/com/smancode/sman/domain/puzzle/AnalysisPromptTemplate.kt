package com.smancode.sman.domain.puzzle

/**
 * 分析 Prompt 模板
 *
 * 设计原则：
 * 1. 通用性 - 不预设业务领域或分析类型
 * 2. 开放性 - 让 LLM 自由发现项目特点
 * 3. 结构化 - 要求 LLM 输出结构化结果（标题、标签、置信度）
 */
class AnalysisPromptTemplate {

    companion object {
        /** 系统角色提示 */
        private const val SYSTEM_ROLE = """
You are an expert code analyst. Your task is to analyze the given code and extract DEEP, VALUABLE knowledge about the project.

CRITICAL REQUIREMENTS (MUST FOLLOW):
1. **NEVER output directory trees** - Don't list file structures
2. **NEVER give shallow descriptions** - Don't just say "this module does X"
3. **MUST extract business semantics** - Rules, data relationships, call chains, state machines
4. **MUST provide insights** - Discover implicit patterns and logic in code
5. **MUST be actionable** - Output should help developers understand and work with the code

Focus on:
- Business rules (validation logic, calculation formulas, workflow conditions)
- Data models and relationships (entities, associations, dependencies)
- Call chains and data flow (who calls whom, data transformation)
- State machines (state transitions, valid transitions, trigger conditions)
- Design patterns and architectural decisions

IMPORTANT:
- Do NOT assume any specific domain or business context
- Discover patterns, conventions, and design decisions from the code itself
- Be objective and only report what you can confidently identify
- Output your findings in the specified structured format
"""

        /** 输出格式说明 */
        private const val OUTPUT_FORMAT = """
## Output Format

Please structure your response EXACTLY as follows:

### TITLE
[One-line summary of your main finding]

### TAGS
[Comma-separated tags, e.g., "spring, rest-api, authentication"]

### CONFIDENCE
[A number between 0.0 and 1.0 indicating how confident you are]

### CONTENT
[Your analysis in Markdown format. Feel free to organize it as you see fit.]

---

Example:
### TITLE
Spring Boot REST API with JWT Authentication

### TAGS
spring-boot, rest-api, jwt, authentication, security

### CONFIDENCE
0.85

### CONTENT
# Spring Boot REST API Analysis

## Technology Stack
- Spring Boot 2.7.x
- Spring Security with JWT

## API Pattern
All endpoints follow RESTful conventions...
"""
    }

    /**
     * 构建分析 Prompt
     *
     * @param target 分析目标（文件或目录路径）
     * @param context 分析上下文
     * @return 完整的 Prompt 字符串
     */
    fun build(target: String, context: AnalysisContext): String {
        return buildString {
            // 1. 系统角色
            appendLine(SYSTEM_ROLE)
            appendLine()

            // 2. 分析目标
            appendLine("## Target to Analyze")
            appendLine()
            appendLine("Path: `$target`")
            appendLine()

            // 3. 文件内容（如果有）
            if (context.hasFiles()) {
                appendLine("## Related Files")
                appendLine()
                context.relatedFiles.forEach { (path, content) ->
                    appendLine("### $path")
                    appendLine("```")
                    appendLine(content)
                    appendLine("```")
                    appendLine()
                }
            }

            // 4. 已有知识（如果有）
            if (context.existingPuzzles.isNotEmpty()) {
                appendLine("## Existing Knowledge")
                appendLine()
                appendLine("The following knowledge about this project already exists:")
                context.existingPuzzles.forEach { puzzle ->
                    appendLine("- ${puzzle.id}: ${puzzle.content.take(100)}...")
                }
                appendLine()
                appendLine("Please complement or update this knowledge, avoid duplication.")
                appendLine()
            }

            // 5. 用户查询（如果有）
            if (context.hasUserQuery()) {
                appendLine("## User Question")
                appendLine()
                appendLine(context.userQuery)
                appendLine()
                appendLine("Please focus your analysis on answering this question.")
                appendLine()
            }

            // 6. 输出格式
            appendLine(OUTPUT_FORMAT)
        }
    }
}
