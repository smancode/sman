package com.smancode.smanagent.smancode.prompt;

import com.smancode.smanagent.util.StackTraceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词加载服务
 * <p>
 * 负责从 resources/prompts 目录加载 .md 文件，
 * 支持变量替换和缓存。
 */
@Service
public class PromptLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(PromptLoaderService.class);

    /**
     * 提示词缓存
     */
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();

    /**
     * 提示词基础路径
     */
    private static final String PROMPTS_BASE_PATH = "prompts/";

    /**
     * 加载提示词（带缓存）
     *
     * @param promptPath 提示词路径，如 "common/system-header.md"
     * @return 提示词内容
     */
    public String loadPrompt(String promptPath) {
        return promptCache.computeIfAbsent(promptPath, this::loadPromptFromFile);
    }

    /**
     * 加载提示词并替换变量（无缓存）
     *
     * @param promptPath 提示词路径
     * @param variables 变量映射
     * @return 替换后的提示词
     */
    public String loadPromptWithVariables(String promptPath, Map<String, String> variables) {
        String prompt = loadPromptFromFile(promptPath);
        return replaceVariables(prompt, variables);
    }

    /**
     * 从文件加载提示词
     *
     * @param promptPath 提示词路径
     * @return 提示词内容
     */
    private String loadPromptFromFile(String promptPath) {
        try {
            Path path = Paths.get("src/main/resources", PROMPTS_BASE_PATH, promptPath);
            logger.debug("加载提示词: {}", path);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            logger.debug("提示词长度: {} 字符", content.length());
            return content;
        } catch (IOException e) {
            logger.error("加载提示词失败: {}, {}", promptPath, StackTraceUtils.formatStackTrace(e));
            throw new RuntimeException("加载提示词失败: " + promptPath, e);
        }
    }

    /**
     * 替换提示词中的变量
     * <p>
     * 支持 {{VARIABLE_NAME}} 格式的变量替换
     *
     * @param prompt    提示词模板
     * @param variables 变量映射
     * @return 替换后的提示词
     */
    private String replaceVariables(String prompt, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return prompt;
        }

        String result = prompt;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            result = result.replace(placeholder, entry.getValue());
        }

        return result;
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        promptCache.clear();
        logger.info("提示词缓存已清除");
    }

    /**
     * 预加载所有提示词（可选）
     */
    public void preloadPrompts() {
        logger.info("开始预加载提示词...");
        String[] promptsToPreload = {
            "common/system-header.md",
            "phases/01-intent-recognition.md",
            "phases/02-requirement-planning.md",
            "phases/03-execution.md",
            "tools/tool-introduction.md"
        };

        for (String promptPath : promptsToPreload) {
            try {
                loadPrompt(promptPath);
            } catch (Exception e) {
                logger.warn("预加载提示词失败: {}", promptPath, e);
            }
        }

        logger.info("提示词预加载完成，缓存数量: {}", promptCache.size());
    }
}
