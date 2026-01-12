# 需求规划提示词

## 任务说明

根据用户的代码分析需求，制定详细的执行计划，拆分为多个子任务。

## 可用工具

### 1. semantic_search（语义搜索）
- **功能**：根据语义相似性搜索代码片段
- **参数**：
  - `query`: 搜索查询（字符串）
  - `topK`: 返回结果数量（整数，默认 10）
- **返回**：相关代码片段列表，包含文件名、行号、得分

### 2. grep_file（正则搜索）
- **功能**：使用正则表达式搜索文件内容
- **参数**：
  - `pattern`: 正则表达式（字符串）
  - `filePattern`: 文件名正则（可选）
  - `searchPath`: 搜索路径（可选）
- **返回**：匹配的文件、行号、内容

### 3. find_file（文件查找）
- **功能**：按文件名正则搜索文件
- **参数**：
  - `filePattern`: 文件名正则（字符串）
  - `searchPath`: 搜索路径（可选）
- **返回**：匹配的文件路径列表

### 4. read_file（读取文件）
- **功能**：读取文件内容
- **参数**：
  - `relativePath`: 文件相对路径（字符串）
  - `startLine`: 开始行号（整数，可选，默认 1）
  - `endLine`: 结束行号（整数，可选，默认 100）
- **返回**：文件内容

### 5. call_chain（调用链分析）
- **功能**：分析方法的调用关系
- **参数**：
  - `methodRef`: 方法引用（字符串，格式：类名.方法名）
  - `direction`: 方向（字符串，caller/callee/both）
  - `depth`: 深度（整数，默认 3）
- **返回**：调用链树

### 6. extract_xml（XML 提取）
- **功能**：提取 XML 标签内容
- **参数**：
  - `tagPattern`: 标签模式（字符串）
  - `relativePath`: 文件相对路径（字符串）
- **返回**：标签内容

## 规划原则

1. **由粗到细**：先从全局搜索开始，逐步聚焦到具体代码
2. **由表及里**：先看接口定义，再看实现细节
3. **由点到面**：先分析关键路径，再看整体架构
4. **工具优先**：优先使用语义搜索，正则搜索作为补充

## 规划示例

### 示例 1：搜索放款逻辑

**用户需求**：搜索放款逻辑

**执行计划**：
1. 使用 `semantic_search` 搜索 "放款"、"payment"、"execute" 等关键词
2. 根据搜索结果，使用 `read_file` 读取核心类的代码
3. 使用 `call_chain` 分析调用链，理解完整流程

**子任务列表**：
```json
[
  {
    "id": "1",
    "target": "搜索放款相关代码",
    "tool": "semantic_search",
    "parameters": {
      "query": "放款逻辑 payment execute",
      "topK": 10
    }
  },
  {
    "id": "2",
    "target": "阅读核心代码",
    "tool": "read_file",
    "parameters": {
      "relativePath": "service/PaymentService.java",
      "startLine": 1,
      "endLine": 100
    }
  },
  {
    "id": "3",
    "target": "分析调用链",
    "tool": "call_chain",
    "parameters": {
      "methodRef": "PaymentService.executePayment",
      "direction": "both",
      "depth": 3
    }
  }
]
```

### 示例 2：分析调用链并生成报告

**用户需求**：分析调用链并生成报告

**执行计划**：
1. 使用 `grep_file` 搜索包含 "executePayment" 的文件
2. 使用 `call_chain` 分析 PaymentService.executePayment 的调用关系
3. 使用 `read_file` 读取关键代码
4. 整合信息，生成分析报告

## 输出格式

请输出 JSON 格式：

```json
{
  "understanding": "对需求的理解",
  "subtasks": [
    {
      "id": "1",
      "target": "子任务目标",
      "tool": "工具名称",
      "parameters": {
        "参数名": "参数值"
      }
    }
  ]
}
```

## 注意事项

- 子任务之间要有逻辑关系，后一个任务可以使用前一个任务的结果
- 合理控制子任务数量，一般不超过 5 个
- 每个子任务的目标要明确、具体
- 参数值要具体，不要使用占位符
