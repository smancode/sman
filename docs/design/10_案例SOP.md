# 10 - 案例SOP

## 目标

为 SmanAgent 项目添加案例 SOP（Standard Operating Procedure，标准操作程序）提取能力，从代码走读中识别复合需求的完整实现流程，生成可复用的业务流程知识。最终将分析结果存入向量库，支持 BGE 召回 + 重排。

**核心价值**：
- 从入口到数据库的完整调用链
- 识别跨多个类的业务流程
- 提取可复用的业务模式（创建订单、支付流程、用户注册等）
- 支持业务能力分析和新功能开发

## 参考

- knowledge-graph-system 的 `BUSINESS_PROCESS_TEST.md`
- knowledge-graph-system 的 `CODEWALKER_INTEGRATION_GUIDE.md`
- knowledge-graph-system 的 `BusinessProcess.java`
- knowledge-graph-system 的 `BusinessProcessExtractor.java`
- knowledge-graph-system 的 `KnowledgeTools.getBusinessProcess()`

## 核心功能

### 1. 业务流程识别

**目标**：从代码走读结果中识别完整的业务流程

**识别策略**：

1. **基于入口识别**
   - 查找所有入口方法（HTTP、MQ、RPC、定时任务）
   - 每个入口方法对应一个业务流程

2. **调用链遍历**
   - 从入口方法开始，使用 BFS 遍历调用链
   - 记录每个调用步骤（类名、方法名、行号）
   - 识别层次类型（Controller、Service、Mapper、Database）
   - 限制最大深度（默认 10 层）

3. **数据库操作识别**
   - 从方法调用中识别 Mapper/Repository 调用
   - 提取操作的数据库表
   - 记录操作类型（INSERT、UPDATE、DELETE、SELECT）

4. **外部接口识别**
   - 识别外部 API 调用（HttpClient、RestTemplate、Feign）
   - 提取外部接口 URL
   - 记录调用参数和返回值

**流程类型分类**：

| 流程类型 | 说明 | 入口特征 |
|----------|------|----------|
| HTTP_REQUEST | HTTP 请求流程 | @RestController、@Controller |
| RPC_CALL | RPC 调用流程 | @DubboService、@Service |
| SCHEDULED_TASK | 定时任务流程 | @XxlJob、@Scheduled |
| MESSAGE_PROCESSING | 消息处理流程 | @KafkaListener、@RabbitListener |
| INTERNAL_SUBFLOW | 内部子流程 | 无入口注解的内部方法 |

**数据模型**：

```kotlin
/**
 * 业务流程
 */
data class BusinessProcess(
    val processId: String,              // 流程唯一标识
    val processName: String,            // 流程名称（如"贷款放款流程"）
    val projectKey: String,             // 项目标识
    val description: String,            // 流程描述
    val entrance: ProcessStep,          // 入口步骤
    val steps: List<ProcessStep>,       // 流程步骤列表
    val processType: ProcessType,       // 流程类型
    val involvedTables: List<String>,   // 涉及的数据库表
    val externalApis: List<String>,     // 涉及的外部接口
    val methodIds: List<String>,        // 涉及的方法ID列表
    val entityIds: List<String>,        // 涉及的业务实体ID
    val ruleIds: List<String>,          // 涉及的业务规则ID
    val capabilityIds: List<String>     // 涉及的业务能力ID
)

/**
 * 流程类型枚举
 */
enum class ProcessType {
    HTTP_REQUEST("HTTP请求"),
    RPC_CALL("RPC调用"),
    SCHEDULED_TASK("定时任务"),
    MESSAGE_PROCESSING("消息处理"),
    INTERNAL_SUBFLOW("内部子流程")
}

/**
 * 流程步骤
 */
data class ProcessStep(
    val stepNumber: Int,                // 步骤序号（从1开始）
    val level: Int,                     // 调用层级（从0开始）
    val methodId: String,               // 方法ID
    val methodName: String,             // 方法名
    val className: String,              // 类名
    val layerType: LayerType,           // 层次类型
    val description: String,            // 步骤描述
    val hasDatabaseOperation: Boolean,  // 是否涉及数据库操作
    val tableName: String?,             // 涉及的数据库表
    val isExternalCall: Boolean,        // 是否是外部调用
    val externalApiUrl: String?,        // 外部接口URL
    val calledMethods: List<String>     // 调用的子方法
)

/**
 * 层次类型枚举
 */
enum class LayerType {
    CONTROLLER("控制器层"),
    SERVICE("服务层"),
    MAPPER("数据访问层"),
    DATABASE("数据库"),
    EXTERNAL_API("外部接口"),
    OTHER("其他")
)
```

### 2. 业务流程提取

**提取流程**：

```kotlin
/**
 * 业务流程提取器
 */
class BusinessProcessExtractor(
    private val projectKey: String,
    private val codeWalker: CodeWalker
) {

    /**
     * 从代码走读结果中提取业务流程
     */
    suspend fun extractFromWalkResult(walkResult: WalkResult): List<BusinessProcess> {
        val processes = mutableListOf<BusinessProcess>()

        // 1. 查找所有入口方法
        val entranceMethods = findEntranceMethods(walkResult.methods)

        // 2. 为每个入口方法构建一个业务流程
        for (entranceMethod in entranceMethods) {
            val process = buildProcessFromEntrance(entranceMethod, walkResult)
            if (process != null) {
                processes.add(process)
            }
        }

        return processes
    }

    /**
     * 查找所有入口方法
     */
    private fun findEntranceMethods(methods: List<MethodContext>): List<MethodContext> {
        return methods.filter { it.entranceType != null }
    }

    /**
     * 从入口方法构建业务流程
     */
    private fun buildProcessFromEntrance(
        entranceMethod: MethodContext,
        walkResult: WalkResult
    ): BusinessProcess? {
        // 1. 生成流程ID
        val processId = generateProcessId(entranceMethod)

        // 2. 确定流程类型
        val processType = determineProcessType(entranceMethod)

        // 3. 构建流程步骤（BFS遍历）
        val steps = buildProcessSteps(entranceMethod, walkResult)

        if (steps.isEmpty()) {
            return null
        }

        // 4. 创建业务流程
        return BusinessProcess(
            processId = processId,
            processName = generateProcessName(entranceMethod, processType),
            projectKey = projectKey,
            description = generateProcessDescription(entranceMethod, steps),
            entrance = steps[0],
            steps = steps,
            processType = processType,
            involvedTables = extractInvolvedTables(steps),
            externalApis = extractExternalApis(steps),
            methodIds = steps.map { it.methodId },
            entityIds = extractEntityIds(steps, walkResult),
            ruleIds = extractRuleIds(steps, walkResult),
            capabilityIds = extractCapabilityIds(steps, walkResult)
        )
    }

    /**
     * 构建流程步骤列表（BFS遍历）
     */
    private fun buildProcessSteps(
        entranceMethod: MethodContext,
        walkResult: WalkResult
    ): List<ProcessStep> {
        val steps = mutableListOf<ProcessStep>()
        val queue = LinkedList<MethodContext>()
        val visited = HashSet<String>()
        val depthMap = HashMap<String, Int>()

        queue.add(entranceMethod)
        visited.add(entranceMethod.methodId)
        depthMap[entranceMethod.methodId] = 0

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            val currentDepth = depthMap[current.methodId] ?: 0

            // 创建流程步骤
            val step = createProcessStep(current, currentDepth)
            steps.add(step)

            // 添加子方法到队列
            current.callees?.forEach { calleeId ->
                if (!visited.contains(calleeId)) {
                    visited.add(calleeId)
                    depthMap[calleeId] = currentDepth + 1

                    val calleeMethod = findMethodContext(calleeId, walkResult)
                    if (calleeMethod != null) {
                        queue.add(calleeMethod)
                    }
                }
            }

            // 限制最大深度
            if (currentDepth >= MAX_TRAVERSAL_DEPTH) {
                break
            }
        }

        return steps
    }

    /**
     * 创建流程步骤
     */
    private fun createProcessStep(methodContext: MethodContext, level: Int): ProcessStep {
        return ProcessStep(
            stepNumber = 0, // 稍后设置
            level = level,
            methodId = methodContext.methodId,
            methodName = methodContext.methodName,
            className = methodContext.className,
            layerType = determineLayerType(methodContext),
            description = generateStepDescription(methodContext),
            hasDatabaseOperation = hasDatabaseOperation(methodContext),
            tableName = extractTableName(methodContext),
            isExternalCall = isExternalCall(methodContext),
            externalApiUrl = extractExternalApiUrl(methodContext),
            calledMethods = methodContext.callees ?: emptyList()
        )
    }

    /**
     * 确定层次类型
     */
    private fun determineLayerType(methodContext: MethodContext): LayerType {
        val className = methodContext.className

        return when {
            className.contains("Controller") || className.contains("Handler") -> LayerType.CONTROLLER
            className.contains("Service") || className.contains("Manager") -> LayerType.SERVICE
            className.contains("Mapper") || className.contains("Dao") ||
            className.contains("Repository") -> LayerType.MAPPER
            className.contains("Entity") || className.contains("DO") -> LayerType.DATABASE
            className.contains("Client") || className.contains("External") -> LayerType.EXTERNAL_API
            else -> LayerType.OTHER
        }
    }

    /**
     * 判断是否有数据库操作
     */
    private fun hasDatabaseOperation(methodContext: MethodContext): Boolean {
        val analysis = methodContext.analysis
        return analysis?.databaseOperations?.isNotEmpty() == true
    }

    /**
     * 提取数据库表名
     */
    private fun extractTableName(methodContext: MethodContext): String? {
        val analysis = methodContext.analysis
        return analysis?.databaseOperations?.firstOrNull()?.tableName
    }

    /**
     * 判断是否是外部调用
     */
    private fun isExternalCall(methodContext: MethodContext): Boolean {
        val className = methodContext.className
        return className.contains("Client") ||
               className.contains("External") ||
               (className.contains("Service") && className.contains("External"))
    }

    /**
     * 提取外部API URL
     */
    private fun extractExternalApiUrl(methodContext: MethodContext): String? {
        // TODO: 从注解或配置中提取真实的外部 API URL
        val analysis = methodContext.analysis
        return analysis?.externalApiUrl
    }

    /**
     * 生成流程名称
     */
    private fun generateProcessName(
        methodContext: MethodContext,
        processType: ProcessType
    ): String {
        val baseName = getBusinessSemanticOrMethodName(methodContext)
        return "$baseName${FLOW_SUFFIX}"
    }

    /**
     * 获取业务语义或方法名
     */
    private fun getBusinessSemanticOrMethodName(methodContext: MethodContext): String {
        val analysis = methodContext.analysis
        return if (!analysis?.businessSemantic.isNullOrEmpty()) {
            analysis.businessSemantic
        } else {
            methodContext.methodName
        }
    }

    /**
     * 生成流程描述
     */
    private fun generateProcessDescription(
        entranceMethod: MethodContext,
        steps: List<ProcessStep>
    ): String {
        return buildString {
            append("从入口方法 ${entranceMethod.fullPath} 开始的端到端业务流程。\n")
            append("包含 ${steps.size} 个步骤，")
            val maxDepth = steps.maxOfOrNull { it.level } ?: 0
            append("最大深度为 $maxDepth。\n")

            val tables = steps.mapNotNull { it.tableName }.distinct()
            if (tables.isNotEmpty()) {
                append("涉及的数据库表: ${tables.joinToString(", ")}。")
            }
        }
    }

    /**
     * 生成步骤描述
     */
    private fun generateStepDescription(methodContext: MethodContext): String {
        val layerType = determineLayerType(methodContext).chineseName
        val baseDescription = "[$layerType] ${methodContext.methodName}"

        val businessSemantic = getBusinessSemanticOrMethodName(methodContext)
        return if (businessSemantic != methodContext.methodName) {
            "$baseDescription - $businessSemantic"
        } else {
            baseDescription
        }
    }

    companion object {
        const val MAX_TRAVERSAL_DEPTH = 10
        const val FLOW_SUFFIX = "流程"
    }
}
```

### 3. 业务流程查询

**工具：get_business_process**

**功能**：根据流程名称查询完整的业务流程

**参数**：
- `processName`（必需）：业务流程名称，支持模糊匹配

**返回**：
```json
{
  "success": true,
  "data": [
    {
      "processId": "process-com.example.LoanController#createLoan-12345678",
      "processName": "创建贷款流程",
      "description": "从入口方法 com.example.LoanController#createLoan 开始的端到端业务流程。\n包含 7 个步骤，最大深度为 3。\n涉及的数据库表: acct_loan。",
      "stepCount": 7,
      "entranceMethodId": "com.example.LoanController#createLoan(com.example.dto.LoanRequest)",
      "entranceClassName": "com.example.LoanController",
      "involvedTables": ["acct_loan"],
      "externalApis": [],
      "callChain": [
        {
          "stepNumber": 1,
          "level": 0,
          "methodId": "com.example.LoanController#createLoan",
          "methodName": "createLoan",
          "className": "com.example.LoanController",
          "layerType": "CONTROLLER",
          "description": "[控制器层] createLoan - 创建贷款申请",
          "hasDatabaseOperation": false,
          "tableName": null,
          "isExternalCall": false,
          "externalApiUrl": null,
          "calledMethods": [
            "com.example.LoanService#createLoan",
            "com.example.LoanValidator#validate"
          ]
        },
        {
          "stepNumber": 2,
          "level": 1,
          "methodId": "com.example.LoanService#createLoan",
          "methodName": "createLoan",
          "className": "com.example.LoanService",
          "layerType": "SERVICE",
          "description": "[服务层] createLoan - 创建贷款",
          "hasDatabaseOperation": false,
          "tableName": null,
          "isExternalCall": false,
          "calledMethods": [
            "com.example.LoanMapper#insert",
            "com.example.AccountService#freezeAmount"
          ]
        },
        {
          "stepNumber": 3,
          "level": 2,
          "methodId": "com.example.LoanMapper#insert",
          "methodName": "insert",
          "className": "com.example.LoanMapper",
          "layerType": "MAPPER",
          "description": "[数据访问层] insert - 插入贷款记录",
          "hasDatabaseOperation": true,
          "tableName": "acct_loan",
          "isExternalCall": false,
          "calledMethods": []
        }
      ]
    }
  ]
}
```

**实现**：

```kotlin
/**
 * 工具：获取业务流程
 */
suspend fun getBusinessProcess(params: Map<String, Any>): ToolResult {
    val processName = params["processName"] as? String
        ?: return ToolResult.failure("缺少 processName 参数")

    // 1. 从向量库中搜索业务流程
    val searchResults = vectorSearchService.search(VectorSearchRequest(
        projectKey = projectKey,
        query = processName,
        recallTopK = 10,
        rerankTopN = 1,
        metadataFilter = mapOf("type" to "business_process")
    ))

    if (searchResults.results.isEmpty()) {
        return ToolResult.failure("未找到业务流程: $processName")
    }

    // 2. 提取业务流程数据
    val process = searchResults.results[0]
    val processId = process.metadata["processId"] as? String
        ?: return ToolResult.failure("流程数据格式错误")

    // 3. 从数据库中加载完整的业务流程
    val businessProcess = businessProcessRepository.findById(processId)
        ?: return ToolResult.failure("流程不存在: $processId")

    // 4. 格式化输出
    val result = mapOf(
        "processId" to businessProcess.processId,
        "processName" to businessProcess.processName,
        "description" to businessProcess.description,
        "stepCount" to businessProcess.steps.size,
        "entranceMethodId" to businessProcess.entrance.methodId,
        "entranceClassName" to businessProcess.entrance.className,
        "involvedTables" to businessProcess.involvedTables,
        "externalApis" to businessProcess.externalApis,
        "callChain" to businessProcess.steps
    )

    return ToolResult.success(listOf(result))
}
```

### 4. 外部接口反向查询

**工具：find_callers_of_external_api**

**功能**：查找调用某外部接口的所有业务流程

**参数**：
- `apiName`（必需）：外部接口名称，支持模糊匹配

**返回**：
```json
{
  "success": true,
  "data": [
    {
      "processName": "贷款放款流程",
      "stepNumber": 5,
      "description": "调用存款核心做资金划拨",
      "externalApiUrl": "http://core.bank.com/api/transfer"
    }
  ]
}
```

**实现**：

```kotlin
/**
 * 工具：反向查询外部接口调用
 */
suspend fun findCallersOfExternalApi(params: Map<String, Any>): ToolResult {
    val apiName = params["apiName"] as? String
        ?: return ToolResult.failure("缺少 apiName 参数")

    // 1. 从数据库中查询所有包含外部接口调用的业务流程
    val processes = businessProcessRepository.findByExternalApiContaining(apiName)

    if (processes.isEmpty()) {
        return ToolResult.failure("未找到调用外部接口 '$apiName' 的流程")
    }

    // 2. 提取调用信息
    val callers = processes.flatMap { process ->
        process.steps.filter { it.isExternalCall && it.externalApiUrl?.contains(apiName) == true }
            .map { step ->
                mapOf(
                    "processName" to process.processName,
                    "stepNumber" to step.stepNumber,
                    "description" to step.description,
                    "externalApiUrl" to step.externalApiUrl
                )
            }
    }

    return ToolResult.success(callers)
}
```

### 5. 向量化存储

**目标**：将业务流程存入向量库，支持语义检索

**向量化策略**：

1. **文本生成**
   ```yaml
   流程名称: ${processName}
   流程类型: ${processType}
   流程描述: ${description}

   入口: ${entrance.className}#${entrance.methodName}

   流程步骤:
   {{#each steps}}
   ${stepNumber}. [${layerType}] ${className}#${methodName}
      {{#if description}}
      说明: ${description}
      {{/if}}
      {{#if hasDatabaseOperation}}
      数据库操作: ${tableName}
      {{/if}}
      {{#if isExternalCall}}
      外部接口: ${externalApiUrl}
      {{/if}}
   {{/each}}

   涉及的表: ${involvedTables}
   外部接口: ${externalApis}
   ```

2. **向量化**
   - 使用 BGE-M3 模型生成 embedding
   - 维度：1024
   - 分片策略：如果文本超过 500 tokens，按步骤分片

3. **存储**
   ```json
   {
     "id": "process-${processId}",
     "vector": [0.1, 0.2, ...],  // 1024维向量
     "metadata": {
       "type": "business_process",
       "projectKey": "${projectKey}",
       "processId": "${processId}",
       "processName": "${processName}",
       "processType": "${processType}",
       "involvedTables": ["acct_loan"],
       "stepCount": 7
     },
     "payload": {
       "process": "${BusinessProcess序列化后的JSON}"
     }
   }
   ```

**实现**：

```kotlin
/**
 * 业务流程向量化服务
 */
class BusinessProcessVectorizationService(
    private val vectorDatabase: VectorDatabase,
    private val vectorClient: BgeM3Client
) {

    /**
     * 将业务流程向量化并存储
     */
    suspend fun vectorize(process: BusinessProcess) {
        val text = buildProcessText(process)
        val vector = vectorClient.embed(text)

        val fragment = VectorFragment(
            id = "process-${process.processId}",
            vector = vector,
            metadata = mapOf(
                "type" to "business_process",
                "projectKey" to process.projectKey,
                "processId" to process.processId,
                "processName" to process.processName,
                "processType" to process.processType.name,
                "involvedTables" to process.involvedTables,
                "stepCount" to process.steps.size
            ),
            payload = mapOf(
                "process" to Json.encodeToString(process)
            )
        )

        vectorDatabase.insert(fragment)
    }

    /**
     * 构建业务流程文本
     */
    private fun buildProcessText(process: BusinessProcess): String {
        return buildString {
            appendLine("流程名称: ${process.processName}")
            appendLine("流程类型: ${process.processType.description}")
            appendLine("流程描述: ${process.description}")
            appendLine()
            appendLine("入口: ${process.entrance.className}#${process.entrance.methodName}")
            appendLine()
            appendLine("流程步骤:")

            for (step in process.steps) {
                appendLine("${step.stepNumber}. [${step.layerType.chineseName}] ${step.className}#${step.methodName}")

                if (step.description.isNotBlank()) {
                    appendLine("   说明: ${step.description}")
                }

                if (step.hasDatabaseOperation) {
                    appendLine("   数据库操作: ${step.tableName}")
                }

                if (step.isExternalCall) {
                    appendLine("   外部接口: ${step.externalApiUrl}")
                }
            }

            if (process.involvedTables.isNotEmpty()) {
                appendLine()
                appendLine("涉及的表: ${process.involvedTables.joinToString(", ")}")
            }

            if (process.externalApis.isNotEmpty()) {
                appendLine()
                appendLine("外部接口: ${process.externalApis.joinToString(", ")}")
            }
        }
    }

    /**
     * 批量向量化
     */
    suspend fun vectorizeBatch(processes: List<BusinessProcess>) {
        processes.forEach { process ->
            try {
                vectorize(process)
            } catch (e: Exception) {
                logger.error("向量化失败: ${process.processId}", e)
            }
        }
    }
}
```

## Kotlin 实现

### 文件位置

```
src/main/kotlin/com/smancode/smanagent/analyzer/
├── process/
│   ├── BusinessProcessExtractor.kt      # 业务流程提取器
│   ├── BusinessProcessVectorizationService.kt  # 向量化服务
│   ├── model/
│   │   ├── BusinessProcess.kt           # 业务流程数据模型
│   │   ├── ProcessStep.kt               # 流程步骤
│   │   ├── ProcessType.kt               # 流程类型枚举
│   │   └── LayerType.kt                 # 层次类型枚举
│   └── repository/
│       └── BusinessProcessRepository.kt # 业务流程仓储
```

### 核心接口

```kotlin
/**
 * 业务流程提取器接口
 */
interface BusinessProcessExtractor {
    /**
     * 从代码走读结果中提取业务流程
     */
    suspend fun extractFromWalkResult(walkResult: WalkResult): List<BusinessProcess>

    /**
     * 保存业务流程到数据库
     */
    suspend fun saveProcesses(processes: List<BusinessProcess>): Int

    /**
     * 根据方法ID查找相关的业务流程
     */
    suspend fun findProcessesByMethod(methodId: String): List<BusinessProcess>
}

/**
 * 业务流程仓储接口
 */
interface BusinessProcessRepository {
    /**
     * 保存业务流程
     */
    suspend fun save(process: BusinessProcess)

    /**
     * 根据ID查找业务流程
     */
    suspend fun findById(processId: String): BusinessProcess?

    /**
     * 根据项目Key查找所有业务流程
     */
    suspend fun findByProjectKey(projectKey: String): List<BusinessProcess>

    /**
     * 根据外部接口查找业务流程
     */
    suspend fun findByExternalApiContaining(apiName: String): List<BusinessProcess>
}

/**
 * 业务流程向量化服务接口
 */
interface BusinessProcessVectorizationService {
    /**
     * 将业务流程向量化并存储
     */
    suspend fun vectorize(process: BusinessProcess)

    /**
     * 批量向量化
     */
    suspend fun vectorizeBatch(processes: List<BusinessProcess>

    /**
     * 从向量库中搜索业务流程
     */
    suspend fun search(query: String, topK: Int = 10): List<BusinessProcess>
}
```

### 数据模型

```kotlin
/**
 * 业务流程
 */
@Serializable
data class BusinessProcess(
    val processId: String,
    val processName: String,
    val projectKey: String,
    val description: String,
    val entrance: ProcessStep,
    val steps: List<ProcessStep>,
    val processType: ProcessType,
    val involvedTables: List<String> = emptyList(),
    val externalApis: List<String> = emptyList(),
    val methodIds: List<String> = emptyList(),
    val entityIds: List<String> = emptyList(),
    val ruleIds: List<String> = emptyList(),
    val capabilityIds: List<String> = emptyList()
)

/**
 * 流程类型枚举
 */
@Serializable
enum class ProcessType(val description: String) {
    HTTP_REQUEST("HTTP请求"),
    RPC_CALL("RPC调用"),
    SCHEDULED_TASK("定时任务"),
    MESSAGE_PROCESSING("消息处理"),
    INTERNAL_SUBFLOW("内部子流程")
}

/**
 * 流程步骤
 */
@Serializable
data class ProcessStep(
    val stepNumber: Int,
    val level: Int,
    val methodId: String,
    val methodName: String,
    val className: String,
    val layerType: LayerType,
    val description: String,
    val hasDatabaseOperation: Boolean = false,
    val tableName: String? = null,
    val isExternalCall: Boolean = false,
    val externalApiUrl: String? = null,
    val calledMethods: List<String> = emptyList()
)

/**
 * 层次类型枚举
 */
@Serializable
enum class LayerType(val chineseName: String) {
    CONTROLLER("控制器层"),
    SERVICE("服务层"),
    MAPPER("数据访问层"),
    DATABASE("数据库"),
    EXTERNAL_API("外部接口"),
    OTHER("其他")
}
```

### 工具集成

```kotlin
/**
 * 案例SOP工具
 */
object CaseSopTools {

    /**
     * 工具1: 获取业务流程
     */
    suspend fun getBusinessProcess(
        params: Map<String, Any>,
        projectKey: String
    ): ToolResult {
        val processName = params["processName"] as? String
            ?: return ToolResult.failure("缺少 processName 参数")

        // 1. 从向量库中搜索业务流程
        val vectorSearchService = ServiceManager.getService(VectorSearchService::class.java)
        val searchResults = vectorSearchService.search(
            query = processName,
            projectKey = projectKey,
            recallTopK = 10,
            rerankTopN = 1,
            metadataFilter = mapOf("type" to "business_process")
        )

        if (searchResults.isEmpty()) {
            return ToolResult.failure("未找到业务流程: $processName")
        }

        // 2. 提取业务流程数据
        val process = searchResults[0]
        val result = mapOf(
            "processId" to process.processId,
            "processName" to process.processName,
            "description" to process.description,
            "stepCount" to process.steps.size,
            "entranceMethodId" to process.entrance.methodId,
            "entranceClassName" to process.entrance.className,
            "involvedTables" to process.involvedTables,
            "externalApis" to process.externalApis,
            "callChain" to process.steps
        )

        return ToolResult.success(listOf(result))
    }

    /**
     * 工具2: 反向查询外部接口调用
     */
    suspend fun findCallersOfExternalApi(
        params: Map<String, Any>,
        projectKey: String
    ): ToolResult {
        val apiName = params["apiName"] as? String
            ?: return ToolResult.failure("缺少 apiName 参数")

        // 1. 从数据库中查询所有包含外部接口调用的业务流程
        val repository = ServiceManager.getService(BusinessProcessRepository::class.java)
        val processes = repository.findByExternalApiContaining(apiName)

        if (processes.isEmpty()) {
            return ToolResult.failure("未找到调用外部接口 '$apiName' 的流程")
        }

        // 2. 提取调用信息
        val callers = processes.flatMap { process ->
            process.steps.filter { it.isExternalCall && it.externalApiUrl?.contains(apiName) == true }
                .map { step ->
                    mapOf(
                        "processName" to process.processName,
                        "stepNumber" to step.stepNumber,
                        "description" to step.description,
                        "externalApiUrl" to step.externalApiUrl
                    )
                }
        }

        return ToolResult.success(callers)
    }
}
```

## 与 knowledge-graph-system 的差异

### 可以借鉴的部分

1. **业务流程提取**
   - 基于入口识别流程
   - BFS 遍历调用链
   - 层次类型识别（Controller、Service、Mapper）
   - 数据库操作识别
   - 外部接口识别

2. **数据模型**
   - BusinessProcess 完整的数据结构
   - ProcessStep 详细的步骤信息
   - 流程类型和层次类型枚举

3. **工具设计**
   - `get_business_process` 工具实现
   - `find_callers_of_external_api` 反向查询
   - JSON 格式的调用链返回

### 需要调整的部分

1. **存储方式**
   - knowledge-graph-system：存入图数据库（InMemoryGraphDatabase）
   - SmanAgent：存入向量库（支持 BGE 召回 + 重排）

2. **代码走读方式**
   - knowledge-graph-system：使用 Spoon 进行静态分析
   - SmanAgent：使用 IntelliJ PSI 进行实时分析

3. **LLM 集成**
   - knowledge-graph-system：独立的 LLM 服务
   - SmanAgent：集成的 ReAct Loop 架构

4. **向量化策略**
   - knowledge-graph-system：先提取流程，后向量化
   - SmanAgent：边提取边向量化（流式处理）

## 专家知识库

### 关键问题

1. **如何识别入口方法？**
   - 检查方法是否有入口注解（@RestController、@KafkaListener 等）
   - 检查类是否在入口包下（controller、handler、listener）
   - 使用启发式类名匹配（*Controller、*Handler）

2. **如何避免循环调用？**
   - 使用 BFS 遍历，记录已访问的方法
   - 使用 visited 集合去重
   - 限制最大深度（默认 10 层）

3. **如何识别数据库操作？**
   - 检查方法是否在 Mapper/Repository 中
   - 检查方法名是否包含 SQL 关键字（insert、update、delete、select）
   - 从 LLM 分析结果中提取 databaseOperations

4. **如何提取外部接口 URL？**
   - 从注解中提取（@FeignClient、@RequestMapping）
   - 从配置文件中提取（application.yml）
   - 从方法调用中提取（RestTemplate.postForObject）

5. **如何生成可读的流程描述？**
   - 使用层次类型标注（[控制器层]、[服务层]）
   - 使用 LLM 分析的业务语义
   - 添加流程统计信息（步骤数、最大深度、涉及的表）

### 最佳实践

1. **流程提取顺序**
   - 先提取所有入口方法
   - 再为每个入口方法构建流程
   - 最后向量化并存储

2. **性能优化**
   - 使用 BFS 遍历（比 DFS 更节省内存）
   - 限制最大深度（避免无限递归）
   - 缓存已访问的方法（避免重复处理）

3. **文本生成**
   - 使用结构化模板（易于解析）
   - 包含完整信息（入口、步骤、表、外部接口）
   - 控制文本长度（不超过 500 tokens）

4. **向量化策略**
   - 先生成文本，再向量化
   - 使用 BGE-M3 模型（支持中文）
   - 批量处理（提高效率）

## 待解决的问题

1. **流程去重**
   - 同一个入口可能被多次走读
   - 如何合并重复的流程？
   - 如何更新已有的流程？

2. **增量更新**
   - 代码变更后如何更新流程？
   - 如何识别流程是否需要更新？
   - 如何保留历史版本？

3. **流程合并**
   - 多个入口可能共享部分流程
   - 如何识别和提取公共流程？
   - 如何表示流程的嵌套关系？

4. **LLM 分析优化**
   - 如何提高业务语义分析的准确率？
   - 如何处理 LLM 分析失败的情况？
   - 如何缓存分析结果？

5. **向量检索优化**
   - 如何设计 metadata 过滤器？
   - 如何提高检索的准确率？
   - 如何支持多条件组合查询？

## 下一步

- [ ] 实现基础的业务流程提取
- [ ] 实现 BFS 调用链遍历
- [ ] 实现层次类型识别
- [ ] 实现数据库操作识别
- [ ] 实现外部接口识别
- [ ] 实现业务流程向量化
- [ ] 集成到工具系统（get_business_process）
- [x] 实现反向查询（find_callers_of_external_api）
- [x] 编写单元测试
- [ ] 性能优化和增量更新

---

## ✅ 实现完成 (2026-01-30)

### 实现文件

- **位置**: `src/main/kotlin/com/smancode/smanagent/analysis/sop/CaseSopGenerator.kt`
- **测试**: 所有测试通过 (196 tests passed)

### 实现功能

1. ✅ **业务场景推断** - 从类名和包名推断业务场景
2. ✅ **SOP 生成** - 生成标准操作流程文档
3. ✅ **前置条件提取** - 识别依赖、配置等前置条件
4. ✅ **操作步骤提取** - 从方法生成操作步骤
5. ✅ **预期结果生成** - 推断操作预期结果

### 验证状态

```bash
./gradlew test
# BUILD SUCCESSFUL - 196 tests completed
```
