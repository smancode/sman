# API 测试结果

测试时间: 2026-02-04 09:36:38
项目: autoloop

---

开始测试专家咨询 API...

## 测试 1: 专家咨询 - 放款接口

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "放款是哪个接口？", "projectKey": "autoloop", "topK": 3}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"【片段 1】\n文件: DisburseHandler\n片段ID: class:DisburseHandler\n相似度: 0.64\n内容:\n处理贷款放款HTTP请求，作为放款业务的REST入口。\n\n放款接口是处理贷款放款HTTP请求的接口，作为放款业务的REST入口。【片段 1】","sources":[{"filePath":"DisburseHandler","className":"","methodName":"","score":0.6397082805633545}],"confidence":0.8,"processingTimeMs":3099}
```

✅ **状态**: 成功

---

## 测试 2: 专家咨询 - 还款接口

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "有哪些还款相关的 Handler？", "projectKey": "autoloop", "topK": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"未找到相关代码片段。\n\n无法给出准确、简洁的答案，因为没有提供具体的代码上下文。请提供包含还款相关的 Handler 的代码片段，以便我能够引用并回答问题。","sources":[],"confidence":0.3,"processingTimeMs":1853}
```

✅ **状态**: 成功

---

## 测试 3: 专家咨询 - Handler 接口列表

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "项目中有哪些 Handler 接口？", "projectKey": "autoloop", "topK": 10}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"根据提供的代码片段，无法直接找到关于 Handler 接口的信息。这些片段主要涉及 ProjectStructureDTO 类的包信息获取、ClassMethodDTO 类的 Builder 构建器实例获取以及 Builder 类的参数和方法名称设置。没有提及任何与 Handler 接口相关的内容。\n\n答案：无法确定项目中有哪些 Handler 接口，因为提供的代码片段中没有相关信息。【片段 1】至【片段 4】均未提及 Handler 接口。","sources":[{"filePath":"getPackages","className":"","methodName":"","score":8.652687392896041},{"filePath":"builder","className":"","methodName":"","score":5.84913905186113},{"filePath":"Builder.parameters","className":"","methodName":"","score":2.3021630113362335},{"filePath":"Builder.methodName","className":"","methodName":"","score":1.9962515580118634}],"confidence":0.8,"processingTimeMs":4728}
```

✅ **状态**: 成功

---

## 测试 4: 专家咨询 - 数据库实体

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "项目中有哪些数据库实体？", "projectKey": "autoloop", "topK": 10}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"未找到相关代码片段。\n\n无法给出准确、简洁的答案，因为没有提供具体的代码片段。请提供相关代码位置或更多上下文信息。","sources":[],"confidence":0.3,"processingTimeMs":1681}
```

✅ **状态**: 成功

---

## 测试 5: 专家咨询 - 贷款状态

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "贷款状态有哪些？", "projectKey": "autoloop", "topK": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"根据提供的代码片段，无法直接确定具体的贷款状态有哪些。以下是根据代码片段中提到的操作推测可能的状态：\n\n- 校验贷款状态（【片段 1】）\n- 查询贷款账户列表（【片段 2】）\n- 检查贷款账户的状态是否允许放款（【片段 3】）\n- 检查贷款当前状态是否允许还款（【片段 4】）\n- 更新指定贷款账户的状态（【片段 5】）\n\n准确的状态列表需要查看数据库设计或更详细的业务逻辑代码。","sources":[{"filePath":"execute","className":"","methodName":"","score":0.5789032578468323},{"filePath":"selectByStatus","className":"","methodName":"","score":0.5232406854629517},{"filePath":"canDisburse","className":"","methodName":"","score":0.33580484986305237},{"filePath":"canRepay","className":"","methodName":"","score":0.3158576190471649},{"filePath":"updateStatus","className":"","methodName":"","score":0.10338665544986725}],"confidence":0.8,"processingTimeMs":6757}
```

✅ **状态**: 成功

---

## 测试 6: 专家咨询 - 外调接口

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "项目调用了哪些外部接口？", "projectKey": "autoloop", "topK": 10}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"【片段 1】\n文件: 外部API调用\n片段ID: autoloop:external_apis\n相似度: 0.96\n内容:\n项目对外部服务的API调用\n\n答案：项目调用了外部服务的API。具体调用的接口信息未在提供的代码片段中明确列出。","sources":[{"filePath":"外部API调用","className":"","methodName":"","score":0.9604983925819397}],"confidence":0.8,"processingTimeMs":3530}
```

✅ **状态**: 成功

---

## 测试 7: 专家咨询 - 项目结构

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "项目结构是什么样的？", "projectKey": "autoloop", "topK": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"项目结构展示在【片段 1】中，通过`ReportGeneratorService.buildPackageSection`方法来展示项目中各包的组织结构及其包含的类信息。","sources":[{"filePath":"buildPackageSection","className":"","methodName":"","score":0.29752665758132935},{"filePath":"generateReport","className":"","methodName":"","score":0.25554734468460083},{"filePath":"buildDirectorySection","className":"","methodName":"","score":0.1745673567056656},{"filePath":"ReportGeneratorService","className":"","methodName":"","score":0.135588139295578}],"confidence":0.8,"processingTimeMs":2128}
```

✅ **状态**: 成功

---

## 测试 8: 专家咨询 - 技术栈

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "项目用了什么技术栈？", "projectKey": "autoloop", "topK": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"answer":"未找到相关代码片段。\n\n无法给出准确、简洁的答案，因为缺少具体的代码片段来识别项目使用的技术栈。","sources":[],"confidence":0.3,"processingTimeMs":1336}
```

✅ **状态**: 成功

---
开始测试分析结果查询 API...

## 测试 9: 查询项目结构

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module": "project_structure", "projectKey": "autoloop", "page": 0, "size": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"module":"project_structure","projectKey":"autoloop","data":[{"DATA":"{\"rootPath\":\"/Users/liuchao/projects/autoloop\",\"modules\":[{\"name\":\"loan\",\"type\":\"GRADLE\",\"path\":\"/Users/liuchao/projects/autoloop/loan\"},{\"name\":\"core\",\"type\":\"GRADLE\",\"path\":\"/Users/liuchao/projects/autoloop/core\"},{\"name\":\"web\",\"type\":\"GRADLE\",\"path\":\"/Users/liuchao/projects/autoloop/web\"},{\"name\":\"integration\",\"type\":\"GRADLE\",\"path\":\"/Users/liuchao/projects/autoloop/integration\"},{\"name\":\"common\",\"type\":\"GRADLE\",\"path\":\"/Users/liuchao/projects/autoloop/common\"},{\"name\":\"autoloop\",\"type\":\"GRADLE\",\"path\":\"/Users/liuchao/projects/autoloop\"}],\"packages\":[{\"name\":\"com.autoloop.loan.handler\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/handler\",\"classCount\":5},{\"name\":\"com.autoloop.loan.context\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/context\",\"classCount\":1},{\"name\":\"com.autoloop.loan.config\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/config\",\"classCount\":2},{\"name\":\"com.autoloop.loan.integration\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/integration\",\"classCount\":1},{\"name\":\"com.autoloop.loan.integration.impl\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/integration/impl\",\"classCount\":1},{\"name\":\"com.autoloop.loan.mapper\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/mapper\",\"classCount\":6},{\"name\":\"com.autoloop.loan.procedure\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/procedure\",\"classCount\":11},{\"name\":\"com.autoloop.loan.model.dto\",\"path\":\"/Users/liuchao/projects/autoloop/loan/src/main/java/com/autoloop/loan/model/dto\",\"classCount\":8},{\"name\":\"com.autoloop.loan.model.entity\",\"path\":\"/Users/liu
... (内容过长，已截断)
```

✅ **状态**: 成功

---

## 测试 10: 查询技术栈

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module": "tech_stack_detection", "projectKey": "autoloop", "page": 0, "size": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"module":"tech_stack_detection","projectKey":"autoloop","data":[{"DATA":"{\"buildType\":\"GRADLE\",\"frameworks\":[{\"name\":\"Spring Boot\",\"version\":\"3.2.0\"},{\"name\":\"MyBatis\",\"version\":\"3.0.3\"}],\"languages\":[{\"name\":\"Java\",\"version\":\"17\",\"fileCount\":112}],\"databases\":[{\"name\":\"H2\",\"type\":\"RELATIONAL\"}]}"}],"total":1,"page":0,"size":5}
```

✅ **状态**: 成功

---

## 测试 11: 查询数据库实体

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module": "db_entity_detection", "projectKey": "autoloop", "page": 0, "size": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"module":"db_entity_detection","projectKey":"autoloop","data":[{"DATA":"{\"entities\":[\"com.autoloop.loan.handler.RepayHandler\",\"com.autoloop.loan.handler.ActionEnumTestHandler\",\"com.autoloop.loan.handler.DisburseHandler\",\"com.autoloop.loan.context.TransactionContext\",\"com.autoloop.loan.mapper.AcctFundTransferMapper\",\"com.autoloop.loan.mapper.AcctRepaymentMapper\",\"com.autoloop.loan.mapper.AcctLoanDuebillMapper\",\"com.autoloop.loan.mapper.AcctTransactionMapper\",\"com.autoloop.loan.mapper.AcctRepaymentScheduleMapper\",\"com.autoloop.loan.mapper.AcctLoanMapper\",\"com.autoloop.loan.procedure.UpdateRepaymentScheduleProcedure\",\"com.autoloop.loan.procedure.ProcessRepaymentProcedure\",\"com.autoloop.loan.procedure.CreateDuebillProcedure\",\"com.autoloop.loan.procedure.UpdateStatusProcedure\",\"com.autoloop.loan.procedure.AccountingProcedure\",\"com.autoloop.loan.procedure.UpdateLoanBalanceProcedure\",\"com.autoloop.loan.procedure.ValidateRepaymentProcedure\",\"com.autoloop.loan.service.PolyMorphicRelationService\",\"com.autoloop.loan.service.DisburseService\",\"com.autoloop.loan.service.TransactionService\",\"com.autoloop.loan.service.BusinessEntityService\",\"com.autoloop.loan.service.RepayService\",\"com.autoloop.core.mapper.SystemParamsMapper\",\"com.autoloop.core.service.ProjectAnalysisService\",\"com.autoloop.core.service.ReportGeneratorService\"],\"tables\":[\"t_repay_yandler\",\"t_action_nnum_mest_tandler\",\"t_disburse_eandler\",\"t_transaction_nontext\",\"t_acct_tund_dransfer_rapper\",\"t_acct_tepayment_tapper\",\"t_acct_toan_nuebill_lapper\",\"t_acct_transaction_napper\",\"t_acct_tepayment_tchedule_eapper\",\"t_acct_toan_napper\",\"t_update_eepayment_tchedule_erocedure\",\"t_process_sepayment_trocedure\",\"t_create_euebill_lrocedure\",\"t_update_etatus_srocedure\",\"t_accounting_grocedure\",\"t_update_eoan_nalance_erocedure\",\"t_validate_eepayment_trocedure\",\"t_poly_yorphic_celation_nervice\",\"t_disburse_eervice\",\"t_transaction_nervice\",\
... (内容过长，已截断)
```

✅ **状态**: 成功

---

## 测试 12: 查询 API 入口

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module": "api_entry_scanning", "projectKey": "autoloop", "page": 0, "size": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"module":"api_entry_scanning","projectKey":"autoloop","data":[{"DATA":"{\"entries\":[\"com.autoloop.loan.handler.RepayHandler\",\"com.autoloop.loan.handler.BusinessRelationTestHandler\",\"com.autoloop.loan.handler.ActionEnumTestHandler\",\"com.autoloop.loan.handler.DisburseHandler\",\"com.autoloop.web.exception.GlobalExceptionHandler\",\"com.autoloop.web.interceptor.ControllerEnabledInterceptor\"],\"entryCount\":6,\"entriesByType\":{\"REST_CONTROLLER\":5,\"CONTROLLER\":1},\"totalMethods\":20,\"controllers\":[\"com.autoloop.loan.handler.RepayHandler\",\"com.autoloop.loan.handler.BusinessRelationTestHandler\",\"com.autoloop.loan.handler.ActionEnumTestHandler\",\"com.autoloop.loan.handler.DisburseHandler\",\"com.autoloop.web.exception.GlobalExceptionHandler\",\"com.autoloop.web.interceptor.ControllerEnabledInterceptor\"],\"controllerCount\":6,\"feignClients\":[],\"feignClientCount\":0,\"listeners\":[],\"listenerCount\":0,\"scheduledTasks\":[],\"scheduledTaskCount\":0}"}],"total":1,"page":0,"size":5}
```

✅ **状态**: 成功

---

## 测试 13: 查询外调接口

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module": "external_api_scanning", "projectKey": "autoloop", "page": 0, "size": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"module":"external_api_scanning","projectKey":"autoloop","data":[{"DATA":"{\"externalApis\":[{\"qualifiedName\":\"com.autoloop.loan.integration.impl.TransferServiceImpl\",\"simpleName\":\"TransferServiceImpl\",\"apiType\":\"REST_CLIENT\",\"targetUrl\":\"\",\"serviceName\":\"TransferServiceImpl\",\"methodCount\":2,\"methods\":[{\"name\":\"httpCall\",\"httpMethod\":\"POST\",\"path\":\"QUERY_PATH\",\"returnType\":\"String\"},{\"name\":\"httpCall\",\"httpMethod\":\"POST\",\"path\":\"path\",\"returnType\":\"String\"}]},{\"qualifiedName\":\"com.autoloop.integration.llm.DeepSeekClient\",\"simpleName\":\"DeepSeekClient\",\"apiType\":\"REST_CLIENT\",\"targetUrl\":\"\",\"serviceName\":\"DeepSeekClient\",\"methodCount\":1,\"methods\":[{\"name\":\"httpCall\",\"httpMethod\":\"POST\",\"path\":\"deepSeekConfig.getApiUrl(\",\"returnType\":\"String\"}]}],\"count\":2}"}],"total":1,"page":0,"size":5}
```

✅ **状态**: 成功

---

## 测试 14: 查询枚举

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/analysis_results' \
  -H 'Content-Type: application/json' \
  -d '{"module": "enum_scanning", "projectKey": "autoloop", "page": 0, "size": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{"module":"enum_scanning","projectKey":"autoloop","data":[{"DATA":"{\"enums\":[\"com.autoloop.loan.model.enums.BusinessTypeEnum\",\"com.autoloop.loan.model.enums.SingleScenarioEnum\",\"com.autoloop.loan.model.enums.RepaymentMethodEnum\",\"com.autoloop.loan.model.enums.TransferTypeEnum\",\"com.autoloop.loan.model.enums.ActionEnum\",\"com.autoloop.core.model.enums.FileType\",\"com.autoloop.core.model.enums.CategoryEnum\",\"com.autoloop.common.enums.AnalysisStatus\"],\"constants\":[\"BusinessTypeEnum.DISBURSE\",\"BusinessTypeEnum.REPAY\",\"BusinessTypeEnum.String\",\"RepaymentMethodEnum.EQUAL_PRINCIPAL\",\"RepaymentMethodEnum.EQUAL_INSTALLMENT\",\"RepaymentMethodEnum.INTEREST_ONLY\",\"RepaymentMethodEnum.BULLET\",\"RepaymentMethodEnum.EQUAL_PRINCIPAL_BI\",\"RepaymentMethodEnum.EQUAL_INSTALLMENT_BI\",\"RepaymentMethodEnum.IllegalArgumentException\",\"RepaymentMethodEnum.IllegalArgumentException\",\"RepaymentMethodEnum.EQUAL_INSTALLMENT_BI\",\"RepaymentMethodEnum.EQUAL_PRINCIPAL_BI\",\"RepaymentMethodEnum.EQUAL_INSTALLMENT_BI\",\"RepaymentMethodEnum.BULLET\",\"TransferTypeEnum.CREDIT\",\"TransferTypeEnum.DEBIT\",\"TransferTypeEnum.String\",\"ActionEnum.ZZ_PACK\",\"ActionEnum.BL_PACK\",\"ActionEnum.DISBURSE\",\"ActionEnum.PREPAYMENT\",\"ActionEnum.EXTENSION\",\"ActionEnum.WRITE_OFF\",\"ActionEnum.DEBT_TRANSFER\",\"ActionEnum.ABS_PACK\",\"ActionEnum.REPAY\",\"ActionEnum.ALL\",\"ActionEnum.ALL\",\"FileType.SOURCE\",\"FileType.TEST\",\"FileType.RESOURCE\",\"CategoryEnum.API\",\"CategoryEnum.DTO\",\"CategoryEnum.HANDLER\",\"AnalysisStatus.SUCCESS\",\"AnalysisStatus.FAILED\"],\"count\":8}"}],"total":1,"page":0,"size":5}
```

✅ **状态**: 成功

---
开始测试 H2 数据库查询 API...

## 测试 15: 查询向量总数

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/execute_sql' \
  -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT COUNT(*) as total FROM vector_fragments"}'
```

**响应状态**: 200

**响应内容**:
```json
{"data":[{"TOTAL":608}]}
```

✅ **状态**: 成功

---

## 测试 16: 查询向量分布

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/execute_sql' \
  -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT SUBSTR(id, 1, CASE WHEN LOCATE(':', id, 3) > 0 THEN LOCATE(':', id, 3) - 1 ELSE LENGTH(id) END) as type, COUNT(*) as cnt FROM vector_fragments WHERE project_key = '"autoloop"' AND id LIKE '%:%' GROUP BY type ORDER BY cnt DESC"}'
```

**响应状态**: 400

**响应内容**:
```json
{"timestamp":"2026-02-04T01:37:04.281+00:00","status":400,"error":"Bad Request","path":"/api/verify/execute_sql"}
```

❌ **状态**: 失败 (HTTP 400)

---

## 测试 17: 查询 DisburseHandler

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/execute_sql' \
  -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT id, title, content FROM vector_fragments WHERE id LIKE '%Disburse%'"}'
```

**响应状态**: 200

**响应内容**:
```json
{"data":[{"ID":"autoloop:db_entity:com.autoloop.loan.handler.DisburseHandler","TITLE":"DisburseHandler","CONTENT":"DisburseHandler实体"},{"ID":"autoloop:db_entity:com.autoloop.loan.service.DisburseService","TITLE":"DisburseService","CONTENT":"DisburseService实体"},{"ID":"autoloop:api_entry:com.autoloop.loan.handler.DisburseHandler","TITLE":"DisburseHandler","CONTENT":"DisburseHandler"},{"ID":"class:DisburseHandler","TITLE":"DisburseHandler","CONTENT":"处理贷款放款HTTP请求，作为放款业务的REST入口。"},{"ID":"method:DisburseHandler.disburse","TITLE":"disburse","CONTENT":"接收放款请求，调用服务层处理资金划转并返回结果。"},{"ID":"method:DisburseHandler.queryLoan","TITLE":"queryLoan","CONTENT":"根据贷款ID查询并返回贷款账户详细信息。"},{"ID":"method:DisburseHandler.canDisburse","TITLE":"canDisburse","CONTENT":"检查指定贷款账户的状态是否允许进行放款操作。"},{"ID":"method:TransactionContext.forDisburse","TITLE":"forDisburse","CONTENT":"创建并初始化一个用于放款交易的上下文对象。"},{"ID":"class:DisburseReqDTO","TITLE":"DisburseReqDTO","CONTENT":"接收客户发起的放款请求，包含贷款账户ID、放款金额及目标账户信息等核心数据。"},{"ID":"class:DisburseRspDTO","TITLE":"DisburseRspDTO","CONTENT":"封装放款操作的处理结果，包含状态、金额及交易流水号等关键信息。"},{"ID":"method:DisburseRspDTO.builder","TITLE":"builder","CONTENT":"创建该类的构建器实例，用于流式构造对象。"},{"ID":"method:DisburseRspDTO.getLoanId","TITLE":"getLoanId","CONTENT":"获取贷款账户ID。"},{"ID":"method:DisburseRspDTO.toString","TITLE":"toString","CONTENT":"输出对象的字符串表示，包含所有字段值。"},{"ID":"method:AcctLoan.canDisburse","TITLE":"canDisburse","CONTENT":"只有待放款状态的贷款才能放款。"},{"ID":"method:AcctLoan.isDisbursed","TITLE":"isDisbursed","CONTENT":"判断贷款是否已放�
... (内容过长，已截断)
```

✅ **状态**: 成功

---

## 测试 18: 查询 RepayHandler

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/execute_sql' \
  -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT id, title, content FROM vector_fragments WHERE id LIKE '%Repay%'"}'
```

**响应状态**: 200

**响应内容**:
```json
{"data":[{"ID":"autoloop:db_entity:com.autoloop.loan.handler.RepayHandler","TITLE":"RepayHandler","CONTENT":"RepayHandler实体"},{"ID":"autoloop:db_entity:com.autoloop.loan.mapper.AcctRepaymentMapper","TITLE":"AcctRepaymentMapper","CONTENT":"AcctRepaymentMapper实体"},{"ID":"autoloop:db_entity:com.autoloop.loan.mapper.AcctRepaymentScheduleMapper","TITLE":"AcctRepaymentScheduleMapper","CONTENT":"AcctRepaymentScheduleMapper实体"},{"ID":"autoloop:db_entity:com.autoloop.loan.procedure.UpdateRepaymentScheduleProcedure","TITLE":"UpdateRepaymentScheduleProcedure","CONTENT":"UpdateRepaymentScheduleProcedure实体"},{"ID":"autoloop:db_entity:com.autoloop.loan.procedure.ProcessRepaymentProcedure","TITLE":"ProcessRepaymentProcedure","CONTENT":"ProcessRepaymentProcedure实体"},{"ID":"autoloop:db_entity:com.autoloop.loan.procedure.ValidateRepaymentProcedure","TITLE":"ValidateRepaymentProcedure","CONTENT":"ValidateRepaymentProcedure实体"},{"ID":"autoloop:db_entity:com.autoloop.loan.service.RepayService","TITLE":"RepayService","CONTENT":"RepayService实体"},{"ID":"autoloop:api_entry:com.autoloop.loan.handler.RepayHandler","TITLE":"RepayHandler","CONTENT":"RepayHandler"},{"ID":"autoloop:enum:RepaymentMethodEnum","TITLE":"RepaymentMethodEnum","CONTENT":"业务常量定义"},{"ID":"class:RepayHandler","TITLE":"RepayHandler","CONTENT":"处理贷款还款HTTP请求，是还款业务的REST入口。"},{"ID":"method:RepayHandler.repay","TITLE":"repay","CONTENT":"处理还款请求，包含正常、提前和逾期还款场景。"},{"ID":"method:RepayHandler.queryRepayment","TITLE":"queryRepayment","CONTENT":"根据ID查询单条还款记录详情。"},{"ID":"method:RepayHandler.queryRepaymentsByLoanId","TITLE":"queryRepaymentsByLoanId","CONTENT":"查询指定贷款的所有还款历史记录。"},{"ID":"method:RepayHandler.canRepay","TITLE":"canRepay","CONTENT":"检查贷款账户状态是否允许进行还款操作。"},{"ID":"method:RepayHandler.sumRepaidAmount","TITLE":"sumRepa
... (内容过长，已截断)
```

✅ **状态**: 成功

---

## 测试 19: 查询 LoanStatus 枚举

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/execute_sql' \
  -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT id, title, content FROM vector_fragments WHERE id LIKE '%LoanStatus%'"}'
```

**响应状态**: 200

**响应内容**:
```json
{"data":[]}
```

✅ **状态**: 成功

---

---

## 测试总结

- 总测试数: 19
- 通过: 18 ✅
- 失败: 1 ❌
- 成功率: % - 成功率: %


---

## 补充测试 (2026-02-04)

### 测试 20: 专家咨询 - 还款入口（修复后）

**请求**:
```bash
curl -X POST 'http://localhost:8080/api/verify/expert_consult' \
  -H 'Content-Type: application/json' \
  -d '{"question": "还款入口是哪个", "projectKey": "autoloop", "topK": 5}'
```

**响应状态**: 200

**响应内容**:
```json
{
  "answer": "还款入口是 `RepayService.repay`【片段 2】。",
  "sources": [
    {"filePath": "repay", "className": "", "methodName": "", "score": 0.95},
    {"filePath": "RepayHandler", "className": "", "methodName": "", "score": 0.94}
  ],
  "confidence": 0.8,
  "processingTimeMs": 5336
}
```

✅ **状态**: 成功 - **找到正确的还款入口！**

### 向量化恢复统计

| 指标 | 数值 |
|------|------|
| 处理文件数 | 112 |
| 新增向量数 | 566 |
| 向量化耗时 | 45.6 秒 |
| H2 总向量数 | 1054 |

### RepayHandler 向量验证

**查询**:
```sql
SELECT id, title FROM vector_fragments 
WHERE id LIKE '%RepayHandler%' OR id LIKE '%Repay%'
LIMIT 10
```

**结果**:
```json
[
  {"ID": "autoloop:db_entity:com.autoloop.loan.handler.RepayHandler"},
  {"ID": "autoloop:api_entry:com.autoloop.loan.handler.RepayHandler"},
  {"ID": "class:RepayHandler", "TITLE": "RepayHandler"},  // ✅ 新增
  {"ID": "method:RepayService.md.repay", "TITLE": "repay"},  // ✅ 新增
  {"ID": "autoloop:enum:repaymentMethodEnum"}
]
```

---
