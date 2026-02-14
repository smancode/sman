package com.smancode.sman.evolution.generator

import com.smancode.sman.evolution.model.QuestionType

/**
 * 问题分类层次枚举
 *
 * 定义了问题探索的四个层次，按照优先级从高到低排列：
 * 1. 项目基础 - 必须先了解的基础信息
 * 2. 业务领域 - 理解业务逻辑和领域知识
 * 3. 技术实现 - 深入代码层面的实现细节
 * 4. 最佳实践 - 提炼可复用的知识和模式
 *
 * 设计原则：
 * - 层次之间有明确的优先级关系
 * - 每个层次包含多个问题类型
 * - LLM 应该按照层次顺序系统性地探索项目
 */
enum class QuestionCategory(
    val level: Int,
    val displayName: String,
    val description: String,
    val questionTypes: List<QuestionType>,
    val focusAreas: List<String>
) {
    /**
     * 第一层：项目基础
     * 必须先了解的基础信息，是后续探索的前提
     */
    PROJECT_FOUNDATION(
        level = 1,
        displayName = "项目基础",
        description = "项目的基础架构和组织方式",
        questionTypes = listOf(
            QuestionType.CODE_STRUCTURE,
            QuestionType.CONFIGURATION,
            QuestionType.DEPENDENCY
        ),
        focusAreas = listOf(
            "项目结构：目录组织、分层架构、模块划分",
            "技术栈：框架、库、工具链",
            "构建与部署：构建命令、配置文件、部署方式"
        )
    ),

    /**
     * 第二层：业务领域
     * 理解业务逻辑和领域知识
     */
    BUSINESS_DOMAIN(
        level = 2,
        displayName = "业务领域",
        description = "业务逻辑和领域知识",
        questionTypes = listOf(
            QuestionType.BUSINESS_LOGIC,
            QuestionType.DOMAIN_KNOWLEDGE,
            QuestionType.DATA_FLOW
        ),
        focusAreas = listOf(
            "业务类型：金融/电商/社交/企业服务等",
            "核心领域：如金融->银行->贷款->核算",
            "业务实体：订单、支付、用户、账户等",
            "业务流程：订单流程、支付流程、对账流程等"
        )
    ),

    /**
     * 第三层：技术实现
     * 深入代码层面的实现细节
     */
    TECHNICAL_IMPLEMENTATION(
        level = 3,
        displayName = "技术实现",
        description = "代码层面的技术实现细节",
        questionTypes = listOf(
            QuestionType.DATA_FLOW,
            QuestionType.DEPENDENCY,
            QuestionType.ERROR_ANALYSIS
        ),
        focusAreas = listOf(
            "代码模式：使用的设计模式",
            "数据流：数据如何流转",
            "依赖关系：模块间如何交互",
            "异常处理：错误如何处理"
        )
    ),

    /**
     * 第四层：最佳实践
     * 提炼可复用的知识和模式
     */
    BEST_PRACTICES(
        level = 4,
        displayName = "最佳实践",
        description = "可复用的设计模式和最佳实践",
        questionTypes = listOf(
            QuestionType.BEST_PRACTICE,
            QuestionType.ERROR_ANALYSIS
        ),
        focusAreas = listOf(
            "项目特色：这个项目的独特做法",
            "可复用模式：可以借鉴的设计",
            "潜在问题：可能的风险点"
        )
    );

    companion object {
        /**
         * 按优先级获取所有层次
         */
        fun orderedByPriority(): List<QuestionCategory> {
            return values().sortedBy { it.level }
        }
    }
}

/**
 * 问题框架 - 结构化的问题生成引导系统
 *
 * 核心职责：
 * 1. 根据已完成的层次确定下一个探索目标
 * 2. 为每个层次提供专注的提示词模板
 * 3. 建立层次与问题类型的映射关系
 *
 * 设计要点：
 * - 层次化：从基础到高级，逐步深入
 * - 聚焦：每个层次有明确的探索重点
 * - 可追踪：记录已完成的层次
 */
class QuestionFramework {

    /**
     * 获取下一个应该探索的层次
     *
     * @param completedCategories 已完成的层次集合
     * @return 下一个应该探索的层次，如果全部完成则返回 null
     */
    fun getNextCategory(completedCategories: Set<QuestionCategory>): QuestionCategory? {
        return QuestionCategory.orderedByPriority()
            .firstOrNull { it !in completedCategories }
    }

    /**
     * 获取某个层次的提示词片段
     *
     * @param category 问题层次
     * @return 该层次的提示词片段，用于引导 LLM 聚焦
     */
    fun getCategoryPrompt(category: QuestionCategory): String {
        return """
<exploration_focus>
    <category>${category.displayName}</category>
    <level>${category.level}</level>
    <description>${category.description}</description>
    <focus_areas>
        ${category.focusAreas.joinToString("\n        ") { "- $it" }}
    </focus_areas>
    <applicable_types>
        ${category.questionTypes.joinToString(", ")}
    </applicable_types>
</exploration_focus>
        """.trimIndent()
    }

    /**
     * 获取某个层次对应的问题类型列表
     *
     * @param category 问题层次
     * @return 该层次适用的问题类型列表
     */
    fun getCategoryQuestionTypes(category: QuestionCategory): List<QuestionType> {
        return category.questionTypes
    }

    /**
     * 获取层次的完成进度描述
     *
     * @param completedCategories 已完成的层次集合
     * @return 进度描述字符串
     */
    fun getProgressDescription(completedCategories: Set<QuestionCategory>): String {
        val total = QuestionCategory.values().size
        val completed = completedCategories.size
        val currentCategory = getNextCategory(completedCategories)

        return if (currentCategory != null) {
            "探索进度: $completed/$total 层次，当前聚焦: ${currentCategory.displayName}"
        } else {
            "探索进度: $completed/$total 层次，所有层次已完成"
        }
    }

    /**
     * 根据问题类型推断所属层次
     *
     * @param questionType 问题类型
     * @return 对应的问题层次
     */
    fun inferCategory(questionType: QuestionType): QuestionCategory {
        return QuestionCategory.values()
            .firstOrNull { questionType in it.questionTypes }
            ?: QuestionCategory.BUSINESS_DOMAIN
    }

    companion object {
        /**
         * 框架整体描述 - 用于系统提示词
         */
        val FRAMEWORK_DESCRIPTION = """
You are exploring a software project using a structured 4-layer framework:

## Layer 1: PROJECT_FOUNDATION (项目基础)
Priority: Highest - Must understand first
Focus: Project structure, tech stack, build and deployment
Types: CODE_STRUCTURE, CONFIGURATION, DEPENDENCY

## Layer 2: BUSINESS_DOMAIN (业务领域)
Priority: High - Understand the business logic
Focus: Business type, domain concepts, business entities, business flows
Types: BUSINESS_LOGIC, DOMAIN_KNOWLEDGE, DATA_FLOW

## Layer 3: TECHNICAL_IMPLEMENTATION (技术实现)
Priority: Medium - Deep dive into code
Focus: Design patterns, data flow, module interactions, error handling
Types: DATA_FLOW, DEPENDENCY, ERROR_ANALYSIS

## Layer 4: BEST_PRACTICES (最佳实践)
Priority: Lower - Extract reusable knowledge
Focus: Unique approaches, reusable patterns, potential risks
Types: BEST_PRACTICE, ERROR_ANALYSIS

**Important**: Always focus on the current layer specified in the prompt.
Do not jump ahead to other layers until the current one is sufficiently explored.
        """.trimIndent()
    }
}
