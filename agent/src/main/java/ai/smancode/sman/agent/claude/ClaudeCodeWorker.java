package ai.smancode.sman.agent.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.Process;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claude Code Worker 进程封装（单次执行模式）
 *
 * 功能：
 * - 封装单个 Claude Code 进程（使用 --resume 模式）
 * - 进程执行完单个请求后自动退出
 * - 跟踪进程状态（存活、就绪）
 * - 记录进程元数据（ID、工作目录、创建时间）
 * - 支持流式读取和 Markdown 增量解析
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
public class ClaudeCodeWorker {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeWorker.class);

    /**
     * 流式内容回调接口
     */
    public interface StreamingCallback {
        /**
         * 当读取到新行时调用
         * @param line 原始行内容
         */
        void onLineRead(String line);

        /**
         * 当检测到完整的 Markdown 块时调用
         * @param markdown Markdown 内容
         * @param chunkIndex 块索引（从 0 开始）
         * @param isComplete 是否是最终完整内容
         */
        void onMarkdownChunk(String markdown, int chunkIndex, boolean isComplete);

        /**
         * 当完成时调用
         * @param fullResponse 完整响应
         */
        void onComplete(String fullResponse);

        /**
         * 当发生错误时调用
         * @param error 错误信息
         */
        void onError(String error);
    }

    private final String workerId;
    private final String sessionId;
    private final String workDir;
    private final Process process;
    private final long createTime;
    private final String logTag;  // 🔥 新增：固定的日志标识符
    private long lastUsed;
    private boolean alive;
    private boolean ready;
    private boolean busy;

    // 不预先初始化 IO，在需要时创建

    /**
     * 构造函数
     *
     * @param workerId   Worker ID
     * @param sessionId  会话 ID
     * @param workDir    工作目录
     * @param process    进程对象
     * @param createTime 创建时间（毫秒时间戳）
     * @param logTag     日志标识符 (格式: [shortUuid_HHMMSS])
     */
    public ClaudeCodeWorker(String workerId, String sessionId, String workDir, Process process, long createTime, String logTag) {
        this.workerId = workerId;
        this.sessionId = sessionId;
        this.workDir = workDir;
        this.process = process;
        this.createTime = createTime;
        this.logTag = logTag;  // 🔥 保存固定的 logTag
        this.lastUsed = createTime;
        this.alive = true;
        this.ready = false;
        this.busy = false;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getWorkDir() {
        return workDir;
    }

    public Process getProcess() {
        return process;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getLastUsed() {
        return lastUsed;
    }

    public void setLastUsed(long lastUsed) {
        this.lastUsed = lastUsed;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    /**
     * 获取进程存活时间（毫秒）
     */
    public long getLifetime() {
        return System.currentTimeMillis() - createTime;
    }

    /**
     * 获取空闲时间（毫秒）
     */
    public long getIdleTime() {
        return System.currentTimeMillis() - lastUsed;
    }

    /**
     * 发送消息给 Claude Code 并获取响应
     *
     * @param message 用户消息
     * @param timeoutSeconds 超时时间（秒）
     * @return Claude Code 的响应
     * @throws InterruptedException 如果等待被中断
     * @throws java.util.concurrent.TimeoutException 如果超时
     */
    public String sendAndReceive(String message, long timeoutSeconds)
            throws InterruptedException, java.util.concurrent.TimeoutException {

        if (!isAlive() || !isReady()) {
            throw new IllegalStateException("Worker not ready: alive=" + isAlive() + ", ready=" + isReady());
        }

        // 临时设置当前线程名称，以便日志显示正确的线程名
        Thread currentThread = Thread.currentThread();
        String originalThreadName = currentThread.getName();
        currentThread.setName(logTag);  // 🔥 使用固定的 logTag

        // 每次调用时创建新的IO流(在外层try块外定义,以便finally能访问)
        final BufferedReader[] readerHolder = new BufferedReader[1];
        final BufferedWriter[] writerHolder = new BufferedWriter[1];

        try {
            log.info("📤 Worker 发送消息（流式模式）:");
            log.info("========================================");
            // 格式化输出：将 XML 标签分行显示
            String formattedMessage = message
                .replace("><", ">\n<")
                .replace("<message>", "\n<message>")
                .replace("</webSocketSessionId>", "</webSocketSessionId>\n");
            log.info("{}", formattedMessage);
            log.info("========================================");

            try {
                readerHolder[0] = new BufferedReader(new InputStreamReader(process.getInputStream()));
                writerHolder[0] = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<String> responseRef = new AtomicReference<>();
                AtomicReference<Exception> errorRef = new AtomicReference<>();

                // 启动读取线程
                Thread readThread = new Thread(() -> {
                    try {
                        StringBuilder response = new StringBuilder();
                        String line;
                        boolean hasContent = false;
    
                        log.debug("🔽 Worker {} 开始读取 Claude Code 输出...", workerId);
    
                        while ((line = readerHolder[0].readLine()) != null) {
    
                            // 收集所有非空行
                            if (!line.trim().isEmpty()) {
                                response.append(line).append("\n");
                                hasContent = true;
                            }
    
                            // 检测响应结束标记（如果有）
                            if (line.contains("=====END_OF_RESPONSE=====")) {
                                log.debug("✅ Worker {} 检测到响应结束标记，停止读取", workerId);
                                break;
                            }

                            // 检测工具调用开始（用于调试）
                            if (line.contains("Thinking") || line.contains("Tool use")) {
                                log.info("🔧 Worker {} 检测到 Claude Code 正在思考或调用工具", workerId);
                            }
                        }
    
                        String result = response.toString()
                            .replace("=====END_OF_RESPONSE=====", "")
                            .trim();
    
                        if (hasContent && !result.isEmpty()) {
                            responseRef.set(result);
                            log.info("📥 Worker 收到完整响应: {} 字符", result.length());
                        } else {
                            log.warn("⚠️  Worker {} 收到空响应", workerId);
                        }
    
                    } catch (IOException e) {
                        log.error("❌ Worker {} 读取响应失败: {}", workerId, e.getMessage(), e);
                        errorRef.set(e);
                    } finally {
                        latch.countDown();
                    }
                });
    
                readThread.setName("worker-" + workerId);
                readThread.setDaemon(true);
                readThread.start();
    
                // 发送消息到 stdin（sessionId 已通过 --resume 参数传递）
                try {
                    writerHolder[0].write(message);
                    writerHolder[0].newLine();
                    writerHolder[0].flush();
    
                    // 关闭writer以发送EOF信号（--print 模式需要EOF才开始处理）
                    writerHolder[0].close();
                    log.info("✅ Worker 消息已发送到 Claude Code stdin (EOF sent)");
                } catch (IOException e) {
                    log.error("❌ Worker {} 发送消息失败: {}", workerId, e.getMessage(), e);
                    errorRef.set(e);
                    latch.countDown();
                }
    
                // 等待响应
                log.info("⏳ Worker {} 等待 Claude Code 响应（最长 {} 秒）...", workerId, timeoutSeconds);
                log.info("⏳ Worker {} 进程状态: isAlive={}, isReady={}", workerId, isAlive(), isReady());
                boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
    
                if (!completed) {
                    log.error("⏰ Worker {} 响应超时（{}秒）", workerId, timeoutSeconds);
                    log.error("⏰ 超时诊断: isAlive={}, isReady={}, hasResponse={}",
                            isAlive(), isReady(), responseRef.get() != null);
                    if (responseRef.get() != null) {
                        log.error("⏰ 部分响应内容: {} ...", responseRef.get().substring(0, Math.min(200, responseRef.get().length())));
                    }
                    readThread.interrupt();
                    throw new java.util.concurrent.TimeoutException("Worker " + workerId + " timeout after " + timeoutSeconds + "s");
                }
    
                if (errorRef.get() != null) {
                    log.error("❌ Worker {} 通信失败", workerId);
                    throw new RuntimeException("Worker communication failed", errorRef.get());
                }
    
                String response = responseRef.get();
                if (response == null || response.isEmpty()) {
                    log.warn("⚠️  Worker {} 返回空响应", workerId);
                    return "❌ Worker 返回空响应";
                }
    
                log.info("========================================");
                log.info("📥 Worker Claude Code 响应:");
                log.info("========================================");
                log.info("{}", response);
                log.info("========================================");

                return response;

            } catch (Exception e) {
                // 内层try块的异常处理
                throw new RuntimeException(e);
            }  // 关闭内层try块

        } finally {
            // 关闭IO流
            try {
                if (readerHolder[0] != null) readerHolder[0].close();
                if (writerHolder[0] != null) writerHolder[0].close();
            } catch (IOException e) {
                log.trace("Worker {} 关闭IO流时出错（可忽略）: {}", workerId, e.getMessage());
            }

            // 恢复原始线程名
            currentThread.setName(originalThreadName);
        }
    }

    /**
     * 流式发送消息并接收响应（支持 Markdown 增量解析）
     *
     * @param message 用户消息
     * @param callback 流式回调
     * @param timeoutSeconds 超时时间（秒）
     * @throws InterruptedException 如果等待被中断
     * @throws java.util.concurrent.TimeoutException 如果超时
     */
    public void sendAndReceiveStreaming(String message, StreamingCallback callback, long timeoutSeconds)
            throws InterruptedException, java.util.concurrent.TimeoutException {

        if (!isAlive() || !isReady()) {
            throw new IllegalStateException("Worker not ready: alive=" + isAlive() + ", ready=" + isReady());
        }

        // 临时设置当前线程名称，以便日志显示正确的线程名
        Thread currentThread = Thread.currentThread();
        String originalThreadName = currentThread.getName();
        currentThread.setName(logTag);  // 🔥 使用固定的 logTag

        // 每次调用时创建新的IO流(在外层try块外定义,以便finally能访问)
        final BufferedReader[] readerHolder = new BufferedReader[1];
        final BufferedWriter[] writerHolder = new BufferedWriter[1];

        try {
            log.info("📤 Worker 发送消息（流式模式）:");
            log.info("========================================");
            log.info("{}", message);
            log.info("========================================");

            try {
                readerHolder[0] = new BufferedReader(new InputStreamReader(process.getInputStream()));
                writerHolder[0] = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
    
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<String> responseRef = new AtomicReference<>();
                AtomicReference<Exception> errorRef = new AtomicReference<>();
    
                // Markdown 累积缓冲区
                StringBuilder markdownBuffer = new StringBuilder();
                int[] chunkIndex = {0};  // 使用数组以便在 lambda 中修改
                String[] lastSentChunk = {""};  // 记录上次发送的块，避免重复
    
                // 启动读取线程
                Thread readThread = new Thread(() -> {
                    try {
                        String line;
                        log.debug("🔽 Worker {} 开始流式读取 Claude Code 输出...", workerId);
    
                        while ((line = readerHolder[0].readLine()) != null) {
    
                            // 回调：通知新行读取
                            callback.onLineRead(line);
    
                            // 累积 Markdown 内容
                            if (!line.trim().isEmpty()) {
                                markdownBuffer.append(line).append("\n");
    
                                // 检测是否是完整的 Markdown 块
                                String currentMarkdown = markdownBuffer.toString();
    
                                // 只有当内容变化时才推送（避免重复推送相同的块）
                                if (!currentMarkdown.equals(lastSentChunk[0]) && isMarkdownComplete(currentMarkdown)) {
                                    callback.onMarkdownChunk(currentMarkdown, chunkIndex[0], false);
                                    lastSentChunk[0] = currentMarkdown;
                                    chunkIndex[0]++;
                                }
                            }
    
                            // 检测响应结束标记
                            if (line.contains("=====END_OF_RESPONSE=====")) {
                                log.debug("✅ Worker {} 检测到响应结束标记，停止读取", workerId);
                                break;
                            }
                        }
    
                        // 最终响应
                        String finalResponse = markdownBuffer.toString()
                            .replace("=====END_OF_RESPONSE=====", "")
                            .trim();

                        // 🔥 过滤 <thinking> 标签及其内容
                        String cleanedResponse = finalResponse.replaceAll("(?s)<thinking>.*?</thinking>\\s*", "");

                        if (!cleanedResponse.isEmpty()) {
                            responseRef.set(cleanedResponse);

                            // 推送最终完整内容（已过滤）
                            log.info("{} 📦 Worker 推送最终完整 Markdown 块 ({} 字符, 已过滤 <thinking>)",
                                    cleanedResponse.length());
                            callback.onMarkdownChunk(cleanedResponse, chunkIndex[0], true);

                            log.info("📥 Worker 流式读取完成: {} 字符", cleanedResponse.length());
                        } else {
                            log.warn("⚠️  Worker {} 收到空响应", workerId);
                        }
    
                    } catch (IOException e) {
                        log.error("❌ Worker {} 读取响应失败: {}", workerId, e.getMessage(), e);
                        errorRef.set(e);
                        callback.onError(e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
    
                readThread.setName("worker-" + workerId);
                readThread.setDaemon(true);
                readThread.start();
    
                // 发送消息到 stdin
                try {
                    writerHolder[0].write(message);
                    writerHolder[0].newLine();
                    writerHolder[0].flush();
    
                    // 关闭writer以发送EOF信号
                    writerHolder[0].close();
                    log.info("✅ Worker 消息已发送到 Claude Code stdin (EOF sent)");
                } catch (IOException e) {
                    log.error("❌ Worker {} 发送消息失败: {}", workerId, e.getMessage(), e);
                    errorRef.set(e);
                    callback.onError(e.getMessage());
                    latch.countDown();
                }
    
                // 等待响应
                log.debug("⏳ Worker {} 等待 Claude Code 响应（最长 {} 秒）...", workerId, timeoutSeconds);
            boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                log.error("⏰ Worker {} 响应超时（{}秒）", workerId, timeoutSeconds);
                readThread.interrupt();
                throw new java.util.concurrent.TimeoutException("Worker " + workerId + " timeout after " + timeoutSeconds + "s");
            }

            if (errorRef.get() != null) {
                log.error("❌ Worker {} 通信失败", workerId);
                throw new RuntimeException("Worker communication failed", errorRef.get());
            }

            String response = responseRef.get();
            if (response == null || response.isEmpty()) {
                log.warn("⚠️  Worker {} 返回空响应", workerId);
                callback.onComplete("❌ Worker 返回空响应");
            } else {
                callback.onComplete(response);
                log.info("========================================");
                log.info("📥 Worker Claude Code 流式响应完成:");
                log.info("========================================");
                log.info("{}", response);
                log.info("========================================");
            }

        } catch (Exception e) {
            // 内层try块的异常处理
            throw new RuntimeException(e);
        }  // 关闭内层try块

        } finally {
            // 关闭IO流
            try {
                if (readerHolder[0] != null) readerHolder[0].close();
                if (writerHolder[0] != null) writerHolder[0].close();
            } catch (IOException e) {
                log.trace("Worker {} 关闭IO流时出错（可忽略）: {}", workerId, e.getMessage());
            }

            // 恢复原始线程名
            currentThread.setName(originalThreadName);
        }
    }

    /**
     * 检测 Markdown 是否完整（可安全解析）
     *
     * 完整性规则：
     * 1. 代码块必须成对（``` 必须闭合）
     * 2. 粗体标记尽量成对（** 尽量闭合，软要求）
     * 3. 不在表格/列表的中间位置
     *
     * @param markdown Markdown 内容
     * @return 是否完整
     */
    private boolean isMarkdownComplete(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return false;
        }

        // 1. 检查代码块是否成对（严格要求）
        long codeBlockCount = markdown.lines()
            .filter(line -> line.trim().startsWith("```"))
            .count();

        if (codeBlockCount % 2 != 0) {
            log.debug("🔍 Markdown 不完整: 代码块未闭合 (count={})", codeBlockCount);
            return false;
        }

        // 2. 检查是否在粗体标记中间（软要求，仅供参考）
        long boldMarkerCount = markdown.chars()
            .filter(ch -> ch == '*')
            .count();

        // 如果粗体标记不成对，可能正在输入粗体文本（但不阻止渲染）
        if (boldMarkerCount % 2 != 0) {
            log.debug("🔍 Markdown 可能不完整: 粗体标记未闭合 (count={})", boldMarkerCount);
            // 不返回 false，允许继续渲染（粗体不闭合不会破坏结构）
        }

        // 3. 检查最后一个字符是否在特殊状态（可选）
        String trimmed = markdown.trim();
        if (trimmed.endsWith("**") || trimmed.endsWith("`") || trimmed.endsWith("[")) {
            log.debug("🔍 Markdown 可能不完整: 以特殊标记结尾");
            // 不返回 false，允许继续渲染
        }

        // 通过所有检查，认为 Markdown 可安全解析
        return true;
    }
}
