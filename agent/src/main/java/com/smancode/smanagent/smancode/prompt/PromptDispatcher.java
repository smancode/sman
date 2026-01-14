package com.smancode.smanagent.smancode.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 提示词分发器（极简架构版）
 * <p>
 * 新架构特点：
 * - 无意图识别：完全由 LLM 决定行为
 * - 无阶段划分：一个主循环处理所有
 * - system-reminder 支持：允许用户随时打断
 */
@Service
public class PromptDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(PromptDispatcher.class);

    @Autowired
    private PromptLoaderService promptLoader;

    /**
     * 构建系统提示词（包含工具介绍）
     * <p>
     * 新架构下只需要这一个基础系统提示词
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
            | call_chain | 调用链分析 | 理解调用关系 |
            | extract_xml | XML 提取 | 提取配置 |
            | apply_change | 代码修改 | 应用代码修改 |

            详细信息请参考工具文档。
            """;
    }

    /**
     * 构建带变量的提示词（保留用于特殊场景）
     *
     * @param promptPath 提示词路径
     * @param variables  变量映射
     * @return 替换后的提示词
     */
    public String buildPromptWithVariables(String promptPath, Map<String, String> variables) {
        return promptLoader.loadPromptWithVariables(promptPath, variables);
    }
}
