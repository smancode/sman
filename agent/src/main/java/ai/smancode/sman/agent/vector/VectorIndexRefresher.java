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

            // 2. 读取 MD5 缓存 (使用 projectKey 隔离)
            Map<String, String> cachedMd5Map = loadMd5Cache(projectKey);

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
     *
     * @param projectKey 项目键 (用于隔离不同项目的缓存)
     */
    private Map<String, String> loadMd5Cache(String projectKey) {
        try {
            // 🔥 修复：使用 projectKey 而不是 projectPath 作为缓存文件名
            String cacheFileName = projectKey + "_md5_cache.json";
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
     * 支持多模块项目：
     * 1. 扫描根目录的 src/main/java 目录下的所有 Java 文件
     * 2. 扫描所有子模块的 src/main/java 目录下的所有 Java 文件
     * 自动过滤 test 目录
     */
    public Map<String, String> scanJavaFiles(String projectPath) {
        Map<String, String> md5Map = new HashMap<>();

        try {
            File projectDir = new File(projectPath);

            // 🔥 增强的路径检测
            if (!projectDir.exists()) {
                log.error("❌ 项目目录不存在: {}", projectPath);
                log.error("   绝对路径: {}", projectDir.getAbsolutePath());
                log.error("   当前系统: os.name=\"{}\"", System.getProperty("os.name"));
                log.error("   用户目录: user.dir=\"{}\"", System.getProperty("user.dir"));
                log.error("   File.separator: {}", File.separator);
                log.error("   路径长度: {}", projectPath.length());

                // 尝试检测路径编码问题
                try {
                    byte[] bytes = projectPath.getBytes("UTF-8");
                    String decoded = new String(bytes, "UTF-8");
                    log.error("   UTF-8 重编码: {}", decoded);
                } catch (Exception e) {
                    log.error("   UTF-8 编码检测失败: {}", e.getMessage());
                }

                // Windows 特定检测
                if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                    log.error("   Windows 检测:");
                    log.error("   - 是否为盘符路径: {}", projectPath.matches("[A-Za-z]:.*"));
                    log.error("   - 是否为 Git Bash 格式: {}", projectPath.matches("/[a-z]/.*"));
                    log.error("   - 尝试列出根目录: {}", new File("C:\\").exists());
                }

                log.error("   💡 可能的原因:");
                log.error("   1. 路径拼写错误");
                log.error("   2. 路径权限不足（需要管理员权限）");
                log.error("   3. 网络驱动器未连接（如果是映射驱动器）");
                log.error("   4. 路径编码问题（包含特殊字符）");

                return md5Map;
            }

            // 🔥 策略1: 扫描根目录的 src/main/java（如果存在）
            File rootSrcMainJava = new File(projectDir, "src/main/java");
            if (rootSrcMainJava.exists()) {
                log.info("🔍 扫描根目录 src/main/java: {}", rootSrcMainJava.getPath());
                scanDirectory(rootSrcMainJava, projectDir, md5Map);
            }

            // 🔥 策略2: 扫描所有子模块的 */src/main/java
            scanMultiModuleSources(projectDir, projectDir, md5Map);

            log.info("✅ 扫描完成: projectPath={}, 文件数={}", projectPath, md5Map.size());

        } catch (Exception e) {
            log.error("扫描 Java 文件失败: {}", e.getMessage(), e);
        }

        return md5Map;
    }

    /**
     * 扫描所有子模块的 src/main/java
     * 支持嵌套模块结构（如 module/submodule/src/main/java）
     */
    private void scanMultiModuleSources(File currentDir, File baseDir, Map<String, String> md5Map) {
        File[] items = currentDir.listFiles();

        if (items == null) {
            return;
        }

        for (File item : items) {
            if (!item.isDirectory()) {
                continue;
            }

            String dirName = item.getName();

            // 跳过隐藏目录和构建目录
            if (dirName.startsWith(".") || dirName.equals("target") || dirName.equals("build")) {
                continue;
            }

            // 🔥 检查是否为标准模块目录（包含 src/main/java）
            File moduleSrcMainJava = new File(item, "src/main/java");
            if (moduleSrcMainJava.exists()) {
                log.info("🔍 扫描子模块 src/main/java: {}", moduleSrcMainJava.getPath());
                scanDirectory(moduleSrcMainJava, baseDir, md5Map);
            } else {
                // 递归检查子目录（处理嵌套模块）
                scanMultiModuleSources(item, baseDir, md5Map);
            }
        }
    }

    /**
     * 递归扫描目录
     * @param dir 当前扫描目录
     * @param baseDir 基准目录（用于计算相对路径）
     * @param md5Map MD5 映射表
     */
    private void scanDirectory(File dir, File baseDir, Map<String, String> md5Map) {
        File[] files = dir.listFiles();

        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                String dirName = file.getName();

                // 跳过隐藏目录和构建目录
                if (dirName.startsWith(".")) {
                    continue;
                }
                if (dirName.equals("target") || dirName.equals("build")) {
                    continue;
                }

                // 🔥 过滤 test 目录
                if (dirName.equals("test")) {
                    log.debug("⏭️  跳过 test 目录: {}", file.getPath());
                    continue;
                }

                // 递归扫描子目录
                scanDirectory(file, baseDir, md5Map);

            } else if (file.getName().endsWith(".java")) {
                // 计算相对路径
                String relativePath = baseDir.toPath().relativize(file.toPath()).toString();
                String md5 = calculateMd5(file);
                md5Map.put(relativePath, md5);

                log.debug("📄 扫描文件: {} (MD5: {})", relativePath, md5.substring(0, 7));
            }
        }
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
            // 🔥 修复：使用 projectKey 隔离缓存
            // 合并现有缓存（使用新 HashMap 避免不可变 Map 的 UnsupportedOperationException）
            Map<String, String> existingCache = new HashMap<>(loadMd5Cache(projectKey));
            existingCache.putAll(newMd5Map);

            // 保存到文件
            String cacheFileName = projectKey + "_md5_cache.json";
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
