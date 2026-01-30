package com.smancode.smanagent.analysis.walkthrough

import com.smancode.smanagent.analysis.model.ClassAstInfo
import com.smancode.smanagent.analysis.scanner.PsiAstScanner
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 代码走读报告生成器
 *
 * 为指定类或模块生成代码走读报告
 */
class CodeWalkthroughGenerator(
    private val psiScanner: PsiAstScanner
) {

    private val logger = LoggerFactory.getLogger(CodeWalkthroughGenerator::class.java)

    /**
     * 为类生成走读报告
     *
     * @param file 类文件路径
     * @return 走读报告
     */
    fun generateForClass(file: Path): CodeWalkthroughReport? {
        val ast = psiScanner.scanFile(file) ?: return null

        // 架构设计
        val architecture = analyzeArchitecture(ast)

        // 核心逻辑
        val coreLogic = analyzeCoreLogic(ast)

        // 数据流转
        val dataFlow = analyzeDataFlow(ast)

        // 关键决策点
        val decisions = analyzeDecisionPoints(ast)

        // 依赖关系
        val dependencies = analyzeDependencies(ast)

        return CodeWalkthroughReport(
            className = ast.className,
            simpleName = ast.simpleName,
            packageName = ast.packageName,
            architecture = architecture,
            coreLogic = coreLogic,
            dataFlow = dataFlow,
            decisionPoints = decisions,
            dependencies = dependencies,
            filePath = file.toString()
        )
    }

    /**
     * 分析架构设计
     */
    private fun analyzeArchitecture(ast: ClassAstInfo): ArchitectureInfo {
        val className = ast.simpleName.lowercase()
        val packageName = ast.packageName.lowercase()

        // 推断架构层次
        val layer = when {
            packageName.contains("controller") || className.endsWith("controller") ->
                ArchitectureLayer.PRESENTATION
            packageName.contains("service") || className.endsWith("service") ->
                ArchitectureLayer.SERVICE
            packageName.contains("repository") || className.endsWith("repository") ||
            packageName.contains("dao") || className.endsWith("dao") ->
                ArchitectureLayer.INFRASTRUCTURE
            packageName.contains("domain") || packageName.contains("model") ||
            packageName.contains("entity") || className.contains("Entity") ->
                ArchitectureLayer.DOMAIN
            packageName.contains("config") || className.endsWith("config") ->
                ArchitectureLayer.CONFIG
            packageName.contains("util") || className.endsWith("util") ||
            packageName.contains("helper") || className.endsWith("helper") ->
                ArchitectureLayer.UTILITY
            else -> ArchitectureLayer.UNKNOWN
        }

        // 推断设计模式
        val patterns = detectDesignPatterns(ast)

        // 推断职责
        val responsibility = inferResponsibility(ast, layer)

        return ArchitectureInfo(
            layer = layer,
            patterns = patterns,
            responsibility = responsibility,
            size = ast.methods.size + ast.fields.size
        )
    }

    /**
     * 分析核心逻辑
     */
    private fun analyzeCoreLogic(ast: ClassAstInfo): CoreLogicInfo {
        val logicDescriptions = mutableListOf<String>()

        // 分析每个方法
        ast.methods.forEach { method ->
            val logic = describeMethodLogic(ast, method)
            if (logic.isNotEmpty()) {
                logicDescriptions.add(logic)
            }
        }

        // 识别核心方法
        val coreMethods = identifyCoreMethods(ast)

        return CoreLogicInfo(
            summary = generateLogicSummary(ast),
            keyMethods = coreMethods,
            methodDescriptions = logicDescriptions
        )
    }

    /**
     * 分析数据流转
     */
    private fun analyzeDataFlow(ast: ClassAstInfo): DataFlowInfo {
        val flows = mutableListOf<DataFlow>()

        // 分析方法参数作为输入
        ast.methods.forEach { method ->
            if (method.parameters.isNotEmpty()) {
                flows.add(DataFlow(
                    source = "外部输入",
                    target = method.name,
                    dataType = method.parameters.firstOrNull() ?: "Unknown",
                    description = "接收参数：${method.parameters.joinToString(", ")}"
                ))
            }
        }

        // 分析返回值作为输出
        ast.methods.forEach { method ->
            if (method.returnType != "Unit" && method.returnType.isNotEmpty()) {
                flows.add(DataFlow(
                    source = method.name,
                    target = "外部输出",
                    dataType = method.returnType,
                    description = "返回：${method.returnType}"
                ))
            }
        }

        // 分析字段作为内部数据
        ast.fields.forEach { field ->
            flows.add(DataFlow(
                source = "内部状态",
                target = field.name,
                dataType = field.type,
                description = "类成员变量：${field.name} : ${field.type}"
            ))
        }

        return DataFlowInfo(
            inputs = flows.filter { it.source == "外部输入" },
            outputs = flows.filter { it.target == "外部输出" },
            internalFlows = flows.filter { it.source == "内部状态" }
        )
    }

    /**
     * 分析关键决策点
     */
    private fun analyzeDecisionPoints(ast: ClassAstInfo): List<DecisionPoint> {
        val decisions = mutableListOf<DecisionPoint>()

        // 根据方法名推断决策点
        ast.methods.forEach { method ->
            val methodName = method.name.lowercase()

            when {
                methodName.contains("validate") || methodName.contains("check") -> {
                    decisions.add(DecisionPoint(
                        location = method.name,
                        type = DecisionType.VALIDATION,
                        description = "验证${method.parameters.joinToString("和 ")}",
                        impact = "验证失败将抛出异常或返回错误"
                    ))
                }
                methodName.contains("if") || methodName.contains("when") -> {
                    decisions.add(DecisionPoint(
                        location = method.name,
                        type = DecisionType.CONDITIONAL,
                        description = "条件判断逻辑",
                        impact = "根据条件执行不同分支"
                    ))
                }
                methodName.contains("create") || methodName.contains("init") -> {
                    decisions.add(DecisionPoint(
                        location = method.name,
                        type = DecisionType.INITIALIZATION,
                        description = "初始化${ast.simpleName}",
                        impact = "确保资源正确初始化"
                    ))
                }
                methodName.contains("process") || methodName.contains("handle") -> {
                    decisions.add(DecisionPoint(
                        location = method.name,
                        type = DecisionType.PROCESSING,
                        description = "处理核心业务逻辑",
                        impact = "影响业务结果"
                    ))
                }
            }
        }

        return decisions
    }

    /**
     * 分析依赖关系
     */
    private fun analyzeDependencies(ast: ClassAstInfo): DependencyInfo {
        val incoming = mutableListOf<String>()
        val outgoing = mutableListOf<String>()

        // 从字段推断依赖
        ast.fields.forEach { field ->
            val fieldType = field.type
            if (isCustomType(fieldType)) {
                outgoing.add("依赖 $fieldType：${field.name}")
            }
        }

        // 从方法返回值推断被依赖
        if (ast.simpleName.isNotEmpty()) {
            incoming.add("被其他类调用")
        }

        return DependencyInfo(
            incoming = incoming,
            outgoing = outgoing,
            stability = calculateStability(ast)
        )
    }

    /**
     * 检测设计模式
     */
    private fun detectDesignPatterns(ast: ClassAstInfo): List<String> {
        val patterns = mutableListOf<String>()
        val className = ast.simpleName.lowercase()

        // 单例模式
        if (className.contains("config") || className.contains("manager") ||
            className.contains("holder")) {
            patterns.add("单例模式 (可能)")
        }

        // 工厂模式
        if (className.contains("factory") || ast.methods.any {
            it.name.contains("create") || it.name.contains("build") }) {
            patterns.add("工厂模式")
        }

        // 策略模式
        if (className.contains("strategy") || ast.packageName.contains("strategy")) {
            patterns.add("策略模式")
        }

        // 观察者模式
        if (className.contains("listener") || className.contains("observer") ||
            className.contains("subscriber")) {
            patterns.add("观察者模式")
        }

        // 仓储模式
        if (className.endsWith("Repository") || className.endsWith("Dao")) {
            patterns.add("仓储模式")
        }

        // 服务模式
        if (className.endsWith("Service")) {
            patterns.add("服务模式")
        }

        return patterns
    }

    /**
     * 推断职责
     */
    private fun inferResponsibility(ast: ClassAstInfo, layer: ArchitectureLayer): String {
        val className = ast.simpleName.lowercase()

        return when (layer) {
            ArchitectureLayer.PRESENTATION -> "处理 HTTP 请求和响应，提供 API 接口"
            ArchitectureLayer.SERVICE -> "实现业务逻辑，协调领域模型和基础设施"
            ArchitectureLayer.INFRASTRUCTURE -> "处理数据持久化和外部系统集成"
            ArchitectureLayer.DOMAIN -> "封装业务概念和业务规则"
            ArchitectureLayer.CONFIG -> "提供配置信息和依赖注入"
            ArchitectureLayer.UTILITY -> "提供通用工具方法和辅助功能"
            ArchitectureLayer.UNKNOWN -> "职责待分析"
        }
    }

    /**
     * 描述方法逻辑
     */
    private fun describeMethodLogic(
        ast: ClassAstInfo,
        method: com.smancode.smanagent.analysis.model.MethodInfo
    ): String {
        val methodName = method.name.lowercase()
        val sb = StringBuilder()

        sb.append("- **${method.name}()**: ")

        when {
            methodName.startsWith("get") || methodName.startsWith("find") ||
            methodName.startsWith("query") || methodName.startsWith("search") -> {
                sb.append("查询操作，")
                if (method.parameters.isNotEmpty()) {
                    sb.append("根据 ${method.parameters.joinToString(" 和 ")} 查找数据")
                } else {
                    sb.append("获取数据")
                }
                sb.append("，返回 ${method.returnType}")
            }
            methodName.startsWith("create") || methodName.startsWith("add") ||
            methodName.startsWith("insert") || methodName.startsWith("save") -> {
                sb.append("创建操作，")
                sb.append("新增 ${method.parameters.firstOrNull() ?: "记录"}")
                sb.append("，返回 ${method.returnType}")
            }
            methodName.startsWith("update") || methodName.startsWith("modify") -> {
                sb.append("更新操作，")
                sb.append("修改 ${method.parameters.firstOrNull() ?: "记录"}")
                sb.append("，返回 ${method.returnType}")
            }
            methodName.startsWith("delete") || methodName.startsWith("remove") -> {
                sb.append("删除操作，")
                sb.append("移除 ${method.parameters.firstOrNull() ?: "记录"}")
                sb.append("，返回 ${method.returnType}")
            }
            methodName.startsWith("validate") || methodName.startsWith("check") -> {
                sb.append("验证操作，")
                sb.append("检查 ${method.parameters.joinToString(" 和 ")} 的合法性")
                sb.append("，返回 ${method.returnType}")
            }
            else -> {
                sb.append("执行 ${method.name} 操作")
                if (method.parameters.isNotEmpty()) {
                    sb.append("，参数：${method.parameters.joinToString(", ")}")
                }
                sb.append("，返回 ${method.returnType}")
            }
        }

        return sb.toString()
    }

    /**
     * 识别核心方法
     */
    private fun identifyCoreMethods(ast: ClassAstInfo): List<String> {
        val coreMethods = mutableListOf<String>()

        ast.methods.forEach { method ->
            val methodName = method.name.lowercase()

            // 公共方法、参数合理、有业务含义
            if (!method.name.startsWith("get") &&
                !method.name.startsWith("set") &&
                !method.name.startsWith("is") &&
                method.parameters.size <= 5) {
                coreMethods.add(method.name)
            }
        }

        return coreMethods.take(10) // 最多返回 10 个
    }

    /**
     * 生成逻辑摘要
     */
    private fun generateLogicSummary(ast: ClassAstInfo): String {
        val sb = StringBuilder()

        sb.append("${ast.simpleName} 类主要负责")

        val layer = when {
            ast.packageName.contains("controller") -> "API 接口层"
            ast.packageName.contains("service") -> "业务服务层"
            ast.packageName.contains("repository") -> "数据访问层"
            ast.packageName.contains("domain") || ast.packageName.contains("model") -> "领域模型层"
            else -> "功能"
        }

        sb.append(layer)
        sb.append("，包含 ${ast.methods.size} 个方法和 ${ast.fields.size} 个字段。")

        return sb.toString()
    }

    /**
     * 计算稳定性
     */
    private fun calculateStability(ast: ClassAstInfo): StabilityLevel {
        // 简单的稳定性评估
        val incoming = 1 // 默认被调用
        val outgoing = ast.fields.count { isCustomType(it.type) }

        val stability = if (incoming + outgoing > 0) {
            incoming.toDouble() / (incoming + outgoing)
        } else {
            0.5
        }

        return when {
            stability > 0.7 -> StabilityLevel.STABLE
            stability > 0.3 -> StabilityLevel.MODERATE
            else -> StabilityLevel.VOLATILE
        }
    }

    /**
     * 判断是否为自定义类型
     */
    private fun isCustomType(type: String): Boolean {
        val primitives = listOf("String", "Int", "Long", "Double", "Float",
            "Boolean", "Unit", "Void", "Any", "List", "Map", "Set", "Optional")
        return type !in primitives && !type.startsWith("kotlin.") &&
            !type.startsWith("java.") && !type.startsWith("javax.")
    }
}

/**
 * 代码走读报告
 */
@Serializable
data class CodeWalkthroughReport(
    val className: String,
    val simpleName: String,
    val packageName: String,
    val architecture: ArchitectureInfo,
    val coreLogic: CoreLogicInfo,
    val dataFlow: DataFlowInfo,
    val decisionPoints: List<DecisionPoint>,
    val dependencies: DependencyInfo,
    val filePath: String
)

/**
 * 架构信息
 */
@Serializable
data class ArchitectureInfo(
    val layer: ArchitectureLayer,
    val patterns: List<String>,
    val responsibility: String,
    val size: Int
)

/**
 * 架构层次
 */
@Serializable
enum class ArchitectureLayer {
    PRESENTATION,    // 表现层
    SERVICE,         // 服务层
    DOMAIN,          // 领域层
    INFRASTRUCTURE,  // 基础设施层
    CONFIG,          // 配置层
    UTILITY,         // 工具层
    UNKNOWN          // 未知
}

/**
 * 核心逻辑信息
 */
@Serializable
data class CoreLogicInfo(
    val summary: String,
    val keyMethods: List<String>,
    val methodDescriptions: List<String>
)

/**
 * 数据流转信息
 */
@Serializable
data class DataFlowInfo(
    val inputs: List<DataFlow>,
    val outputs: List<DataFlow>,
    val internalFlows: List<DataFlow>
)

/**
 * 数据流
 */
@Serializable
data class DataFlow(
    val source: String,
    val target: String,
    val dataType: String,
    val description: String
)

/**
 * 决策点
 */
@Serializable
data class DecisionPoint(
    val location: String,
    val type: DecisionType,
    val description: String,
    val impact: String
)

/**
 * 决策类型
 */
@Serializable
enum class DecisionType {
    VALIDATION,     // 验证
    CONDITIONAL,    // 条件
    INITIALIZATION, // 初始化
    PROCESSING,     // 处理
    ERROR_HANDLING  // 错误处理
}

/**
 * 依赖信息
 */
@Serializable
data class DependencyInfo(
    val incoming: List<String>,
    val outgoing: List<String>,
    val stability: StabilityLevel
)

/**
 * 稳定性级别
 */
@Serializable
enum class StabilityLevel {
    STABLE,     // 稳定（被依赖多，依赖少）
    MODERATE,   // 中等
    VOLATILE    // 易变（依赖多，被依赖少）
}
