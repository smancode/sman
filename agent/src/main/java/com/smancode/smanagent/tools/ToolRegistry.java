package com.smancode.smanagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * <p>
 * 管理所有可用工具，提供工具查询和获取功能。
 */
@Service
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 所有工具实例（通过 Spring 自动注入）
     */
    @Autowired(required = false)
    private List<Tool> tools;

    /**
     * 工具名称 -> 工具实例 映射
     */
    private final Map<String, Tool> toolMap = new HashMap<>();

    @PostConstruct
    public void init() {
        if (tools == null) {
            logger.warn("没有找到任何工具实现");
            return;
        }

        // 注册所有工具
        for (Tool tool : tools) {
            String name = tool.getName();
            if (toolMap.containsKey(name)) {
                logger.warn("工具名称冲突: {}，已存在，跳过注册", name);
                continue;
            }
            toolMap.put(name, tool);
            logger.info("注册工具: {} - {}", name, tool.getDescription());
        }

        logger.info("工具注册完成，共注册 {} 个工具", toolMap.size());
    }

    /**
     * 根据名称获取工具
     *
     * @param name 工具名称
     * @return 工具实例，如果不存在则返回 null
     */
    public Tool getTool(String name) {
        logger.debug("ToolRegistry.getTool 获取工具: name={}", name);
        Tool tool = toolMap.get(name);
        if (tool == null) {
            logger.warn("ToolRegistry.getTool 未找到工具: name={}", name);
        } else {
            logger.debug("ToolRegistry.getTool 找到工具: name={}, class={}", name, tool.getClass().getSimpleName());
        }
        return tool;
    }

    /**
     * 获取所有工具
     *
     * @return 所有工具列表
     */
    public List<Tool> getAllTools() {
        return new ArrayList<>(toolMap.values());
    }

    /**
     * 获取所有工具名称
     *
     * @return 所有工具名称列表
     */
    public List<String> getToolNames() {
        return new ArrayList<>(toolMap.keySet());
    }

    /**
     * 检查工具是否存在
     *
     * @param name 工具名称
     * @return 是否存在
     */
    public boolean hasTool(String name) {
        return toolMap.containsKey(name);
    }

    /**
     * 获取工具描述（用于生成工具介绍）
     *
     * @return 工具描述列表
     */
    public List<ToolDescription> getToolDescriptions() {
        return toolMap.values().stream()
            .map(tool -> new ToolDescription(
                tool.getName(),
                tool.getDescription(),
                buildParameterSummary(tool)
            ))
            .collect(Collectors.toList());
    }

    /**
     * 构建参数摘要
     *
     * @param tool 工具实例
     * @return 参数摘要字符串
     */
    private String buildParameterSummary(Tool tool) {
        Map<String, ParameterDef> params = tool.getParameters();
        if (params == null || params.isEmpty()) {
            return "无参数";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ParameterDef> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }

            ParameterDef def = entry.getValue();
            sb.append(entry.getKey()).append(": ");

            if (def.isRequired()) {
                sb.append("[必需] ");
            } else {
                sb.append("[可选] ");
            }

            sb.append(def.getType().getSimpleName());
        }

        return sb.toString();
    }

    /**
     * 工具描述
     */
    public static class ToolDescription {
        private final String name;
        private final String description;
        private final String parameters;

        public ToolDescription(String name, String description, String parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getParameters() {
            return parameters;
        }

        @Override
        public String toString() {
            return String.format("%s: %s (参数: %s)", name, description, parameters);
        }
    }
}
