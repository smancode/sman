package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.part.SubTaskPart;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.smancode.prompt.PromptDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SubTask 并行执行器
 * <p>
 * 根据 SubTask 的 dependsOn 关系，智能编排并行执行：
 * - 无依赖的 SubTask 可以并行执行
 * - 有依赖的 SubTask 等待依赖完成后执行
 * <p>
 * 使用线程池：subTaskExecutorService
 */
@Component
public class SubTaskParallelExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SubTaskParallelExecutor.class);

    @Autowired
    private LlmService llmService;

    @Autowired
    private PromptDispatcher promptDispatcher;

    @Autowired
    private SubTaskExecutor subTaskExecutor;

    @Autowired
    @Qualifier("subTaskExecutorService")
    private ExecutorService executorService;

    @Value("${subtask.max-internal-iterations:3}")
    private int maxInternalIterations;

    /**
     * 并行执行 SubTask 列表
     *
     * @param subTasks    SubTask 列表
     * @param session     会话
     * @param partPusher  Part 推送器
     * @return 执行结果映射（subTaskId -> conclusion）
     */
    public Map<String, String> executeParallel(List<SubTaskPart> subTasks,
                                                Session session,
                                                Consumer<com.smancode.smanagent.model.part.Part> partPusher) {
        if (subTasks == null || subTasks.isEmpty()) {
            return Map.of();
        }

        logger.info("开始并行执行 SubTask: 总数={}", subTasks.size());

        // 1. 构建 ID -> SubTask 映射
        Map<String, SubTaskPart> subTaskMap = subTasks.stream()
                .collect(Collectors.toMap(SubTaskPart::getId, st -> st));

        // 2. 构建依赖图
        Map<String, Set<String>> dependencyGraph = buildDependencyGraph(subTasks);

        // 3. 拓扑排序，分层执行
        List<List<SubTaskPart>> layers = topologicalSort(subTasks, dependencyGraph);

        logger.info("SubTask 分层结果: 共 {} 层", layers.size());
        for (int i = 0; i < layers.size(); i++) {
            logger.info("  第 {} 层: {} 个 SubTask", i + 1, layers.get(i).size());
        }

        // 4. 按层执行
        Map<String, String> conclusions = new ConcurrentHashMap<>();

        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            List<SubTaskPart> layer = layers.get(layerIndex);
            logger.info("执行第 {} 层: SubTask 数量={}", layerIndex + 1, layer.size());

            // 并行执行当前层的所有 SubTask
            List<CompletableFuture<Void>> futures = layer.stream()
                    .map(subTask -> executeSubTaskAsync(subTask, session, partPusher, conclusions))
                    .toList();

            // 等待当前层全部完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            logger.info("第 {} 层执行完成", layerIndex + 1);
        }

        logger.info("所有 SubTask 执行完成: 总数={}, 结论数={}", subTasks.size(), conclusions.size());
        return conclusions;
    }

    /**
     * 异步执行单个 SubTask
     */
    private CompletableFuture<Void> executeSubTaskAsync(SubTaskPart subTask,
                                                        Session session,
                                                        Consumer<com.smancode.smanagent.model.part.Part> partPusher,
                                                        Map<String, String> conclusions) {
        return CompletableFuture.runAsync(() -> {
            logger.info("执行 SubTask: id={}, target={}, question={}",
                    subTask.getId(), subTask.getTarget(), subTask.getQuestion());

            try {
                // 标记开始
                subTask.start();
                partPusher.accept(subTask);

                // 执行 SubTask（内部 LLM 循环）
                String conclusion = executeSubTaskWithLoop(subTask, session, partPusher, conclusions);

                // 完成并保存结论
                subTask.complete(conclusion);
                conclusions.put(subTask.getId(), conclusion);

                partPusher.accept(subTask);

                logger.info("SubTask 完成: id={}, 结论长度={}",
                        subTask.getId(), conclusion != null ? conclusion.length() : 0);

            } catch (Exception e) {
                logger.error("SubTask 执行失败: id={}", subTask.getId(), e);
                subTask.block(e.getMessage());
                partPusher.accept(subTask);
            }
        }, executorService);
    }

    /**
     * 执行 SubTask（支持内部 LLM 循环）
     */
    private String executeSubTaskWithLoop(SubTaskPart subTask,
                                          Session session,
                                          Consumer<com.smancode.smanagent.model.part.Part> partPusher,
                                          Map<String, String> existingConclusions) {
        StringBuilder finalConclusion = new StringBuilder();

        for (int iteration = 0; iteration < maxInternalIterations; iteration++) {
            logger.debug("SubTask 内部迭代: id={}, iteration={}/{}",
                    subTask.getId(), iteration + 1, maxInternalIterations);

            // 构建 Prompt（包含其他 SubTask 的结论）
            String prompt = buildSubTaskPrompt(subTask, session, existingConclusions, iteration);

            // 调用 LLM
            String response = llmService.simpleRequest(prompt);

            // 检查是否需要更多信息（调用 search 工具）
            if (needsSearch(response)) {
                // 这里应该让 LLM 调用 search 工具
                // 暂时跳过，等待工具系统集成
                logger.debug("SubTask 请求搜索: id={}, iteration={}", subTask.getId(), iteration);
                continue;
            }

            // LLM 已生成结论
            finalConclusion.append(response);
            break;
        }

        return finalConclusion.toString();
    }

    /**
     * 构建 SubTask Prompt
     */
    private String buildSubTaskPrompt(SubTaskPart subTask, Session session,
                                      Map<String, String> existingConclusions, int iteration) {
        StringBuilder sb = new StringBuilder();

        sb.append("# SubTask\n");
        sb.append(String.format("- 目标对象: %s\n", subTask.getTarget()));
        sb.append(String.format("- 问题: %s\n", subTask.getQuestion()));
        if (subTask.getReason() != null) {
            sb.append(String.format("- 原因: %s\n", subTask.getReason()));
        }
        sb.append("\n");

        // 添加已完成 SubTask 的结论
        if (!existingConclusions.isEmpty()) {
            sb.append("# 已完成的 SubTask 结论\n");
            existingConclusions.forEach((id, conclusion) -> {
                if (!id.equals(subTask.getId())) {  // 不包含自己
                    String shortConclusion = conclusion != null && conclusion.length() > 200
                            ? conclusion.substring(0, 200) + "..."
                            : conclusion;
                    sb.append(String.format("- %s: %s\n", id, shortConclusion));
                }
            });
            sb.append("\n");
        }

        if (iteration == 0) {
            sb.append("# 任务\n");
            sb.append("请回答上述问题。");
        } else {
            sb.append(String.format("# 任务（迭代 %d）\n", iteration + 1));
            sb.append("请继续分析并给出结论。");
        }

        return sb.toString();
    }

    /**
     * 检查 LLM 响应是否表示需要搜索
     */
    private boolean needsSearch(String response) {
        if (response == null) return false;
        String lower = response.toLowerCase();
        return lower.contains("需要搜索") || lower.contains("需要更多信息")
                || lower.contains("请搜索") || lower.contains("search for");
    }

    /**
     * 构建依赖图
     */
    private Map<String, Set<String>> buildDependencyGraph(List<SubTaskPart> subTasks) {
        Map<String, Set<String>> graph = new HashMap<>();

        for (SubTaskPart subTask : subTasks) {
            Set<String> dependencies = new HashSet<>();
            if (subTask.hasDependencies()) {
                dependencies = new HashSet<>(subTask.getDependsOn());
            }
            graph.put(subTask.getId(), dependencies);
        }

        return graph;
    }

    /**
     * 拓扑排序，分层执行
     * <p>
     * 返回：按依赖关系分层的 SubTask 列表
     * - 第 0 层：无依赖的 SubTask（可并行）
     * - 第 1 层：只依赖第 0 层的 SubTask（可并行）
     * - ...
     */
    private List<List<SubTaskPart>> topologicalSort(List<SubTaskPart> subTasks,
                                                     Map<String, Set<String>> dependencyGraph) {
        Map<String, SubTaskPart> subTaskMap = subTasks.stream()
                .collect(Collectors.toMap(SubTaskPart::getId, st -> st));

        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : subTaskMap.keySet()) {
            inDegree.put(id, dependencyGraph.getOrDefault(id, Collections.emptySet()).size());
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<List<SubTaskPart>> layers = new ArrayList<>();

        while (!queue.isEmpty()) {
            List<SubTaskPart> currentLayer = new ArrayList<>();

            // 处理当前层的所有节点
            int layerSize = queue.size();
            for (int i = 0; i < layerSize; i++) {
                String id = queue.poll();
                currentLayer.add(subTaskMap.get(id));
            }

            layers.add(currentLayer);

            // 更新入度
            for (SubTaskPart processed : currentLayer) {
                for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
                    if (entry.getValue().contains(processed.getId())) {
                        int newDegree = inDegree.get(entry.getKey()) - 1;
                        inDegree.put(entry.getKey(), newDegree);
                        if (newDegree == 0) {
                            queue.offer(entry.getKey());
                        }
                    }
                }
            }
        }

        // 检查是否有环
        if (layers.stream().mapToInt(List::size).sum() != subTasks.size()) {
            logger.warn("检测到循环依赖，部分 SubTask 可能无法执行");
        }

        return layers;
    }
}
