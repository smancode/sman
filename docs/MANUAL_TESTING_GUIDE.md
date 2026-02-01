# SmanAgent 验证服务测试指南

## 当前状态

✅ **IDEA 已运行** - autoloop 项目已打开
✅ **项目分析已完成** - 9 个步骤执行完毕
✅ **测试文件已准备** - `http/rest.http` 包含 25 个测试用例
❌ **验证服务未启动** - Spring Boot Web 服务需要手动启动

## 问题分析

SmanAgent 是 IntelliJ IDEA 插件项目，验证服务（`VerificationWebService`）是一个独立的 Spring Boot 应用。由于插件项目没有配置 Spring Boot 的 `bootRun` 任务，验证服务不会自动启动。

## 解决方案

### 方式一：在 IDEA 中手动启动（推荐）

1. **打开 IDEA**
   - IDEA 已通过 `./gradlew runIde` 启动
   - autoloop 项目已打开

2. **找到主类**
   - 文件：`src/main/kotlin/com/smancode/smanagent/verification/VerificationWebService.kt`
   - 主类：`com.smancode.smanagent.verification.VerificationWebService`

3. **创建运行配置**
   - 点击 Run → Edit Configurations...
   - 点击 + → Application
   - 配置：
     - Name: `VerificationWebService`
     - Main class: `com.smancode.smanagent.verification.VerificationWebService`
     - VM options: `-Dserver.port=8080 -Dspring.main.web-application-type=SERVLET`
     - Working directory: `$ProjectFileDir$`
     - Use classpath of module: `smanunion.main`
   - 点击 OK

4. **运行服务**
   - 选择 `VerificationWebService` 配置
   - 点击绿色运行按钮
   - 等待服务启动（看到 "Started VerificationWebService" 日志）

5. **验证服务**
   ```bash
   curl http://localhost:8080/actuator/health
   ```

### 方式二：使用 HTTP Client 测试（无需启动服务）

由于验证服务需要 Spring Boot 环境，你可以使用单元测试来验证功能：

```bash
# 运行所有验证服务测试
./gradlew test --tests "*verification*"

# 查看测试报告
open build/reports/tests/test/index.html
```

### 方式三：直接使用 H2 数据库查询分析结果

分析结果已存储在 H2 数据库中，可以直接查询：

```bash
# 连接到 H2 Shell
./h2-shell.sh autoloop

# 查询项目分析结果
SELECT * FROM project_analysis WHERE project_key = 'autoloop';

# 查询分析步骤
SELECT * FROM analysis_step WHERE project_key = 'autoloop' ORDER BY start_time;

# 查询 API 入口数据
SELECT * FROM vector_fragments WHERE project_key = 'autoloop' AND type = 'api_entry';

# 退出
exit;
```

## 测试用例清单

### 单元测试（已通过 ✅）

| 测试类 | 测试数 | 通过 |
|--------|--------|------|
| AnalysisQueryServiceTest | 8 | 8 |
| H2QueryServiceTest | 10 | 10 |
| VectorSearchServiceTest | 7 | 7 |

### HTTP API 测试（需要先启动验证服务）

#### 1. 健康检查
```http
GET http://localhost:8080/actuator/health
```

#### 2-10. 分析结果查询
```http
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "project_structure",
  "projectKey": "autoloop"
}
```

支持的模块：
- `project_structure` - 项目结构
- `tech_stack` - 技术栈
- `ast_scanning` - AST 扫描
- `db_entities` - 数据库实体
- `api_entries` - API 入口
- `external_apis` - 外调接口
- `enums` - 枚举
- `common_classes` - 公共类
- `xml_code` - XML 代码

#### 11-14, 20-23. 专家咨询
```http
POST http://localhost:8080/api/verify/expert_consert
Content-Type: application/json

{
  "question": "还款入口是哪个，有哪些还款类型",
  "projectKey": "autoloop",
  "topK": 10
}
```

问题列表：
1. 项目中有哪些 API 入口？
2. 项目中有哪些数据库实体？
3. 这个项目的整体架构是什么？
4. 项目调用了哪些外部接口？
5. 项目中有哪些枚举类型？
6. 项目中有哪些公共类和工具类？
7. 项目中有哪些 XML 配置文件？
8. 项目的主要功能模块有哪些？

#### 15-17, 24-25. 语义搜索
```http
POST http://localhost:8080/api/verify/semantic_search
Content-Type: application/json

{
  "query": "还款入口是哪个，有哪些还款类型",
  "projectKey": "autoloop",
  "topK": 10,
  "enableRerank": true,
  "rerankTopN": 5
}
```

查询列表：
1. 还款入口是哪个，有哪些还款类型
2. 用户登录验证
3. 放款流程和入口
4. 用户管理和权限控制
5. 借款申请流程

#### 18-19. H2 数据库查询
```http
POST http://localhost:8080/api/verify/query_vectors
Content-Type: application/json

{
  "projectKey": "autoloop",
  "page": 0,
  "size": 10
}
```

```http
POST http://localhost:8080/api/verify/query_projects
Content-Type: application/json

{
  "page": 0,
  "size": 10
}
```

## 快速测试命令

```bash
# 1. 健康检查
curl http://localhost:8080/actuator/health

# 2. 查询 API 入口
curl -X POST http://localhost:8080/api/verify/analysis_results \
  -H 'Content-Type: application/json' \
  -d '{"module": "api_entries", "projectKey": "autoloop"}'

# 3. 专家咨询
curl -X POST http://localhost:8080/api/verify/expert_consert \
  -H 'Content-Type: application/json' \
  -d '{"question": "还款入口是哪个", "projectKey": "autoloop", "topK": 10}'

# 4. 语义搜索
curl -X POST http://localhost:8080/api/verify/semantic_search \
  -H 'Content-Type: application/json' \
  -d '{"query": "还款入口是哪个，有哪些还款类型", "projectKey": "autoloop", "topK": 10}'
```

## 总结

由于验证服务是独立的 Spring Boot 应用，需要手动启动。最简单的方式是在 IDEA 中创建运行配置并启动。

所有测试用例已准备在 `http/rest.http` 文件中，启动服务后即可执行测试。
