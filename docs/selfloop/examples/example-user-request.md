# 示例: 用户需求处理

## 场景描述

用户请求: "增加先本后息的还款方式"

这是一个典型的功能需求，前台需要加载后台学习成果来高效处理。

## 完整流程

### Step 1: 用户输入

```
用户在 SmanChatPanel 输入: "增加先本后息的还款方式"
```

### Step 2: 意图分析

```kotlin
// IntentAnalyzer.analyze("增加先本后息的还款方式")

// LLM 调用
val prompt = """
分析用户请求，提取关键信息。

用户请求: "增加先本后息的还款方式"

输出 JSON:
{
  "domains": ["领域关键词"],
  "action": "操作类型",
  "keywords": ["其他关键词"]
}
"""

// LLM 响应
{
  "domains": ["还款", "还款方式"],
  "action": "ADD_FEATURE",
  "keywords": ["先本后息", "还款方式", "计算逻辑", "枚举"]
}
```

### Step 3: 多路召回

```kotlin
// 并行执行三路召回

// 3.1 向量语义搜索
val vectorResults = bgeM3Client.search(
    query = "还款方式",
    projectKey = "my-project",
    topK = 10
)

// 返回结果:
[
  { id: "lr-001", question: "还款方式有哪些类型？", score: 0.92 },
  { id: "lr-002", question: "还款计算的核心逻辑是什么？", score: 0.89 },
  { id: "lr-003", question: "还款模块与其他模块的交互关系？", score: 0.86 },
  { id: "lr-004", question: "先息后本的实现方式？", score: 0.83 },
  ...
]

// 3.2 领域知识查询
val domainKnowledge = memoryManager.getDomainKnowledge("my-project", "还款")

// 返回:
{
  domain: "还款",
  summary: "还款模块处理所有还款相关业务...",
  keyConcepts: [
    { name: "还款方式", description: "定义还款的计算方式" },
    { name: "还款计划", description: "还款的时间安排" }
  ],
  keyFiles: [
    "src/main/java/com/example/repayment/RepaymentService.java",
    "src/main/java/com/example/repayment/RepaymentType.java",
    "src/main/java/com/example/repayment/RepaymentCalculator.java"
  ]
}

// 3.3 代码片段搜索
val codeFragments = vectorStore.searchCode(
    query = "还款方式枚举",
    projectKey = "my-project",
    topK = 5
)

// 返回:
[
  {
    filePath: "src/main/java/com/example/repayment/RepaymentType.java",
    content: "public enum RepaymentType { EQUAL_PRINCIPAL_INTEREST, EQUAL_PRINCIPAL, INTEREST_FIRST }"
  },
  ...
]
```

### Step 4: Rerank

```kotlin
// BGE-Reranker 精排
val reranked = reranker.rerank(
    query = "增加先本后息的还款方式",
    candidates = vectorResults
)

// 最终排序:
[
  { id: "lr-001", question: "还款方式有哪些类型？", score: 0.95 },
  { id: "lr-004", question: "先息后本的实现方式？", score: 0.93 },
  { id: "lr-002", question: "还款计算的核心逻辑是什么？", score: 0.90 },
  ...
]
```

### Step 5: 构建增强上下文

```kotlin
// EnhancedContext.toSystemPromptSection()

val contextSection = """
## 项目背景
- 技术栈: Spring Boot 3.2, MyBatis, MySQL
- 项目路径: /Users/liuchao/projects/my-project

## 相关领域知识
### 还款
还款模块处理所有还款相关业务，包括还款计划生成、还款执行、还款状态跟踪等。
关键文件: RepaymentService.java, RepaymentCalculator.java

## 相关学习记录 (后台已学到的知识)
### 知识 1 (相关度: 95%)
问题: 还款方式有哪些类型？
答案: 还款方式包括等额本息(EQUAL_PRINCIPAL_INTEREST)、等额本金(EQUAL_PRINCIPAL)、
      先息后本(INTEREST_FIRST)三种。定义在 RepaymentType 枚举中。
涉及文件: RepaymentType.java, RepaymentCalculator.java

### 知识 2 (相关度: 93%)
问题: 先息后本的实现方式？
答案: 先息后本在 RepaymentCalculator 中实现，calculateInterestFirst() 方法。
      前期只还利息，最后一期归还全部本金。
涉及文件: RepaymentCalculator.java

### 知识 3 (相关度: 90%)
问题: 还款计算的核心逻辑是什么？
答案: 还款计算在 RepaymentCalculator 中实现，使用策略模式。
      根据不同的 RepaymentType 调用不同的计算方法。
涉及文件: RepaymentCalculator.java

## 相关代码片段
文件: src/main/java/com/example/repayment/RepaymentType.java
```
public enum RepaymentType {
    EQUAL_PRINCIPAL_INTEREST,  // 等额本息
    EQUAL_PRINCIPAL,           // 等额本金
    INTEREST_FIRST             // 先息后本
}
```
"""
```

### Step 6: SmanLoop ReAct 循环

```
========== Round 1: 理解需求 ==========

LLM 思考:
基于上下文，我理解用户需求:
1. 需要增加新的还款方式: 先本后息
2. 现有枚举有三种: 等额本息、等额本金、先息后本
3. 需要新增: PRINCIPAL_FIRST (先本后息)
4. 需要在 RepaymentCalculator 中增加对应计算方法

========== Round 2: 定位代码 ==========

工具调用: read_file
参数: { relativePath: "src/main/java/com/example/repayment/RepaymentType.java" }

结果:
```java
public enum RepaymentType {
    EQUAL_PRINCIPAL_INTEREST,  // 等额本息
    EQUAL_PRINCIPAL,           // 等额本金
    INTEREST_FIRST             // 先息后本
}
```

工具调用: read_file
参数: { relativePath: "src/main/java/com/example/repayment/RepaymentCalculator.java" }

结果:
```java
public class RepaymentCalculator {

    public BigDecimal calculate(RepaymentType type, Loan loan) {
        switch (type) {
            case EQUAL_PRINCIPAL_INTEREST:
                return calculateEqualPrincipalInterest(loan);
            case EQUAL_PRINCIPAL:
                return calculateEqualPrincipal(loan);
            case INTEREST_FIRST:
                return calculateInterestFirst(loan);
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    // ... 各计算方法实现
}
```

========== Round 3: 设计方案 ==========

LLM 思考:
修改方案:
1. 在 RepaymentType 中增加 PRINCIPAL_FIRST 枚举值
2. 在 RepaymentCalculator.calculate() 的 switch 中增加 case
3. 新增 calculatePrincipalFirst() 方法
4. 计算逻辑: 前期只还本金，最后一期归还全部利息

========== Round 4: 应用修改 ==========

工具调用: apply_change
参数: {
  relativePath: "src/main/java/com/example/repayment/RepaymentType.java",
  searchContent: "public enum RepaymentType {\n    EQUAL_PRINCIPAL_INTEREST,\n    EQUAL_PRINCIPAL,\n    INTEREST_FIRST\n}",
  newContent: "public enum RepaymentType {\n    EQUAL_PRINCIPAL_INTEREST,\n    EQUAL_PRINCIPAL,\n    INTEREST_FIRST,\n    PRINCIPAL_FIRST  // 先本后息\n}"
}

工具调用: apply_change
参数: {
  relativePath: "src/main/java/com/example/repayment/RepaymentCalculator.java",
  searchContent: "case INTEREST_FIRST:\n                return calculateInterestFirst(loan);\n            default:",
  newContent: "case INTEREST_FIRST:\n                return calculateInterestFirst(loan);\n            case PRINCIPAL_FIRST:\n                return calculatePrincipalFirst(loan);\n            default:"
}

// 新增计算方法
工具调用: apply_change
参数: {
  mode: "APPEND",
  relativePath: "src/main/java/com/example/repayment/RepaymentCalculator.java",
  newContent: "\n    private BigDecimal calculatePrincipalFirst(Loan loan) {\n        // 先本后息: 前期只还本金，最后一期归还全部利息\n        BigDecimal principalPerPeriod = loan.getAmount().divide(\n            BigDecimal.valueOf(loan.getPeriods()), 2, RoundingMode.HALF_UP);\n        return principalPerPeriod; // 前 N-1 期\n    }"
}
```

### Step 7: 返回结果给用户

```
已为您添加"先本后息"还款方式:

1. 在 RepaymentType.java 中增加了 PRINCIPAL_FIRST 枚举值

2. 在 RepaymentCalculator.java 中:
   - 增加了 case PRINCIPAL_FIRST 分支
   - 新增了 calculatePrincipalFirst() 方法

计算逻辑:
- 前 N-1 期: 每期归还 本金 / 期数
- 最后一期: 归还剩余本金 + 全部利息

建议测试:
1. 验证新的还款计算是否正确
2. 检查数据库字段是否需要更新 (如有)
3. 更新前端还款方式选项
```

## 关键价值

通过加载后台学习成果，SmanLoop 能够:

1. **快速定位代码**: 已知 RepaymentType 和 RepaymentCalculator 的位置
2. **准确理解结构**: 已知枚举值和策略模式
3. **高效设计方案**: 基于已有模式扩展，而非从头分析
4. **减少探索步骤**: 从 10+ 步减少到 4 步
