package com.smancode.smanagent.tools.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务图谱客户端（本地存储）
 * <p>
 * 接收来自 knowledge-graph-system 的业务图谱数据，并提供查询接口。
 * <p>
 * 数据流向：
 * knowledge-graph-system → /api/knowledge/update → 本地存储 → SearchSubAgent 查询
 * <p>
 * 存储内容：
 * - 业务背景（BusinessContext）
 * - 业务知识（BusinessKnowledge：规则、SOP、流程）
 * - 业务↔代码映射（CodeMapping）
 */
@Component
public class KnowledgeGraphClient {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphClient.class);

    // TODO: 实际项目中应该使用数据库持久化
    // 这里使用内存存储作为占位符

    /**
     * 业务背景存储
     * Key: projectKey
     * Value: 业务上下文信息
     */
    private final Map<String, BusinessContext> businessContextMap = new ConcurrentHashMap<>();

    /**
     * 业务知识存储
     * Key: projectKey + businessEntity
     * Value: 业务知识（规则、SOP等）
     */
    private final Map<String, BusinessKnowledge> businessKnowledgeMap = new ConcurrentHashMap<>();

    /**
     * 业务↔代码映射
     * Key: projectKey
     * Value: 映射关系
     */
    private final Map<String, CodeMapping> codeMappingMap = new ConcurrentHashMap<>();

    // ==================== 写入接口（供 knowledge-graph-system 调用）====================

    /**
     * 更新业务背景
     * <p>
     * 由 knowledge-graph-system 调用，更新指定项目的业务背景信息。
     *
     * @param projectKey 项目标识
     * @param context    业务背景
     */
    public void updateBusinessContext(String projectKey, BusinessContext context) {
        logger.info("更新业务背景: projectKey={}, context={}", projectKey, context);
        businessContextMap.put(projectKey, context);
    }

    /**
     * 更新业务知识
     * <p>
     * 由 knowledge-graph-system 调用，更新指定业务实体的知识。
     *
     * @param projectKey     项目标识
     * @param businessEntity 业务实体（如"520提额"、"浮层提示"）
     * @param knowledge      业务知识
     */
    public void updateBusinessKnowledge(String projectKey, String businessEntity, BusinessKnowledge knowledge) {
        String key = projectKey + ":" + businessEntity;
        logger.info("更新业务知识: key={}, knowledge={}", key, knowledge);
        businessKnowledgeMap.put(key, knowledge);
    }

    /**
     * 更新代码映射
     * <p>
     * 由 knowledge-graph-system 调用，更新业务与代码的双向映射关系。
     *
     * @param projectKey 项目标识
     * @param mapping    代码映射
     */
    public void updateCodeMapping(String projectKey, CodeMapping mapping) {
        logger.info("更新代码映射: projectKey={}, mapping={}", projectKey, mapping);
        codeMappingMap.put(projectKey, mapping);
    }

    // ==================== 查询接口（供 SearchSubAgent 调用）====================

    /**
     * 搜索业务上下文
     *
     * @param projectKey 项目标识
     * @param query      查询内容
     * @return 业务背景信息
     */
    public BusinessContext searchBusinessContext(String projectKey, String query) {
        BusinessContext context = businessContextMap.get(projectKey);
        if (context == null) {
            logger.debug("未找到业务背景: projectKey={}", projectKey);
            return null;
        }

        // TODO: 根据查询内容匹配相关业务背景
        // 这里简化处理，直接返回全部
        return context;
    }

    /**
     * 搜索业务知识
     *
     * @param projectKey     项目标识
     * @param businessEntity 业务实体
     * @return 业务知识
     */
    public BusinessKnowledge searchBusinessKnowledge(String projectKey, String businessEntity) {
        String key = projectKey + ":" + businessEntity;
        return businessKnowledgeMap.get(key);
    }

    /**
     * 业务→代码映射
     *
     * @param projectKey     项目标识
     * @param businessEntity 业务实体
     * @return 相关代码列表
     */
    public java.util.List<CodeEntry> mapBusinessToCode(String projectKey, String businessEntity) {
        CodeMapping mapping = codeMappingMap.get(projectKey);
        if (mapping == null) {
            return java.util.Collections.emptyList();
        }

        return mapping.businessToCode.getOrDefault(businessEntity, java.util.Collections.emptyList());
    }

    /**
     * 代码→业务映射
     *
     * @param projectKey 项目标识
     * @param className  类名
     * @return 业务上下文
     */
    public String mapCodeToBusiness(String projectKey, String className) {
        CodeMapping mapping = codeMappingMap.get(projectKey);
        if (mapping == null) {
            return null;
        }

        return mapping.codeToBusiness.get(className);
    }

    // ==================== 数据模型 ====================

    /**
     * 业务背景
     */
    public static class BusinessContext {
        private String projectName;
        private String description;
        private java.util.List<String> domains;
        private java.util.Map<String, Object> metadata;

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public java.util.List<String> getDomains() {
            return domains;
        }

        public void setDomains(java.util.List<String> domains) {
            this.domains = domains;
        }

        public java.util.Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(java.util.Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        @Override
        public String toString() {
            return "BusinessContext{" +
                    "projectName='" + projectName + '\'' +
                    ", description='" + description + '\'' +
                    ", domains=" + domains +
                    '}';
        }
    }

    /**
     * 业务知识
     */
    public static class BusinessKnowledge {
        private String entity;
        private String description;
        private java.util.List<String> rules;
        private java.util.List<String> sops;
        private java.util.List<String> processes;

        public String getEntity() {
            return entity;
        }

        public void setEntity(String entity) {
            this.entity = entity;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public java.util.List<String> getRules() {
            return rules;
        }

        public void setRules(java.util.List<String> rules) {
            this.rules = rules;
        }

        public java.util.List<String> getSops() {
            return sops;
        }

        public void setSops(java.util.List<String> sops) {
            this.sops = sops;
        }

        public java.util.List<String> getProcesses() {
            return processes;
        }

        public void setProcesses(java.util.List<String> processes) {
            this.processes = processes;
        }

        @Override
        public String toString() {
            return "BusinessKnowledge{" +
                    "entity='" + entity + '\'' +
                    ", description='" + description + '\'' +
                    ", rules=" + rules +
                    ", sops=" + sops +
                    '}';
        }
    }

    /**
     * 代码映射
     */
    public static class CodeMapping {
        /**
         * 业务 → 代码
         * Key: 业务实体
         * Value: 相关代码列表
         */
        private java.util.Map<String, java.util.List<CodeEntry>> businessToCode = new ConcurrentHashMap<>();

        /**
         * 代码 → 业务
         * Key: 类名
         * Value: 业务描述
         */
        private java.util.Map<String, String> codeToBusiness = new ConcurrentHashMap<>();

        public java.util.Map<String, java.util.List<CodeEntry>> getBusinessToCode() {
            return businessToCode;
        }

        public void setBusinessToCode(java.util.Map<String, java.util.List<CodeEntry>> businessToCode) {
            this.businessToCode = businessToCode;
        }

        public java.util.Map<String, String> getCodeToBusiness() {
            return codeToBusiness;
        }

        public void setCodeToBusiness(java.util.Map<String, String> codeToBusiness) {
            this.codeToBusiness = codeToBusiness;
        }
    }

    /**
     * 代码入口
     */
    public static class CodeEntry {
        private String className;
        private String method;
        private String description;

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return "CodeEntry{" +
                    "className='" + className + '\'' +
                    ", method='" + method + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
    }
}
