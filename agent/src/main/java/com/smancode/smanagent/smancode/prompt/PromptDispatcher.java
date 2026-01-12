package com.smancode.smanagent.smancode.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 提示词分发器
 * <p>
 * 负责根据不同场景组装完整的提示词，包括系统提示词、工具介绍等。
 */
@Service
public class PromptDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(PromptDispatcher.class);

    @Autowired
    private PromptLoaderService promptLoader;

    /**
     * 构建意图识别提示词
     *
     * @param userInput 用户输入
     * @return 完整提示词
     */
    public String buildIntentRecognitionPrompt(String userInput) {
        String systemPrompt = promptLoader.loadPrompt("common/system-header.md");
        String taskPrompt = promptLoader.loadPrompt("phases/01-intent-recognition.md");

        return systemPrompt + "\n\n" + taskPrompt + "\n\n## 用户输入\n\n" + userInput;
    }

    /**
     * 构建需求规划提示词
     *
     * @param userInput     用户输入
     * @param toolSummary 工具摘要（可选）
     * @return 完整提示词
     */
    public String buildRequirementPlanningPrompt(String userInput, String toolSummary) {
        String systemPrompt = promptLoader.loadPrompt("common/system-header.md");
        String taskPrompt = promptLoader.loadPrompt("phases/02-requirement-planning.md");

        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt).append("\n\n");
        sb.append(taskPrompt).append("\n\n");

        if (toolSummary != null && !toolSummary.isEmpty()) {
            sb.append("## 可用工具\n\n").append(toolSummary).append("\n\n");
        }

        sb.append("## 用户需求\n\n").append(userInput);

        return sb.toString();
    }

    /**
     * 构建子任务执行提示词
     *
     * @param userInput    用户输入
     * @param subtask      当前子任务
     * @param context      上下文信息（前序任务的结果）
     * @return 完整提示词
     */
    public String buildExecutionPrompt(String userInput, String subtask, String context) {
        String systemPrompt = promptLoader.loadPrompt("common/system-header.md");
        String taskPrompt = promptLoader.loadPrompt("phases/03-execution.md");

        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt).append("\n\n");
        sb.append(taskPrompt).append("\n\n");
        sb.append("## 用户原始需求\n\n").append(userInput).append("\n\n");
        sb.append("## 当前子任务\n\n").append(subtask).append("\n\n");

        if (context != null && !context.isEmpty()) {
            sb.append("## 前序任务结果\n\n").append(context).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建系统提示词（包含工具介绍）
     *
     * @return 完整系统提示词
     */
    public String buildSystemPrompt() {
        String systemPrompt = promptLoader.loadPrompt("common/system-header.md");
        String toolIntroduction = promptLoader.loadPrompt("tools/tool-introduction.md");

        return systemPrompt + "\n\n" + toolIntroduction;
    }

    /**
     * 获取工具摘要
     *
     * @return 工具摘要（精简版）
     */
    public String getToolSummary() {
        return """
            ## 可用工具摘要

            | 工具 | 功能 | 使用场景 |
            |------|------|----------|
            | semantic_search | 语义搜索 | 找功能实现 |
            | grep_file | 正则搜索 | 找方法使用 |
            | find_file | 文件查找 | 找类文件 |
            | read_file | 读取文件 | 看代码实现 |
            | call_chain | 调用链分析 | 理调用关系 |
            | extract_xml | XML 提取 | 提取配置 |

            详细信息请参考工具文档。
            """;
    }

    /**
     * 构建带变量的提示词
     *
     * @param promptPath 提示词路径
     * @param variables  变量映射
     * @return 替换后的提示词
     */
    public String buildPromptWithVariables(String promptPath, Map<String, String> variables) {
        return promptLoader.loadPromptWithVariables(promptPath, variables);
    }
}
