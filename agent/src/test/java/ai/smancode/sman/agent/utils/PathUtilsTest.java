package ai.smancode.sman.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PathUtils 单元测试
 *
 * @author SiliconMan Team
 * @since 2.0
 */
class PathUtilsTest {

    @Test
    void testConvertToGitBashPath() {
        // Windows 盘符路径转 Git Bash
        assertEquals("/c/Users/projects", PathUtils.convertToGitBashPath("C:\\Users\\projects"));
        assertEquals("/d/data/app", PathUtils.convertToGitBashPath("D:\\data\\app"));
        assertEquals("/e/workspace", PathUtils.convertToGitBashPath("E:\\workspace"));

        // 已经是 Git Bash 格式，不应该转换
        assertEquals("/c/Users/projects", PathUtils.convertToGitBashPath("/c/Users/projects"));

        // 无效输入
        assertNull(PathUtils.convertToGitBashPath(null));
        assertEquals("", PathUtils.convertToGitBashPath(""));
        assertEquals("invalid", PathUtils.convertToGitBashPath("invalid"));
    }

    @Test
    void testConvertToWindowsPath() {
        // Git Bash 路径转 Windows
        assertEquals("C:\\Users\\projects", PathUtils.convertToWindowsPath("/c/Users/projects"));
        assertEquals("D:\\data\\app", PathUtils.convertToWindowsPath("/d/data/app"));
        assertEquals("E:\\workspace", PathUtils.convertToWindowsPath("/e/workspace"));

        // 已经是 Windows 格式，不应该转换
        assertEquals("C:\\Users\\projects", PathUtils.convertToWindowsPath("C:\\Users\\projects"));

        // 无效输入
        assertNull(PathUtils.convertToWindowsPath(null));
        assertEquals("", PathUtils.convertToWindowsPath(""));
        assertEquals("invalid", PathUtils.convertToWindowsPath("invalid"));
    }

    @Test
    void testNormalizeSlashes() {
        // 反斜杠转正斜杠
        assertEquals("C:/Users/projects", PathUtils.normalizeSlashes("C:\\Users\\projects"));
        assertEquals("/home/user/projects", PathUtils.normalizeSlashes("/home/user\\projects"));

        // 已经是正斜杠，不应该转换
        assertEquals("/home/user/projects", PathUtils.normalizeSlashes("/home/user/projects"));

        // 无效输入
        assertNull(PathUtils.normalizeSlashes(null));
        assertEquals("", PathUtils.normalizeSlashes(""));
    }

    @Test
    void testIsWindowsPath() {
        assertTrue(PathUtils.isWindowsPath("C:\\path"));
        assertTrue(PathUtils.isWindowsPath("D:\\data\\app"));
        assertTrue(PathUtils.isWindowsPath("c:\\path")); // 小写盘符

        assertFalse(PathUtils.isWindowsPath("/c/path")); // Git Bash 格式
        assertFalse(PathUtils.isWindowsPath("/home/user")); // Unix 格式
        assertFalse(PathUtils.isWindowsPath(null));
    }

    @Test
    void testIsGitBashPath() {
        assertTrue(PathUtils.isGitBashPath("/c/path"));
        assertTrue(PathUtils.isGitBashPath("/d/data/app"));
        assertTrue(PathUtils.isGitBashPath("/e/workspace"));

        assertFalse(PathUtils.isGitBashPath("C:\\path")); // Windows 格式
        assertFalse(PathUtils.isGitBashPath("/home/user")); // Unix 格式
        assertFalse(PathUtils.isGitBashPath(null));
    }

    @Test
    void testGetCurrentPathType() {
        PathUtils.PathType type = PathUtils.getCurrentPathType();

        // 只能断言返回值是三个之一
        assertTrue(type == PathUtils.PathType.WINDOWS ||
                   type == PathUtils.PathType.GIT_BASH ||
                   type == PathUtils.PathType.UNIX);
    }

    @Test
    void testJoin() {
        // 拼接路径
        String result = PathUtils.join("/base/path", "relative/path");
        assertTrue(result.endsWith("relative/path"));

        // 边界情况
        assertEquals("relative", PathUtils.join("", "relative"));
        assertEquals("/base/path", PathUtils.join("/base/path", ""));
        assertEquals("relative", PathUtils.join(null, "relative"));
        assertEquals("/base/path", PathUtils.join("/base/path", null));
    }

    @Test
    void testNormalizePath() {
        // 测试 normalizePath 在不同环境下的行为
        // 由于测试环境可能不同，这里只测试不抛异常

        String windowsPath = "C:\\Users\\projects\\autoloop";
        String normalized = PathUtils.normalizePath(windowsPath);

        assertNotNull(normalized);
        assertFalse(normalized.isEmpty());

        // Unix 路径
        String unixPath = "/home/user/projects";
        String normalizedUnix = PathUtils.normalizePath(unixPath);
        assertNotNull(normalizedUnix);
    }
}
