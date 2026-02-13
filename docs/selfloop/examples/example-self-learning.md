# 示例: 后台自学习流程

## 场景描述

后台 SelfEvolutionLoop 自主运行，发现知识盲点并学习。

## 完整流程

### Step 1: 加载项目记忆

```kotlin
// SelfEvolutionLoop 加载项目记忆
val memory = memoryManager.getProjectMemory("my-project")

// 返回:
ProjectMemory(
    projectKey = "my-project",
    domainKnowledge = [
        DomainKnowledge(domain = "还款", summary = "...", keyFiles = [...]),
        DomainKnowledge(domain = "订单", summary = "...", keyFiles = [...])
    ],
    learningRecordIds = ["lr-001", "lr-002", "lr-003"],
    evolutionStatus = EvolutionStatus(
        questionsGeneratedToday = 5,
        totalQuestionsExplored = 15
    )
)
```

### Step 2: 生成好问题

```kotlin
// QuestionGenerator.generate(memory, count = 3)

// LLM Prompt:
"""
你是一个代码分析专家，正在深入理解一个项目。

## 项目背景
- 技术栈: Spring Boot 3.2, MyBatis, MySQL
- 已知领域: 还款, 订单

## 已学到的知识
- 还款方式有哪些类型？
- 还款计算的核心逻辑是什么？
- 订单状态如何流转？

## 知识盲点
- 支付模块尚未探索
- 通知模块与其他模块的关系不明

## 任务
生成 3 个值得探索的好问题。
"""

// LLM 响应:
{
  "questions": [
    {
      "question": "支付模块的核心流程是什么？",
      "type": "BUSINESS_LOGIC",
      "priority": 9,
      "reason": "支付是核心业务模块，且尚未探索",
      "suggestedTools": ["expert_consult", "read_file", "call_chain"],
      "expectedOutcome": "理解支付流程，找到支付入口和核心类"
    },
    {
      "question": "还款模块如何与支付模块交互？",
      "type": "DEPENDENCY",
      "priority": 8,
      "reason": "建立模块间的关联关系",
      "suggestedTools": ["grep_file", "call_chain"],
      "expectedOutcome": "理解模块间调用关系"
    },
    {
      "question": "项目中使用了哪些设计模式？",
      "type": "BEST_PRACTICE",
      "priority": 6,
      "reason": "理解代码架构风格",
      "suggestedTools": ["expert_consult", "grep_file"],
      "expectedOutcome": "发现策略模式、工厂模式等"
    }
  ]
}
```

### Step 3: 死循环检测

```kotlin
// DoomLoopGuard.shouldSkipQuestion(projectKey, question)

// 检查 1: 是否在退避期
backoffManager.isInBackoff("my-project")  // false

// 检查 2: 每日配额
quotaManager.canGenerateQuestion("my-project")  // true (5/50)

// 检查 3: 问题去重
questionDeduplicator.isDuplicate("支付模块的核心流程是什么？", "my-project")
// 向量搜索 -> 相似度最高的 0.72 -> 不重复

// 结果: 通过检测，可以执行
```

### Step 4: 工具探索

```kotlin
// ToolExplorer.explore(question)

// ========== 探索步骤 1 ==========
// LLM 决策: 使用 expert_consult 搜索 "支付"
val action1 = NextAction(
    toolName = "expert_consult",
    parameters = mapOf("query" to "支付", "topK" to 10)
)

// 执行结果:
找到 8 个相关代码片段:
- PaymentService.java (similarity: 0.91)
- PaymentController.java (similarity: 0.88)
- PaymentRepository.java (similarity: 0.85)
...

// ========== 探索步骤 2 ==========
// LLM 决策: 读取 PaymentService.java
val action2 = NextAction(
    toolName = "read_file",
    parameters = mapOf("relativePath" to "src/main/java/com/example/payment/PaymentService.java")
)

// 执行结果:
PaymentService 包含以下核心方法:
- createPayment(): 创建支付单
- executePayment(): 执行支付
- queryPaymentStatus(): 查询支付状态
- handleCallback(): 处理支付回调

// ========== 探索步骤 3 ==========
// LLM 决策: 分析调用链
val action3 = NextAction(
    toolName = "call_chain",
    parameters = mapOf("method" to "PaymentService.executePayment", "direction" to "DOWNSTREAM")
)

// 执行结果:
executePayment() 调用:
- PaymentValidator.validate()
- PaymentGateway.pay()
- PaymentRepository.save()
- NotificationService.notify()

// ========== 探索步骤 4 ==========
// LLM 决策: 信息已足够，停止探索
val action4 = NextAction(shouldStop = true)

// 探索结果:
ExplorationResult(
    question = GeneratedQuestion(question = "支付模块的核心流程是什么？", ...),
    steps = [
        ToolCallStep(toolName = "expert_consult", ...),
        ToolCallStep(toolName = "read_file", ...),
        ToolCallStep(toolName = "call_chain", ...)
    ],
    success = true
)
```

### Step 5: LLM 总结学习成果

```kotlin
// LearningRecorder.summarize(question, explorationResult)

// LLM Prompt:
"""
你刚刚探索了一个问题，请总结你学到的内容。

## 问题
支付模块的核心流程是什么？

## 探索过程
### 步骤 1: expert_consult
结果: 找到 PaymentService, PaymentController 等核心类

### 步骤 2: read_file
结果: PaymentService 包含 createPayment, executePayment 等方法

### 步骤 3: call_chain
结果: executePayment 调用 validate -> gateway -> save -> notify

请总结学到的内容。
"""

// LLM 响应:
{
  "answer": "支付模块的核心流程在 PaymentService 中实现。" +
            "流程为: 创建支付单(createPayment) -> 执行支付(executePayment) -> 处理回调(handleCallback)。" +
            "executePayment 内部调用: PaymentValidator 校验 -> PaymentGateway 执行 -> PaymentRepository 持久化 -> NotificationService 通知。" +
            "支付状态通过 queryPaymentStatus 查询。",
  "confidence": 0.92,
  "sourceFiles": [
    "src/main/java/com/example/payment/PaymentService.java",
    "src/main/java/com/example/payment/PaymentController.java",
    "src/main/java/com/example/payment/PaymentRepository.java",
    "src/main/java/com/example/payment/PaymentGateway.java"
  ],
  "tags": ["支付", "流程", "PaymentService"],
  "domain": "支付"
}
```

### Step 6: 向量化并持久化

```kotlin
// 1. 向量化问题和答案
val questionVector = bgeM3Client.embed("支付模块的核心流程是什么？")
val answerVector = bgeM3Client.embed(answer)

// 2. 构建学习记录
val record = LearningRecord(
    id = "lr-004",
    projectKey = "my-project",
    createdAt = System.currentTimeMillis(),
    question = "支付模块的核心流程是什么？",
    questionType = QuestionType.BUSINESS_LOGIC,
    answer = "...",
    explorationPath = [...],
    confidence = 0.92,
    sourceFiles = [...],
    questionVector = questionVector,
    answerVector = answerVector,
    tags = ["支付", "流程", "PaymentService"],
    domain = "支付"
)

// 3. 持久化
// 3.1 写入 H2
db.insert("learning_records", record)

// 3.2 写入向量索引
vectorStore.index(record.id, record.answerVector, metadata)

// 3.3 更新 project_map.json
projectMap.learningRecordIndex.add("lr-004")
```

### Step 7: 更新项目记忆

```kotlin
// 更新领域知识
if (!memory.domainKnowledge.any { it.domain == "支付" }) {
    memoryManager.addDomainKnowledge("my-project", DomainKnowledge(
        domain = "支付",
        summary = "支付模块处理支付核心流程...",
        keyFiles = [...]
    ))
}

// 更新进化状态
memory.evolutionStatus.questionsGeneratedToday++  // 6
memory.evolutionStatus.totalQuestionsExplored++   // 16
```

### Step 8: 休眠后继续

```kotlin
// 休眠 1 分钟后，开始下一轮迭代
delay(60000)
// -> 回到 Step 1
```

## 学习成果示例

经过一段时间的后台自学习，project_map.json 可能包含:

```json
{
  "learningRecordIndex": [
    "lr-001: 还款方式有哪些类型？",
    "lr-002: 还款计算的核心逻辑是什么？",
    "lr-003: 订单状态如何流转？",
    "lr-004: 支付模块的核心流程是什么？",
    "lr-005: 还款模块如何与支付模块交互？",
    "lr-006: 项目中使用了哪些设计模式？",
    "lr-007: 通知模块的实现方式？",
    "lr-008: 数据库表之间的关联关系？",
    "lr-009: 异常处理机制是什么？",
    "lr-010: 日志记录规范是什么？",
    ...
  ],
  "domainKnowledgeIndex": [
    "dk-还款",
    "dk-订单",
    "dk-支付",
    "dk-通知"
  ]
}
```

## 关键特点

1. **自主驱动**: 不需要用户干预，持续学习
2. **好问题优先**: LLM 生成有价值的问题
3. **避免重复**: 向量相似度检测已探索问题
4. **结构化知识**: 产出领域知识和学习记录
5. **前台可用**: 学习成果可被用户请求使用
