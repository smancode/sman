package ai.smancode.sman.agent.vector;

import ai.smancode.sman.agent.config.ProjectConfigService;
import ai.smancode.sman.agent.models.VectorModels.DocumentVector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量索引刷新器
 *
 * 功能：
 * 1. MD5 变化检测
 * 2. 扫描项目 Java 文件
 * 3. 增量更新向量索引
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class VectorIndexRefresher {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexRefresher.class);

    @Autowired
    private ProjectConfigService projectConfigService;

    @Value("${data.md5-cache-dir:./data/file-md5-cache}")
    private String md5CacheDir;

    @Value("${data.base-path:data}")
    private String dataBasePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 扫描所有 Java 文件（用于强制刷新）
     *
     * @param projectKey 项目键
     * @return 所有 Java 文件列表
     */
    public List<String> scanAllJavaFiles(String projectKey) {
        log.info("🔍 扫描所有 Java 文件: projectKey={}", projectKey);

        try {
            String projectPath = projectConfigService.getProjectPath(projectKey);
            Map<String, String> allFiles = scanJavaFiles(projectPath);

            log.info("✅ 扫描完成: projectKey={}, 文件数={}", projectKey, allFiles.size());

            return new ArrayList<>(allFiles.keySet());

        } catch (Exception e) {
            log.error("扫描 Java 文件失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 检测项目文件变化（包含删除检测）
     *
     * @param projectKey 项目键
     * @return 变化的文件列表（仅新增/修改）
     */
    public List<String> detectChangedFiles(String projectKey) {
        FileChangeDetectionResult result = detectChangedFilesWithDeletion(projectKey);
        return result.getAddedOrModifiedFiles();
    }

    /**
     * 检测项目文件变化（包含删除检测）
     *
     * @param projectKey 项目键
     * @return 文件变化检测结果（包含新增/修改/删除）
     */
    public FileChangeDetectionResult detectChangedFilesWithDeletion(String projectKey) {
        log.info("🔍 检测项目文件变化（含删除）: projectKey={}", projectKey);

        try {
            // 1. 获取项目路径
            String projectPath = projectConfigService.getProjectPath(projectKey);

            // 2. 读取 MD5 缓存
            Map<String, String> cachedMd5Map = loadMd5Cache(projectPath);

            // 3. 扫描当前 Java 文件
            Map<String, String> currentMd5Map = scanJavaFiles(projectPath);

            // 4. 检测新增或修改的文件
            List<String> addedOrModifiedFiles = new ArrayList<>();

            for (Map.Entry<String, String> entry : currentMd5Map.entrySet()) {
                String filePath = entry.getKey();
                String currentMd5 = entry.getValue();
                String cachedMd5 = cachedMd5Map.get(filePath);

                if (cachedMd5 == null || !cachedMd5.equals(currentMd5)) {
                    addedOrModifiedFiles.add(filePath);
                    log.debug("检测到文件变化: {} (MD5: {} -> {})",
                            filePath,
                            cachedMd5 != null ? cachedMd5.substring(0, 7) : "N/A",
                            currentMd5.substring(0, 7));
                }
            }

            // 5. 🔥 检测删除的文件
            List<String> deletedFiles = new ArrayList<>();

            for (String cachedFile : cachedMd5Map.keySet()) {
                if (!currentMd5Map.containsKey(cachedFile)) {
                    deletedFiles.add(cachedFile);
                    log.debug("检测到文件删除: {}", cachedFile);
                }
            }

            log.info("✅ 文件变化检测完成: projectKey={}, 新增/修改={}, 删除={}",
                    projectKey, addedOrModifiedFiles.size(), deletedFiles.size());

            return new FileChangeDetectionResult(addedOrModifiedFiles, deletedFiles);

        } catch (Exception e) {
            log.error("检测文件变化失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return new FileChangeDetectionResult(Collections.emptyList(), Collections.emptyList());
        }
    }

    /**
     * 加载 MD5 缓存
     */
    private Map<String, String> loadMd5Cache(String projectPath) {
        try {
            // 生成缓存文件名 (将路径中的 / 替换为 _)
            String cacheFileName = projectPath.replace('/', '_').replace('.', '_') + "_md5_cache.json";
            Path cacheFile = Path.of(md5CacheDir, cacheFileName);

            if (!Files.exists(cacheFile)) {
                log.debug("MD5 缓存文件不存在: {}", cacheFile);
                return Collections.emptyMap();
            }

            // 读取 JSON
            Map<String, String> md5Map = objectMapper.readValue(
                    cacheFile.toFile(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class)
            );

            log.debug("加载 MD5 缓存: file={}, count={}", cacheFile, md5Map.size());
            return md5Map;

        } catch (Exception e) {
            log.warn("加载 MD5 缓存失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 扫描 Java 文件并计算 MD5
     */
    public Map<String, String> scanJavaFiles(String projectPath) {
        Map<String, String> md5Map = new HashMap<>();

        try {
            File projectDir = new File(projectPath);

            if (!projectDir.exists()) {
                log.warn("项目目录不存在: {}", projectPath);
                return md5Map;
            }

            // 递归扫描 Java 文件
            Queue<File> queue = new LinkedList<>();
            queue.add(projectDir);

            while (!queue.isEmpty()) {
                File dir = queue.poll();
                File[] files = dir.listFiles();

                if (files == null) {
                    continue;
                }

                for (File file : files) {
                    if (file.isDirectory()) {
                        // 跳过隐藏目录和构建目录
                        String name = file.getName();
                        if (!name.startsWith(".") && !name.equals("target") && !name.equals("build")) {
                            queue.add(file);
                        }
                    } else if (file.getName().endsWith(".java")) {
                        // 计算相对路径
                        String relativePath = projectDir.toPath().relativize(file.toPath()).toString();
                        String md5 = calculateMd5(file);
                        md5Map.put(relativePath, md5);
                    }
                }
            }

            log.debug("扫描 Java 文件: projectPath={}, count={}", projectPath, md5Map.size());

        } catch (Exception e) {
            log.error("扫描 Java 文件失败: {}", e.getMessage(), e);
        }

        return md5Map;
    }

    /**
     * 计算文件 MD5
     */
    private String calculateMd5(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] content = Files.readAllBytes(file.toPath());
            byte[] hash = md.digest(content);

            // 转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();

        } catch (Exception e) {
            log.warn("计算 MD5 失败: file={}, error={}", file.getPath(), e.getMessage());
            return "";
        }
    }

    /**
     * 更新 MD5 缓存
     */
    public void updateMd5Cache(String projectKey, Map<String, String> newMd5Map) {
        try {
            String projectPath = projectConfigService.getProjectPath(projectKey);

            // 合并现有缓存
            Map<String, String> existingCache = loadMd5Cache(projectPath);
            existingCache.putAll(newMd5Map);

            // 保存到文件
            String cacheFileName = projectPath.replace('/', '_').replace('.', '_') + "_md5_cache.json";
            Path cacheFile = Path.of(md5CacheDir, cacheFileName);

            // 确保目录存在
            Files.createDirectories(cacheFile.getParent());

            // 写入 JSON
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), existingCache);

            log.info("✅ MD5 缓存已更新: file={}, count={}", cacheFile, existingCache.size());

        } catch (Exception e) {
            log.error("更新 MD5 缓存失败: {}", e.getMessage(), e);
        }
    }
}
