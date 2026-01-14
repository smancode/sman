package com.smancode.smanagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SmanCode 配置属性
 */
@Component
@ConfigurationProperties(prefix = "smancode")
public class SmanCodeProperties {

    /**
     * Orchestrator 配置
     */
    private Orchestrator orchestrator = new Orchestrator();

    /**
     * Prompt 配置
     */
    private Prompt prompt = new Prompt();

    /**
     * ReAct 配置
     */
    private React react = new React();

    public Orchestrator getOrchestrator() {
        return orchestrator;
    }

    public void setOrchestrator(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public React getReact() {
        return react;
    }

    public void setReact(React react) {
        this.react = react;
    }

    /**
     * Orchestrator 配置
     */
    public static class Orchestrator {
        /**
         * 最大迭代次数
         */
        private int maxIterations = 20;

        /**
         * 是否启用迭代分析
         */
        private boolean enableIterativeAnalysis = true;

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public boolean isEnableIterativeAnalysis() {
            return enableIterativeAnalysis;
        }

        public void setEnableIterativeAnalysis(boolean enableIterativeAnalysis) {
            this.enableIterativeAnalysis = enableIterativeAnalysis;
        }
    }

    /**
     * Prompt 配置
     */
    public static class Prompt {
        /**
         * 最大系统提示词长度
         */
        private int maxSystemPromptLength = 5000;

        /**
         * 最大迭代提示词长度
         */
        private int maxIterationPromptLength = 3000;

        public int getMaxSystemPromptLength() {
            return maxSystemPromptLength;
        }

        public void setMaxSystemPromptLength(int maxSystemPromptLength) {
            this.maxSystemPromptLength = maxSystemPromptLength;
        }

        public int getMaxIterationPromptLength() {
            return maxIterationPromptLength;
        }

        public void setMaxIterationPromptLength(int maxIterationPromptLength) {
            this.maxIterationPromptLength = maxIterationPromptLength;
        }
    }

    /**
     * ReAct 配置
     */
    public static class React {
        /**
         * ReAct 循环最大步数
         */
        private int maxSteps = 25;

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }
    }
}
