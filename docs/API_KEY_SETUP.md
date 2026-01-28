# 配置 API Key 快速指南

## 问题

```
缺少 LLM_API_KEY 环境变量
```

## 解决方案

### 方法 1：设置环境变量（推荐）

**macOS / Linux**:

```bash
# 临时设置（当前终端会话）
export LLM_API_KEY="your-glm-4-flash-api-key"

# 永久设置（添加到 ~/.zshrc 或 ~/.bash_profile）
echo 'export LLM_API_KEY="your-glm-4-flash-api-key"' >> ~/.zshrc
source ~/.zshrc
```

**Windows**:

```cmd
# 临时设置
set LLM_API_KEY=your-glm-4-flash-api-key

# 永久设置（系统环境变量）
# 控制面板 → 系统 → 高级系统设置 → 环境变量 → 新建
```

### 方法 2：在 IDEA 中设置（开发时）

1. `Run` → `Edit Configurations...`
2. 选择你的运行配置
3. `Environment variables` → 点击 `...`
4. 添加：`LLM_API_KEY=your-glm-4-flash-api-key`
5. 点击 `OK`

### 方法 3：修改代码（仅用于测试）

⚠️ **不推荐用于生产环境**

编辑 `src/main/kotlin/com/smancode/smanagent/ide/service/SmanAgentService.kt:110`：

```kotlin
// 修改前
val apiKey = System.getenv("LLM_API_KEY") ?: throw IllegalArgumentException("缺少 LLM_API_KEY 环境变量")

// 修改后（仅测试）
val apiKey = "your-actual-api-key-here"  // ⚠️ 不要提交到代码仓库！
```

## 获取 API Key

1. 访问 [智谱AI开放平台](https://open.bigmodel.cn/)
2. 注册/登录账号
3. 进入 `API Key` 页面
4. 创建新的 API Key
5. 复制 Key（格式类似：`xxxxxxxxxxxxxxxxxxxxx.xxxxxxxxxxxxxxxxxxxxxxxx`）

## 验证配置

设置后，重启 IntelliJ IDEA，然后在 SmanAgent Chat 中输入：

```
你好
```

如果看到回复，说明配置成功！

## 常见问题

### Q: 设置了环境变量还是提示缺少？

**A**: 确保 IDEA 完全重启（不是关闭项目），环境变量才会生效。

### Q: API Key 格式是什么样的？

**A**: 智谱AI的 API Key 格式：`id.secret`，例如：
```
1234567890.abcdef1234567890abcdef1234567890abcdef
```

### Q: 免费额度够用吗？

**A**: 智谱AI新用户有一定免费额度，GLM-4-Flash 模型性价比高，适合开发测试。

---

**配置完成后，插件就可以正常使用了！** 🚀
