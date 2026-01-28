# SmanAgent 配置说明

## 当前配置方式

目前 LLM 配置是通过环境变量 `LLM_API_KEY` 设置的，配置硬编码在 `SmanAgentService.kt:151-173` 中。

## 建议的配置文件方案

可以创建一个配置文件来管理这些配置。以下是推荐的实现方式：

### 方案 1：使用 properties 文件（推荐）

在 `resources/` 目录下创建 `smanagent.properties`：

```properties
# LLM 配置
llm.api.key=${LLM_API_KEY}
llm.base.url=https://open.bigmodel.cn/api/paas/v4/chat/completions
llm.model.name=glm-4-flash
llm.max.tokens=8192

# 重试配置
llm.retry.max=3
llm.retry.base.delay=1000
```

### 方案 2：使用 YAML 文件

在 `resources/` 目录下创建 `smanagent.yml`：

```yaml
llm:
  api-key: ${LLM_API_KEY}
  base-url: https://open.bigmodel.cn/api/paas/v4/chat/completions
  model-name: glm-4-flash
  max-tokens: 8192

  retry:
    max: 3
    base-delay: 1000
```

### 方案 3：使用 IDE 的 PersistentStateComponent

这样可以持久化用户的配置，并在设置界面中修改。

## 当前临时解决方案

在 IntelliJ IDEA 中设置环境变量：

1. Run → Edit Configurations...
2. 选择插件运行配置
3. Environment variables:
   ```
   LLM_API_KEY=your_api_key_here
   ```

或在系统环境变量中设置：
```bash
export LLM_API_KEY=your_api_key_here
```

需要我实现配置文件的读取方案吗？
