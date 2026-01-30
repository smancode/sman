# Tool Introduction

## ⚡ Most Important Rule

**START WITH `expert_consult` FOR EVERYTHING**

When in doubt, call `expert_consult` first. It's an expert consultation tool with **bidirectional Business ↔ Code understanding**.

> 90% of queries can be answered by `expert_consult` alone. Only use other tools when `expert_consult` results suggest you need deeper investigation.

## Quick Reference

| Tool | Best For | When to Use |
|------|----------|-------------|
| **expert_consult** | **Business ↔ Code expert consultation** | **Business/rule/code analysis: prioritize this first** |
| read_file | **Read any file** | You already know the file name (Java, XML, YAML, etc.) |
| grep_file | Find method usage | You know the method name and want all usages |
| find_file | Find files by pattern | You know the file name pattern |
| call_chain | Analyze call relationships | You need to understand who calls what |
| extract_xml | Extract XML content | You need to parse XML configurations |
| apply_change | Modify code | You've read the code and are ready to change it |
| **run_shell_command** | **Execute shell commands** | **Build, test, run, or any CLI operations** |

## Expert Consultation: Bidirectional Business ↔ Code

**`expert_consult` is your FIRST choice for both business and code questions:**

### Direction 1: Business → Code
**Ask about business requirements, get code entries:**
- "新增还款方式怎么配置" → Returns: Business rules + Configuration classes + Code locations
- "520提额浮层提示怎么实现" → Returns: UI logic + Backend handlers + Workflow
- "资金划拨流程是怎样的" → Returns: Process flow + Key services + State machines

### Direction 2: Code → Business
**Ask about code, get business context:**
- "BusinessContract在哪些业务场景里面会用？" → Returns: All business scenarios + Usage patterns + Related rules
- "transaction.xml在放还款里面怎么工作的？" → Returns: Payment workflow + Transaction logic + Business meanings
- "PaymentService.executePayment是干什么的？" → Returns: Business purpose + Scenarios + Related entities

**Key Point**: Knowledge graph has **bidirectional mappings** between business entities and code. `expert_consult` leverages this to answer questions from BOTH directions.

## Decision Tree

**Question: What does the user want?**

```
┌─────────────────────────────────────────┐
│  User asks something (business or code)  │
└─────────────────┬───────────────────────┘
                  │
                  ▼
        ┌─────────────────┐
        │ Call expert_consult │ ◄─── START HERE ALWAYS
        │  with the query │
        └────────┬────────┘
                 │
                 ▼
        ┌─────────────────────────┐
        │ Analyze consult results │
        └────────┬────────────────┘
                 │
        ┌────────┴────────┐
        │                 │
        ▼                 ▼
   ┌─────────┐      ┌──────────┐
   | Enough? │      | Need     │
   | Done.   │      | more?    │
   └─────────┘      └────┬─────┘
                          │
           ┌──────────────┼──────────────┐
           │              │              │
           ▼              ▼              ▼
    ┌──────────┐   ┌──────────┐   ┌──────────┐
    │read_file │   │grep_file │   │call_chain│
    └──────────┘   └──────────┘   └──────────┘
```

## Examples

### Business → Code Examples
- User: "新增还款方式怎么配置" → `expert_consult(query: "新增还款方式怎么配置")`
- User: "520提额浮层提示怎么实现" → `expert_consult(query: "520提额浮层提示怎么实现")`
- User: "资金划拨流程是怎样的" → `expert_consult(query: "资金划拨流程是怎样的")`

### Code → Business Examples
- User: "BusinessContract在哪些业务场景里面会用？" → `expert_consult(query: "BusinessContract在哪些业务场景里面会用？")`
- User: "transaction.xml在放还款里面怎么工作的？" → `expert_consult(query: "transaction.xml在放还款里面怎么工作的？")`
- User: "PaymentService.executePayment是干什么的？" → `expert_consult(query: "PaymentService.executePayment是干什么的？")`

## Key Points

1. **`expert_consult` is bidirectional** - Business → Code AND Code → Business
2. **`expert_consult` returns comprehensive answers** - Business context + knowledge + code entries + relationships
3. **`expert_consult` maps entities** - Understands how business entities map to code and vice versa
4. **Other tools are for deep dives** - Use them after `expert_consult` when you need more specific information
5. **`read_file` is still the fastest** - If you already know the exact class name, use `read_file(simpleName="ClassName")`

## Code Examples

```json
// Example 1: Business → Code (MOST COMMON)
{
  "toolName": "expert_consult",
  "parameters": {
    "query": "新增还款方式怎么配置"
  }
}
// Returns: Business rules + Configuration classes + Implementation locations

// Example 2: Code → Business
{
  "toolName": "expert_consult",
  "parameters": {
    "query": "BusinessContract在哪些业务场景里面会用？"
  }
}
// Returns: All business scenarios + Usage patterns + Related rules

// Example 3: Direct class read (ONLY when you know the exact name)
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "PaymentService"
  }
}

// Example 4: Read entire file (recommended)
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "PaymentService",
    "startLine": 1,
    "endLine": 999999
  }
}
```

### File Reading Tips

**Default behavior**: `read_file` reads first 300 lines by default. This is sufficient for most files.

**For complete file content**: Use `endLine=999999` to read the entire file.

**Use line range parameters**:
- `startLine`: 开始行号（默认 1）
- `endLine`: 结束行号（默认 300，设置为 999999 读取完整文件）
- Example: `read_file(simpleName="MyClass", startLine=1, endLine=999999)`

---

## Shell Command Execution: run_shell_command

**`run_shell_command` allows you to execute shell commands directly in the project directory.**

### Use Cases

**Build & Test**:
- User: "帮我构建" → `run_shell_command(command="./gradlew build")`
- User: "运行测试" → `run_shell_command(command="./gradlew test")`
- User: "打包应用" → `run_shell_command(command="./gradlew bootJar")`

**Git Operations**:
- User: "查看状态" → `run_shell_command(command="git status")`
- User: "提交代码" → `run_shell_command(command="git add . && git commit -m 'message'")`

**Run Application**:
- User: "启动应用" → `run_shell_command(command="./gradlew bootRun")`
- User: "运行 Main 类" → `run_shell_command(command="java -cp build/libs MyApp")`

### Important Notes

1. **Auto-detect Build System**: Check project context for Gradle/Maven/npm
2. **Cross-platform Compatible**: Automatically adjusts for Windows (gradlew.bat) vs Unix (./gradlew)
3. **Streaming Output**: Real-time feedback for long-running commands
4. **Working Directory**: Commands execute in project root automatically

### Code Examples

```json
// Build project (Gradle)
{
  "toolName": "run_shell_command",
  "parameters": {
    "command": "./gradlew build"
  }
}

// Run tests
{
  "toolName": "run_shell_command",
  "parameters": {
    "command": "./gradlew test"
  }
}

// Start Spring Boot app
{
  "toolName": "run_shell_command",
  "parameters": {
    "command": "./gradlew bootRun"
  }
}
```

---

**Remember**: When in doubt, `expert_consult` it out! The bidirectional expert consultation will guide you to the right next steps.
