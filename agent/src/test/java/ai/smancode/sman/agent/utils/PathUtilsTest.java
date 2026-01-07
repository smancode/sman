package ai.smancode.sman.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PathUtils 单元测试
 *
 * 测试数据基于实际的 Claude Code CLI 会话目录命名规则
 * 企业内部实际目录: C--dev-sman-agent-data-claude-code-workspaces
 */
class PathUtilsTest {

    @Test
    void testEncodeCliSessionPath_UnixAbsolutePath() {
        // Unix 绝对路径: /home/user/path → -home-user-path
        String input = "/home/user/path";
        String expected = "-home-user-path";
        String result = PathUtils.encodeCliSessionPath(input);
        assertEquals(expected, result, "Unix 路径编码错误");
    }

    @Test
    void testEncodeCliSessionPath_WindowsPathWithDrive() {
        // Windows 路径: C:\dev\path → C--dev-path
        // 注意：冒号和反斜杠都被替换为 -，且保留连续的 --
        String input = "C:\\dev\\path";
        String expected = "C--dev-path";
        String result = PathUtils.encodeCliSessionPath(input);
        assertEquals(expected, result, "Windows 路径编码错误");
    }

    @Test
    void testEncodeCliSessionPath_EnterpriseWindowsPath() {
        // 企业内部实际路径: C:\dev\sman-agent\data\claude-code-workspaces
        // CLI 实际编码: C--dev-sman-agent-data-claude-code-workspaces
        String input = "C:\\dev\\sman-agent\\data\\claude-code-workspaces";
        String expected = "C--dev-sman-agent-data-claude-code-workspaces";
        String result = PathUtils.encodeCliSessionPath(input);
        assertEquals(expected, result, "企业内部 Windows 路径编码错误");
    }

    @Test
    void testEncodeCliSessionPath_ActualWorkDirBase() {
        // macOS 实际工作目录
        String input = "/Users/liuchao/projects/sman/agent/data/claude-code-workspaces";
        String expected = "-Users-liuchao-projects-sman-agent-data-claude-code-workspaces";
        String result = PathUtils.encodeCliSessionPath(input);
        assertEquals(expected, result, "macOS 工作目录编码错误");
    }

    @Test
    void testEncodeCliSessionPath_WindowsWorkDir_Drive() {
        // Windows D 盘工作目录
        String input = "D:\\sman\\data\\claude-code-workspaces";
        String expected = "D--sman-data-claude-code-workspaces";
        String result = PathUtils.encodeCliSessionPath(input);
        assertEquals(expected, result, "Windows D 盘工作目录编码错误");
    }

    @Test
    void testBuildCliSessionFilePath() {
        // 测试完整的会话文件路径构建（macOS）
        String workDirBase = "/Users/liuchao/projects/sman/agent/data/claude-code-workspaces";
        String sessionId = "a44229ec-7387-47a2-916c-ab19cb844936";

        String result = PathUtils.buildCliSessionFilePath(workDirBase, sessionId);

        // 基于 ls 验证的实际目录名
        String expected = System.getProperty("user.home") + "/.claude/projects/-Users-liuchao-projects-sman-agent-data-claude-code-workspaces/" + sessionId + ".jsonl";

        assertEquals(expected, result, "会话文件路径构建错误");
    }

    @Test
    void testBuildCliSessionFilePath_Windows() {
        // 测试 Windows 环境的会话文件路径构建
        String workDirBase = "C:\\dev\\sman-agent\\data\\claude-code-workspaces";
        String sessionId = "a44229ec-7387-47a2-916c-ab19cb844936";

        String result = PathUtils.buildCliSessionFilePath(workDirBase, sessionId);

        // 企业内部实际目录名: C--dev-sman-agent-data-claude-code-workspaces
        String homeDir = System.getProperty("user.home");
        String expected = homeDir + "/.claude/projects/C--dev-sman-agent-data-claude-code-workspaces/" + sessionId + ".jsonl";

        assertEquals(expected, result, "Windows 会话文件路径构建错误");
    }

    @Test
    void testEncodeCliSessionPath_PreservesConsecutiveDashes() {
        // 验证连续的 - 不会被合并
        String input = "/path//to///dir";
        String expected = "-path--to---dir";  // 连续斜杠产生连续的 -，且保留
        String result = PathUtils.encodeCliSessionPath(input);
        assertEquals(expected, result, "连续斜杠处理错误");
    }

    @Test
    void testEncodeCliSessionPath_RelativePath() {
        // 相对路径: data/workspace → data-workspace
        String input = "data/workspace";
        String expected = "data-workspace";
        String result = PathUtils.encodeCliSessionPath(input);
        assertEquals(expected, result, "相对路径编码错误");
    }
}
