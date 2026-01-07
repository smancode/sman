package ai.smancode.sman.agent.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 路径工具类
 *
 * 功能：
 * - 统一路径格式处理
 * - 支持 Windows Git Bash 路径转换
 * - 跨平台路径兼容性处理
 *
 * @author SiliconMan Team
 * @since 2.0
 */
public class PathUtils {

    private static final Logger log = LoggerFactory.getLogger(PathUtils.class);

    /**
     * 规范化路径以支持不同环境
     *
     * 转换规则：
     * - Windows (原生): C:\Users\projects\autoloop
     * - Windows Git Bash: /c/Users/projects/autoloop
     * - Linux/Mac: /home/user/projects/autoloop
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        // 🔥 调试日志
        log.debug("🔍 [PathUtils] 输入路径: \"{}\"", path);

        // 检测操作系统（更宽松的检测）
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = osName.contains("windows");

        log.debug("🔍 [PathUtils] os.name=\"{}\", isWindows={}", System.getProperty("os.name"), isWindows);
        log.debug("🔍 [PathUtils] isGitBashPath={}", isGitBashPath(path));

        // 🔥 特殊处理：如果是 Git Bash 路径格式，无论什么系统都尝试转换
        if (isGitBashPath(path)) {
            if (isWindows) {
                // Windows 系统：/c/dev -> C:\dev
                String converted = convertToWindowsPath(path);
                log.info("✅ [PathUtils] Git Bash 路径已转换: {} -> {}", path, converted);
                return converted;
            } else {
                // 非 Windows 系统（macOS/Linux）：保持 Git Bash 格式
                // 这种情况下，可能需要用户手动配置正确的路径
                log.warn("⚠️ [PathUtils] 检测到 Git Bash 路径格式，但当前系统不是 Windows: {}", path);
                log.warn("   当前系统: {}", System.getProperty("os.name"));
                log.warn("   路径可能无效，请检查 application.yml 配置");
                return path;
            }
        }

        if (!isWindows) {
            // 非 Windows 系统，只处理斜杠统一
            String normalized = normalizeSlashes(path);
            log.debug("🔍 [PathUtils] 非 Windows 系统，统一斜杠: {} -> {}", path, normalized);
            return normalized;
        }

        // Windows 系统下的路径处理
        String normalized = normalizeWindowsPath(path);
        log.debug("🔍 [PathUtils] Windows 系统路径规范化: {} -> {}", path, normalized);
        return normalized;
    }

    /**
     * Windows 路径规范化
     *
     * @param path Windows 原始路径
     * @return 规范化后的路径
     */
    private static String normalizeWindowsPath(String path) {
        // 1. 如果已经是 Unix 风格路径（以 / 开头），可能已经转换过，直接返回
        if (path.startsWith("/") && !path.startsWith("//")) {
            return path;
        }

        // 2. 转换 Windows 盘符路径为 Git Bash 风格
        // C:\Users\projects\autoloop -> /c/Users/projects/autoloop
        if (path.matches("[A-Za-z]:.*")) {
            return convertToGitBashPath(path);
        }

        // 3. 如果路径包含反斜杠但不是 Windows 盘符路径，直接转换斜杠
        if (path.contains("\\")) {
            return normalizeSlashes(path);
        }

        return path;
    }

    /**
     * 转换 Windows 路径为 Git Bash 格式
     *
     * 示例：
     * - C:\Users\projects -> /c/Users/projects
     * - D:\data\app -> /d/data/app
     * - C:\\Users\\projects -> /c/Users/projects (处理双反斜杠)
     *
     * @param windowsPath Windows 风格路径
     * @return Git Bash 风格路径
     */
    public static String convertToGitBashPath(String windowsPath) {
        if (windowsPath == null || !windowsPath.matches("[A-Za-z]:.*")) {
            return windowsPath;
        }

        // 提取盘符 (如 C:) 并转为小写
        String driveLetter = windowsPath.substring(0, 1).toLowerCase();

        // 移除盘符和冒号 (如 C:)
        String pathWithoutDrive = windowsPath.substring(2);

        // 转换反斜杠为正斜杠
        pathWithoutDrive = pathWithoutDrive.replace('\\', '/');

        // 🔥 修复双斜杠问题：将连续的斜杠合并为单个斜杠
        pathWithoutDrive = pathWithoutDrive.replaceAll("/+", "/");

        // 拼接成 Git Bash 格式: /c/Users/projects/autoloop
        String gitBashPath = "/" + driveLetter + pathWithoutDrive;

        log.debug("🔄 Windows 路径转 Git Bash: {} -> {}", windowsPath, gitBashPath);

        return gitBashPath;
    }

    /**
     * 转换 Git Bash 路径为 Windows 格式
     *
     * 示例：
     * - /c/Users/projects -> C:\\Users\\projects
     * - /d/data/app -> D:\\data\\app
     *
     * @param gitBashPath Git Bash 风格路径
     * @return Windows 风格路径
     */
    public static String convertToWindowsPath(String gitBashPath) {
        if (gitBashPath == null || !gitBashPath.matches("/[a-z]/.*")) {
            return gitBashPath;
        }

        // 提取盘符 (如 /c -> C:)
        String driveLetter = gitBashPath.substring(1, 2).toUpperCase();

        // 移除斜杠和盘符 (如 /c/ -> 空)
        String pathWithoutDrive = gitBashPath.substring(3);

        // 转换正斜杠为反斜杠
        pathWithoutDrive = pathWithoutDrive.replace('/', '\\');

        // 拼接成 Windows 格式: C:\Users\projects
        String windowsPath = driveLetter + ":\\" + pathWithoutDrive;

        log.debug("🔄 Git Bash 路径转 Windows: {} -> {}", gitBashPath, windowsPath);

        return windowsPath;
    }

    /**
     * 统一斜杠格式（将反斜杠转换为正斜杠）
     *
     * @param path 原始路径
     * @return 统一斜杠后的路径
     */
    public static String normalizeSlashes(String path) {
        if (path == null || !path.contains("\\")) {
            return path;
        }

        String normalized = path.replace('\\', '/');
        log.debug("🔄 统一斜杠: {} -> {}", path, normalized);

        return normalized;
    }

    /**
     * 检查路径是否为 Windows 盘符路径
     *
     * @param path 路径
     * @return true 如果是 Windows 盘符路径 (如 C:\path)
     */
    public static boolean isWindowsPath(String path) {
        return path != null && path.matches("[A-Za-z]:.*");
    }

    /**
     * 检查路径是否为 Git Bash 风格路径
     *
     * @param path 路径
     * @return true 如果是 Git Bash 路径 (如 /c/Users/path)
     */
    public static boolean isGitBashPath(String path) {
        return path != null && path.matches("/[a-z]/.*");
    }

    /**
     * 获取当前运行环境的路径类型
     *
     * @return 路径类型 (WINDOWS, GIT_BASH, UNIX)
     */
    public static PathType getCurrentPathType() {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (!osName.contains("windows")) {
            return PathType.UNIX;
        }

        // Windows 系统，进一步判断是 Git Bash 还是原生 Windows
        // 可以通过检查环境变量判断
        String gitBashEnv = System.getenv("MSYSTEM"); // Git Bash 特有环境变量
        if (gitBashEnv != null && !gitBashEnv.isEmpty()) {
            return PathType.GIT_BASH;
        }

        return PathType.WINDOWS;
    }

    /**
     * 根据当前环境自动转换路径
     *
     * @param path 输入路径
     * @return 适合当前环境的路径
     */
    public static String autoConvertForCurrentEnv(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        PathType currentType = getCurrentPathType();

        switch (currentType) {
            case GIT_BASH:
                // 如果是 Windows 路径，转换为 Git Bash 格式
                if (isWindowsPath(path)) {
                    return convertToGitBashPath(path);
                }
                break;

            case WINDOWS:
                // 如果是 Git Bash 路径，转换为 Windows 格式
                if (isGitBashPath(path)) {
                    return convertToWindowsPath(path);
                }
                break;

            case UNIX:
                // UNIX 系统，只统一斜杠
                return normalizeSlashes(path);
        }

        return path;
    }

    /**
     * 拼接路径（自动处理斜杠）
     *
     * @param base 基础路径
     * @param relative 相对路径
     * @return 拼接后的路径
     */
    public static String join(String base, String relative) {
        if (base == null || base.isEmpty()) {
            return relative;
        }
        if (relative == null || relative.isEmpty()) {
            return base;
        }

        // 移除 base 末尾的斜杠
        base = base.replaceAll("/+$", "\\\\");

        // 移除 relative 开头的斜杠
        relative = relative.replaceAll("^/+", "");

        return base + File.separator + relative;
    }

    /**
     * 路径类型枚举
     */
    public enum PathType {
        WINDOWS,   // Windows 原生路径 (C:\path)
        GIT_BASH,  // Git Bash 路径 (/c/path)
        UNIX       // Unix/Linux/Mac 路径 (/home/user/path)
    }

    /**
     * 将路径编码为 Claude Code CLI 的会话目录名
     *
     * CLI 的编码规则（通过实际测试验证）：
     * - Unix: /home/user/path → -home-user-path
     * - Windows: C:\dev\path → C--dev-path （注意：冒号和反斜杠都被替换为 -，且不合并连续的 -）
     * - 所有斜杠（/ 和 \）替换为 -
     * - 所有冒号（:）替换为 -
     * - 🔥 不合并连续的 - （与 CLI 行为一致）
     *
     * @param path 原始路径
     * @return CLI 会话目录名
     */
    public static String encodeCliSessionPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        log.debug("🔍 [PathUtils] 编码 CLI 会话路径: \"{}\"", path);

        // 1. 统一斜杠（全部替换为 -）
        String encoded = path.replace("/", "-").replace("\\", "-");

        // 2. 替换冒号（Windows 盘符）
        encoded = encoded.replace(":", "-");

        // 🔥 3. 不需要合并连续的 -（CLI 实际行为就是保留连续的 -）
        // 删除了: encoded.replaceAll("-+", "-")

        log.debug("✅ [PathUtils] 编码结果: \"{}\" -> \"{}\"", path, encoded);

        return encoded;
    }

    /**
     * 构建 Claude Code CLI 会话文件的完整路径
     *
     * @param workDirBase CLI 工作目录
     * @param sessionId 会话 ID
     * @return 会话文件的完整路径
     */
    public static String buildCliSessionFilePath(String workDirBase, String sessionId) {
        String encodedPath = encodeCliSessionPath(workDirBase);
        String homeDir = System.getProperty("user.home");
        return homeDir + "/.claude/projects/" + encodedPath + "/" + sessionId + ".jsonl";
    }
}
