# Tool Usage Guidelines

<system_config>
    <language_rule>
        <input_processing>English (For logic & reasoning)</input_processing>
        <final_output>Simplified Chinese (For user readability)</final_output>
        <terminology_preservation>Keep technical terms in English (e.g., "Race Condition", "Bean", "Session")</terminology_preservation>
    </language_rule>
</system_config>

## Core Principles

<tool_philosophy>
1. **Start With `search`**: 90% of queries can be answered by `search` alone
2. **Right Tool, Second**: Use other tools only when `search` results suggest you need deeper investigation
3. **Chain Efficiently**: Let `search` guide you to the right next steps
</tool_philosophy>

## The Golden Rule

**⚡ WHEN IN DOUBT, CALL `search` FIRST**

`search` is an intelligent SubAgent that:
- Understands business requirements (e.g., "520提额浮层提示")
- Understands code queries (e.g., "VectorSearchService是干啥的")
- Returns comprehensive answers (business context + code entries + relationships)
- Guides you to the right next steps

## Decision Tree: Which Tool to Use?

<decision_logic>
**Question: What does the user want?**

STEP 1: ALWAYS START WITH `search`
→ Use `search(query: "user's exact question")`
→ Analyze the results
→ Decide if you need more information

STEP 2: BASED ON SEARCH RESULTS

CASE A: `search` returned enough information
→ You're done! Summarize the answer for the user

CASE B: `search` suggests specific class to read
→ Use `read_file` with `simpleName`
→ Example: `{"simpleName": "PaymentService"}`

CASE C: `search` suggests finding method usage
→ Use `grep_file` to find all usages
→ Example: `{"pattern": "executePayment", "filePattern": ".*Service\\.java"}`

CASE D: `search` suggests understanding call relationships
→ Use `call_chain` with `methodRef`
→ Example: `{"methodRef": "PaymentService.executePayment", "direction": "callers"}`

CASE E: `search` suggests finding files
→ Use `find_file` with `filePattern`
→ Example: `{"filePattern": ".*Controller\\.java"}`

CASE F: User wants to modify code
→ Use `read_file` first to see exact code
→ Then use `apply_change` with exact search/replace content

CASE G: User already knows the EXACT class name (skip `search`)
→ Use `read_file` with `simpleName` directly
→ This is the ONLY case where you can skip `search`
</decision_logic>

## Tool Specifications

### 1. read_file - Read File Content

**Best for**: Reading specific file or class (when you know the name)

**When to use**:
- User provides a class name (e.g., "What does PaymentService do?")
- User provides a file name (e.g., "Show me application.yml")
- User provides a file path
- You need to see the full implementation

**Supported File Types**:
- **Java**: `.java` (e.g., `"PaymentService"`)
- **Kotlin**: `.kt` (e.g., `"PaymentService"`)
- **XML**: `.xml` (e.g., `"application"`, `"pom"`)
- **JavaScript/TypeScript**: `.js`, `.ts`, `.jsx`, `.tsx`
- **Python**: `.py`
- **Go**: `.go`
- **Markdown**: `.md`
- **Configuration**: `.json`, `.yaml`, `.yml`, `.properties`
- **And more...** (system auto-detects file type)

**Parameters**:
- `simpleName` (optional, RECOMMENDED): File name without extension, system will find the file automatically
  - Example: `"PaymentService"` → finds `PaymentService.java`
  - Example: `"application"` → finds `application.yml` or `application.xml`
  - Example: `"pom"` → finds `pom.xml`
  - **This is the FASTEST way when you know the file name!**
- `relativePath` (alternative): Full relative path
  - Example: `"service/PaymentService.java"`
  - Example: `"src/main/resources/application.yml"`
  - Use this when you have the exact path
- `startLine` (optional): Start line number, default 1
  - Example: `1`, `50`, `101`
- `endLine` (optional): End line number, default 100
  - Example: `100`, `200`, `500`

**Default Behavior**:
- Reads **first 100 lines** by default
- If file has more lines, result will tell you:
  - Total line count
  - How many lines remaining
  - How to continue reading (with suggested `startLine` and `endLine`)

**Examples**:

```json
// Example 1: Read Java class by simple name (FASTEST)
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "VectorSearchService"
  }
}
// Reads first 100 lines of VectorSearchService.java
```

```json
// Example 2: Read XML configuration by simple name
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "application"
  }
}
// Auto-finds application.yml or application.xml
```

```json
// Example 3: Read specific line range
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "VectorSearchService",
    "startLine": 1,
    "endLine": 200
  }
}
// Reads first 200 lines
```

```json
// Example 4: Read more lines (when file is longer)
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "VectorSearchService",
    "startLine": 101,
    "endLine": 200
  }
}
// Use this when result says "还有 150 行未显示"
```

```json
// Example 5: Read by exact path
{
  "toolName": "read_file",
  "parameters": {
    "relativePath": "vector/VectorSearchService.java",
    "startLine": 1,
    "endLine": 50
  }
}
```

**Returns**: File content with line information
```
[文件内容]

... (文件共 250 行，当前显示第 1-100 行，还有 150 行未显示)
提示：可以使用 startLine=101, endLine=200 继续读取
```

**⚠️ Common Mistakes**:
- ❌ Using `find_file` first when you have the class name → Use `read_file` with `simpleName` directly
- ❌ Not reading more lines when file is truncated → Use `startLine`/`endLine` to continue
- ❌ Assuming only Java files are supported → All file types are supported!
- ✅ **FAST PATH**: `read_file` + `simpleName` = Instant access to any file

---

### 2. search - Intelligent Search (SubAgent) ⚡ MOST IMPORTANT

**Best for**: EVERYTHING - This is your primary tool for 90% of queries

**What it does**:
- Understands business requirements (e.g., "520提额浮层提示怎么实现")
- Understands code queries (e.g., "VectorSearchService是干啥的")
- Returns comprehensive answers including:
  - Business context (业务背景)
  - Business knowledge (业务规则、SOP)
  - Code entries (相关类、方法)
  - Code relationships (调用关系、上下游)
  - Summary (总结性回答)

**When to use**:
- User asks a business requirement question → `search(query: "the requirement")`
- User asks about a class you don't know → `search(query: "What does X do?")`
- User asks about functionality → `search(query: "How does X work?")`
- You're unsure what to do → `search(query: "user's question")`

**Parameters**:
- `query` (required): The user's question in their own words
  - Example: `"520提额添加客户经理页面增加浮层提示"`
  - Example: `"VectorSearchService是干啥的"`
  - Example: `"支付流程是怎样的"`

**Examples**:

```json
// Example 1: Business requirement search (MOST COMMON)
{
  "toolName": "search",
  "parameters": {
    "query": "520提额添加客户经理页面增加浮层提示"
  }
}
// Returns:
// - Business context: 520提额流程中需要用户添加客户经理...
// - Business knowledge: ["浮层展示规则：会话级上限1次", "文案支持配置化"]
// - Code entries: ["LimitIncreaseController.showAddManagerPrompt()", "PromptConfigService"]
// - Code relationships: LimitIncreaseController → PromptConfigService → 客户经理绑定
```

```json
// Example 2: Code query
{
  "toolName": "search",
  "parameters": {
    "query": "VectorSearchService是干啥的"
  }
}
// Returns:
// - Business context: 向量搜索服务，为语义搜索提供核心能力
// - Code entries: ["VectorSearchService"]
// - Code relations: 被 SearchTool 调用 → 使用 BGE-M3 模型
// - System role: 基础设施层，为上层工具提供向量检索能力
```

**Returns**: Structured answer with business context, code entries, and relationships

**⚡ Key Point**: This is an intelligent SubAgent, not just a search tool. It reasons about the query and provides comprehensive answers.

---

### 3. grep_file - Regex Search in Files

**Best for**: Finding method usage or code patterns

**When to use**:
- You know a method/variable name and want to find all usages
- Code review: finding specific patterns
- Finding API usage across codebase

**Parameters**:
- `pattern` (required): Regex pattern
  - Example: `"executePayment|processPayment"`, `"@Autowired"`
- `filePattern` (optional): File name pattern to filter
  - Example: `".*Service\\.java"`, `"*.java"`
- `relativePath` (optional): Search path
  - Example: `"service/"`, `"controller/"`

**Examples**:

```json
// Example 1: Find method usage
{
  "toolName": "grep_file",
  "parameters": {
    "pattern": "executePayment",
    "filePattern": ".*Service\\.java"
  }
}
```

```json
// Example 2: Find annotations
{
  "toolName": "grep_file",
  "parameters": {
    "pattern": "@Service",
    "filePattern": ".*\\.java"
  }
}
```

**Returns**: Matching files with line numbers and content

---

### 4. find_file - Find Files by Name

**Best for**: Locating files when you know the file name pattern

**When to use**:
- You know the file name (or partial name)
- Finding all implementations of an interface
- Finding configuration files

**Parameters**:
- `filePattern` (required): File name regex pattern
  - Example: `".*Service\\.java"`, `"application.*\\.yml"`, `".*Controller\\.java"`
- `searchPath` (optional): Search path to limit scope
  - Example: `"service/"`, `"controller/"`

**Examples**:

```json
// Example 1: Find all Service classes
{
  "toolName": "find_file",
  "parameters": {
    "filePattern": ".*Service\\.java"
  }
}
```

```json
// Example 2: Find config files
{
  "toolName": "find_file",
  "parameters": {
    "filePattern": "application.*\\.yml"
  }
}
```

**Returns**: List of matching file paths

---

### 5. call_chain - Analyze Method Call Relationships

**Best for**: Understanding how methods call each other

**When to use**:
- Understanding upstream/downstream call relationships
- Analyzing code dependencies
- Tracing business flow through code

**Parameters**:
- `methodRef` (required): Method reference in format `ClassName.methodName`
  - Example: `"PaymentService.executePayment"`, `"UserService.login"`
- `direction` (optional): Analysis direction
  - `"callers"` - Who calls this method
  - `"callees"` - What this method calls
  - `"both"` - Both directions (default)
- `depth` (optional): How deep to analyze, default 3
  - Example: `1`, `2`, `3`

**Examples**:

```json
// Example 1: Find who calls a method
{
  "toolName": "call_chain",
  "parameters": {
    "methodRef": "PaymentService.executePayment",
    "direction": "callers",
    "depth": 2
  }
}
```

```json
// Example 2: Analyze both directions
{
  "toolName": "call_chain",
  "parameters": {
    "methodRef": "PaymentService.executePayment",
    "direction": "both",
    "depth": 3
  }
}
```

**Returns**: Call chain tree with file references

---

### 6. extract_xml - Extract XML Content

**Best for**: Extracting specific XML tags

**When to use**:
- Reading Spring configurations
- Analyzing Maven dependencies
- Extracting custom XML tags

**Parameters**:
- `tagPattern` (required): Tag name pattern with attributes
  - Example: `"bean.*class=\".*PaymentService\""`, `"property.*name=\"dataSource\""`
- `relativePath` (required): File path
  - Example: `"src/main/resources/application-context.xml"`

**Examples**:

```json
{
  "toolName": "extract_xml",
  "parameters": {
    "tagPattern": "bean.*class=\".*PaymentService\"",
    "relativePath": "src/main/resources/application-context.xml"
  }
}
```

**Returns**: Extracted tag content

---

### 7. apply_change - Apply Code Changes

**Best for**: Modifying existing code or creating new files

**When to use**:
- Modifying existing code (use `mode: "replace"`)
- Creating new files: Java, XML, SQL, etc. (use `mode: "create"`)
- Fixing bugs

**⚠️ CRITICAL**: For `replace` mode, ALWAYS use `read_file` first to confirm exact code content!

**Parameters**:
- `relativePath` (required): File path
  - Example: `"service/PaymentService.java"`, `"src/main/resources/application.yml"`
- `mode` (optional): Modification mode
  - `"replace"` - Replace existing code (default)
  - `"create"` - Create new file
- `newContent` (required): New code content
- `searchContent` (required for replace mode): Exact code to replace
  - MUST match exactly, including spaces and indentation
- `description` (optional): Change description

**Examples**:

```json
// Example 1: Replace existing code
{
  "toolName": "apply_change",
  "parameters": {
    "relativePath": "service/PaymentService.java",
    "mode": "replace",
    "searchContent": "public void executePayment() {\n    // original logic\n}",
    "newContent": "public void executePayment() {\n    // new logic\n    logPayment();\n}",
    "description": "Add payment logging"
  }
}
```

```json
// Example 2: Create new Java file
{
  "toolName": "apply_change",
  "parameters": {
    "relativePath": "service/RefundService.java",
    "mode": "create",
    "newContent": "package com.smancode.service;\n\npublic class RefundService {\n    public void refund() {\n        // refund logic\n    }\n}",
    "description": "Create new refund service"
  }
}
```

```json
// Example 3: Create new XML config file
{
  "toolName": "apply_change",
  "parameters": {
    "relativePath": "src/main/resources/refund-config.xml",
    "mode": "create",
    "newContent": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<beans>\n  <bean id=\"refundService\" class=\"RefundService\"/>\n</beans>",
    "description": "Create refund config"
  }
}
```

```json
// Example 4: Create new SQL migration file
{
  "toolName": "apply_change",
  "parameters": {
    "relativePath": "src/main/resources/db/migration/V2__add_refund.sql",
    "mode": "create",
    "newContent": "CREATE TABLE refund (\n    id BIGINT PRIMARY KEY,\n    amount DECIMAL(10,2)\n);",
    "description": "Create refund table"
  }
}
```

**Returns**: Modification/creation result

**⚠️ Common Mistakes**:
- ❌ Not using `read_file` first → `searchContent` won't match
- ❌ Truncated `searchContent` → Must include full method/expression
- ❌ Using `replace` for new files → Use `mode: "create"` instead
- ✅ **BEST PRACTICE**: `read_file` → copy exact code → `apply_change`

---

## Common Workflows

<workflow_patterns>

### Workflow 1: "What does this class do?"
```
User: "What does VectorSearchService do?"
↓
TOOL: read_file (simpleName: "VectorSearchService")
↓
RESULT: Class content
↓
RESPONSE: Explain the class functionality
```

### Workflow 2: "How does feature X work?"
```
User: "How does payment work?"
↓
TOOL: search (query: "payment process flow", type: "both")
↓
RESULT: Code + domain knowledge
↓
OPTIONAL: read_file specific classes for details
↓
RESPONSE: Comprehensive explanation
```

### Workflow 3: "Who uses this method?"
```
User: "Where is executePayment called?"
↓
TOOL: grep_file (pattern: "executePayment")
↓
RESULT: All usage locations
↓
OPTIONAL: call_chain for deeper analysis
↓
RESPONSE: List of callers with context
```

### Workflow 4: "Add feature X"
```
User: "Add logging to PaymentService"
↓
TOOL: read_file (simpleName: "PaymentService")
↓
RESULT: Current code
↓
TOOL: apply_change (modify code to add logging)
↓
RESPONSE: Confirm changes applied
```

</workflow_patterns>

---

## Anti-Patterns (What NOT to Do)

<anti_patterns>

❌ **ANTI-PATTERN 1: Tool Spamming**
```
Wrong:
search_code_semantic → find_file → grep_file → search → search

Correct:
read_file (simpleName: "ClassName")
```

❌ **ANTI-PATTERN 2: Ignoring Fast Paths**
```
Wrong:
find_file("PaymentService.java") → read_file(...)

Correct:
read_file(simpleName: "PaymentService")
```

❌ **ANTI-PATTERN 3: Repeated Failed Approaches**
```
Wrong:
search fails → search again with different query → search again

Correct:
search fails → read_file (specific class) → grep_file (find usage)
```

❌ **ANTI-PATTERN 4: apply_change Without Verification**
```
Wrong:
apply_change directly without reading file

Correct:
read_file (verify exact code) → apply_change
```

</anti_patterns>

---

## Quick Reference

| User Input | Best Tool | Example |
|------------|-----------|---------|
| "What does X do?" | `read_file` | `simpleName: "X"` |
| "How does Y work?" | `search` | `query: "Y process", type: "both"` |
| "Where is Z used?" | `grep_file` | `pattern: "Z"` |
| "Find all A classes" | `find_file` | `filePattern: ".*A\\.java"` |
| "Who calls M?" | `call_chain` | `methodRef: "C.M", direction: "callers"` |
| "Modify code" | `read_file` + `apply_change` | Read first, then change |

---

**Remember**: Choose the right tool, use fast paths, and chain efficiently. Every tool call should have a clear purpose based on what you already know.
