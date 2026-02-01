# SmanAgent 验证服务测试指南

## 快速开始

### 1. 启动插件

```bash
cd /Users/liuchao/projects/smanunion
./gradlew runIde
```

### 2. 在 IDEA 中执行项目分析

1. 在打开的 IDEA 中，点击菜单：`Tools → SmanAgent → Show Tool Window`
2. 点击设置按钮 ⚙️
3. 点击"项目分析"按钮
4. 等待分析完成（9个步骤）

### 3. 执行测试

#### 方式一：使用 IntelliJ IDEA HTTP Client

1. 打开 `http/rest.http` 文件
2. 点击每个请求旁边的绿色运行按钮
3. 查看响应结果

#### 方式二：使用 curl 命令

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# 1. 项目结构
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H 'Content-Type: application/json' \
  -d '{"module": "project_structure", "projectKey": "autoloop"}'

# 2. 技术栈
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H 'Content-Type: application/json' \
  -d '{"module": "tech_stack", "projectKey": "autoloop"}'

# 3. API 入口
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H 'Content-Type: application/json' \
  -d '{"module": "api_entries", "projectKey": "autoloop"}'

# 4. 专家咨询
curl -X POST http://localhost:8080/api/verify/expert_consert \
  -H 'Content-Type: application/json' \
  -d '{"question": "还款入口是哪个，有哪些还款类型", "projectKey": "autoloop", "topK": 10}'

# 5. 语义搜索
curl -X POST http://localhost:8080/api/verify/semantic_search \
  -H 'Content-Type: application/json' \
  -d '{"query": "还款入口是哪个，有哪些还款类型", "projectKey": "autoloop", "topK": 10}'
```

## 25 个测试用例清单

### 1. 健康检查
```
GET http://localhost:8080/actuator/health
```

### 2-10. 分析结果查询 (9个模块)
```
POST http://localhost:8080/api/verify/analysis_results
{
  "module": "project_structure | tech_stack | ast_scanning | db_entities | api_entries | external_apis | enums | common_classes | xml_code",
  "projectKey": "autoloop"
}
```

### 11-18, 20-23. 专家咨询 (8个问题)
```
POST http://localhost:8080/api/verify/expert_consert
{
  "question": "问题文本",
  "projectKey": "autoloop",
  "topK": 10
}

问题列表：
- 项目中有哪些 API 入口？
- 项目中有哪些数据库实体？它们之间的关系是什么？
- 这个项目的整体架构是什么？使用了哪些技术栈？
- 项目调用了哪些外部接口？
- 项目中有哪些枚举类型？分别代表什么含义？
- 项目中有哪些公共类和工具类？
- 项目中有哪些 XML 配置文件？
- 项目的主要功能模块有哪些？
```

### 19, 24-27. 语义搜索 (5个查询)
```
POST http://localhost:8080/api/verify/semantic_search
{
  "query": "查询文本",
  "projectKey": "autoloop",
  "topK": 10,
  "enableRerank": true
}

查询列表：
- 还款入口是哪个，有哪些还款类型
- 用户登录验证
- 放款流程和入口
- 用户管理和权限控制
- 借款申请流程
```

### 28-29. H2 数据库查询 (2个)
```
POST http://localhost:8080/api/verify/query_vectors
{
  "projectKey": "autoloop",
  "page": 0,
  "size": 10
}

POST http://localhost:8080/api/verify/query_projects
{
  "page": 0,
  "size": 10
}
```

## 测试结果示例

### 成功响应示例

#### 健康检查
```json
{
  "status": "UP"
}
```

#### 分析结果查询
```json
{
  "module": "api_entries",
  "projectKey": "autoloop",
  "data": [
    {
      "className": "RepaymentController",
      "method": "repay",
      "path": "/api/repayment",
      "httpMethod": "POST"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20
}
```

#### 专家咨询
```json
{
  "answer": "根据分析结果，autoloop 项目的还款入口是 RepaymentController.repay() 方法...",
  "sources": [
    {
      "fragmentId": "frag-001",
      "fileName": "RepaymentController.kt",
      "lineNumber": 42,
      "content": "fun repay(@RequestBody request: RepaymentRequest)",
      "score": 0.95
    }
  ],
  "confidence": 0.85,
  "processingTimeMs": 1234
}
```

#### 语义搜索
```json
{
  "query": "还款入口是哪个，有哪些还款类型",
  "recallResults": [
    {
      "fragmentId": "frag-001",
      "fileName": "RepaymentController.kt",
      "content": "fun repay(@RequestBody request: RepaymentRequest)",
      "score": 0.89
    }
  ],
  "rerankResults": [
    {
      "fragmentId": "frag-001",
      "fileName": "RepaymentController.kt",
      "content": "fun repay(@RequestBody request: RepaymentRequest)",
      "score": 0.92
    }
  ],
  "processingTimeMs": 567
}
```

## 故障排查

### 1. 服务未启动
- 确认已运行 `./gradlew runIde`
- 确认 IDEA 已完全启动
- 检查端口 8080 是否被占用

### 2. 分析结果为空
- 确认已点击"项目分析"按钮
- 确认分析已完成（检查日志）
- 确认 projectKey 正确（autoloop）

### 3. 专家咨询失败
- 检查 LLM_API_KEY 环境变量
- 查看 IDEA 日志获取详细错误信息

### 4. 语义搜索失败
- 检查 BGE_ENABLED 是否启用
- 检查 BGE_ENDPOINT 配置

## 相关文件

| 文件 | 说明 |
|------|------|
| `http/rest.http` | IntelliJ IDEA HTTP Client 测试文件 |
| `scripts/test-verification-api.sh` | Bash 自动化测试脚本 |
| `scripts/run-analysis-and-test.sh` | 完整的测试执行脚本 |
| `docs/VERIFICATION_TEST_REPORT.md` | 单元测试报告 |
