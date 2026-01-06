package ai.smancode.sman.agent.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 向量索引自动初始化器
 *
 * 功能：服务启动时自动扫描并加载所有已存在的向量索引
 *
 * 工作流程：
 * 1. 扫描 vector.index.path 目录下的所有子目录
 * 2. 对每个包含 meta.json 的目录调用 VectorSearchService.initializeIndex()
 * 3. 同步到 VectorCacheManager 的 activeCache
 * 4. 记录加载成功的索引数量
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class VectorIndexInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexInitializer.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private VectorCacheManager cacheManager;

    @Value("${vector.index.path:data/vector-index}")
    private String vectorIndexPath;

    @Override
    public void run(String... args) {
        log.info("🚀 开始自动加载向量索引...");

        try {
            Path indexDir = Path.of(vectorIndexPath);

            // 检查索引目录是否存在
            if (!Files.exists(indexDir)) {
                log.warn("⚠️ 向量索引目录不存在: {}", indexDir);
                log.info("💡 提示: 首次启动时索引目录会自动创建");
                return;
            }

            // 扫描所有子目录（每个子目录代表一个项目的索引）
            Path[] projectDirs = Files.list(indexDir)
                    .filter(Files::isDirectory)
                    .toArray(Path[]::new);

            if (projectDirs.length == 0) {
                log.info("📭 向量索引目录为空: {}", indexDir);
                return;
            }

            log.info("📂 发现 {} 个项目索引目录", projectDirs.length);

            int successCount = 0;
            int failCount = 0;

            for (Path projectDir : projectDirs) {
                String projectKey = projectDir.getFileName().toString();

                try {
                    // 检查是否包含 meta.json（JVector 格式标识）
                    Path metaFile = projectDir.resolve("meta.json");
                    if (!Files.exists(metaFile)) {
                        log.debug("跳过非索引目录: {} (缺少 meta.json)", projectKey);
                        continue;
                    }

                    // 初始化索引
                    vectorSearchService.initializeIndex(projectKey);

                    // 同步到 cacheManager
                    VectorSearchService.JVectorIndexData indexData = vectorSearchService.getJVectorIndex(projectKey);
                    if (indexData != null) {
                        cacheManager.setActiveIndex(projectKey, indexData);
                        log.info("✅ 索引已同步到缓存管理器: projectKey={}", projectKey);
                    }

                    successCount++;
                    log.info("✅ 索引加载成功: projectKey={}", projectKey);

                } catch (Exception e) {
                    failCount++;
                    log.error("❌ 索引加载失败: projectKey={}, error={}", projectKey, e.getMessage());
                }
            }

            log.info("🎉 向量索引自动加载完成: 成功={}, 失败={}", successCount, failCount);

            // 输出索引统计信息
            if (successCount > 0) {
                var indexedProjects = vectorSearchService.getIndexedProjects();
                log.info("📊 已加载索引的项目: {}", indexedProjects);

                for (String projectKey : indexedProjects) {
                    var stats = vectorSearchService.getIndexStats(projectKey);
                    log.info("📈 {} - 文档数量: {}", projectKey, stats.get("documentCount"));
                }

                // 输出缓存管理器统计
                var cacheStats = cacheManager.getStats();
                log.info("📊 缓存管理器统计: {}", cacheStats);
            }

        } catch (Exception e) {
            log.error("❌ 向量索引自动初始化失败: {}", e.getMessage(), e);
        }
    }
}
