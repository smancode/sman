# SmanAgent 统一打包

这个目录包含将后端 Agent 和前端插件打包成单一安装包的脚本和配置。

## 使用方法

### 一键打包

```bash
cd /Users/liuchao/projects/smanagent
./dist/build-unified-plugin.sh
```

这个脚本会：
1. 构建后端 Agent JAR
2. 复制后端 JAR 到插件 lib 目录
3. 构建包含后端的插件 ZIP
4. 生成可直接安装的插件包

### 构建产物

打包完成后，插件位于：
```
ide-plugin/build/distributions/ide-plugin-1.1.0.zip
```

这个 ZIP 文件包含了：
- 前端插件代码
- 后端 Agent JAR (约 46MB)
- 所有必需的依赖

## 安装步骤

1. 在 IntelliJ IDEA 中：`File` → `Settings` → `Plugins`
2. 点击齿轮图标 → `Install Plugin from Disk...`
3. 选择 `ide-plugin-1.1.0.zip`
4. 重启 IDE

## 环境变量

插件需要配置以下环境变量：

```bash
# 必需
export SMANCODE_LLM_API_KEY="your-api-key"

# 可选
export SMANCODE_LLM_BASE_URL="https://api.openai.com/v1"
export SMANCODE_LLM_MODEL="gpt-4"
export SMANCODE_KNOWLEDGE_URL="http://your-knowledge-service"
```

## 工作原理

打包后的插件会：

1. **自动启动后端**：
   - 插件启动时检测端口 8080
   - 如果后端未运行，自动启动独立 JVM 进程
   - 启动参数：`-Xmx512m -Xms256m`

2. **本地通信**：
   - 前端通过 WebSocket 连接到后端
   - 地址：`ws://localhost:8080/ws/agent`
   - 完全本地通信，无网络请求

3. **进程隔离**：
   - 后端独立进程运行
   - 与插件 JVM 完全隔离
   - 避免依赖冲突

## 与原项目的关系

**保持不变**：
- `agent/` - 后端源码（无改动）
- `ide-plugin/` - 前端源码（仅修改启动逻辑）
- 所有原有功能完全保留

**仅新增**：
- `dist/` - 打包脚本和文档
- 后端自动启动逻辑（在 `SmanAgentPlugin.kt` 中）

## 开发流程

### 正常开发（不需要打包）

```bash
# 启动后端（开发模式）
cd agent
./gradlew bootRun

# 启动前端（开发模式）
cd ide-plugin
./gradlew runIde
```

### 发布打包

```bash
# 一键打包
./dist/build-unified-plugin.sh
```

## 故障排除

### 后端启动失败

检查端口是否被占用：
```bash
lsof -i :8080
```

### 找不到 JAR 文件

确保打包脚本已执行：
```bash
./dist/build-unified-plugin.sh
```

### WebSocket 连接失败

1. 检查后端是否运行
2. 查看插件日志：`Help` → `Show Log`
3. 手动启动后端测试

## 相关文档

- [架构设计](../ARCHITECTURE.md)
- [项目 README](../README.md)
