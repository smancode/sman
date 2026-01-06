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

        // 检测操作系统
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = osName.contains("windows");

        if (!isWindows) {
            // 非 Windows 系统，只处理斜杠统一
            return normalizeSlashes(path);
        }

        // Windows 系统下的路径处理
        return normalizeWindowsPath(path);
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
}
