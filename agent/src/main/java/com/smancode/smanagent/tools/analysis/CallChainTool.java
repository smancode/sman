package com.smancode.smanagent.tools.analysis;

import com.smancode.smanagent.tools.AbstractTool;
import com.smancode.smanagent.tools.ParameterDef;
import com.smancode.smanagent.tools.Tool;
import com.smancode.smanagent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用链分析工具
 * <p>
 * 分析方法的完整调用链（向上和向下）。
 * 执行模式：intellij（IDE 执行）
 */
@Component
public class CallChainTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(CallChainTool.class);

    @Override
    public String getName() {
        return "call_chain";
    }

    @Override
    public String getDescription() {
        return "分析方法调用链（向上和向下）";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("className", new ParameterDef("className", String.class, true, "完整类名"));
        params.put("methodName", new ParameterDef("methodName", String.class, true, "方法名"));
        params.put("direction", new ParameterDef("direction", String.class, false, "方向：up(谁调我)/down(我调谁)/both", "both"));
        params.put("maxDepth", new ParameterDef("maxDepth", Integer.class, false, "最大深度（默认 5）", 5));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "intellij"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String className = (String) params.get("className");
            if (className == null || className.trim().isEmpty()) {
                return ToolResult.failure("缺少 className 参数");
            }

            String methodName = (String) params.get("methodName");
            if (methodName == null || methodName.trim().isEmpty()) {
                return ToolResult.failure("缺少 methodName 参数");
            }

            String direction = getOptString(params, "direction", "both");
            int maxDepth = getOptInt(params, "maxDepth", 5);

            if (maxDepth <= 0) {
                return ToolResult.failure("maxDepth 必须大于 0");
            }

            logger.info("执行调用链分析: projectKey={}, className={}, methodName={}, direction={}, maxDepth={}",
                projectKey, className, methodName, direction, maxDepth);

            // 注意：这个工具需要在 IDE 中执行
            String displayContent = String.format(
                "工具需要在 IDE 中执行\n" +
                "参数：className=%s, methodName=%s, direction=%s, maxDepth=%d",
                className, methodName, direction, maxDepth
            );

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(null, "调用链分析（IDE 执行）", displayContent);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("调用链分析失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("分析失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }
}
