# 项目上下文 (Project Context)

## 项目概述

本项目已完成基础分析，你可以通过以下方式访问项目信息：

### 1. 项目结构和技术栈

以下信息已自动从项目分析结果中提取：

{{PROJECT_CONTEXT_SUMMARY}}

### 2. 如何访问更多项目信息

本项目已完成基础分析，你可以通过以下方式访问详细信息：

#### expert_consult 工具（推荐）

这是最强大的查询工具，提供**业务 ↔ 代码双向查询**能力：

**业务 → Code**（从需求查代码）：
```
expert_consult(question="项目中有哪些 API 入口？")
expert_consult(question="新增还款方式怎么配置？")
expert_consult(question="资金划拨流程是怎样的？")
```

**Code → 业务**（从代码查业务）：
```
expert_consult(question="BusinessContract在哪些业务场景里面会用？")
expert_consult(question="PaymentService.executePayment是干什么的？")
```

#### 分析结果数据存储

所有分析结果存储在 H2 数据库中：
- **路径**: `~/.sman/vector/{projectKey}/data.mv.db`
- **表**: `analysis_step`

可用数据模块：
| 步骤 | 数据 |
|------|------|
| `project_structure` | 项目模块、包结构 |
| `tech_stack_detection` | 框架、数据库、中间件 |
| `ast_scanning` | 类、方法、字段 AST 信息 |
| `db_entity_detection` | 数据库实体和关系 |
| `api_entry_scanning` | HTTP/API 入口 |
| `external_api_scanning` | Feign/Retrofit 客户端 |
| `enum_scanning` | 枚举类 |
| `common_class_scanning` | 工具类、帮助类 |
| `xml_code_scanning` | MyBatis Mapper |

#### 直接查询示例（高级用法）

如果你想直接查询数据库，可以使用 `read_file` 工具配合 H2 Shell：

```
read_file(simpleName="H2QueryService", startLine=1, endLine=999)
```

### 3. 最佳实践

1. **优先使用 `expert_consult`**：90% 的问题都可以通过这个工具解决
2. **精确定位后再用其他工具**：`expert_consult` 告诉你类名后，用 `read_file` 读取完整代码
3. **利用调用链分析**：使用 `call_chain` 工具理解方法调用关系

### 4. 数据可用性

- **项目结构**: ✓ 可用
- **技术栈**: ✓ 可用
- **API 入口**: ✓ 可用
- **数据库实体**: ✓ 可用
- **枚举定义**: ✓ 可用
- **AST 信息**: ✓ 可用（完整）
- **向量化数据**: ✓ 可用（语义搜索）
