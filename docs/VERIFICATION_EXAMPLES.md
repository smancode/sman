# SmanAgent 验证服务使用示例

## 目录

1. [启动服务](#1-启动服务)
2. [专家咨询示例](#2-专家咨询示例)
3. [向量搜索示例](#3-向量搜索示例)
4. [分析结果查询示例](#4-分析结果查询示例)
5. [H2 数据查询示例](#5-h2-数据查询示例)
6. [集成测试说明](#6-集成测试说明)
7. [故障排查](#7-故障排查)

---

## 1. 启动服务

### 1.1 使用启动脚本（推荐）

```bash
# 使用默认端口 8080
./scripts/verification-web.sh

# 使用自定义端口
VERIFICATION_PORT=9090 ./scripts/verification-web.sh
```

**预期输出**:
```
正在构建 JAR...
启动验证服务...
端口: 8080
日志: /Users/liuchao/projects/smanunion/logs/verification/verification-web.log

API 端点:
  - POST http://localhost:8080/api/verify/expert_consult
  - POST http://localhost:8080/api/verify/semantic_search
  - POST http://localhost:8080/api/verify/analysis_results

按 Ctrl+C 停止服务
```

### 1.2 使用 Gradle 直接启动

```bash
# 构建
./gradlew clean build -x test

# 启动（默认端口 8080）
java -jar build/libs/smanunion-2.0.0.jar --spring.main.web-application-type=SERVLET

# 启动（自定义端口）
java -Dserver.port=9090 -jar build/libs/smanunion-2.0.0.jar --spring.main.web-application-type=SERVLET
```

### 1.3 健康检查

```bash
# 检查服务是否启动
curl http://localhost:8080/actuator/health

# 预期响应
# {"status":"UP"}
```

### 1.4 查看日志

```bash
# 实时查看日志
tail -f logs/verification/verification-web.log

# 查看最近 100 行
tail -n 100 logs/verification/verification-web.log
```

---

## 2. 专家咨询示例

### 2.1 基本用法

```bash
curl -X POST http://localhost:8080/api/verify/expert_consult \
  -H "Content-Type: application/json" \
  -d '{
    "question": "放款入口是哪个？",
    "projectKey": "loan-system",
    "topK": 10,
    "enableRerank": true
  }'
```

**预期响应**:
```json
{
    "answer": "放款入口是 LoanController.createLoan() 方法，位于 src/main/kotlin/com/example/LoanController.kt 第 42 行...",
    "sources": [
        {
            "fragmentId": "frag-001",
            "fileName": "LoanController.kt",
            "lineNumber": 42,
            "content": "@PostMapping\nfun createLoan(@RequestBody request: CreateLoanRequest)",
            "score": 0.95,
            "type": "code"
        }
    ],
    "confidence": 0.85,
    "processingTimeMs": 1234
}
```

### 2.2 简化参数

```bash
curl -X POST http://localhost:8080/api/verify/expert_consult \
  -H "Content-Type: application/json" \
  -d '{
    "question": "如何验证用户权限？",
    "projectKey": "auth-system"
  }'
```

### 2.3 错误处理示例

**缺少必填参数**:
```bash
curl -X POST http://localhost:8080/api/verify/expert_consult \
  -H "Content-Type: application/json" \
  -d '{
    "projectKey": "loan-system"
  }'

# 预期响应: 400 Bad Request
# {"message": "question 不能为空"}
```

---

## 3. 向量搜索示例

### 3.1 基本用法

```bash
curl -X POST http://localhost:8080/api/verify/semantic_search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "用户登录验证",
    "projectKey": "auth-system",
    "topK": 10,
    "enableRerank": true,
    "rerankTopN": 5
  }'
```

**预期响应**:
```json
{
    "query": "用户登录验证",
    "recallResults": [
        {
            "fragmentId": "frag-001",
            "fileName": "LoginController.kt",
            "content": "fun login(username: String, password: String): ResponseEntity",
            "score": 0.89,
            "rank": 1
        },
        {
            "fragmentId": "frag-002",
            "fileName": "AuthService.kt",
            "content": "fun authenticate(token: String): Boolean",
            "score": 0.82,
            "rank": 2
        }
    ],
    "rerankResults": [
        {
            "fragmentId": "frag-001",
            "fileName": "LoginController.kt",
            "content": "fun login(username: String, password: String): ResponseEntity",
            "score": 0.95,
            "rank": 1
        }
    ],
    "processingTimeMs": 567
}
```

### 3.2 不启用重排序

```bash
curl -X POST http://localhost:8080/api/verify/semantic_search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "数据库连接配置",
    "projectKey": "my-project",
    "enableRerank": false
  }'
```

### 3.3 全局搜索（不指定项目）

```bash
curl -X POST http://localhost:8080/api/verify/semantic_search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Spring Boot 配置"
  }'
```

---

## 4. 分析结果查询示例

### 4.1 查询 API 入口

```bash
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{
    "module": "api_entries",
    "projectKey": "loan-system",
    "page": 0,
    "size": 20
  }'
```

**预期响应**:
```json
{
    "module": "api_entries",
    "projectKey": "loan-system",
    "data": [
        {
            "path": "/api/loan/create",
            "method": "POST",
            "controller": "LoanController",
            "description": "创建贷款"
        },
        {
            "path": "/api/loan/approve",
            "method": "POST",
            "controller": "LoanController",
            "description": "审批贷款"
        }
    ],
    "total": 2,
    "page": 0,
    "size": 20
}
```

### 4.2 查询技术栈

```bash
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{
    "module": "tech_stack",
    "projectKey": "my-project"
  }'
```

### 4.3 查询数据库实体

```bash
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{
    "module": "db_entities",
    "projectKey": "my-project",
    "page": 0,
    "size": 50
  }'
```

### 4.4 使用过滤条件

```bash
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{
    "module": "api_entries",
    "projectKey": "loan-system",
    "filters": {
        "path": "/api/loan"
    },
    "page": 0,
    "size": 20
  }'
```

### 4.5 支持的所有模块

```bash
# 项目结构
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "project_structure", "projectKey": "my-project"}'

# AST 扫描
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "ast_scan", "projectKey": "my-project"}'

# 外调接口
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "external_apis", "projectKey": "my-project"}'

# 枚举扫描
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "enums", "projectKey": "my-project"}'

# 公共类扫描
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "common_classes", "projectKey": "my-project"}'

# XML 代码扫描
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "xml_codes", "projectKey": "my-project"}'

# 案例 SOP
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "case_sop", "projectKey": "my-project"}'

# 向量化
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "vectorization", "projectKey": "my-project"}'

# 代码走读
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H "Content-Type: application/json" \
  -d '{"module": "code_walkthrough", "projectKey": "my-project"}'
```

---

## 5. H2 数据查询示例

### 5.1 查询向量片段

```bash
curl -X POST http://localhost:8080/api/verify/h2_query \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT * FROM vector_fragments WHERE project_key = ? LIMIT 10",
    "projectKey": "loan-system",
    "limit": 10
  }'
```

**预期响应**:
```json
{
    "results": [
        {
            "fragment_id": "frag-001",
            "project_key": "loan-system",
            "content": "class LoanController {...}",
            "file_path": "src/main/kotlin/LoanController.kt",
            "created_at": "2026-01-31T12:00:00"
        }
    ],
    "columns": ["fragment_id", "project_key", "content", "file_path", "created_at"],
    "rowCount": 1,
    "executionTimeMs": 23
}
```

### 5.2 查询项目信息

```bash
curl -X POST http://localhost:8080/api/verify/h2_query \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT * FROM project_info WHERE project_key = ?",
    "projectKey": "loan-system"
  }'
```

### 5.3 统计查询

```bash
curl -X POST http://localhost:8080/api/verify/h2_query \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT COUNT(*) as count FROM vector_fragments WHERE project_key = ?",
    "projectKey": "loan-system"
  }'
```

### 5.4 联表查询

```bash
curl -X POST http://localhost:8080/api/verify/h2_query \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT vf.*, pi.project_name FROM vector_fragments vf JOIN project_info pi ON vf.project_key = pi.project_key WHERE vf.project_key = ? LIMIT 5",
    "projectKey": "loan-system",
    "limit": 5
  }'
```

---

## 6. 集成测试说明

### 6.1 运行单元测试

```bash
# 运行所有测试
./gradlew test

# 运行验证服务测试
./gradlew test --tests "*VerificationApiControllersTest*"

# 运行特定测试类
./gradlew test --tests "*ExpertConsultApiTests*"
```

### 6.2 查看测试报告

```bash
# HTML 报告
open build/reports/tests/test/index.html

# 覆盖率报告（如果配置了 JaCoCo）
open build/reports/jacoco/test/html/index.html
```

### 6.3 运行测试脚本

```bash
# 使用提供的测试脚本
./scripts/test-verification-api.sh
```

### 6.4 手动集成测试

```bash
# 1. 启动服务
./scripts/verification-web.sh &
SERVICE_PID=$!

# 2. 等待服务启动
sleep 5

# 3. 运行测试
curl -X POST http://localhost:8080/api/verify/expert_consult \
  -H "Content-Type: application/json" \
  -d '{"question": "测试", "projectKey": "test"}'

# 4. 停止服务
kill $SERVICE_PID
```

---

## 7. 故障排查

### 7.1 服务无法启动

**问题**: 启动时提示端口已被占用

```bash
# 检查端口占用
lsof -i :8080

# 解决方案 1: 杀死占用进程
kill -9 <PID>

# 解决方案 2: 使用其他端口
VERIFICATION_PORT=9090 ./scripts/verification-web.sh
```

**问题**: 找不到 Java

```bash
# 检查 Java 版本
java -version

# 需要 Java 17+
# 如果没有，请安装 Java 17
```

### 7.2 API 调用失败

**问题**: 404 Not Found

```bash
# 检查服务是否启动
curl http://localhost:8080/actuator/health

# 检查 URL 是否正确
# 正确: /api/verify/expert_consult
# 错误: /api/verify/expertConsult
```

**问题**: 400 Bad Request

```bash
# 检查请求格式
curl -X POST http://localhost:8080/api/verify/expert_consult \
  -H "Content-Type: application/json" \
  -d '...'

# 确保设置了 Content-Type: application/json
```

**问题**: 500 Internal Server Error

```bash
# 查看日志
tail -f logs/verification/verification-web.log

# 检查环境变量
echo $LLM_API_KEY
echo $BGE_ENDPOINT
```

### 7.3 性能问题

**问题**: 响应时间过长

```bash
# 检查 topK 参数
# topK 越大，响应时间越长
# 建议: topK <= 50

# 检查是否启用了重排序
# 重排序会增加响应时间
# 如果对精度要求不高，可以禁用
curl -X POST http://localhost:8080/api/verify/semantic_search \
  -H "Content-Type: application/json" \
  -d '{"query": "...", "enableRerank": false}'
```

### 7.4 数据库问题

**问题**: H2 数据库连接失败

```bash
# 检查数据库文件
ls -la ~/.smanunion/h2/

# 检查数据库连接配置
# 默认: ~/.smanunion/h2/verification
```

### 7.5 日志调试

```bash
# 启用 DEBUG 日志
java -Dlogging.level.com.smancode.smanagent=DEBUG \
     -jar build/libs/smanunion-2.0.0.jar \
     --spring.main.web-application-type=SERVLET

# 查看详细日志
tail -f logs/verification/verification-web.log | grep DEBUG
```

---

## 附录

### A. 环境变量配置

```bash
# LLM 配置
export LLM_API_KEY=your_api_key_here
export LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4/chat/completions
export LLM_MODEL_NAME=glm-4-flash

# BGE-M3 配置
export BGE_ENABLED=true
export BGE_ENDPOINT=your_bge_endpoint
export BGE_MODEL_NAME=bge-m3

# Reranker 配置
export RERANKER_ENABLED=true
export RERANKER_BASE_URL=your_reranker_endpoint
export RERANKER_API_KEY=your_reranker_api_key

# 服务配置
export VERIFICATION_PORT=8080
export LOG_DIR=logs/verification
```

### B. 配置文件

配置文件位置: `src/main/resources/smanagent.properties`

```properties
# LLM 配置
llm.api.key=${LLM_API_KEY}
llm.base.url=${LLM_BASE_URL}
llm.model.name=${LLM_MODEL_NAME}

# BGE-M3 配置
bge.enabled=${BGE_ENABLED}
bge.endpoint=${BGE_ENDPOINT}
bge.model.name=${BGE_MODEL_NAME}

# Reranker 配置
reranker.enabled=${RERANKER_ENABLED}
reranker.base.url=${RERANKER_BASE_URL}
reranker.api.key=${RERANKER_API_KEY}
```

### C. 常用命令

```bash
# 快速重启
fuser -k 8080/tcp && ./scripts/verification-web.sh

# 批量测试
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/verify/expert_consult \
    -H "Content-Type: application/json" \
    -d '{"question": "测试", "projectKey": "test"}'
done

# 性能测试
ab -n 1000 -c 10 -p request.json -T application/json \
   http://localhost:8080/api/verify/expert_consult
```

---

## 联系方式

- 问题反馈: [GitHub Issues](https://github.com/your-repo/issues)
- 文档: [项目 Wiki](https://github.com/your-repo/wiki)
