package com.smancode.sman.analysis.sop

import com.smancode.sman.analysis.model.ClassAstInfo
import com.smancode.sman.analysis.scanner.PsiAstScanner
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 案例 SOP 生成器
 *
 * 从代码中提取业务场景，生成标准操作流程（SOP）文档
 */
class CaseSopGenerator(
    private val psiScanner: PsiAstScanner
) {

    private val logger = LoggerFactory.getLogger(CaseSopGenerator::class.java)

    /**
     * 从类生成 SOP
     *
     * @param file 类文件路径
     * @return SOP 文档
     */
    fun generateFromClass(file: Path): CaseSop? {
        val ast = psiScanner.scanFile(file) ?: return null

        // 推断业务场景
        val scenario = inferBusinessScenario(ast)

        // 提取前置条件
        val preconditions = extractPreconditions(ast)

        // 提取操作步骤
        val steps = extractSteps(ast)

        // 提取预期结果
        val expectedResults = extractExpectedResults(ast)

        return CaseSop(
            title = scenario.title,
            description = scenario.description,
            category = scenario.category,
            preconditions = preconditions,
            steps = steps,
            expectedResults = expectedResults,
            relatedClasses = listOf(ast.className),
            tags = scenario.tags
        )
    }

    /**
     * 从方法生成 SOP
     *
     * @param file 类文件路径
     * @param methodName 方法名
     * @return SOP 文档
     */
    fun generateFromMethod(file: Path, methodName: String): CaseSop? {
        val ast = psiScanner.scanFile(file) ?: return null
        val method = ast.methods.find { it.name == methodName } ?: return null

        // 推断业务场景
        val scenario = inferMethodScenario(ast, method)

        // 提取操作步骤
        val steps = extractMethodSteps(ast, method)

        // 提取预期结果
        val expectedResults = extractMethodExpectedResults(method)

        return CaseSop(
            title = scenario.title,
            description = scenario.description,
            category = scenario.category,
            preconditions = emptyList(),
            steps = steps,
            expectedResults = expectedResults,
            relatedClasses = listOf(ast.className),
            tags = scenario.tags
        )
    }

    /**
     * 推断业务场景
     */
    private fun inferBusinessScenario(ast: ClassAstInfo): BusinessScenario {
        val className = ast.simpleName.lowercase()
        val packageName = ast.packageName.lowercase()

        // 推断类别
        val category = when {
            packageName.contains("controller") || className.endsWith("controller") ->
                SopCategory.API_INTERACTION
            packageName.contains("service") || className.endsWith("service") ->
                SopCategory.BUSINESS_LOGIC
            packageName.contains("repository") || className.endsWith("repository") ||
            packageName.contains("dao") || className.endsWith("dao") ->
                SopCategory.DATA_ACCESS
            packageName.contains("config") || className.endsWith("config") ->
                SopCategory.CONFIGURATION
            packageName.contains("util") || className.endsWith("util") ||
            packageName.contains("helper") || className.endsWith("helper") ->
                SopCategory.UTILITY
            else -> SopCategory.OTHER
        }

        // 生成标题
        val title = when (category) {
            SopCategory.API_INTERACTION -> "API 调用：${ast.simpleName}"
            SopCategory.BUSINESS_LOGIC -> "业务处理：${ast.simpleName}"
            SopCategory.DATA_ACCESS -> "数据操作：${ast.simpleName}"
            SopCategory.CONFIGURATION -> "配置管理：${ast.simpleName}"
            SopCategory.UTILITY -> "工具使用：${ast.simpleName}"
            SopCategory.OTHER -> "操作流程：${ast.simpleName}"
        }

        // 生成描述
        val description = "本文档描述 ${ast.simpleName} 的标准操作流程"

        // 生成标签
        val tags = mutableListOf(
            category.name.lowercase().replace("_", "-")
        )
        tags.addAll(extractBusinessTags(className, packageName))

        return BusinessScenario(
            title = title,
            description = description,
            category = category,
            tags = tags
        )
    }

    /**
     * 推断方法场景
     */
    private fun inferMethodScenario(ast: ClassAstInfo, method: com.smancode.sman.analysis.model.MethodInfo): BusinessScenario {
        val methodName = method.name.lowercase()

        // 推断操作类型
        val operationType = when {
            methodName.startsWith("create") || methodName.startsWith("add") ||
            methodName.startsWith("insert") || methodName.startsWith("save") ->
                "创建"
            methodName.startsWith("update") || methodName.startsWith("modify") ||
            methodName.startsWith("edit") || methodName.startsWith("change") ->
                "更新"
            methodName.startsWith("delete") || methodName.startsWith("remove") ->
                "删除"
            methodName.startsWith("get") || methodName.startsWith("find") ||
            methodName.startsWith("query") || methodName.startsWith("search") ->
                "查询"
            methodName.startsWith("validate") || methodName.startsWith("check") ->
                "验证"
            methodName.startsWith("process") || methodName.startsWith("handle") ->
                "处理"
            else -> "操作"
        }

        val title = "${operationType}：${ast.simpleName}.${method.name}()"
        val description = "本文档描述 ${ast.simpleName}.${method.name}() 的操作流程"
        val category = SopCategory.BUSINESS_LOGIC

        return BusinessScenario(
            title = title,
            description = description,
            category = category,
            tags = listOf(category.name.lowercase(), operationType.lowercase())
        )
    }

    /**
     * 提取前置条件
     */
    private fun extractPreconditions(ast: ClassAstInfo): List<String> {
        val preconditions = mutableListOf<String>()

        // 检查依赖注入
        if (ast.fields.isNotEmpty()) {
            preconditions.add("确保依赖组件已正确配置")
        }

        // 检查配置类
        if (ast.packageName.contains("config") || ast.simpleName.contains("Config")) {
            preconditions.add("确保配置文件已正确设置")
        }

        // 检查数据库相关
        if (ast.packageName.contains("repository") ||
            ast.packageName.contains("dao") ||
            ast.simpleName.contains("Repository")) {
            preconditions.add("确保数据库连接正常")
            preconditions.add("确保相关数据表已创建")
        }

        return preconditions
    }

    /**
     * 提取操作步骤（类级别）
     */
    private fun extractSteps(ast: ClassAstInfo): List<SopStep> {
        val steps = mutableListOf<SopStep>()

        // 公共入口方法
        val publicMethods = ast.methods.filter { !it.name.startsWith("get") &&
            !it.name.startsWith("set") && !it.name.startsWith("is") }

        publicMethods.forEachIndexed { index, method ->
            val stepNumber = index + 1
            val stepName = when {
                method.name.startsWith("create") || method.name.startsWith("add") ->
                    "创建记录"
                method.name.startsWith("update") -> "更新记录"
                method.name.startsWith("delete") -> "删除记录"
                method.name.startsWith("get") -> "获取数据"
                method.name.startsWith("find") -> "查找数据"
                method.name.startsWith("process") -> "处理请求"
                else -> "执行${method.name}"
            }

            steps.add(SopStep(
                step = stepNumber,
                action = stepName,
                description = "调用 ${method.name}() 方法",
                method = method.name,
                parameters = method.parameters,
                expectedOutput = inferMethodOutput(method)
            ))
        }

        return steps
    }

    /**
     * 提取操作步骤（方法级别）
     */
    private fun extractMethodSteps(ast: ClassAstInfo, method: com.smancode.sman.analysis.model.MethodInfo): List<SopStep> {
        val steps = mutableListOf<SopStep>()

        // 参数准备
        if (method.parameters.isNotEmpty()) {
            steps.add(SopStep(
                step = 1,
                action = "准备参数",
                description = "准备以下参数：${method.parameters.joinToString(", ")}",
                method = "",
                parameters = method.parameters,
                expectedOutput = "参数验证通过"
            ))
        }

        // 执行方法
        steps.add(SopStep(
            step = 2,
            action = "执行操作",
            description = "调用 ${method.name}() 方法",
            method = method.name,
            parameters = method.parameters,
            expectedOutput = inferMethodOutput(method)
        ))

        // 处理结果
        steps.add(SopStep(
            step = 3,
            action = "处理结果",
            description = "根据返回值进行后续处理",
            method = "",
            parameters = emptyList(),
            expectedOutput = "操作完成"
        ))

        return steps
    }

    /**
     * 提取预期结果
     */
    private fun extractExpectedResults(ast: ClassAstInfo): List<String> {
        val results = mutableListOf<String>()

        // 根据类名推断
        val className = ast.simpleName.lowercase()
        when {
            className.contains("controller") -> {
                results.add("返回 HTTP 响应")
                results.add("状态码：200 表示成功")
            }
            className.contains("service") -> {
                results.add("业务逻辑执行完成")
                results.add("返回处理结果")
            }
            className.contains("repository") || className.contains("dao") -> {
                results.add("数据库操作成功")
                results.add("返回操作结果或实体对象")
            }
        }

        return results
    }

    /**
     * 提取方法预期结果
     */
    private fun extractMethodExpectedResults(method: com.smancode.sman.analysis.model.MethodInfo): List<String> {
        val results = mutableListOf<String>()

        when {
            method.returnType.contains("List") -> results.add("返回列表数据")
            method.returnType.contains("Optional") -> results.add("返回 Optional 对象")
            method.returnType == "Boolean" -> results.add("返回操作结果（true/false）")
            method.returnType == "Unit" || method.returnType == "Void" -> results.add("无返回值")
            method.returnType.contains("Response") || method.returnType.contains("Result") ->
                results.add("返回响应/结果对象")
            else -> results.add("返回 ${method.returnType} 类型的结果")
        }

        return results
    }

    /**
     * 提取业务标签
     */
    private fun extractBusinessTags(className: String, packageName: String): List<String> {
        val tags = mutableListOf<String>()

        // 业务领域关键词
        val businessKeywords = listOf(
            "user", "order", "payment", "product", "inventory", "shipment",
            "customer", "account", "transaction", "report", "audit",
            "auth", "permission", "role", "config", "notification"
        )

        businessKeywords.forEach { keyword ->
            if (className.contains(keyword, ignoreCase = true) ||
                packageName.contains(keyword, ignoreCase = true)) {
                tags.add(keyword)
            }
        }

        return tags
    }

    /**
     * 推断方法输出
     */
    private fun inferMethodOutput(method: com.smancode.sman.analysis.model.MethodInfo): String {
        return when {
            method.returnType.contains("List") -> "列表对象"
            method.returnType.contains("Optional") -> "Optional 对象"
            method.returnType == "Boolean" -> "true/false"
            method.returnType == "Unit" || method.returnType == "Void" -> "无"
            else -> method.returnType
        }
    }
}

/**
 * 案例 SOP 文档
 */
@Serializable
data class CaseSop(
    val title: String,
    val description: String,
    val category: SopCategory,
    val preconditions: List<String>,
    val steps: List<SopStep>,
    val expectedResults: List<String>,
    val relatedClasses: List<String>,
    val tags: List<String>
)

/**
 * SOP 类别
 */
@Serializable
enum class SopCategory {
    API_INTERACTION,  // API 交互
    BUSINESS_LOGIC,   // 业务逻辑
    DATA_ACCESS,      // 数据访问
    CONFIGURATION,    // 配置管理
    UTILITY,          // 工具使用
    OTHER             // 其他
}

/**
 * SOP 步骤
 */
@Serializable
data class SopStep(
    val step: Int,
    val action: String,
    val description: String,
    val method: String,
    val parameters: List<String>,
    val expectedOutput: String
)

/**
 * 业务场景
 */
private data class BusinessScenario(
    val title: String,
    val description: String,
    val category: SopCategory,
    val tags: List<String>
)
