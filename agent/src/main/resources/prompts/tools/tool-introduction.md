# 工具介绍

## 可用工具列表

### 1. semantic_search - 语义搜索
**功能**：根据语义相似性搜索代码片段

**使用场景**：
- 不确定具体代码位置，只记得功能描述
- 需要找到实现某个功能的所有相关代码
- 代码重构时，需要找到受影响的代码

**参数**：
- `query`（必需）：搜索查询，如 "放款逻辑"、"用户验证"
- `topK`（可选）：返回结果数量，默认 10

**返回**：相关代码片段列表，包含：
- 文件路径和行号
- 代码内容
- 相似度得分

**示例**：
```json
{
  "query": "放款逻辑",
  "topK": 10
}
```

---

### 2. grep_file - 正则搜索
**功能**：使用正则表达式搜索文件内容

**使用场景**：
- 知道代码特征，如方法名、变量名
- 需要找到所有使用某个 API 的地方
- 代码审查时，查找特定模式

**参数**：
- `pattern`（必需）：正则表达式，如 `executePayment|processPayment`
- `filePattern`（可选）：文件名正则，如 `.*Service\.java`
- `searchPath`（可选）：搜索路径，如 `service/`

**返回**：匹配的文件列表，包含：
- 文件路径
- 行号
- 匹配的内容

**示例**：
```json
{
  "pattern": "public.*execute.*Payment",
  "filePattern": ".*Service\\.java"
}
```

---

### 3. find_file - 文件查找
**功能**：按文件名正则搜索文件

**使用场景**：
- 知道文件名（或部分文件名）
- 需要找到某个类或接口的所有实现
- 查找配置文件

**参数**：
- `filePattern`（必需）：文件名正则，如 `.*Service\.java`、`application.*\.yml`
- `searchPath`（可选）：搜索路径

**返回**：匹配的文件路径列表

**示例**：
```json
{
  "filePattern": ".*PaymentService\\.java"
}
```

---

### 4. read_file - 读取文件
**功能**：读取文件内容

**使用场景**：
- 需要查看某个类的完整代码
- 阅读配置文件
- 分析某个方法的实现

**参数**：
- `relativePath`（必需）：文件相对路径，如 `service/PaymentService.java`
- `startLine`（可选）：开始行号，默认 1
- `endLine`（可选）：结束行号，默认 100

**返回**：文件内容

**示例**：
```json
{
  "relativePath": "service/PaymentService.java",
  "startLine": 1,
  "endLine": 100
}
```

---

### 5. call_chain - 调用链分析
**功能**：分析方法的调用关系

**使用场景**：
- 理解方法的上下游调用关系
- 分析代码的依赖关系
- 追踪业务流程的代码实现

**参数**：
- `methodRef`（必需）：方法引用，格式 `类名.方法名`，如 `PaymentService.executePayment`
- `direction`（可选）：方向，`caller`（调用者）、`callee`（被调用者）、`both`（双向），默认 `both`
- `depth`（可选）：深度，默认 3

**返回**：调用链树

**示例**：
```json
{
  "methodRef": "PaymentService.executePayment",
  "direction": "both",
  "depth": 3
}
```

---

### 6. extract_xml - XML 提取
**功能**：提取 XML 标签内容

**使用场景**：
- 提取 Spring 配置
- 分析 Maven 依赖
- 提取自定义 XML 标签

**参数**：
- `tagPattern`（必需）：标签模式，如 `bean.*id="paymentService"`
- `relativePath`（必需）：文件相对路径

**返回**：标签内容

**示例**：
```json
{
  "tagPattern": "bean.*class=\".*PaymentService\"",
  "relativePath": "src/main/resources/application-context.xml"
}
```

---

### 7. apply_change - 代码修改
**功能**：应用代码修改

**使用场景**：
- 添加新方法
- 修改现有代码
- 重构代码结构
- 修复 bug

**参数**：
- `relativePath`（必需）：文件相对路径，如 `service/PaymentService.java`
- `searchContent`（必需）：搜索内容，要被替换的原代码
- `replaceContent`（必需）：替换内容，修改后的代码
- `description`（可选）：修改描述，说明本次修改的目的

**返回**：修改结果，包含：
- 修改的文件路径
- 修改的行数
- 修改预览

**示例**：
```json
{
  "relativePath": "service/PaymentService.java",
  "searchContent": "public void executePayment() {\n    // 原有逻辑\n}",
  "replaceContent": "public void executePayment() {\n    // 新增逻辑\n    logPayment();\n}",
  "description": "添加支付日志记录"
}
```

**注意事项**：
- `searchContent` 必须精确匹配原代码（包括空格、缩进）
- `replaceContent` 会替换整个 `searchContent` 匹配的部分
- 建议先使用 `read_file` 查看原代码，确保 `searchContent` 准确

---

## 工具选择指南

| 需求 | 推荐工具 | 说明 |
|------|----------|------|
| 找功能实现 | semantic_search | 语义搜索最准确 |
| 找方法使用 | grep_file | 正则匹配方法名 |
| 找类文件 | find_file | 按文件名查找 |
| 看代码实现 | read_file | 读取文件内容 |
| 理调用关系 | call_chain | 分析调用链 |
| 提取配置 | extract_xml | 提取 XML 标签 |
| **修改代码** | **apply_change** | **应用代码修改** |

---

## 典型工作流程

当用户要求"实现功能X"时，建议按以下流程操作：

1. **理解需求** → 用 semantic_search 查找相关代码
2. **定位代码** → 用 find_file 找到文件，read_file 查看具体实现
3. **分析影响** → 用 call_chain 分析调用关系
4. **实施修改** → 用 apply_change 修改代码
5. **验证结果** → 如果需要，再次 read_file 确认修改正确

---

## 注意事项

1. **参数校验**：所有必需参数必须提供，否则工具会报错
2. **返回格式**：工具返回统一格式的结果，包含 `success`、`data`、`displayTitle`、`displayContent`
3. **错误处理**：如果工具执行失败，会返回错误信息，请根据错误信息调整策略
4. **代码修改**：使用 apply_change 前，务必先 read_file 确认原代码，确保 searchContent 准确匹配
