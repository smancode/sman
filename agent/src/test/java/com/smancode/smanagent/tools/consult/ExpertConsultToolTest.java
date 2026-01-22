package com.smancode.smanagent.tools.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.config.KnowledgeProperties;
import com.smancode.smanagent.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ExpertConsultTool 单元测试
 * <p>
 * 测试完整的 Knowledge 服务响应解析逻辑
 */
class ExpertConsultToolTest {

    private ExpertConsultTool expertConsultTool;
    private RestTemplate mockRestTemplate;
    private KnowledgeProperties knowledgeProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // 创建 mock RestTemplate
        mockRestTemplate = Mockito.mock(RestTemplate.class);

        // 创建 ExpertConsultTool 实例
        expertConsultTool = new ExpertConsultTool();

        // 使用反射设置 final 字段 restTemplate
        Field restTemplateField = ExpertConsultTool.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        restTemplateField.set(expertConsultTool, mockRestTemplate);

        // 创建并设置 KnowledgeProperties
        knowledgeProperties = new KnowledgeProperties();
        knowledgeProperties.setBaseUrl("http://localhost:8081");
        knowledgeProperties.setSearchPath("/api/knowledge/search");

        Field knowledgePropertiesField = ExpertConsultTool.class.getDeclaredField("knowledgeProperties");
        knowledgePropertiesField.setAccessible(true);
        knowledgePropertiesField.set(expertConsultTool, knowledgeProperties);
    }

    @Test
    void testSuccessResponse_CompleteData() throws Exception {
        // 构建完整的成功响应
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);

        Map<String, Object> data = new HashMap<>();
        data.put("summary", "根据业务规则分析，同一客户在同一账单日的不同借据按以下顺序批扣：先扣借据A，再扣借据B。");
        data.put("confidence", 0.92);

        // entities
        Map<String, Object> entity1 = new HashMap<>();
        entity1.put("name", "借据");
        entity1.put("sourceText", "借据编号 LN001");

        Map<String, Object> nodeDetail = new HashMap<>();
        nodeDetail.put("nodeId", "entity:loan");
        nodeDetail.put("name", "借据");
        nodeDetail.put("type", "BUSINESS_ENTITY");
        nodeDetail.put("codeNodes", java.util.List.of("com.autoloop.entity.Loan", "com.autoloop.dto.LoanDTO"));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tableName", "t_loan");
        attributes.put("primaryKey", "loan_id");
        nodeDetail.put("attributes", attributes);

        entity1.put("nodeDetail", nodeDetail);

        data.put("entities", java.util.List.of(entity1));

        // rules
        Map<String, Object> rule1 = new HashMap<>();
        rule1.put("name", "批扣顺序规则");
        rule1.put("description", "同一账单日的借据按创建时间先后顺序批扣");

        Map<String, Object> rule2 = new HashMap<>();
        rule2.put("name", "金额优先级规则");
        rule2.put("description", "小额借据优先批扣");

        data.put("rules", java.util.List.of(rule1, rule2));

        // businessFlow
        Map<String, Object> step1 = new HashMap<>();
        step1.put("name", "批扣初始化");
        step1.put("description", "加载待批扣借据列表");

        Map<String, Object> step2 = new HashMap<>();
        step2.put("name", "借据排序");
        step2.put("description", "按规则对借据进行排序");

        Map<String, Object> step3 = new HashMap<>();
        step3.put("name", "执行扣款");
        step3.put("description", "逐笔执行扣款操作");

        data.put("businessFlow", java.util.List.of(step1, step2, step3));

        // codeLocation
        Map<String, Object> codeLocation = new HashMap<>();
        codeLocation.put("className", "AutoDebitService");
        codeLocation.put("methodName", "executeBatchDebit");
        codeLocation.put("filePath", "com/autoloop/service/AutoDebitService.java");
        data.put("codeLocation", codeLocation);

        data.put("error", null);
        response.put("data", data);
        response.put("duration", "1234ms");
        response.put("traceId", "ABC12345");

        String jsonResponse = objectMapper.writeValueAsString(response);

        // Mock RestTemplate 响应
        when(mockRestTemplate.postForEntity(
                any(String.class),
                any(),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

        // 执行测试
        Map<String, Object> params = new HashMap<>();
        params.put("query", "同一客户不同借据的批扣顺序是什么？");

        ToolResult result = expertConsultTool.execute("test-project", params);

        // 验证结果
        assertTrue(result.isSuccess(), "应该成功");
        assertNotNull(result.getDisplayContent(), "显示内容不应为空");

        String displayContent = result.getDisplayContent();

        // 验证包含 summary
        assertTrue(displayContent.contains("根据业务规则分析"), "应包含 summary");

        // 验证包含实体信息
        assertTrue(displayContent.contains("关联业务实体"), "应包含实体标题");
        assertTrue(displayContent.contains("借据"), "应包含实体名称");
        assertTrue(displayContent.contains("借据编号 LN001"), "应包含 sourceText");
        assertTrue(displayContent.contains("com.autoloop.entity.Loan"), "应包含代码位置");

        // 验证包含规则信息
        assertTrue(displayContent.contains("相关业务规则"), "应包含规则标题");
        assertTrue(displayContent.contains("同一账单日的借据按创建时间先后顺序批扣"), "应包含规则描述");

        // 验证包含流程信息
        assertTrue(displayContent.contains("业务流程"), "应包含流程标题");
        assertTrue(displayContent.contains("加载待批扣借据列表"), "应包含流程步骤");

        // 验证包含代码位置
        assertTrue(displayContent.contains("代码位置"), "应包含代码位置标题");
        assertTrue(displayContent.contains("AutoDebitService"), "应包含类名");
        assertTrue(displayContent.contains("executeBatchDebit"), "应包含方法名");

        // 验证包含置信度
        assertTrue(displayContent.contains("置信度"), "应包含置信度");
        assertTrue(displayContent.contains("92%"), "应包含置信度数值");
    }

    @Test
    void testSuccessResponse_MinimalData() throws Exception {
        // 最小成功响应（只有 summary）
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);

        Map<String, Object> data = new HashMap<>();
        data.put("summary", "简单的回答内容");
        data.put("confidence", 0.8);
        response.put("data", data);

        String jsonResponse = objectMapper.writeValueAsString(response);

        when(mockRestTemplate.postForEntity(
                any(String.class),
                any(),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "简单问题");

        ToolResult result = expertConsultTool.execute("test-project", params);

        assertTrue(result.isSuccess());
        assertTrue(result.getDisplayContent().contains("简单的回答内容"));
        assertTrue(result.getDisplayContent().contains("置信度: 80%"));
    }

    @Test
    void testFailureResponse() throws Exception {
        // 失败响应
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "项目配置不存在: invalid-project-key");
        response.put("duration", "15ms");
        response.put("traceId", "ABC12345");

        String jsonResponse = objectMapper.writeValueAsString(response);

        when(mockRestTemplate.postForEntity(
                any(String.class),
                any(),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        ToolResult result = expertConsultTool.execute("test-project", params);

        assertFalse(result.isSuccess(), "应该失败");
        assertTrue(result.getError().contains("项目配置不存在"), "应包含错误信息");
    }

    @Test
    void testMissingQueryParameter() {
        Map<String, Object> params = new HashMap<>();
        // 不提供 query 参数

        ToolResult result = expertConsultTool.execute("test-project", params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少 query 参数"));
    }

    @Test
    void testEmptyQueryParameter() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "   ");  // 只有空格

        ToolResult result = expertConsultTool.execute("test-project", params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少 query 参数"));
    }
}
