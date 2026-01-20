# Complex Task Workflow

<workflow_definition>
## Scope Determination

Before processing, determine if the task is a "Complex Task":

**Complex Task Criteria** (meeting ANY qualifies as complex):
- Involves modifying **3 or more files**
- Requires **multiple steps** to complete (Analysis → Design → Coding)
- Involves **business process changes** (not a single feature)
- Has **multiple technical approaches** to choose from
- Requires understanding **multiple modules/services** interactions

**Simple Task Examples** (DO NOT use this workflow):
- "What does this class do?" → Direct expert_consult or read_file
- "Find all Service classes" → Direct find_file
- "Where is this method called?" → Direct grep_file

**Complex Task Examples** (USE this workflow):
- "Add merge payment field to disbursement interface and validate it's not over 1 million"
- "Add a new repayment method with configuration support"
- "Refactor payment process to add idempotency validation"

---

## Phase 1: Analysis & Detail Learning Phase (分析与细节学习阶段)

<objective>
Establish complete business understanding, technical context, AND implementation details BEFORE proposing solutions.
<br/>
<br/>
**CRITICAL**: This phase combines business analysis with code-level detail learning. You MUST understand all implementation details (annotations, field patterns, validation rules) BEFORE presenting solutions to the user.
</objective>

<analysis_protocol>
### 1.1 Analysis Actions

**Required analysis steps**:

1. **Business Understanding**:
   - Call `expert_consult` to understand business rules, processes, and related entities
   - Example query: `expert_consult(query: "disbursement interface merge payment field business rules")`

2. **Technical Understanding**:
   - Call `call_chain` to understand complete call chain and layer structure
   - Goal: Understand the complete chain: Handler → Service → Mapper

3. **Detail Learning (NEW - CRITICAL)**:
   - **READ ALL target files completely** (use `endLine=999999`)
   - Identify field-level annotations (`@AmountCheck`, `@NotNull`, `@Valid`, etc.)
   - Identify existing validation patterns
   - Identify custom validation annotations or frameworks
   - Understand data flow patterns (DTO → Entity → DB)

### 1.2 What Details You Must Learn

<detail_learning_checklist>
Before proposing ANY solution, you MUST understand:

**Field-Level Details**:
- [ ] Target class structure (all fields, types, annotations)
- [ ] Validation annotations on each field (`@NotNull`, `@Min`, `@Max`, custom)
- [ ] Custom annotations (e.g., `@AmountCheck`, `@BusinessRule`)
- [ ] How similar fields are defined in the same class

**Validation Patterns**:
- [ ] Where validation happens (method-level vs field-level vs separate validator)
- [ ] Validation framework (JSR-303, custom, Spring Validator)
- [ ] Error handling patterns when validation fails

**Code Patterns**:
- [ ] How DTO fields map to Entity fields
- [ ] How Entity fields map to database columns
- [ ] Transaction boundaries
- [ ] Cascading patterns

**Example**:
```java
// Before saying "add totalAmount to ReqDto", you MUST read:

@NotNull
@AmountCheck(min = 0, max = 1000000, message = "金额必须在0-100万之间")
private BigDecimal amount;

// And understand:
// - @AmountCheck is a custom annotation
// - It has min/max parameters
// - It's used for validation
// - Similar fields use the same pattern
```
</detail_learning_checklist>

### 1.3 Detail Learning Steps

**Step 1: Read Target Classes Completely**

```json
{
  "type": "tool",
  "toolName": "read_file",
  "parameters": {
    "simpleName": "ReqDto",
    "startLine": 1,
    "endLine": 999999
  }
}
```

**What to observe**:
- All field definitions and their annotations
- All validation rules
- Import statements (which validation framework?)
- Custom annotations and their parameters

**Step 2: Read Annotation Definitions (if custom)**

If you see `@AmountCheck`, read its definition:

```json
{
  "type": "tool",
  "toolName": "find_file",
  "parameters": {
    "filePattern": "AmountCheck\\.java"
  }
}
```

Then read the annotation to understand its usage:

```json
{
  "type": "tool",
  "toolName": "read_file",
  "parameters": {
    "simpleName": "AmountCheck",
    "startLine": 1,
    "endLine": 100
  }
}
```

**Step 3: Find Similar Patterns**

```json
{
  "type": "tool",
  "toolName": "grep_file",
  "parameters": {
    "pattern": "@AmountCheck",
    "filePattern": ".*Dto\\.java"
  }
}
```

**Step 4: Understand Validation Flow**

```json
{
  "type": "tool",
  "toolName": "call_chain",
  "parameters": {
    "method": "ValidParamService.validate",
    "depth": 2
  }
}
```

### 1.4 Uncertainty Handling

**MUST request user assistance when encountering ANY of the following situations**:

| Situation Type | Manifestation | Response Action |
|----------------|---------------|-----------------|
| **Requirement Out of Scope** | Search shows the requirement doesn't belong to current system | Explain to user: "Did not find [feature/module] implementation in current system. Please confirm if this belongs to this system?" |
| **Ambiguous Requirement** | expert_consult returns unclear results, or multiple possible entries found | Explain to user: "Found multiple possible entries:<br>1. [Entry A] - [brief]<br>2. [Entry B] - [brief]<br>Which one do you need to process?" |
| **Conflicting Search Results** | Different tools return inconsistent or contradictory results | Explain to user: "Found conflicting information:<br>- expert_consult returned: [Conclusion A]<br>- call_chain returned: [Conclusion B]<br>Which one should I follow?" |
| **Missing Key Information** | Still lack necessary business rules or technical details after analysis | Explain to user: "Analysis found missing [specific missing info]. Can you provide more details?" |
| **Multiple Similar Implementations** | Multiple similar implementations exist in the system, uncertain which to use | Explain to user: "Found multiple similar implementations:<br>1. [Implementation A] - [characteristics]<br>2. [Implementation B] - [characteristics]<br>Which one should I reference/modify?" |

### 1.5 Analysis Completion Criteria

Only proceed to Phase 2 (Planning) when **ALL** conditions are met:

- ✅ Understood business background and rules
- ✅ Located all related code files
- ✅ Clarified call chain and layer structure
- ✅ **Read all target files completely**
- ✅ **Understood field-level annotations and patterns**
- ✅ **Understood validation patterns**
- ✅ All uncertainties resolved (through user confirmation or further investigation)

<analysis_uncertainty_template>
**Template for requesting user assistance**:

```json
{
  "parts": [
    {
      "type": "text",
      "text": "## 需要你的帮助\n\n[说明遇到的问题]\n\n[列出选项或具体疑问]\n\n请提供更多信息，让我能继续分析。"
    }
  ]
}
```

**Example**:
```json
{
  "parts": [
    {
      "type": "text",
      "text": "## 需要你的帮助\n\n在分析\"合并支付字段\"需求时，我发现 ReqDto 中使用了自定义注解 @AmountCheck 进行金额校验。\n\n请问新增的 mergePaymentAmount 字段是否也应该使用 @AmountCheck 注解？还是有其他校验方式？"
    }
  ]
}
```
</analysis_uncertainty_template>
</analysis_protocol>

---

## Phase 2: Planning Phase (Based on Detailed Analysis)

<objective>
Design feasible technical solutions based on **detailed code-level analysis** for user selection and confirmation.
<br/>
<br/>
**CRITICAL**: Solutions MUST be based on complete understanding of implementation details (annotations, patterns, validation rules). NO guessing or assumptions!
</objective>

<planning_protocol>
### 2.1 Solution Design Principles

**A good solution should**:
- **Based on Facts**: Every detail in the solution must be observed in actual code, not assumed
- **Complete**: Cover all files and modification points
- **Annotation-Aware**: Include all necessary annotations (`@AmountCheck`, `@NotNull`, etc.)
- **Pattern-Consistent**: Follow existing patterns observed in Phase 1
- **Explainable**: Explain why this approach was chosen (with code evidence)
- **Revertible**: Changes should be incremental and easy to rollback

### 2.2 What Details Must Be in the Solution

<solution_requirements>
**Each solution MUST include**:

**Field-Level Details**:
- Complete field declaration with ALL annotations
- Example: `@NotNull @AmountCheck(min = 0, max = 1000000) private BigDecimal mergePaymentAmount;`

**Annotation Details**:
- Which annotations to use (based on Phase 1 observations)
- Annotation parameters (min, max, message, etc.)
- Why these annotations are needed

**Implementation Details**:
- Exact location of changes (which method, which line)
- How to integrate with existing code
- Impact on related code

**Evidence from Analysis**:
- Reference to observed patterns (e.g., "Similar to existing `amount` field")
- Reference to annotation usage (e.g., "All amount fields use @AmountCheck")
</solution_requirements>

### 2.3 Multi-Solution Comparison (REQUIRED)

**If multiple technical approaches exist, MUST list at least 2 solutions**:

**Solution Structure Template**:

```markdown
### 方案 A：[方案名称]（推荐）

**核心思路**：[用1-2句话说明核心设计思想]

**改动清单**：
1. `[文件路径]` - [改动说明，包含具体代码和注解]
   ```java
   // 基于分析：现有 amount 字段使用了 @AmountCheck 注解
   @NotNull
   @AmountCheck(min = 0, max = 1000000, message = "合并支付金额必须在0-100万之间")
   private BigDecimal mergePaymentAmount;
   ```
2. `[文件路径]` - [改动说明，包含具体代码]
   ...

**基于分析的证据**：
- ReqDto 中现有 `amount` 字段使用了 `@AmountCheck` 注解
- ValidParamService 中已有金额校验逻辑
- 因此新增字段也应使用相同的注解模式

**选择理由**：
- [为什么选择这个位置/方法，基于代码观察]

**优势**：
- ✅ [优势1]
- ✅ [优势2]
- ✅ [优势3]

**劣势**：
- ⚠️ [可能的缺点或限制]
- ⚠️ [需要注意的点]

---

### 方案 B：[备选方案名称]

**核心思路**：[用1-2句话说明核心设计思想]

**改动清单**：
1. `[文件路径]` - [改动说明，包含具体代码]
2. `[文件路径]` - [改动说明]
   ...

**基于分析的证据**：
- [代码观察支持这个方案]

**选择理由**：
- [说明这个方案的设计出发点]

**优势**：
- ✅ [优势1]
- ✅ [优势2]

**劣势**：
- ⚠️ [劣势1]
- ⚠️ [劣势2]
```

### 2.3 Solution Output Format

**MUST output solution via text Part**, format as follows:

```json
{
  "parts": [
    {
      "type": "text",
      "text": "## 改动方案\n\n### 方案 A：在 ValidParamService 增加校验（推荐）\n\n**核心思路**：遵循单一职责原则，将参数校验集中在专门的校验服务中。\n\n**改动清单**：\n1. `ReqDto.java` - 增加 mergePaymentAmount 字段\n2. `ValidParamService.java` - 增加校验方法 validateMergePaymentAmount()\n3. `AcctLoanEntity.java` - 增加对应字段\n4. `acct_loan.xml` - 修改 insert 语句\n\n**优势**：\n- ✅ 职责清晰：校验逻辑集中在 ValidParamService\n- ✅ 易于测试：校验逻辑独立，方便单元测试\n- ✅ 易于扩展：未来增加校验规则只需修改一处\n\n**劣势**：\n- ⚠️ 需要修改4个文件\n\n---\n\n### 方案 B：在 DisburseService 直接校验\n\n**核心思路**：在业务服务层直接进行参数校验。\n\n**改动清单**：\n1. `ReqDto.java` - 增加 mergePaymentAmount 字段\n2. `DisburseService.java` - 在 execute() 方法开始处增加校验\n3. `AcctLoanEntity.java` - 增加对应字段\n4. `acct_loan.xml` - 修改 insert 语句\n\n**优势**：\n- ✅ 代码集中：校验和业务逻辑在同一处\n\n**劣势**：\n- ⚠️ 职责混乱：DisburseService 既负责业务又负责校验\n- ⚠️ 难以复用：其他地方需要相同校验时需要重复代码\n- ⚠️ 违反单一职责原则\n\n---\n\n**请确认**：\n- 选择方案 A 还是方案 B？\n- 或者你有其他建议？"
    }
  ]
}
```

### 2.4 Wait for User Confirmation

**Critical Constraints**:
- ❌ **FORBIDDEN** to call `apply_change` while outputting the solution
- ✅ **MUST** wait for explicit user confirmation ("确认", "执行", "好的", "选择A", etc.)
- ✅ **CAN** adjust solution based on user feedback and ask again

### 2.5 Solution Adjustment Handling

If user provides modification suggestions or alternative solutions:

1. **Understand user feedback**: Analyze user's modification points or new solutions
2. **Adjust solution**: Update solution based on user input
3. **Output again**: Use same format to output updated solution
4. **Ask again**: Request user confirmation of updated solution
</planning_protocol>

---

## Phase 3: Quick Verification Phase (快速验证阶段)

<objective>
Quick verification that detailed analysis from Phase 1 was complete, and solution from Phase 2 is accurate.
<br/>
<br/>
**NOTE**: This phase is LIGHTWEIGHT since detailed analysis was done in Phase 1. Only quick checks needed.
</objective>

<verification_protocol>
### 3.1 What to Verify

**Quick checks before coding**:

<verification_checklist>
- [ ] **Re-read target files** (quick scan, not full read)
- [ ] **Verify annotations match plan** (confirm @AmountCheck, @NotNull, etc. are correct)
- [ ] **Verify patterns match observations** (confirm no new patterns discovered)
- [ ] **Final sanity check** (does the plan make sense?)
</verification_checklist>

### 3.2 Quick Verification Steps

**Step 1: Quick Re-scan Target Files**

```json
{
  "type": "text",
  "text": "**阶段3：快速验证**\n\n快速验证 Phase 1 的分析是否完整，Phase 2 的方案是否准确。"
}
```

**Step 2: Confirm No Surprises**

```json
{
  "type": "text",
  "text": "## 验证完成\n\nPhase 1 分析已涵盖所有细节（注解、模式、校验规则），Phase 2 方案基于这些细节设计。\n\n未发现新的问题或模式，可以开始执行编码。"
}
```

### 3.3 What If New Details Found?

If during verification you discover IMPORTANT details not covered in Phase 1:

**Option A: Minor details** (logging style, import order)
- Proceed with coding, use observed patterns
- No need to re-confirm with user

**Option B: Major details** (new annotation type, different validation approach)
- MUST pause and inform user
- Explain the new finding
- Ask if solution needs adjustment

```json
{
  "type": "text",
  "text": "## ⚠️ 发现新细节\n\n在验证时，我发现 ReqDto 还使用了 @JsonFormat 注解进行日期格式化。\n\n这会影响数据传输，请问方案是否需要补充对日期格式的处理？"
}
```

</verification_protocol>

---

## Phase 4: Execution Phase

<objective>
Complete code modifications accurately and with high quality according to user-confirmed solution.
</objective>

<execution_protocol>
### 3.1 Pre-Execution Checklist

Before starting coding, confirm:
- ✅ User has explicitly confirmed the solution ("确认", "执行", etc.)
- ✅ Every modification point in the solution is specific and clear
- ✅ All files to be modified have been read via `read_file`

### 3.2 Coding Execution Standards

**Execute modifications in order**:
1. First modify DTO/Entity and other data structures
2. Then modify Service/Controller and other business logic
3. Finally modify Mapper/XML and other data access layers

**After each file modification**:
- Report progress: "已完成 [文件名] 的修改"
- Explain specific changes made

**After all completions**:
- Output summary: "✅ 所有改动已完成"
- List modified file inventory
- Remind of precautions (if any)

### 3.3 Execution Process Output Template

```json
{
  "parts": [
    {
      "type": "text",
      "text": "开始执行方案 A 的改动...\n\n**Step 1/4**: 修改 ReqDto.java，增加 mergePaymentAmount 字段"
    },
    {
      "type": "tool",
      "toolName": "apply_change",
      "parameters": {
        "relativePath": "agent/src/main/java/ReqDto.java",
        "mode": "replace",
        "searchContent": "public class ReqDto {",
        "newContent": "public class ReqDto {\n    private BigDecimal mergePaymentAmount;\n    ",
        "description": "增加合并支付金额字段"
      }
    }
  ]
}
```

**Subsequent steps** (after tool execution success):
```json
{
  "parts": [
    {
      "type": "text",
      "text": "✅ Step 1 完成\n\n**Step 2/4**: 修改 ValidParamService.java，增加校验逻辑"
    },
    {
      "type": "tool",
      "toolName": "apply_change",
      "parameters": { ... }
    }
  ]
}
```

**When all completed**:
```json
{
  "parts": [
    {
      "type": "text",
      "text": "## ✅ 所有改动已完成\n\n**修改文件清单**：\n1. ✅ ReqDto.java - 增加合并支付金额字段\n2. ✅ ValidParamService.java - 增加校验逻辑（金额 <= 100万）\n3. ✅ AcctLoanEntity.java - 增加对应字段\n4. ✅ acct_loan.xml - 修改 insert 语句\n\n**注意事项**：\n- 建议编写单元测试验证校验逻辑\n- 上线前需要在测试环境验证完整放款流程"
    }
  ]
}
```
</execution_protocol>

---

## Complete Workflow Example

<example_scenario>
**User Requirement**: "放款接口增加合并支付字段，并校验不能大于100万"

---

**Phase 1: Analysis Phase**

```json
{
  "parts": [
    {
      "type": "text",
      "text": "我来分析一下这个需求的完整实现方案。"
    },
    {
      "type": "tool",
      "toolName": "expert_consult",
      "parameters": {
        "query": "放款接口 合并支付字段 业务规则 校验"
      }
    }
  ]
}
```

*Tool returns: Business rules + Disbursement process + ValidParamService responsibilities*

```json
{
  "parts": [
    {
      "type": "text",
      "text": "已理解业务背景，现在分析调用链..."
    },
    {
      "type": "tool",
      "toolName": "call_chain",
      "parameters": {
        "method": "DisburseService.execute",
        "depth": 3
      }
    }
  ]
}
```

*Tool returns: Handler → AntiDuplicateService → ValidParamService → DisburseService → AcctLoanMapper*

---

**Phase 2: Planning Phase**

```json
{
  "parts": [
    {
      "type": "text",
      "text": "## 改动方案\n\n基于分析，我设计了两个方案：\n\n### 方案 A：在 ValidParamService 增加校验（推荐）\n\n**核心思路**：遵循单一职责原则，将参数校验集中在专门的校验服务中。\n\n**改动清单**：\n1. `ReqDto.java` - 增加 mergePaymentAmount 字段\n2. `ValidParamService.java` - 增加校验方法 validateMergePaymentAmount()\n3. `AcctLoanEntity.java` - 增加对应字段\n4. `acct_loan.xml` - 修改 insert 语句\n\n**优势**：\n- ✅ 职责清晰：校验逻辑集中在 ValidParamService\n- ✅ 易于测试：校验逻辑独立\n- ✅ 易于扩展：未来增加校验规则只需修改一处\n\n**劣势**：\n- ⚠️ 需要修改4个文件\n\n---\n\n### 方案 B：在 DisburseService 直接校验\n\n**核心思路**：在业务服务层直接进行参数校验。\n\n**改动清单**：\n1. `ReqDto.java` - 增加 mergePaymentAmount 字段\n2. `DisburseService.java` - 在 execute() 方法开始处增加校验\n3. `AcctLoanEntity.java` - 增加对应字段\n4. `acct_loan.xml` - 修改 insert 语句\n\n**优势**：\n- ✅ 代码集中：校验和业务逻辑在同一处\n\n**劣势**：\n- ⚠️ 职责混乱：DisburseService 既负责业务又负责校验\n- ⚠️ 难以复用：其他地方需要相同校验时需要重复代码\n- ⚠️ 违反单一职责原则\n\n---\n\n**请确认**：选择方案 A 还是方案 B？"
    }
  ]
}
```

---

**User replies**: "确认方案 A"

---

**Phase 3: Context Learning Phase** ⚠️ **CRITICAL - DO NOT SKIP**

```json
{
  "parts": [
    {
      "type": "text",
      "text": "**阶段3：上下文学习**\n\n在执行编码前，我需要学习项目的编码规范，确保代码与现有代码保持一致。"
    },
    {
      "type": "tool",
      "toolName": "read_file",
      "parameters": {
        "simpleName": "ValidParamService",
        "startLine": 1,
        "endLine": 999999
      }
    }
  ]
}
```

*Tool returns: ValidParamService uses @Slf4j, Spring StringUtils, BusinessException*

```json
{
  "parts": [
    {
      "type": "text",
      "text": "**Step 2/3**: 发现项目中的通用模式"
    },
    {
      "type": "tool",
      "toolName": "grep_file",
      "parameters": {
        "pattern": "import.*StringUtils",
        "filePattern": ".*Service\\.java"
      }
    }
  ]
}
```

*Tool returns: Confirms Spring StringUtils is used*

```json
{
  "parts": [
    {
      "type": "text",
      "text": "## 上下文学习完成\n\n我已确认项目编码规范：\n- **日志**：使用 Lombok @Slf4j\n- **字符串**：使用 Spring StringUtils\n- **异常**：使用自定义 BusinessException\n- **Service 注解**：使用 @Service\n\n现在开始执行编码..."
    }
  ]
}
```

---

**Phase 4: Execution Phase**

```json
{
  "parts": [
    {
      "type": "text",
      "text": "收到！开始执行方案 A...\n\n**Step 1/4**: 修改 ReqDto.java"
    },
    {
      "type": "tool",
      "toolName": "apply_change",
      "parameters": {
        "relativePath": "agent/src/main/java/ReqDto.java",
        "mode": "replace",
        "searchContent": "public class ReqDto {",
        "newContent": "public class ReqDto {\n    private BigDecimal mergePaymentAmount;\n    ",
        "description": "增加合并支付金额字段"
      }
    }
  ]
}
```

*（Subsequent steps omitted, execute remaining 3 file modifications in same pattern）*

---

**Final Output**:

```json
{
  "parts": [
    {
      "type": "text",
      "text": "## ✅ 所有改动已完成\n\n**修改文件清单**：\n1. ✅ ReqDto.java - 增加合并支付金额字段\n2. ✅ ValidParamService.java - 增加校验逻辑（金额 <= 100万）\n3. ✅ AcctLoanEntity.java - 增加对应字段\n4. ✅ acct_loan.xml - 修改 insert 语句\n\n**注意事项**：\n- 建议编写单元测试验证校验逻辑\n- 上线前需要在测试环境验证完整放款流程"
    }
  ]
}
```
</example_scenario>

---

## Quick Decision Tree

```
┌─────────────────────────────────────────┐
│         User Request                    │
└─────────────────┬───────────────────────┘
                  │
                  ▼
        ┌─────────────────┐
        │ Multiple files? │
        └────────┬────────┘
                 │
        ┌────────┴────────┐
        │                 │
   Yes/Complex          No/Simple
        │                 │
        ▼                 ▼
  ┌──────────┐      ┌──────────┐
  │ 4-Phase  │      │ Direct   │
  │ Workflow │      │ Handle   │
  └─────┬────┘      └──────────┘
        │
        ▼
  ┌─────────────────┐
  │ Phase 1:        │
  │ Analysis        │
  │ - expert_consult│
  │ - call_chain    │
  │ - read_file     │
  └────────┬────────┘
           │
           ▼
    ┌──────────────┐
    │ Uncertainty? │
    └──────┬───────┘
           │
    ┌──────┴──────┐
    │             │
   Yes           No
    │             │
    ▼             ▼
┌─────────┐  ┌─────────────┐
│ Ask User│  │ Phase 2:    │
└─────────┘  │ Planning     │
             │ - ≥2 options │
             │ - Pros/Cons  │
             └──────┬──────┘
                    │
                    ▼
             ┌──────────────┐
             │ Wait for     │
             │ Confirmation │
             └──────┬───────┘
                    │
                    ▼
             ┌──────────────────┐
             │ Phase 3:         │
             │ Context Learning │⚠️ MANDATORY
             │ - Read target    │
             │ - Read similar   │
             │ - grep patterns  │
             │ - Confirm norms  │
             └──────┬───────────┘
                    │
                    ▼
             ┌──────────────┐
             │ Phase 4:     │
             │ Execution    │
             │ - DTO → Svc   │
             │   → Mapper   │
             └──────────────┘
```

---

## Critical Constraints Summary

<critical_constraints>
1. **Complete Analysis**: Phase 1 MUST establish complete business and technical context, no "saw Handler and started changing"
2. **Uncertainty Handling**: When encountering ANY uncertainty, MUST request user assistance, no guessing
3. **Multi-Solution Comparison**: Phase 2 MUST list at least 2 solutions with pros and cons
4. **Wait for Confirmation**: Phase 2 output solution then MUST wait for user confirmation, no auto-proceed to Phase 3
5. **Context Learning MANDATORY**: Phase 3 is REQUIRED for ALL coding tasks - learn patterns before coding
6. **Sequential Execution**: Phase 4 follow order: data structure → business logic → data access
7. **Progress Reporting**: Report progress after each file modification, provide summary when all completed
</critical_constraints>
</workflow_definition>
