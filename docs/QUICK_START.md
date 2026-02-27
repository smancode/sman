# SmanCode 快速启动指南

> 版本: v1.0
> 日期: 2026-02-27

---

## 一、安装插件

### 1.1 下载与安装

1. 构建插件：`./gradlew buildPlugin`
2. 下载位置：`build/distributions/smanunion-2.0.0.zip`
3. IntelliJ IDEA：`File` → `Settings` → `Plugins` → ⚙️ → `Install Plugin from Disk`
4. 重启 IDEA

### 1.2 配置 API Key

```bash
# 设置环境变量（推荐）
export LLM_API_KEY="your-api-key"

# 或在插件设置中配置
# Settings → Tools → SmanAgent → API Key
```

> 获取 API Key：访问 [智谱AI开放平台](https://open.bigmodel.cn/)

### 1.3 快速启动

```bash
# 使用脚本启动（自动加载环境变量）
./runIde.sh

# 或直接启动
./gradlew runIde
```

---

## 二、基本使用

### 2.1 打开工具窗口

- `View` → `Tool Windows` → `SmanAgent Chat`
- 快捷键：`Cmd+Shift+A` → 搜索 "SmanAgent"

### 2.2 常用命令

| 命令 | 示例 |
|------|------|
| 分析代码结构 | `分析这个项目的代码结构` |
| 查看方法实现 | `ReadFileTool.execute 方法做了什么？` |
| 搜索代码 | `搜索项目中所有使用 LlmService 的地方` |
| 提交代码 | `/commit` |

### 2.3 内置工具

| 工具 | 功能 |
|------|------|
| `read_file` | 读取文件内容 |
| `grep_file` | 文件内容搜索 |
| `search` | 智能搜索（万能入口） |
| `read_class` | 读取类定义 |
| `call_chain` | 调用链分析 |
| `text_search` | 文本搜索 |

---

## 三、配置选项

### 3.1 API 配置

| 配置项 | 默认值 |
|--------|--------|
| API Key | 从环境变量读取 |
| Base URL | `https://open.bigmodel.cn/api/paas/v4` |
| Model Name | `GLM-5` |

### 3.2 高级配置

```properties
# ReAct 循环最大步数
react.maxSteps = 10

# 上下文压缩阈值
contextCompaction.maxTokens = 8000

# 向量化服务
bge.endpoint = http://localhost:8000

# Reranker
reranker.base.url = http://localhost:8001/v1
```

---

## 四、快捷键

| 快捷键 | 功能 |
|--------|------|
| `Cmd+Shift+A` | 搜索并打开 SmanAgent |
| `Enter` | 发送消息 |
| `Shift+Enter` | 换行 |
| `Cmd+K` | 清空输入框 |
| `Ctrl+L` | 注入当前文件/选中代码 |

---

## 五、故障排查

### 5.1 401 Unauthorized

**原因**：API Key 无效
**解决**：检查 API Key 是否正确，确认账户有可用额度

### 5.2 插件无响应

**原因**：网络或服务问题
**解决**：
1. 检查网络连接
2. 查看日志：`Help` → `Show Log in Explorer`
3. 重启 IDEA

### 5.3 工具执行失败

**原因**：文件路径或项目问题
**解决**：
1. 确认项目已正确打开
2. 检查文件路径是否正确

---

## 六、构建命令

```bash
# 编译
./gradlew compileKotlin

# 构建插件
./gradlew buildPlugin

# 运行测试
./gradlew test

# 运行指定测试
./gradlew test --tests "*PuzzleCoordinatorTest*"

# 在沙盒中运行
./gradlew runIde
```

---

## 七、变更历史

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v1.0 | 2026-02-27 | 合并 QUICK_START.md 和 USAGE.md |
