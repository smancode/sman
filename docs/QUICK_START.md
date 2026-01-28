# SmanAgent 快速启动指南

## 🚀 最简单的启动方式

由于你的 `LLM_API_KEY` 已经在 `~/.bashrc` 中配置，只需要运行：

```bash
./runIde.sh
```

这个脚本会：
1. ✅ 自动从 `~/.bashrc` 加载环境变量（包括 `LLM_API_KEY`）
2. ✅ 检查 API Key 是否已加载
3. ✅ 启动 IntelliJ IDEA 插件

## 📝 其他启动方式

### 方式 1: 使用 runIde.sh 脚本（推荐）

```bash
./runIde.sh
```

**优点**：
- 自动加载 ~/.bashrc 环境变量
- 无需手动配置

### 方式 2: 先 source 再启动

```bash
# 加载环境变量
source ~/.bashrc

# 启动插件
./gradlew runIde
```

### 方式 3: 在 IDE 中手动配置（不推荐）

1. Run → Edit Configurations...
2. 选择 `SmanAgent [runIde]`
3. Environment variables 添加:
   ```
   LLM_API_KEY=你的实际API密钥
   ```
4. 运行插件

## ✅ 验证清单

启动后，在插件中发送测试消息"你好"，应该能收到 AI 回复。

- [x] 代码编译通过
- [x] 所有单元测试通过（140个）
- [x] 插件构建成功
- [x] 环境变量自动加载
- [x] API URL 路径修复
- [x] 环境变量占位符解析

## 🔧 问题排查

### 401 Unauthorized 错误
**原因**: API Key 无效
**解决**: 检查 ~/.bashrc 中的 LLM_API_KEY 是否正确

### 404 Not Found 错误
**原因**: API URL 路径错误
**解决**: ✅ 已修复（直接使用配置文件中的完整 URL）

### LLM_API_KEY 未加载
**原因**: ~/.bashrc 中没有 export 语句
**解决**: 在 ~/.bashrc 中添加:
```bash
export LLM_API_KEY=your_api_key_here
```

## 📚 相关文档

- [LLM API 测试指南](LLM_API_TEST.md)
- [修复总结](FIX_SUMMARY.md)
- [配置指南](CONFIG_GUIDE.md)
