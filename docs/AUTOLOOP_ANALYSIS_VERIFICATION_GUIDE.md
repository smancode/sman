# Autoloop 项目分析完整验证指南

## 目标

验证点击"项目分析"按钮后的完整流程：
1. 执行 9 个分析步骤
2. 验证每个步骤的分析结果
3. 测试向量化和语义搜索

## 前置条件

| 检查项 | 命令 | 预期结果 |
|--------|------|---------|
| BGE 服务 | `curl http://localhost:8000/health` | `{"status":"healthy"}` |
| Reranker 服务 | `curl http://localhost:8001/health` | `{"status":"healthy"}` |
| autoloop 项目 | `ls -la ../autoloop` | 项目目录存在 |

## 第一步：在 IDEA 中执行项目分析

### 1.1 启动 IDEA 并打开项目

```bash
# 方式一：通过命令行
./gradlew runIde

# 方式二：直接在 IDEA 中打开 smanunion 项目
```

### 1.2 打开 autoloop 项目

1. 在 IDEA 中，点击 `File → Open`
2. 选择 `../autoloop` 目录
3. 等待 IDEA 索引完成

### 1.3 执行项目分析

1. 在 IDEA 右侧找到 `SmanAgent` 工具窗口
2. 点击 **"项目分析"** 按钮
3. 等待以下 9 个步骤完成：

| # | 步骤名称 | 描述 | 验证点 |
|---|---------|------|--------|
| 1 | project_structure | 项目结构扫描 | 应该包含模块和包信息 |
| 2 | tech_stack_detection | 技术栈检测 | 应该识别 Spring Boot/Kotlin |
| 3 | ast_scanning | AST 扫描 | 应该扫描到类和方法 |
| 4 | db_entity_detection | 数据库实体检测 | 应该找到 @Entity 类 |
| 5 | api_entry_scanning | API 入口扫描 | 应该找到 Controller |
| 6 | external_api_scanning | 外调接口扫描 | 应该找到 Feign/HTTP 客户端 |
| 7 | enum_scanning | 枚举扫描 | 应该找到枚举类 |
| 8 | common_class_scanning | 公共类扫描 | 应该找到工具类 |
| 9 | xml_code_scanning | XML 代码扫描 | 应该找到 Mapper 或配置 |

## 第二步：验证分析结果

### 2.1 使用 H2 Shell 查询

```bash
# 连接到 H2 数据库
java -cp ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar \
  org.h2.tools.Shell \
  -url "jdbc:h2:$HOME/.smanunion/autoloop/analysis" \
  -user sa \
  -password ""
```

### 2.2 查询分析结果

```sql
-- 查看项目分析记录
SELECT * FROM project_analysis WHERE project_key = 'autoloop';

-- 查看分析步骤
SELECT step_name, status, error_message
FROM analysis_step
WHERE project_key = 'autoloop'
ORDER BY start_time;

-- 查看向量片段数量
SELECT COUNT(*) FROM vector_fragments WHERE project_key = 'autoloop';

-- 按 tag 统计片段
SELECT tag, COUNT(*) as count
FROM vector_fragments
WHERE project_key = 'autoloop'
GROUP BY tag;
```

### 2.3 预期结果

| 检查项 | 预期结果 |
|--------|---------|
| project_analysis 记录 | 1 条，status = 'COMPLETED' |
| analysis_step 记录 | 9 条，全部 status = 'COMPLETED' |
| vector_fragments 数量 | > 0 |

## 第三步：启动验证服务

### 3.1 在 IDEA 中启动 VerificationWebService

1. 打开 `VerificationWebService.kt`
2. 右键 → `Run 'VerificationWebService'`
3. 等待服务启动（看到 `Started VerificationWebService`）

### 3.2 验证服务状态

```bash
curl http://localhost:8080/actuator/health
```

预期输出：
```json
{"status":"UP"}
```

## 第四步：运行 HTTP 测试

打开 `http/rest.http`，执行以下测试：

### 4.1 健康检查

```http
GET http://localhost:8080/actuator/health
```

### 4.2 查询分析结果（9 个模块）

```http
### 1. 项目结构
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "project_structure",
  "projectKey": "autoloop"
}

### 2. 技术栈
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "tech_stack",
  "projectKey": "autoloop"
}

### 3. AST 扫描
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "ast_scanning",
  "projectKey": "autoloop"
}

### 4. 数据库实体
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "db_entities",
  "projectKey": "autoloop"
}

### 5. API 入口
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "api_entries",
  "projectKey": "autoloop"
}

### 6. 外调接口
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "external_apis",
  "projectKey": "autoloop"
}

### 7. 枚举
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "enums",
  "projectKey": "autoloop"
}

### 8. 公共类
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "common_classes",
  "projectKey": "autoloop"
}

### 9. XML 代码
POST http://localhost:8080/api/verify/analysis_results
Content-Type: application/json

{
  "module": "xml_code",
  "projectKey": "autoloop"
}
```

### 4.3 语义搜索测试

```http
### 测试 1: 还款入口查询
POST http://localhost:8080/api/verify/semantic_search
Content-Type: application/json

{
  "query": "还款入口是哪个",
  "projectKey": "autoloop",
  "topK": 10,
  "enableRerank": true,
  "rerankTopN": 5
}

### 预期结果: 应该找到 RepaymentController 相关的代码

### 测试 2: 用户登录查询
POST http://localhost:8080/api/verify/semantic_search
Content-Type: application/json

{
  "query": "用户登录验证",
  "projectKey": "autoloop",
  "topK": 5,
  "enableRerank": true
}

### 预期结果: 应该找到 LoginController 相关的代码

### 测试 3: 放款流程查询
POST http://localhost:8080/api/verify/semantic_search
Content-Type: application/json

{
  "query": "放款流程和入口",
  "projectKey": "autoloop",
  "topK": 10,
  "enableRerank": true,
  "rerankTopN": 3
}

### 预期结果: 应该找到 LoanController 相关的代码
```

## 第五步：验证结果记录

### 5.1 创建验证报告

使用以下模板记录验证结果：

```markdown
# Autoloop 项目分析验证报告

**验证时间**: ___________
**验证人**: ___________

## 前置条件

| 检查项 | 状态 |
|--------|------|
| BGE 服务 (8000) | ☐ 通过 |
| Reranker 服务 (8001) | ☐ 通过 |
| autoloop 项目 | ☐ 通过 |

## 分析步骤验证

| 步骤 | 描述 | 状态 | 备注 |
|------|------|------|------|
| 1 | project_structure | ☐ 通过 ☐ 失败 | |
| 2 | tech_stack_detection | ☐ 通过 ☐ 失败 | |
| 3 | ast_scanning | ☐ 通过 ☐ 失败 | |
| 4 | db_entity_detection | ☐ 通过 ☐ 失败 | |
| 5 | api_entry_scanning | ☐ 通过 ☐ 失败 | |
| 6 | external_api_scanning | ☐ 通过 ☐ 失败 | |
| 7 | enum_scanning | ☐ 通过 ☐ 失败 | |
| 8 | common_class_scanning | ☐ 通过 ☐ 失败 | |
| 9 | xml_code_scanning | ☐ 通过 ☐ 失败 | |

## 语义搜索验证

| 查询 | 预期结果 | 实际结果 | 状态 |
|------|---------|---------|------|
| "还款入口是哪个" | RepaymentController | | ☐ 通过 ☐ 失败 |
| "用户登录验证" | LoginController | | ☐ 通过 ☐ 失败 |
| "放款流程和入口" | LoanController | | ☐ 通过 ☐ 失败 |

## 问题记录

| 问题 | 严重程度 | 状态 |
|------|---------|------|
| | | |

## 总结

**整体状态**: ☐ 全部通过 ☐ 部分通过 ☐ 失败
```

## 常见问题

### Q1: 点击"项目分析"后没有反应？

**A**: 检查：
1. IDEA 是否已打开 autoloop 项目
2. 查看 IDEA 底部是否有进度条
3. 查看 `Help → Show Log in Explorer` 查看日志

### Q2: 分析过程中断失败？

**A**: 常见原因：
1. BGE 服务未启动
2. 项目代码有语法错误
3. 内存不足

解决方案：
```bash
# 检查 BGE 服务
curl http://localhost:8000/health

# 增加 IDEA 内存
# Help → Edit Custom VM Options → 添加 -Xmx4g
```

### Q3: VerificationWebService 启动失败？

**A**: 确保在 IDEA 中配置了正确的运行配置：
- Main class: `com.smancode.smanagent.verification.VerificationWebService`
- VM options: `-Dserver.port=8080`

### Q4: 语义搜索返回空结果？

**A**: 检查：
1. 项目分析是否完成
2. 向量化是否成功
3. H2 数据库中是否有向量片段

```bash
# 检查向量片段数量
java -cp ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar \
  org.h2.tools.Shell \
  -url "jdbc:h2:$HOME/.smanunion/autoloop/analysis" \
  -user sa -password "" \
  -sql "SELECT COUNT(*) FROM vector_fragments"
```

## 总结

完整验证流程：

1. ✅ 检查前置条件（BGE、Reranker 服务）
2. ✅ 在 IDEA 中打开 autoloop 项目
3. ✅ 点击"项目分析"按钮
4. ✅ 等待 9 个步骤完成
5. ✅ 使用 H2 Shell 验证分析结果
6. ✅ 启动 VerificationWebService
7. ✅ 运行 25 个 HTTP 测试用例
8. ✅ 记录验证结果

**注意**: 由于项目分析需要 IntelliJ IDEA 环境（PSI），无法完全自动化，需要在 IDEA 中手动执行。
