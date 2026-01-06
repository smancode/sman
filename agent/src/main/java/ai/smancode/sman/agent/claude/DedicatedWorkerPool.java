package ai.smancode.sman.agent.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 专属Worker池管理器
 *
 * 功能：
 * - 为每个 sessionId（UUID）创建专属的 Worker 进程
 * - 确保 Worker 进程不会重启，避免 sessionId 锁定问题
 * - 实现 Session Affinity（会话粘性）
 *
 * 优势：
 * - 完全隔离：每个 session 有独立 worker
 * - 多轮对话：worker 一直运行，记住上下文
 * - 无干扰：不同 UUID 互不影响
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Service
public class DedicatedWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(DedicatedWorkerPool.class);

    @Value("${claude-code.path:claude-code}")
    private String claudeCodePath;

    @Value("${claude-code.work-dir-base:${user.dir}/data/claude-code-workspaces}")
    private String workDirBase;

    // 存储专属worker：sessionId → worker
    private final Map<String, ClaudeCodeWorker> dedicatedWorkers = new ConcurrentHashMap<>();

    /**
     * 获取专属worker（如果不存在则创建）
     *
     * @param sessionId 会话ID（UUID格式）
     * @return 专属的Worker进程
     * @throws IOException 创建失败
     */
    public ClaudeCodeWorker acquireWorker(String sessionId) throws IOException {
        ClaudeCodeWorker worker = dedicatedWorkers.get(sessionId);

        // 检查是否已有专属worker
        if (worker != null && worker.isAlive() && worker.isReady()) {
            log.debug("♻️ 复用专属worker: sessionId={}, workerId={}", sessionId, worker.getWorkerId());
            worker.setBusy(true);
            return worker;
        }

        // 创建新的专属worker
        log.info("✨ 创建专属worker: sessionId={}", sessionId);
        worker = createDedicatedWorker(sessionId);
        dedicatedWorkers.put(sessionId, worker);

        return worker;
    }

    /**
     * 释放专属worker（保持运行，不关闭）
     *
     * @param sessionId 会话ID
     * @param worker Worker进程
     */
    public void releaseWorker(String sessionId, ClaudeCodeWorker worker) {
        worker.setBusy(false);
        log.debug("🔄 释放专属worker: sessionId={}, workerId={}, 保持运行",
                  sessionId, worker.getWorkerId());
    }

    /**
     * 清除会话绑定并关闭worker
     *
     * @param sessionId 会话ID
     */
    public void clearSession(String sessionId) {
        ClaudeCodeWorker worker = dedicatedWorkers.remove(sessionId);
        if (worker != null) {
            log.info("🗑️ 清除专属worker: sessionId={}, workerId={}",
                     sessionId, worker.getWorkerId());

            // 关闭worker进程
            if (worker.isAlive()) {
                worker.getProcess().destroy();
            }
        }
    }

    /**
     * 创建专属worker
     *
     * @param sessionId 会话ID
     * @return Worker进程
     * @throws IOException 创建失败
     */
    private ClaudeCodeWorker createDedicatedWorker(String sessionId) throws IOException {
        // 使用sessionId作为workerId的一部分
        String workerId = "dedicated-" + sessionId.substring(0, 8);

        // 🔥 生成固定的 logTag (整个会话使用同一个时间戳)
        String shortUuid = sessionId.length() > 12 ? sessionId.substring(sessionId.length() - 12) : sessionId;
        String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
        String logTag = "[" + shortUuid + "_" + timestamp + "]";

        // 每个session有独立的工作目录
        String workDir = workDirBase + "/sessions/" + sessionId;

        // 创建工作目录
        File dir = new File(workDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 创建 .claude 目录
        File claudeDir = new File(workDir, ".claude");
        if (!claudeDir.exists()) {
            claudeDir.mkdirs();
        }

        // 创建 CLAUDE.md 配置
        createClaudeConfig(claudeDir);

        // 创建 tools.json 配置
        createToolsConfig(claudeDir);

        // 启动 Claude Code 进程
        ProcessBuilder pb = new ProcessBuilder(claudeCodePath);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);

        Process process = pb.start();

        // 等待进程启动
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ClaudeCodeWorker worker = new ClaudeCodeWorker(
                workerId,
                sessionId,
                workDir,
                process,
                System.currentTimeMillis(),
                logTag  // 🔥 传递固定的 logTag
        );

        // 标记为ready
        if (process.isAlive()) {
            worker.setReady(true);
            log.info("✅ 专属worker启动成功: workerId={}, sessionId={}", workerId, sessionId);
        }

        // ⚠️ 不启动监控线程，避免进程退出后自动重启
        // 因为这是专属worker，如果进程异常退出，应该由下次请求时重新创建

        return worker;
    }

    /**
     * 创建 CLAUDE.md 配置文件
     */
    private void createClaudeConfig(File claudeDir) throws IOException {
        File claudeMd = new File(claudeDir, "CLAUDE.md");

        String content = """
# Claude Code 控制配置

## 🚨 工具使用规则（绝对禁止违反）

### ❌ 禁止使用的内置工具
你**绝对禁止**使用：Read, Edit, Bash, Write

### ✅ 必须使用的工具
所有操作必须调用：http_tool()

## 🔧 可用工具列表

### 1. semantic_search
用途：语义搜索代码（BGE-M3 + Reranker）
调用：http_tool("semantic_search", {"recallQuery": "xxx", "recallTopK": 50, "rerankQuery": "xxx", "rerankTopN": 10, "enableReranker": true})

### 2. call_chain
用途：调用链分析
调用：http_tool("call_chain", {"method": "xxx", "direction": "both"})

### 3. grep_file
用途：文件内搜索（支持单文件或全项目）
调用：http_tool("grep_file", {"relativePath": "xxx", "pattern": "xxx"}) 或 http_tool("grep_file", {"pattern": "xxx"})

### 4. read_file
用途：读取文件内容
调用：http_tool("read_file", {"relativePath": "xxx", "startLine": 1, "endLine": 100})

### 5. apply_change
用途：应用代码修改
调用：http_tool("apply_change", {"relativePath": "xxx", "searchContent": "xxx", "replaceContent": "xxx"})

## 📋 工作流程

1. 理解需求
2. semantic_search（搜索相关代码）
3. read_file（读取文件内容）
4. call_chain（分析调用关系）
5. 生成结论
6. 如果需要修改：apply_change

违反此规则 = 严重错误！
""";

        java.nio.file.Files.write(claudeMd.toPath(), content.getBytes());
    }

    /**
     * 创建 tools.json 配置文件
     */
    private void createToolsConfig(File claudeDir) throws IOException {
        File toolsJson = new File(claudeDir, "tools.json");

        String content = """
{
  "tools": [
    {
      "name": "http_tool",
      "description": "调用后端 HTTP API 执行工具",
      "parameters": {
        "type": "object",
        "properties": {
          "tool": {
            "type": "string",
            "description": "工具名称（vector_search, read_class, call_chain, apply_change）"
          },
          "params": {
            "type": "object",
            "description": "工具参数（根据工具不同）"
          }
        },
        "required": ["tool", "params"]
      }
    }
  ]
}
""";

        java.nio.file.Files.write(toolsJson.toPath(), content.getBytes());
    }

    /**
     * 获取统计信息
     */
    public PoolStats getStats() {
        int totalWorkers = dedicatedWorkers.size();
        int aliveWorkers = 0;
        int busyWorkers = 0;

        for (ClaudeCodeWorker worker : dedicatedWorkers.values()) {
            if (worker.isAlive()) {
                aliveWorkers++;
                if (worker.isBusy()) {
                    busyWorkers++;
                }
            }
        }

        return new PoolStats(totalWorkers, aliveWorkers, busyWorkers);
    }

    /**
     * 清理所有worker
     */
    public void shutdown() {
        log.info("🛑 关闭专属Worker池...");

        for (ClaudeCodeWorker worker : dedicatedWorkers.values()) {
            if (worker.isAlive()) {
                log.info("🛑 停止worker: {}", worker.getWorkerId());
                worker.getProcess().destroy();
            }
        }

        dedicatedWorkers.clear();
        log.info("✅ 专属Worker池已关闭");
    }

    /**
     * 池状态统计
     */
    public static class PoolStats {
        private final int totalWorkers;
        private final int aliveWorkers;
        private final int busyWorkers;

        public PoolStats(int totalWorkers, int aliveWorkers, int busyWorkers) {
            this.totalWorkers = totalWorkers;
            this.aliveWorkers = aliveWorkers;
            this.busyWorkers = busyWorkers;
        }

        public int getTotalWorkers() {
            return totalWorkers;
        }

        public int getAliveWorkers() {
            return aliveWorkers;
        }

        public int getBusyWorkers() {
            return busyWorkers;
        }

        public int getIdleWorkers() {
            return aliveWorkers - busyWorkers;
        }
    }
}
