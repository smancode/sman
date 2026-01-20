# Coding Best Practices and Context Learning

<coding_standards_definition>
## Objective

Ensure code modifications follow project conventions and integrate seamlessly with existing codebase patterns.
</coding_standards_definition>

---

## Part 1: Coding Standards Loading (规范加载)

<standards_hierarchy>
### Three-Tier Standards Hierarchy

**Priority Order** (from highest to lowest):
1. **Team/Project Standards** (团队/项目规范) - Highest priority
2. **Company/Department Standards** (公司/部门规范) - Medium priority
3. **Industry Standards** (业界规范) - Fallback baseline

### How Standards Are Loaded

<loading_mechanism>
**Method 1: Configuration Files (项目内配置文件)**

ALWAYS check for these files before making changes:
- `CHECKSTYLE.xml` / `checkstyle.xml` - Code style rules
- `.editorconfig` - Editor configuration
- `pom.xml` / `build.gradle` - Build configuration (may contain checkstyle/spotbugs plugins)
- `.clang-format` / `.google-java-format.xml` - Format settings
- `README.md` / `CONTRIBUTING.md` / `CODING.md` - Project conventions
- `docs/coding-standards.md` - Explicit coding standards

**Method 2: Existing Code Patterns (现有代码模式)**

LEARN FROM the codebase by reading similar files:
- Read 2-3 similar classes to understand patterns
- Identify common patterns: logging, exception handling, imports, annotations
- MIMIC the observed patterns

**Method 3: expert_consult Query (专家咨询)**

Ask about project-specific conventions:
```
expert_consult(query: "项目中日志怎么打印？用 Lombok 还是 logger？")
expert_consult(query: "项目中字符串处理用哪个工具类？StringUtils 还是其他？")
expert_consult(query: "项目中异常处理有什么规范？")
```
</loading_mechanism>

### Standards Discovery Checklist

Before coding, ALWAYS verify:

<checklist>
- [ ] **Logging**: Check how other classes log - Lombok `@Slf4j` or explicit `Logger`?
- [ ] **StringUtils**: Which utility class? Apache Commons Lang? Spring? Guava? Custom?
- [ ] **Exception Handling**: Checked or unchecked? Custom exception classes?
- [ ] **Annotations**: Required annotations? (`@Service`, `@Component`, etc.)
- [ ] **Imports**: Organization and grouping patterns?
- [ ] **Naming**: Field/method naming conventions?
- [ ] **Code Style**: Brace style, indentation, line length?
- [ ] **SQL Practices**: New SQL files or extend existing? Naming conventions?
</checklist>
</standards_hierarchy>

---

## Part 2: Context Learning (上下文学习)

<context_learning_protocol>
### Principle: Learn from Existing Code

**BEFORE writing any code, LEARN from the existing codebase**:

<learning_workflow>
#### Step 1: Read Similar Classes

**Always read 2-3 similar classes before modifying**:

**Example**: Before modifying `ValidParamService`, read:
- `ValidParamService.java` (the target class)
- `OtherParamService.java` (similar class)
- `BusinessCheckService.java` (related class)

**What to observe**:
```java
// Logging pattern
@Slf4j  // Lombok annotation?
// OR
private static final Logger logger = LoggerFactory.getLogger(ValidParamService.class);  // Explicit logger?

// Exception handling pattern
throw new BusinessException(ErrorCode.PARAM_ERROR, "参数错误");  // Custom exception?
// OR
throw new IllegalArgumentException("参数错误");  // Standard exception?

// StringUtils usage
StringUtils.isEmpty(str)  // Apache Commons?
// OR
StringUtils.hasText(str)  // Spring?
// OR
StringUtil.isBlank(str)   // Custom?

// Annotations
@Service  // Spring annotation?
@Component  // Or Component?
@Transactional  // Transactional?
```

#### Step 2: grep_file for Pattern Discovery

**Use grep_file to find patterns across codebase**:

```json
// Find logging patterns
{
  "toolName": "grep_file",
  "parameters": {
    "pattern": "private.*Logger|@Slf4j",
    "filePattern": ".*Service\\.java"
  }
}

// Find StringUtils usage
{
  "toolName": "grep_file",
  "parameters": {
    "pattern": "import.*StringUtils|StringUtils\\.",
    "filePattern": ".*\\.java"
  }
}

// Find exception patterns
{
  "toolName": "grep_file",
  "parameters": {
    "pattern": "throw new.*Exception",
    "filePattern": ".*Service\\.java"
  }
}
```

#### Step 3: Learn SQL Patterns

**For SQL changes, ALWAYS check existing patterns**:

```json
// Find SQL file naming conventions
{
  "toolName": "find_file",
  "parameters": {
    "filePattern": ".*\\.sql"
  }
}

// Read existing SQL files to understand:
// - Naming: V1__description.sql or V{version}__{description}.sql?
// - Location: db/migration/ or resources/sql/?
// - Style: UPPERCASE keywords or lowercase?
// - Constraints: How are foreign keys defined?
```

#### Step 4: Verify Imports

**Check import statements in similar classes**:

```java
// Read a similar class to see import patterns
import org.springframework.stereotype.Service;  // Spring annotations?
import lombok.extern.slf4j.Slf4j;  // Lombok?
import org.apache.commons.lang3.StringUtils;  // Apache Commons?
import com.smancode.common.exception.BusinessException;  // Custom exceptions?
```
</learning_workflow>
</context_learning_protocol>

---

## Part 3: Execution Phase Coding Rules

<execution_coding_rules>
### Phase 3.5: Context Learning (NEW STEP)

**INSERT this step between "Planning Phase" and "Execution Phase"**:

```
Phase 1: Analysis Phase
    ↓
Phase 2: Planning Phase
    ↓
Phase 3: Context Learning Phase ⬅️ NEW!
    ↓
Phase 4: Execution Phase
```

### Context Learning Phase Actions

<cl_phase_actions>
**Action 1: Read Target Files**

Before applying any changes, read ALL files to be modified:

```json
{
  "parts": [
    {
      "type": "text",
      "text": "**阶段3.5：上下文学习**\n\n在执行编码前，我需要学习项目的编码规范和现有模式。\n\n**Step 1/3**: 读取目标文件，了解现有代码模式"
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

**Action 2: Pattern Discovery**

Use grep_file to find patterns:

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
        "pattern": "private.*Logger|@Slf4j",
        "filePattern": ".*Service\\.java"
      }
    }
  ]
}
```

**Action 3: Expert Consult (Optional)**

Ask about specific conventions:

```json
{
  "parts": [
    {
      "type": "text",
      "text": "**Step 3/3**: 咨询项目特定的编码规范"
    },
    {
      "type": "tool",
      "toolName": "expert_consult",
      "parameters": {
        "query": "项目中日志打印、StringUtils、异常处理的规范是什么？"
      }
    }
  ]
}
```

**Action 4: Confirm Understanding**

Confirm what you learned before coding:

```json
{
  "parts": [
    {
      "type": "text",
      "text": "## 上下文学习完成\n\n基于现有代码分析，我确认以下编码规范：\n\n**日志**：使用 Lombok @Slf4j\n**字符串**：使用 Spring StringUtils\n**异常**：使用自定义 BusinessException\n**注解**：Service 类使用 @Service\n\n现在开始执行编码..."
    }
  ]
}
```
</cl_phase_actions>
</execution_coding_rules>

---

## Part 4: Common Patterns and Decision Matrix

<pattern_reference>
### Pattern 1: Logging

<logging_patterns>
**Option A: Lombok @Slf4j**
```java
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ValidParamService {
    public void validate() {
        log.info("Validating params...");
    }
}
```

**Option B: Explicit Logger**
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ValidParamService {
    private static final Logger logger = LoggerFactory.getLogger(ValidParamService.class);

    public void validate() {
        logger.info("Validating params...");
    }
}
```

**How to decide**: Read existing Service classes, count occurrences
</logging_patterns>

### Pattern 2: StringUtils

<string_utils_patterns>
**Option A: Apache Commons Lang**
```java
import org.apache.commons.lang3.StringUtils;

if (StringUtils.isEmpty(str)) { ... }
```

**Option B: Spring Utils**
```java
import org.springframework.util.StringUtils;

if (!StringUtils.hasText(str)) { ... }
```

**Option C: Guava**
```java
import com.google.common.base.Strings;

if (Strings.isNullOrEmpty(str)) { ... }
```

**Option D: Custom**
```java
import com.smancode.common.util.StringUtil;

if (StringUtil.isBlank(str)) { ... }
```

**How to decide**: Use grep_file to find import patterns
</string_utils_patterns>

### Pattern 3: Exception Handling

<exception_patterns>
**Option A: Custom Business Exception**
```java
import com.smancode.common.exception.BusinessException;
import com.smancode.common.errorcode.ErrorCode;

throw new BusinessException(ErrorCode.PARAM_ERROR, "参数错误：合并支付金额不能超过100万");
```

**Option B: Standard Exception**
```java
throw new IllegalArgumentException("参数错误：合并支付金额不能超过100万");
```

**Option C: Checked Exception**
```java
throw new ValidationException("参数错误：合并支付金额不能超过100万");
```

**How to decide**: grep_file + read similar validation methods
</exception_patterns>

### Pattern 4: SQL Changes

<sql_patterns>
**Decision: New SQL File vs Extend Existing**

**Use NEW SQL file when**:
- Adding a new table
- Major schema changes
- Database migration scripts
- Following Flyway/Liquibase conventions

**Use EXTEND EXISTING when**:
- Adding columns to existing tables (ALTER TABLE in existing migration file)
- Modifying existing stored procedures
- Adding new queries to mapper XML

**Naming Convention Discovery**:
```bash
# Find existing SQL files
find_file(filePattern: ".*\\.sql")

# Check naming pattern:
# - V1__create_table.sql (Flyway)
# - V001__description.sql (Liquibase)
# - YYYYMMDD_description.sql (timestamp-based)

# Read existing files to understand:
# - Keyword casing (UPPERCASE vs lowercase)
# - Indentation style
# - Comment style
# - Constraint naming
```
</sql_patterns>
</pattern_reference>

---

## Part 5: Complete Example with Context Learning

<complete_workflow_example>
**Scenario**: Add validation to ValidParamService

**Phase 1: Analysis** (completed)

**Phase 2: Planning** (completed, user confirmed Plan A)

**Phase 3: Context Learning** ⬅️ **NEW PHASE**

```json
{
  "parts": [
    {
      "type": "text",
      "text": "**阶段3.5：上下文学习**\n\n让我先学习项目的编码规范，确保代码与现有代码保持一致。"
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

*Returns: ValidParamService uses @Slf4j, Spring StringUtils, BusinessException*

```json
{
  "parts": [
    {
      "type": "tool",
      "toolName": "read_file",
      "parameters": {
        "simpleName": "OtherParamService",
        "startLine": 1,
        "endLine": 100
      }
    }
  ]
}
```

*Returns: Confirms same patterns - @Slf4j, Spring StringUtils, BusinessException*

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

**Phase 4: Execution**

```json
{
  "parts": [
    {
      "type": "text",
      "text": "**Step 1/4**: 修改 ReqDto.java，增加 mergePaymentAmount 字段"
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

```json
{
  "parts": [
    {
      "type": "text",
      "text": "✅ Step 1 完成\n\n**Step 2/4**: 修改 ValidParamService.java，增加校验逻辑\n\n*注意：使用项目规范 - @Slf4j 日志、Spring StringUtils、BusinessException*"
    },
    {
      "type": "tool",
      "toolName": "apply_change",
      "parameters": {
        "relativePath": "agent/src/main/java/ValidParamService.java",
        "mode": "replace",
        "searchContent": "public void validate(ReqDto reqDto) {\n        // 原有校验",
        "newContent": "public void validate(ReqDto reqDto) {\n        // 原有校验\n        \n        // 新增：合并支付金额校验\n        validateMergePaymentAmount(reqDto.getMergePaymentAmount());",
        "description": "增加合并支付金额校验调用"
      }
    }
  ]
}
```

```json
{
  "parts": [
    {
      "type": "text",
      "text": "✅ Step 2 完成\n\n**Step 2.5/4**: 在 ValidParamService 中新增校验方法"
    },
    {
      "type": "tool",
      "toolName": "apply_change",
      "parameters": {
        "relativePath": "agent/src/main/java/ValidParamService.java",
        "mode": "replace",
        "searchContent": "}",
        "newContent": "    \n    /**\n     * 校验合并支付金额\n     * @param amount 合并支付金额\n     * @throws BusinessException 超过100万时抛出异常\n     */\n    private void validateMergePaymentAmount(BigDecimal amount) {\n        if (amount != null && amount.compareTo(new BigDecimal(\"1000000\")) > 0) {\n            log.warn(\"合并支付金额超过限制: {}\", amount);\n            throw new BusinessException(ErrorCode.PARAM_ERROR, \n                \"合并支付金额不能超过100万\");\n        }\n    }\n}",
        "description": "新增合并支付金额校验方法"
      }
    }
  ]
}
```
</complete_workflow_example>

---

## Summary Checklist

<final_checklist>
### Before Coding (Context Learning Phase)

- [ ] Read target file(s) completely
- [ ] Read 2-3 similar files to understand patterns
- [ ] Use grep_file to find common patterns (logging, utils, exceptions)
- [ ] Check for configuration files (checkstyle, editorconfig, pom.xml)
- [ ] Use expert_consult for project-specific conventions
- [ ] Confirm understanding before proceeding

### During Coding (Execution Phase)

- [ ] Follow observed patterns exactly
- [ ] Use same imports as existing code
- [ ] Use same annotations as existing code
- [ ] Use same naming conventions
- [ ] Use same code style (braces, indentation)
- [ ] For SQL: follow existing file naming and style

### After Coding

- [ ] Verify no missing imports
- [ ] Verify consistent code style
- [ ] Verify no compilation errors
- [ ] Confirm changes match confirmed plan
</final_checklist>

---

## Critical Rules

<critical_rules>
1. **NEVER guess** - Always read existing code to confirm patterns
2. **NEVER assume** - Use grep_file to discover actual usage
3. **ALWAYS learn** - Context learning is MANDATORY before coding
4. **CONSISTENCY is key** - Match existing patterns exactly
5. **WHEN in doubt** - Ask user or use expert_consult
</critical_rules>
</coding_standards_definition>
