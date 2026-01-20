package com.smancode.smanagent.smancode.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.part.ToolPart;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用 app.log 中 LLM 返回的真实 JSON 测试 batch 工具
 * <p>
 * 数据来源：logs/app.log 中的 0120_230009_4VUUAOPZ_230009 请求
 * LLM 响应时间：2026-01-20 23:01:22
 */
class BatchToolRealLogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testRealLogJson() throws Exception {
        // 从 logs/app.log 提取的真实 LLM 响应（2026-01-20 23:01:22）
        // 这是 LLM 返回的完整 JSON，包含 6 个 apply_change 调用
        String realJson = """
                {
                  "type": "tool",
                  "toolName": "batch",
                  "parameters": {
                    "tool_calls": [
                      {
                        "tool": "apply_change",
                        "parameters": {
                          "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                          "mode": "replace",
                          "searchContent": "/**\\n * 目录扫描服务\\n * \\n */\\n@Service\\npublic class DirectoryScanService {",
                          "newContent": "/**\\n * 目录扫描服务\\n * \\n * 负责扫描项目目录，分析项目结构，并提供以下功能：\\n * - 生成目录树结构\\n * - 统计文件类型分布\\n * - 构建包结构信息\\n * - 收集Java文件列表\\n * \\n * @author SmanAgent\\n */\\n@Service\\npublic class DirectoryScanService {",
                          "description": "完善类级别注释"
                        }
                      },
                      {
                        "tool": "apply_change",
                        "parameters": {
                          "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                          "mode": "replace",
                          "searchContent": "    public ProjectStructureDTO scanDirectory(String projectPath, List<Path> javaFiles) {",
                          "newContent": "    /**\\n     * 扫描目录并返回项目结构信息\\n     * \\n     * 该方法会对指定目录进行全面的扫描分析，包括：\\n     * 1. 构建目录树结构（最多8层深度）\\n     * 2. 统计所有文件类型的分布情况\\n     * 3. 按包组织所有Java文件\\n     * 4. 收集所有Java文件的相对路径\\n     * \\n     * @param projectPath 项目根路径（绝对路径）\\n     * @param javaFiles Java文件列表（绝对路径）\\n     * @return 项目结构信息DTO，包含目录树、包结构、文件统计等信息\\n     */\\n    public ProjectStructureDTO scanDirectory(String projectPath, List<Path> javaFiles) {",
                          "description": "为 scanDirectory 方法添加注释"
                        }
                      },
                      {
                        "tool": "apply_change",
                        "parameters": {
                          "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                          "mode": "replace",
                          "searchContent": "    private Map<String, Integer> calculateFileTypeStats(Path rootPath) {",
                          "newContent": "    /**\\n     * 计算目录中各文件类型的数量统计\\n     * \\n     * 遍历指定目录及其所有子目录，统计每种文件扩展名出现的次数。\\n     * 例如：.java: 50, .xml: 10, .yml: 3\\n     * \\n     * @param rootPath 项目根路径\\n     * @return 文件类型统计Map，key为文件扩展名（包含点号），value为文件数量\\n     */\\n    private Map<String, Integer> calculateFileTypeStats(Path rootPath) {",
                          "description": "为 calculateFileTypeStats 方法添加注释"
                        }
                      },
                      {
                        "tool": "apply_change",
                        "parameters": {
                          "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                          "mode": "replace",
                          "searchContent": "    /**\\n     * 构建包结构\\n     * \\n     */\\n    private List<PackageInfoDTO> buildPackageStructure(List<Path> javaFiles, Path rootPath) {",
                          "newContent": "    /**\\n     * 构建包结构信息\\n     * \\n     * 根据Java文件列表，解析每个文件的package声明，将文件按包进行分组。\\n     * 每个包包含该包下的所有类名，并统计类的数量。\\n     * \\n     * @param javaFiles Java文件列表（绝对路径）\\n     * @param rootPath 项目根路径\\n     * @return 包结构列表，按包名排序，每个包包含包名、类列表和类数量\\n     */\\n    private List<PackageInfoDTO> buildPackageStructure(List<Path> javaFiles, Path rootPath) {",
                          "description": "为 buildPackageStructure 方法添加注释"
                        }
                      },
                      {
                        "tool": "apply_change",
                        "parameters": {
                          "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                          "mode": "replace",
                          "searchContent": "    private String extractPackageName(Path javaFile) throws IOException {",
                          "newContent": "    /**\\n     * 从Java文件中提取包名\\n     * \\n     * 读取Java文件内容，查找package声明行（例如：\\\"package com.example.service;\\\"），\\n     * 并提取包名部分。如果文件中没有package声明，则返回\\\"default\\\"。\\n     * \\n     * @param javaFile Java文件路径\\n     * @return 包名字符串\\n     * @throws IOException 读取文件失败时抛出\\n     */\\n    private String extractPackageName(Path javaFile) throws IOException {",
                          "description": "为 extractPackageName 方法添加注释"
                        }
                      },
                      {
                        "tool": "apply_change",
                        "parameters": {
                          "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                          "mode": "replace",
                          "searchContent": "    private String getFileExtension(String fileName) {",
                          "newContent": "    /**\\n     * 获取文件的扩展名\\n     * \\n     * 从文件名中提取最后一个点号后的部分作为扩展名。\\n     * 例如：\\\"Test.java\\\" -> \\\".java\\\"，\\\"pom.xml\\\" -> \\\".xml\\\"\\n     * \\n     * @param fileName 文件名\\n     * @return 文件扩展名（包含点号），如果没有扩展名则返回\\\"无扩展名\\\"\\n     */\\n    private String getFileExtension(String fileName) {",
                          "description": "为 getFileExtension 方法添加注释"
                        }
                      }
                    ]
                  },
                  "summary": "read_file(path: core/src/main/java/com/autoloop/core/service/DirectoryScanService.java): Found DirectoryScanService class with methods for scanning directory, calculating file type stats, building package structure, extracting package name and getting file extension. Current Javadoc is minimal and needs improvement."
                }
                """;

        System.out.println("=== 开始测试真实日志数据 ===");
        System.out.println("来源：logs/app.log 的 0120_230009_4VUUAOPZ_230009 请求");
        System.out.println("时间：2026-01-20 23:01:22");
        System.out.println();

        // 1. 解析 JSON
        JsonNode partJson = objectMapper.readTree(realJson);

        // 2. 使用反射调用实际的解析方法
        // 这个 JSON 是标准格式 {"type": "tool", "toolName": "batch", "parameters": {...}}
        // 应该使用 createToolPart 方法
        SmanAgentLoop loop = new SmanAgentLoop();
        Method method = SmanAgentLoop.class.getDeclaredMethod(
            "createToolPart", JsonNode.class, String.class, String.class
        );
        method.setAccessible(true);

        // 3. 执行解析
        ToolPart toolPart = (ToolPart) method.invoke(loop, partJson, "acdb5d83-c512-4d70-9663-708cb2deadd0", "0120_230009_4VUUAOPZ");

        // 4. 验证基本属性
        assertEquals("batch", toolPart.getToolName(), "工具名称应为 batch");
        System.out.println("✅ toolName: " + toolPart.getToolName());

        // 5. 获取解析后的参数
        Map<String, Object> params = toolPart.getParameters();
        System.out.println("✅ params 解析成功");
        System.out.println("   参数键数量: " + params.keySet().size());

        // 6. 关键验证：tool_calls 参数
        assertNotNull(params.get("tool_calls"), "❌ tool_calls 不应为 null");
        assertTrue(params.get("tool_calls") instanceof List,
                   "❌ tool_calls 应该是 List 类型，实际: " + params.get("tool_calls").getClass());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) params.get("tool_calls");

        // 7. 验证数量：应该是 6 个工具调用
        assertEquals(6, toolCalls.size(),
                     "❌ 应该有 6 个工具调用（与日志一致），实际: " + toolCalls.size());
        System.out.println("✅ tool_calls 数量: " + toolCalls.size());

        // 8. 验证每个工具调用
        for (int i = 0; i < toolCalls.size(); i++) {
            Map<String, Object> call = toolCalls.get(i);
            assertNotNull(call.get("tool"), "第 " + (i+1) + " 个工具的 tool 不应为 null");
            assertNotNull(call.get("parameters"), "第 " + (i+1) + " 个工具的 parameters 不应为 null");

            String toolName = (String) call.get("tool");
            assertEquals("apply_change", toolName,
                         "第 " + (i+1) + " 个工具应为 apply_change，实际: " + toolName);

            Object paramsObj = call.get("parameters");
            System.out.println("   - parameters 类型: " + (paramsObj != null ? paramsObj.getClass() : "null"));
            System.out.println("   - parameters 值: " + paramsObj);

            @SuppressWarnings("unchecked")
            Map<String, Object> callParams = (Map<String, Object>) paramsObj;

            // 验证每个 apply_change 的参数
            assertNotNull(callParams.get("relativePath"), "第 " + (i+1) + " 个工具的 relativePath 不应为 null");
            assertNotNull(callParams.get("mode"), "第 " + (i+1) + " 个工具的 mode 不应为 null");
            assertNotNull(callParams.get("searchContent"), "第 " + (i+1) + " 个工具的 searchContent 不应为 null");
            assertNotNull(callParams.get("newContent"), "第 " + (i+1) + " 个工具的 newContent 不应为 null");
            assertNotNull(callParams.get("description"), "第 " + (i+1) + " 个工具的 description 不应为 null");

            System.out.println("✅ 工具 " + (i+1) + "/" + toolCalls.size() + ": " + toolName);
            System.out.println("   - relativePath: " + callParams.get("relativePath"));
            System.out.println("   - mode: " + callParams.get("mode"));
            System.out.println("   - description: " + callParams.get("description"));
        }

        System.out.println();
        System.out.println("=== ✅ 所有测试通过 ===");
        System.out.println("batch 工具参数解析与真实日志完全一致！");
    }
}
