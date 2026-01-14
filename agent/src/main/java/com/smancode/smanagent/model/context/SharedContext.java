package com.smancode.smanagent.model.context;

import com.smancode.smanagent.model.subtask.SubTaskConclusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享上下文
 * <p>
 * 用于在 SubTask 之间和 IntegrationAgent 之间共享信息。
 * <p>
 * 存储内容：
 * - SubTask 结论（conclusions）：每个 SubTask 执行后的结论
 * - 全局上下文（globalContext）：领域知识、已扫描的文件等累积信息
 */
public class SharedContext {

    private static final Logger logger = LoggerFactory.getLogger(SharedContext.class);

    /**
     * 存储 SubTask 结论（subTaskId -> SubTaskConclusion）
     */
    private final Map<String, SubTaskConclusion> conclusions = new ConcurrentHashMap<>();

    /**
     * 全局上下文（key -> value）
     * 用于存储领域知识片段、已扫描的文件等累积信息
     */
    private final Map<String, Object> globalContext = new ConcurrentHashMap<>();

    /**
     * 添加 SubTask 结论
     *
     * @param subTaskId   SubTask ID
     * @param conclusion  SubTask 结论
     */
    public void addConclusion(String subTaskId, SubTaskConclusion conclusion) {
        if (subTaskId == null || subTaskId.isEmpty()) {
            throw new IllegalArgumentException("subTaskId 不能为空");
        }
        if (conclusion == null) {
            throw new IllegalArgumentException("conclusion 不能为空");
        }

        conclusions.put(subTaskId, conclusion);
        logger.debug("添加 SubTask 结论: subTaskId={}, 结论长度={}",
                subTaskId, conclusion.getConclusion() != null ? conclusion.getConclusion().length() : 0);
    }

    /**
     * 获取 SubTask 结论
     *
     * @param subTaskId  SubTask ID
     * @return SubTask 结论，不存在则返回 null
     */
    public SubTaskConclusion getConclusion(String subTaskId) {
        if (subTaskId == null || subTaskId.isEmpty()) {
            throw new IllegalArgumentException("subTaskId 不能为空");
        }
        return conclusions.get(subTaskId);
    }

    /**
     * 获取所有 SubTask 结论
     *
     * @return 所有 SubTask 结论的不可变视图
     */
    public Map<String, SubTaskConclusion> getAllConclusions() {
        return Map.copyOf(conclusions);
    }

    /**
     * 添加全局上下文
     *
     * @param key    键
     * @param value  值
     */
    public void addGlobalContext(String key, Object value) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("value 不能为空");
        }

        globalContext.put(key, value);
        logger.debug("添加全局上下文: key={}, 类型={}", key, value.getClass().getSimpleName());
    }

    /**
     * 获取全局上下文
     *
     * @param key  键
     * @return 值，不存在则返回 null
     */
    public Object getGlobalContext(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        return globalContext.get(key);
    }

    /**
     * 获取所有全局上下文
     *
     * @return 所有全局上下文的不可变视图
     */
    public Map<String, Object> getAllGlobalContext() {
        return Map.copyOf(globalContext);
    }

    /**
     * 清空所有内容（用于测试）
     */
    public void clear() {
        conclusions.clear();
        globalContext.clear();
        logger.debug("清空 SharedContext");
    }

    /**
     * 获取 SubTask 结论数量
     */
    public int getConclusionCount() {
        return conclusions.size();
    }

    /**
     * 获取全局上下文数量
     */
    public int getGlobalContextCount() {
        return globalContext.size();
    }
}
