package com.smancode.smanagent.controller;

import com.smancode.smanagent.tools.knowledge.KnowledgeGraphClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 业务图谱更新接口
 * <p>
 * 供 knowledge-graph-system 调用，更新本地业务图谱数据。
 * <p>
 * 数据流向：
 * knowledge-graph-system → POST /api/knowledge/* → KnowledgeGraphClient 本地存储
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeGraphController {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphController.class);

    @Autowired
    private KnowledgeGraphClient knowledgeGraphClient;

    /**
     * 更新业务背景
     * <p>
     * 由 knowledge-graph-system 调用。
     *
     * @param body 请求体
     *             {
     *             "projectKey": "project-abc",
     *             "projectName": "某金融系统",
     *             "description": "这是一个金融系统...",
     *             "domains": ["提额", "支付", "风控"]
     *             }
     */
    @PostMapping("/update/context")
    public ResponseEntity<Map<String, Object>> updateBusinessContext(@RequestBody Map<String, Object> body) {
        try {
            String projectKey = (String) body.get("projectKey");
            if (projectKey == null || projectKey.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "缺少 projectKey"
                ));
            }

            KnowledgeGraphClient.BusinessContext context = new KnowledgeGraphClient.BusinessContext();
            context.setProjectName((String) body.get("projectName"));
            context.setDescription((String) body.get("description"));
            context.setDomains((java.util.List<String>) body.get("domains"));
            context.setMetadata((Map<String, Object>) body.get("metadata"));

            knowledgeGraphClient.updateBusinessContext(projectKey, context);

            logger.info("业务背景更新成功: projectKey={}", projectKey);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "业务背景更新成功"
            ));

        } catch (Exception e) {
            logger.error("更新业务背景失败", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 更新业务知识
     * <p>
     * 由 knowledge-graph-system 调用。
     *
     * @param body 请求体
     *             {
     *             "projectKey": "project-abc",
     *             "businessEntity": "520提额",
     *             "description": "520提额业务描述",
     *             "rules": ["规则1", "规则2"],
     *             "sops": ["SOP1", "SOP2"],
     *             "processes": ["流程1", "流程2"]
     *             }
     */
    @PostMapping("/update/knowledge")
    public ResponseEntity<Map<String, Object>> updateBusinessKnowledge(@RequestBody Map<String, Object> body) {
        try {
            String projectKey = (String) body.get("projectKey");
            String businessEntity = (String) body.get("businessEntity");

            if (projectKey == null || projectKey.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "缺少 projectKey"
                ));
            }

            if (businessEntity == null || businessEntity.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "缺少 businessEntity"
                ));
            }

            KnowledgeGraphClient.BusinessKnowledge knowledge = new KnowledgeGraphClient.BusinessKnowledge();
            knowledge.setEntity(businessEntity);
            knowledge.setDescription((String) body.get("description"));
            knowledge.setRules((java.util.List<String>) body.get("rules"));
            knowledge.setSops((java.util.List<String>) body.get("sops"));
            knowledge.setProcesses((java.util.List<String>) body.get("processes"));

            knowledgeGraphClient.updateBusinessKnowledge(projectKey, businessEntity, knowledge);

            logger.info("业务知识更新成功: projectKey={}, entity={}", projectKey, businessEntity);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "业务知识更新成功"
            ));

        } catch (Exception e) {
            logger.error("更新业务知识失败", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 更新代码映射
     * <p>
     * 由 knowledge-graph-system 调用。
     *
     * @param body 请求体
     *             {
     *             "projectKey": "project-abc",
     *             "businessToCode": {
     *             "520提额": [
     *             {"className": "LimitIncreaseController", "method": "showAddManagerPrompt", "description": "显示添加客户经理提示"},
     *             {"className": "PromptConfigService", "description": "浮层配置服务"}
     *             ]
     *             },
     *             "codeToBusiness": {
     *             "LimitIncreaseController": "520提额控制器",
     *             "PromptConfigService": "浮层配置服务"
     *             }
     *             }
     */
    @PostMapping("/update/mapping")
    public ResponseEntity<Map<String, Object>> updateCodeMapping(@RequestBody Map<String, Object> body) {
        try {
            String projectKey = (String) body.get("projectKey");
            if (projectKey == null || projectKey.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "缺少 projectKey"
                ));
            }

            KnowledgeGraphClient.CodeMapping mapping = new KnowledgeGraphClient.CodeMapping();

            // 解析 businessToCode
            Map<String, java.util.List<Map<String, String>>> businessToCodeData =
                    (Map<String, java.util.List<Map<String, String>>>) body.get("businessToCode");
            if (businessToCodeData != null) {
                for (Map.Entry<String, java.util.List<Map<String, String>>> entry : businessToCodeData.entrySet()) {
                    java.util.List<KnowledgeGraphClient.CodeEntry> codeEntries = new java.util.ArrayList<>();
                    for (Map<String, String> codeData : entry.getValue()) {
                        KnowledgeGraphClient.CodeEntry codeEntry = new KnowledgeGraphClient.CodeEntry();
                        codeEntry.setClassName(codeData.get("className"));
                        codeEntry.setMethod(codeData.get("method"));
                        codeEntry.setDescription(codeData.get("description"));
                        codeEntries.add(codeEntry);
                    }
                    mapping.getBusinessToCode().put(entry.getKey(), codeEntries);
                }
            }

            // 解析 codeToBusiness
            Map<String, String> codeToBusinessData = (Map<String, String>) body.get("codeToBusiness");
            if (codeToBusinessData != null) {
                mapping.getCodeToBusiness().putAll(codeToBusinessData);
            }

            knowledgeGraphClient.updateCodeMapping(projectKey, mapping);

            logger.info("代码映射更新成功: projectKey={}", projectKey);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "代码映射更新成功"
            ));

        } catch (Exception e) {
            logger.error("更新代码映射失败", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 查询业务图谱状态（调试用）
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "业务图谱服务运行中",
                "note", "当前使用内存存储，重启后数据丢失。实际项目应使用数据库持久化。"
        ));
    }
}
