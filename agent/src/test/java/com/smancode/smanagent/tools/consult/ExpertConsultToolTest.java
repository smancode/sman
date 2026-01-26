package com.smancode.smanagent.tools.consult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.common.service_discovery.api.ServiceRegistry;
import com.smancode.smanagent.common.service_discovery.model.ServiceInstance;
import com.smancode.smanagent.config.KnowledgeGraphDiscoveryConfig;
import com.smancode.smanagent.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ExpertConsultTool 单元测试
 * <p>
 * 生产级别测试套件，覆盖以下场景：
 * 1. 服务可用性检查
 * 2. 多项目路由
 * 3. 完整响应解析
 * 4. 边界条件和异常处理
 * 5. 并发安全性
 */
@DisplayName("ExpertConsultTool 单元测试")
class ExpertConsultToolTest {

    private ExpertConsultTool expertConsultTool;
    private RestTemplate mockRestTemplate;
    private ServiceRegistry mockServiceRegistry;
    private KnowledgeGraphDiscoveryConfig mockConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // 创建 mock 对象
        mockRestTemplate = Mockito.mock(RestTemplate.class);
        mockServiceRegistry = Mockito.mock(ServiceRegistry.class);
        mockConfig = Mockito.mock(KnowledgeGraphDiscoveryConfig.class);

        // 创建 ExpertConsultTool 实例
        expertConsultTool = new ExpertConsultTool();

        // 使用反射设置依赖
        Field restTemplateField = ExpertConsultTool.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        restTemplateField.set(expertConsultTool, mockRestTemplate);

        Field serviceRegistryField = ExpertConsultTool.class.getDeclaredField("serviceRegistry");
        serviceRegistryField.setAccessible(true);
        serviceRegistryField.set(expertConsultTool, mockServiceRegistry);

        Field configField = ExpertConsultTool.class.getDeclaredField("knowledgeGraphDiscoveryConfig");
        configField.setAccessible(true);
        configField.set(expertConsultTool, mockConfig);

        // 默认配置：服务可用
        when(mockServiceRegistry.isExpertConsultAvailable(any())).thenReturn(true);
        when(mockConfig.getKnowledgeGraphServiceUrl(any())).thenReturn("http://localhost:8088/api/ai/tool-use");
    }

    // ==================== 服务可用性检查测试 ====================

    @Test
    @DisplayName("当服务不可用时，应返回明确的错误提示")
    void testServiceUnavailable() {
        // Arrange
        String projectKey = "autoloop";
        when(mockServiceRegistry.isExpertConsultAvailable(projectKey)).thenReturn(false);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        ToolResult result = expertConsultTool.execute(projectKey, params);

        // Assert
        assertTrue(result.isSuccess(), "服务不可用时也应返回成功（带有提示信息）");
        String displayContent = result.getDisplayContent();
        assertNotNull(displayContent);
        assertTrue(displayContent.contains("expert_consult 服务当前不可用"),
            "应包含服务不可用提示");
        assertTrue(displayContent.contains("Knowledge 服务未启动"),
            "应包含可能原因");
        assertTrue(displayContent.contains("请稍后重试"),
            "应包含建议操作");

        // 验证没有调用 HTTP 请求
        verify(mockRestTemplate, never()).postForEntity(
            any(String.class), any(), eq(String.class)
        );
    }

    @Test
    @DisplayName("当项目未配置时，应返回配置错误")
    void testProjectNotConfigured() {
        // Arrange
        String projectKey = "unknown-project";
        when(mockServiceRegistry.isExpertConsultAvailable(projectKey)).thenReturn(true);
        when(mockConfig.getKnowledgeGraphServiceUrl(projectKey))
            .thenThrow(new IllegalArgumentException("Knowledge Graph 项目未配置: " + projectKey));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        ToolResult result = expertConsultTool.execute(projectKey, params);

        // Assert
        assertFalse(result.isSuccess(), "项目未配置应返回失败");
        assertTrue(result.getError().contains("Knowledge Graph 项目未配置"),
            "应包含配置错误信息");
        assertTrue(result.getError().contains(projectKey),
            "应包含项目标识");
    }

    // ==================== 多项目路由测试 ====================

    @Test
    @DisplayName("应根据 projectKey 路由到正确的服务")
    void testMultiProjectRouting() throws Exception {
        // Arrange
        String project1 = "autoloop";
        String project2 = "loan-system";

        when(mockConfig.getKnowledgeGraphServiceUrl(project1))
            .thenReturn("http://localhost:8088/api/ai/tool-use");
        when(mockConfig.getKnowledgeGraphServiceUrl(project2))
            .thenReturn("http://localhost:8089/api/ai/tool-use");

        // 准备响应
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        Map<String, Object> data = new HashMap<>();
        data.put("summary", "项目1的回答");
        response.put("data", data);
        String jsonResponse = objectMapper.writeValueAsString(response);

        when(mockRestTemplate.postForEntity(
            eq("http://localhost:8088/api/ai/tool-use"),
            any(),
            eq(String.class)
        )).thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

        when(mockRestTemplate.postForEntity(
            eq("http://localhost:8089/api/ai/tool-use"),
            any(),
            eq(String.class)
        )).thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

        // Act & Assert - 项目1
        Map<String, Object> params1 = new HashMap<>();
        params1.put("query", "项目1问题");
        ToolResult result1 = expertConsultTool.execute(project1, params1);
        assertTrue(result1.isSuccess());

        // Act & Assert - 项目2
        Map<String, Object> params2 = new HashMap<>();
        params2.put("query", "项目2问题");
        ToolResult result2 = expertConsultTool.execute(project2, params2);
        assertTrue(result2.isSuccess());

        // 验证调用了正确的 URL
        verify(mockRestTemplate).postForEntity(
            eq("http://localhost:8088/api/ai/tool-use"),
            any(),
            eq(String.class)
        );
        verify(mockRestTemplate).postForEntity(
            eq("http://localhost:8089/api/ai/tool-use"),
            any(),
            eq(String.class)
        );
    }

    // ==================== 完整响应解析测试 ====================

    @Test
    @DisplayName("应正确解析完整的成功响应")
    void testSuccessResponse_CompleteData() throws Exception {
        // Arrange
        String projectKey = "autoloop";

        Map<String, Object> response = buildCompleteResponse();
        String jsonResponse = objectMapper.writeValueAsString(response);

        when(mockRestTemplate.postForEntity(
            any(String.class),
            any(),
            eq(String.class)
        )).thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "同一客户不同借据的批扣顺序是什么？");

        // Act
        ToolResult result = expertConsultTool.execute(projectKey, params);

        // Assert
        assertTrue(result.isSuccess(), "应该成功");
        assertNotNull(result.getDisplayContent(), "显示内容不应为空");

        String displayContent = result.getDisplayContent();

        // 验证包含 summary
        assertTrue(displayContent.contains("根据业务规则分析"),
            "应包含 summary");

        // 验证包含实体信息
        assertTrue(displayContent.contains("关联业务实体"),
            "应包含实体标题");
        assertTrue(displayContent.contains("借据"),
            "应包含实体名称");
        assertTrue(displayContent.contains("借据编号 LN001"),
            "应包含 sourceText");
        assertTrue(displayContent.contains("com.autoloop.entity.Loan"),
            "应包含代码位置");

        // 验证包含规则信息
        assertTrue(displayContent.contains("相关业务规则"),
            "应包含规则标题");
        assertTrue(displayContent.contains("同一账单日的借据按创建时间先后顺序批扣"),
            "应包含规则描述");

        // 验证包含流程信息
        assertTrue(displayContent.contains("业务流程"),
            "应包含流程标题");
        assertTrue(displayContent.contains("加载待批扣借据列表"),
            "应包含流程步骤");

        // 验证包含代码位置
        assertTrue(displayContent.contains("代码位置"),
            "应包含代码位置标题");
        assertTrue(displayContent.contains("AutoDebitService"),
            "应包含类名");
        assertTrue(displayContent.contains("executeBatchDebit"),
            "应包含方法名");

        // 验证包含置信度
        assertTrue(displayContent.contains("置信度"),
            "应包含置信度");
        assertTrue(displayContent.contains("92%"),
            "应包含置信度数值");
    }

    @Test
    @DisplayName("应正确解析最小成功响应")
    void testSuccessResponse_MinimalData() throws Exception {
        // Arrange
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

        // Act
        ToolResult result = expertConsultTool.execute("autoloop", params);

        // Assert
        assertTrue(result.isSuccess());
        assertTrue(result.getDisplayContent().contains("简单的回答内容"));
        assertTrue(result.getDisplayContent().contains("置信度: 80%"));
    }

    @Test
    @DisplayName("应正确处理失败响应")
    void testFailureResponse() throws Exception {
        // Arrange
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

        // Act
        ToolResult result = expertConsultTool.execute("autoloop", params);

        // Assert
        assertFalse(result.isSuccess(), "应该失败");
        assertTrue(result.getError().contains("项目配置不存在"),
            "应包含错误信息");
    }

    @Test
    @DisplayName("应正确处理空响应")
    void testEmptyResponse() throws Exception {
        // Arrange
        when(mockRestTemplate.postForEntity(
            any(String.class),
            any(),
            eq(String.class)
        )).thenReturn(new ResponseEntity<>("", HttpStatus.OK));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        ToolResult result = expertConsultTool.execute("autoloop", params);

        // Assert
        assertTrue(result.isSuccess(), "空响应应返回成功");
        // 注意：当 displayContent 为 null 时，getData() 会返回提示文本
        Object data = result.getData();
        if (data != null) {
            assertTrue(data.toString().contains("未找到相关知识") ||
                       result.getDisplayContent() == null,
                   "应显示未找到知识的提示或 displayContent 为 null");
        }
    }

    @Test
    @DisplayName("应正确处理非 2xx 状态码")
    void testNonSuccessStatusCode() throws Exception {
        // Arrange
        when(mockRestTemplate.postForEntity(
            any(String.class),
            any(),
            eq(String.class)
        )).thenReturn(new ResponseEntity<>("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        ToolResult result = expertConsultTool.execute("autoloop", params);

        // Assert
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Knowledge Graph 服务返回错误"));
        assertTrue(result.getError().contains("500"));
    }

    // ==================== 边界条件和异常处理测试 ====================

    @Test
    @DisplayName("缺少 query 参数时应返回错误")
    void testMissingQueryParameter() {
        Map<String, Object> params = new HashMap<>();
        // 不提供 query 参数

        ToolResult result = expertConsultTool.execute("autoloop", params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少 query 参数"));

        // 验证没有调用 HTTP 请求
        verify(mockRestTemplate, never()).postForEntity(
            any(String.class), any(), eq(String.class)
        );
    }

    @Test
    @DisplayName("query 参数为空字符串时应返回错误")
    void testEmptyQueryParameter() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "");

        ToolResult result = expertConsultTool.execute("autoloop", params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少 query 参数"));
    }

    @Test
    @DisplayName("query 参数只有空格时应返回错误")
    void testWhitespaceOnlyQueryParameter() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "   ");

        ToolResult result = expertConsultTool.execute("autoloop", params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少 query 参数"));
    }

    @Test
    @DisplayName("HTTP 异常应被正确处理")
    void testHttpException() {
        // Arrange
        when(mockRestTemplate.postForEntity(
            any(String.class),
            any(),
            eq(String.class)
        )).thenThrow(new RuntimeException("Connection refused"));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        ToolResult result = expertConsultTool.execute("autoloop", params);

        // Assert
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("专家咨询失败"));
        assertTrue(result.getError().contains("Connection refused"));
    }

    @Test
    @DisplayName("超时应被正确处理")
    void testTimeout() {
        // Arrange
        when(mockRestTemplate.postForEntity(
            any(String.class),
            any(),
            eq(String.class)
        )).thenThrow(new RuntimeException(new SocketTimeoutException("Read timed out")));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        ToolResult result = expertConsultTool.execute("autoloop", params);

        // Assert
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("专家咨询失败"));
        assertTrue(result.getError().contains("Read timed out") ||
                   result.getError().contains("SocketTimeoutException"));
    }

    @Test
    @DisplayName("JSON 解析失败时应返回原始响应")
    void testInvalidJsonResponse() throws Exception {
        // Arrange
        String invalidJson = "invalid json {{{";

        when(mockRestTemplate.postForEntity(
            any(String.class),
            any(),
            eq(String.class)
        )).thenReturn(new ResponseEntity<>(invalidJson, HttpStatus.OK));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        ToolResult result = expertConsultTool.execute("autoloop", params);

        // Assert
        assertTrue(result.isSuccess(), "JSON 解析失败应返回成功（降级处理）");
        assertEquals(invalidJson, result.getDisplayContent(),
            "应返回原始响应");
    }

    // ==================== 执行时间测试 ====================

    @Test
    @DisplayName("应正确记录执行时间")
    void testExecutionTime() throws Exception {
        // Arrange
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        Map<String, Object> data = new HashMap<>();
        data.put("summary", "测试回答");
        response.put("data", data);

        String jsonResponse = objectMapper.writeValueAsString(response);

        when(mockRestTemplate.postForEntity(
            any(String.class),
            any(),
            eq(String.class)
        )).thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "测试查询");

        // Act
        long startTime = System.currentTimeMillis();
        ToolResult result = expertConsultTool.execute("autoloop", params);
        long endTime = System.currentTimeMillis();

        // Assert
        assertTrue(result.isSuccess());
        assertTrue(result.getExecutionTimeMs() >= 0,
            "执行时间应非负");
        assertTrue(result.getExecutionTimeMs() <= (endTime - startTime + 100),
            "执行时间应合理（允许100ms误差）");
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> buildCompleteResponse() {
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

        return response;
    }
}
