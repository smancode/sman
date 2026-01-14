# Tool Introduction

## ⚡ Most Important Rule

**START WITH `search` FOR EVERYTHING**

When in doubt, call `search` first. It's an intelligent SubAgent that understands both business requirements and code queries.

> 90% of queries can be answered by `search` alone. Only use other tools when `search` results suggest you need deeper investigation.

## Quick Reference

| Tool | Best For | When to Use |
|------|----------|-------------|
| **search** | **万能搜索入口** | **绝大多数情况，优先使用这个** |
| read_file | **Read any file** | You already know the file name (Java, XML, YAML, etc.) |
| grep_file | Find method usage | You know the method name and want all usages |
| find_file | Find files by pattern | You know the file name pattern |
| call_chain | Analyze call relationships | You need to understand who calls what |
| extract_xml | Extract XML content | You need to parse XML configurations |
| apply_change | Modify code | You've read the code and are ready to change it |

## Decision Tree

**Question: What does the user want?**

```
┌─────────────────────────────────────────┐
│  User asks something (business or code)  │
└─────────────────┬───────────────────────┘
                  │
                  ▼
        ┌─────────────────┐
        │  Call search    │ ◄─── START HERE ALWAYS
        │  with the query │
        └────────┬────────┘
                 │
                 ▼
        ┌─────────────────────────┐
        │  Analyze search results │
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

**Examples**:
- User: "VectorSearchService是干啥的" → `search(query: "VectorSearchService是干啥的")`
- User: "520提额浮层提示怎么实现" → `search(query: "520提额浮层提示怎么实现")`
- User: "What does PaymentService do?" → `search(query: "What does PaymentService do?")`
- User: "支付流程是怎样的" → `search(query: "支付流程是怎样的")`

## Key Points

1. **`search` is intelligent SubAgent** - It understands business requirements and code queries
2. **`search` returns comprehensive answers** - Business context + knowledge + code entries + relationships
3. **Other tools are for deep dives** - Use them after `search` when you need more specific information
4. **`read_file` is still the fastest** - If you already know the exact class name, use `read_file(simpleName="ClassName")`

## Examples

```json
// Example 1: Business requirement search (MOST COMMON)
{
  "toolName": "search",
  "parameters": {
    "query": "520提额添加客户经理页面增加浮层提示"
  }
}
// Returns: Business context, rules, code entries, relationships

// Example 2: Code query
{
  "toolName": "search",
  "parameters": {
    "query": "VectorSearchService是干啥的"
  }
}
// Returns: What it does, how it's used, system role

// Example 3: Direct class read (ONLY when you know the exact name)
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "PaymentService"
  }
}

// Example 4: Read specific lines of a file
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "PaymentService",
    "startLine": 1,
    "endLine": 100
  }
}

// Example 5: Read more lines (when file is longer than 100 lines)
{
  "toolName": "read_file",
  "parameters": {
    "simpleName": "PaymentService",
    "startLine": 101,
    "endLine": 200
  }
}
```

### File Reading Tips

**Default behavior**: `read_file` reads first 100 lines by default.

**When file is longer**: The result will tell you the total lines and how to read more:
```
... (文件共 250 行，当前显示第 1-100 行，还有 150 行未显示)
提示：可以使用 startLine=101, endLine=200 继续读取
```

**Use line range parameters**:
- `startLine`: 开始行号（默认 1）
- `endLine`: 结束行号（默认 100）
- Example: `read_file(simpleName="MyClass", startLine=1, endLine=200)`

---

**Remember**: When in doubt, `search` it out! The intelligent search will guide you to the right next steps.
