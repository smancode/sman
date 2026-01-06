# Windows + Git Bash 支持方案

**文档版本**: v1.1
**创建日期**: 2025-01-05
**更新日期**: 2026-01-05

---

## 1. 兼容性确认

### ✅ 可以在 Windows + Git Bash 中运行

**原因**：
- Git Bash 提供了完整的 Unix-like 环境
- bash 脚本可以直接运行
- Node.js 和 claude-code-cli 在 Windows 上正常工作

---

## 2. Windows 环境准备

### 2.1 安装必要软件

| 软件 | 版本要求 | 安装方式 |
|------|---------|---------|
| **Git Bash** | 最新版 | https://git-scm.com/download/win |
| **Node.js** | 18+ | https://nodejs.org/ |
| **Claude Code CLI** | 2.0+ | `npm install -g @anthropic-ai/claude-code` |
| **JDK** | 21+ | https://adoptium.net/ |

### 2.2 验证安装

```bash
# 在 Git Bash 中执行
java -version       # 应该显示 openjdk 21
node --version      # 应该显示 v18+
claude --version    # 应该显示 2.0+
```

---

## 3. Claude Code CLI 路径（Windows）

### 问题：脚本中的路径检测

**当前脚本**：
```bash
if command -v claude &> /dev/null; then
    CLAUDE_BIN="$(command -v claude)"
else
    CLAUDE_BIN="$HOME/.vscode/extensions/..."
fi
```

**Windows Git Bash 中的问题**：
- `command -v claude` 可能找不到（如果在 cmd 中安装）
- 需要处理 Windows 路径格式（`\` vs `/`）

### 解决方案

**方案 1：确保 claude 在 PATH 中**

```bash
# 在 Git Bash 中检查
which claude

# 如果找不到，手动添加到 PATH
export PATH="$PATH:/c/Users/$USERNAME/AppData/Roaming/npm"
```

**方案 2：修改脚本支持 Windows**

```bash
#!/bin/bash
# claude-code-stdio (Windows 兼容版)

# 检测操作系统
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
    # Windows (Git Bash)
    CLAUDE_BIN="claude"  # 确保在 PATH 中
    # 或者使用完整路径
    # CLAUDE_BIN="/c/Users/$USERNAME/AppData/Roaming/npm/claude"
else
    # macOS/Linux
    if command -v claude &> /dev/null; then
        CLAUDE_BIN="$(command -v claude)"
    else
        CLAUDE_BIN="$HOME/.vscode/extensions/anthropic.claude-code-2.0.75-darwin-arm64/resources/native-binary/claude"
    fi
fi

read SESSION_ID

exec "$CLAUDE_BIN" \
  --print \
  --session-id "$SESSION_ID" \
  --output-format text \
  --input-format text \
  --disallowedTools "Read,Edit,Write,Bash,Prompt" \
  --dangerously-skip-permissions
```

---

## 4. 启动脚本（Windows）

### 4.1 使用 Git Bash 启动

```bash
# 在 Git Bash 中
cd /c/Users/liuchao/projects/sman/agent
./start.sh
```

### 4.2 可能的调整

**问题 1：权限错误**
```bash
# 赋予脚本执行权限
chmod +x start.sh stop.sh
```

**问题 2：路径格式**
```bash
# Windows 路径自动转换
C:\Users\liuchao\projects  →  /c/Users/liuchao/projects
```

**问题 3：端口占用**
```powershell
# 在 PowerShell 中检查端口占用
netstat -ano | findstr :8080

# 如果被占用，杀死进程
taskkill /PID <进程ID> /F
```

---

## 5. `--disallowedTools` 参数说明

### 5.1 作用

**禁止 Claude Code 使用特定的内置工具**。

### 5.2 为什么需要？

**原因**：我们希望 Claude Code 通过 HTTP API 调用我们后端的工具，而不是直接使用内置工具。

**对比**：

| 工具 | 内置工具行为 | 我们期望的行为 |
|------|-------------|---------------|
| **Read** | 直接读取本地文件 | 通过 `read_class` API 读取 |
| **Edit** | 直接编辑文件 | 通过 `apply_change` API 修改 |
| **Bash** | 执行 shell 命令 | 禁止执行（安全） |
| **Write** | 直接写入文件 | 通过 `apply_change` API 写入 |
| **Prompt** | 使用自定义 Prompt | 禁止（固定 Prompt） |

### 5.3 效果

**使用 `--disallowedTools` 后**：

```bash
# ❌ Claude Code 不能执行
- Read(file)
- Edit(file, search, replace)
- Bash(command)
- Write(file, content)
- Prompt(custom prompt)

# ✅ Claude Code 只能通过 HTTP API 调用
http_tool("vector_search", {"query": "xxx"})
http_tool("read_class", {"className": "xxx"})
http_tool("apply_change", {...})
```

### 5.4 Windows 特殊说明

**在 Windows 中，`Bash` 工具尤其重要禁止**：
- Git Bash 环境下，Bash 命令可能执行出错
- Windows 路径格式可能导致 Bash 命令失败
- 安全考虑：禁止直接执行 shell 命令

---

## 6. 完整示例（Windows）

### 6.1 安装 claude-code-cli

```powershell
# 在 PowerShell 中
npm install -g @anthropic-ai/claude-code
```

### 6.2 验证安装

```bash
# 在 Git Bash 中
$ claude --version
2.0.14 (Claude Code)
```

### 6.3 启动 Agent

```bash
# 在 Git Bash 中
cd /c/Users/liuchao/projects/sman/agent
./start.sh
```

### 6.4 测试

```bash
# 测试 API
curl -X POST http://localhost:8080/api/analysis/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好","projectKey":"test","sessionId":"win-test-001"}'
```

---

## 7. 故障排查

### 7.1 claude 命令找不到

**错误**：`bash: claude: command not found`

**解决**：
```bash
# 添加到 PATH（在 .bashrc 中）
echo 'export PATH="$PATH:/c/Users/$USERNAME/AppData/Roaming/npm"' >> ~/.bashrc
source ~/.bashrc
```

### 7.2 脚本执行权限错误

**错误**：`bash: ./start.sh: Permission denied`

**解决**：
```bash
chmod +x start.sh stop.sh /tmp/claude-code-stdio
```

### 7.3 Java 版本不兼容

**错误**：`Unsupported class file major version`

**解决**：
```powershell
# 在 PowerShell 中下载 JDK 21
# https://adoptium.net/
```

---

## 8. 推荐配置

**Windows Server 最佳配置**：

```yaml
# application.yml
claude-code:
  pool:
    size: 10              # Windows 进程开销较大，减少数量
    max-lifetime: 3600000 # 1小时（Windows 进程回收）

logging:
  file:
    name: logs\\sman-agent.log  # Windows 路径分隔符
```

---

## 9. 下一步

✅ **已支持 Windows + Git Bash**

测试步骤：
1. 在 Git Bash 中运行 `./start.sh`
2. 检查日志 `tail -f logs/app.log`
3. 测试多轮对话 API

---

**文档结束**
